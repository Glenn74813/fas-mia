package com.miaa.cas.budgeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.support.AbstractPayablesIT;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * Budget reservation under concurrent submissions (Phase 3 exit gate): the allotment is never
 * over-committed, and reservations touching several budget lines in different orders do not deadlock.
 */
@TestPropertySource(properties = "cas.budget.enforcement=BLOCK")
class BudgetConcurrencyIT extends AbstractPayablesIT {

    @Test
    void simultaneousSubmissionsNeverOverCommitTheAllotment() throws Exception {
        int year = newOpenYear();
        approvedBudget(year, "10000.00", "10000.00");
        String supplier = createSupplier(false).get("id").asText();
        List<JsonNode> invoices = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            invoices.add(draft(supplier, "C" + i, year, List.of(invoiceLine("PLC-5203", "2000.00"))));
        }
        List<Integer> statuses = submitTogether(invoices);
        // 6 x 2,000 against 10,000: exactly five reserve, one is refused.
        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(5);
        assertThat(statuses.stream().filter(s -> s == 422).count()).isEqualTo(1);
        assertThat(reserved(year, "PLC-5203")).isEqualByComparingTo("10000.00");
    }

    @Test
    void multiLineReservationsInOppositeOrdersDoNotDeadlock() throws Exception {
        int year = newOpenYear();
        approvedBudget(year, "100000.00", "100000.00");
        String supplier = createSupplier(false).get("id").asText();
        List<JsonNode> invoices = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            List<Map<String, Object>> lines = i % 2 == 0
                    ? List.of(invoiceLine("PLC-5203", "1000.00"), invoiceLine("PLC-5201", "500.00"))
                    : List.of(invoiceLine("PLC-5201", "500.00"), invoiceLine("PLC-5203", "1000.00"));
            invoices.add(draft(supplier, "D" + i, year, lines));
        }
        List<Integer> statuses = submitTogether(invoices);
        assertThat(statuses).containsOnly(200);
        assertThat(reserved(year, "PLC-5203")).isEqualByComparingTo("6000.00");
        assertThat(reserved(year, "PLC-5201")).isEqualByComparingTo("3000.00");
    }

    // ------------------------------------------------------------------ helpers

    /** Submits every invoice at once from separate threads and returns the HTTP statuses. */
    private List<Integer> submitTogether(List<JsonNode> invoices) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(invoices.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (JsonNode inv : invoices) {
            Callable<Integer> c = () -> {
                start.await();
                return apAction(preparer, "accounts-payable/invoices", inv.get("id").asText(), "submit", inv.get("version").asLong(), null)
                        .andReturn().getResponse().getStatus();
            };
            results.add(pool.submit(c));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) {
            statuses.add(f.get(120, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return statuses;
    }

    private BigDecimal reserved(int year, String accountCode) {
        return jdbc.queryForObject("""
                SELECT coalesce(sum(r.amount), 0) FROM budget_reservations r JOIN budget_allocations a ON a.id = r.allocation_id
                JOIN budgets b ON b.id = a.budget_id JOIN fiscal_years y ON y.id = b.fiscal_year_id
                WHERE y.fiscal_year = ? AND a.account_id = ? AND r.status = 'ACTIVE'""", BigDecimal.class, year, account(accountCode));
    }

    private JsonNode draft(String supplier, String number, int year, List<Map<String, Object>> lines) throws Exception {
        JsonNode inv = body(mvc.perform(post("/api/v1/accounts-payable/invoices").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(invoice(supplier, number + "-" + UUID.randomUUID().toString().substring(0, 6),
                        LocalDate.of(year, 3, 1), lines, false)))).andExpect(status().isCreated()).andReturn());
        attach(preparer, "accounts-payable/invoices", inv.get("id").asText());
        return inv;
    }

    private void approvedBudget(int year, String professional, String utilities) throws Exception {
        Session budgetOfficer = login(createUser("bo", "BUDGET_OFFICER"));
        UUID fyId = period(year, 1).getFiscalYearId();
        JsonNode b = body(mvc.perform(post("/api/v1/budgets").header("Authorization", budgetOfficer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("fiscalYearId", fyId, "name", "COB " + year))))
                .andExpect(status().isCreated()).andReturn());
        String bid = b.get("id").asText();
        Map<String, Object> prof = new HashMap<>();
        prof.put("accountId", account("PLC-5203"));
        prof.put("costCenterId", costCenter("PLC-CC-FIN"));
        prof.put("amount", professional);
        Map<String, Object> util = new HashMap<>(prof);
        util.put("accountId", account("PLC-5201"));
        util.put("amount", utilities);
        b = body(mvc.perform(put("/api/v1/budgets/" + bid + "/allocations").header("Authorization", budgetOfficer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", b.get("version").asLong(),
                        "allocations", List.of(prof, util))))).andExpect(status().isOk()).andReturn());
        b = ok(mvc.perform(post("/api/v1/budgets/" + bid + "/submit").header("Authorization", budgetOfficer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", b.get("version").asLong())))));
        ok(mvc.perform(post("/api/v1/budgets/" + bid + "/approve").header("Authorization", financeManager.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", b.get("version").asLong())))));
    }
}
