package com.miaa.cas.treasury;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.support.AbstractReceivablesIT;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Cash-flow and expenditure forecast (TOR §3.5.1, B.7). The test database is shared, so changes are measured as deltas. */
class CashForecastIT extends AbstractReceivablesIT {

    @Test
    void receivablesPayablesVouchersAndBudgetFallInTheirBuckets() throws Exception {
        int year = newOpenYear();
        String url = "/api/v1/treasury/cash-forecast?granularity=MONTH&periods=4&from=" + year + "-01-01";
        JsonNode before = forecast(url);

        // A billing due 14 Feb, an invoice due 19 Feb (7,920.00 after 1 % EWT), and a DV to be paid on 10 Mar.
        String customer = createCustomer(null).get("id").asText();
        String billing = postedBilling(billing("INVOICE", customer, LocalDate.of(year, 1, 15), "PLC-4203", "50000.00", false, null))
                .get("id").asText();
        String supplier = createSupplier(false).get("id").asText();
        String invoice = postedInvoice(supplier, "CF-" + year, LocalDate.of(year, 1, 20), "8000.00").get("id").asText();
        UUID dv = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO disbursement_vouchers (id, dv_number, supplier_id, particulars, status, amount, payment_date,
                    prepared_by_id, prepared_by, created_at, updated_at, updated_by)
                VALUES (?, ?, ?::uuid, 'Forecast test', 'APPROVED', 3000.00, ?, ?, 'cfprep', now(), now(), 'cfprep')""",
                dv, "DV-CF-" + dv.toString().substring(0, 8), supplier, Date.valueOf(LocalDate.of(year, 3, 10)), preparerUser.id());
        // An approved budget of 365,000.00 on the invoice's expense account: 8,000.00 of it is used by the invoice.
        UUID fy = jdbc.queryForObject("SELECT id FROM fiscal_years WHERE fiscal_year = ?", UUID.class, year);
        UUID budget = UUID.randomUUID();
        jdbc.update("INSERT INTO budgets (id, fiscal_year_id, name, status, prepared_by_id, prepared_by, approved_at, approved_by, "
                + "created_at, updated_at, updated_by) VALUES (?, ?, 'Forecast test', 'APPROVED', ?, 'cfprep', now(), 'cfapp', now(), now(), 'cfprep')",
                budget, fy, preparerUser.id());
        jdbc.update("INSERT INTO budget_allocations (id, budget_id, account_id, amount) VALUES (?, ?, ?, 365000.00)",
                UUID.randomUUID(), budget, account("PLC-5203"));

        JsonNode after = forecast(url);
        assertThat(after.get("buckets")).hasSize(4);
        assertThat(after.at("/buckets/0/start").asText()).isEqualTo(year + "-01-01");
        assertThat(after.at("/buckets/3/end").asText()).isEqualTo(year + "-04-30");
        assertThat(delta(before, after, 1, "receivables")).isEqualByComparingTo("50000.00");
        assertThat(delta(before, after, 1, "payables")).isEqualByComparingTo("7920.00");
        assertThat(delta(before, after, 2, "vouchers")).isEqualByComparingTo("3000.00");
        // 357,000.00 left, spread over 365 days: January's 31 days, then February's 28.
        BigDecimal perDay = new BigDecimal("357000.00").divide(BigDecimal.valueOf(365), 10, RoundingMode.HALF_UP);
        assertThat(amount(after, 0, "budget")).isEqualByComparingTo(perDay.multiply(BigDecimal.valueOf(31)).setScale(2, RoundingMode.HALF_UP));
        assertThat(amount(after, 1, "budget")).isEqualByComparingTo(perDay.multiply(BigDecimal.valueOf(28)).setScale(2, RoundingMode.HALF_UP));
        // Each bucket opens at the previous close.
        for (int b = 1; b < 4; b++) {
            assertThat(amount(after, b, "opening")).isEqualByComparingTo(amount(after, b - 1, "closing"));
        }
        assertThat(itemSources(after, billing)).containsExactly("RECEIVABLE");
        assertThat(itemSources(after, invoice)).containsExactly("PAYABLE");
        assertThat(itemSources(after, dv.toString())).containsExactly("VOUCHER");

        // Once the invoice is on a DV, the DV carries it and the payable is not counted twice.
        jdbc.update("INSERT INTO disbursement_voucher_lines (id, dv_id, invoice_id, amount) VALUES (?, ?, ?::uuid, 7920.00)",
                UUID.randomUUID(), dv, invoice);
        JsonNode onDv = forecast(url);
        assertThat(itemSources(onDv, invoice)).isEmpty();
        assertThat(delta(after, onDv, 1, "payables")).isEqualByComparingTo("-7920.00");

        // Without the budget, the expenditure line is zero.
        JsonNode noBudget = forecast(url + "&includeBudget=false");
        assertThat(amount(noBudget, 0, "budget")).isZero();
        assertThat(noBudget.get("notes").toString()).doesNotContain("Budgeted spending");
    }

    @Test
    void weeksRunMondayToSundayAndTheRequestIsChecked() throws Exception {
        List<LocalDate[]> weeks = CashForecastService.ranges(LocalDate.of(2026, 10, 28), CashForecastService.Granularity.WEEK, 3);
        assertThat(weeks.get(0)).containsExactly(LocalDate.of(2026, 10, 28), LocalDate.of(2026, 11, 1));
        assertThat(weeks.get(1)).containsExactly(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 8));
        assertThat(weeks.get(2)[1]).isEqualTo(LocalDate.of(2026, 11, 15));

        JsonNode f = forecast("/api/v1/treasury/cash-forecast");
        assertThat(f.get("granularity").asText()).isEqualTo("WEEK");
        assertThat(f.get("buckets")).hasSize(13);
        mvc.perform(get("/api/v1/treasury/cash-forecast?periods=54").header("Authorization", preparer.bearer()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/treasury/cash-forecast").header("Authorization", login(createUser("cfsupport", "SUPPORT_AGENT")).bearer()))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode forecast(String url) throws Exception {
        return body(mvc.perform(get(url).header("Authorization", preparer.bearer())).andExpect(status().isOk()).andReturn());
    }

    private static BigDecimal amount(JsonNode f, int bucket, String field) {
        return new BigDecimal(f.at("/buckets/" + bucket + "/" + field).asText());
    }

    private static BigDecimal delta(JsonNode before, JsonNode after, int bucket, String field) {
        return amount(after, bucket, field).subtract(amount(before, bucket, field));
    }

    private static List<String> itemSources(JsonNode f, String id) {
        List<String> out = new java.util.ArrayList<>();
        f.get("items").forEach(i -> {
            if (i.get("id").asText().equals(id)) {
                out.add(i.get("source").asText());
            }
        });
        return out;
    }
}
