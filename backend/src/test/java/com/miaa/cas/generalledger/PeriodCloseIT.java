package com.miaa.cas.generalledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.generalledger.domain.AccountingPeriod;
import com.miaa.cas.support.AbstractAccountingIT;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class PeriodCloseIT extends AbstractAccountingIT {

    @Test
    void closeRequiresCleanChecklistOrJustifiedWaiverAndBlocksPosting() throws Exception {
        int year = newOpenYear();
        // An approved-but-unposted voucher in March.
        JsonNode v = createVoucher(preparer, voucher("STANDARD", LocalDate.of(year, 3, 20), utilitiesLines("80.00")));
        String id = v.get("id").asText();
        attachPdf(preparer, id);
        v = ok(action(preparer, id, "submit", v.get("version").asLong(), null));
        v = ok(action(reviewer, id, "approve", v.get("version").asLong(), null));
        v = ok(action(approver, id, "approve", v.get("version").asLong(), null));

        AccountingPeriod march = period(year, 3);
        JsonNode checklist = body(mvc.perform(get("/api/v1/accounting-periods/" + march.getId() + "/close-checklist")
                .header("Authorization", approver.bearer())).andExpect(status().isOk()).andReturn());
        assertThat(checklist.get("readyToClose").asBoolean()).isFalse();
        assertThat(checklist.toString()).contains("NO_UNPOSTED_VOUCHERS", "PRIOR_PERIODS_CLOSED", "LEDGER_CACHE_CONSISTENT",
                "BANK_RECONCILIATIONS", "DEPRECIATION_POSTED", "AP_RECONCILED", "AR_RECONCILED", "FA_RECONCILED", "INVENTORY_RECONCILED");
        assertThat(checklist.toString()).doesNotContain("Module not yet implemented");

        closePeriod(approver, march, List.of(), null).andExpect(status().isUnprocessableEntity());
        closePeriod(approver, march, List.of("NO_UNPOSTED_VOUCHERS", "PRIOR_PERIODS_CLOSED"), "short")
                .andExpect(status().isUnprocessableEntity());
        closePeriod(approver, march, withSharedDataWaivers("NO_UNPOSTED_VOUCHERS", "PRIOR_PERIODS_CLOSED"),
                "Voucher deferred to April by the Chief Accountant").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        postVoucher(poster, id, v.get("version").asLong(), "closed-" + UUID.randomUUID())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations[?(@ =~ /.*is closed.*/)]").exists());
        assertThat(jdbc.queryForObject("SELECT waived_checks::text FROM period_status_changes WHERE period_id = ? "
                + "AND change_type = 'CLOSE'", String.class, march.getId())).contains("NO_UNPOSTED_VOUCHERS");
    }

    @Test
    void reopenNeedsASecondPerson() throws Exception {
        int year = newOpenYear();
        AccountingPeriod jan = period(year, 1);
        // Other tests' fiscal years stay open, so the prior-period check is waived with a reason here.
        closePeriod(approver, jan, withSharedDataWaivers("PRIOR_PERIODS_CLOSED"), "Earlier test fiscal years are out of scope")
                .andExpect(status().isOk());

        TestUser both = createUser("closer", "CHIEF_ACCOUNTANT", "FINANCE_MANAGER");
        Session closer = login(both);
        JsonNode req = body(mvc.perform(post("/api/v1/accounting-periods/" + jan.getId() + "/reopen-requests")
                .header("Authorization", closer.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("reason", "Late supplier invoice must be booked in January"))))
                .andExpect(status().isCreated()).andReturn());
        String requestId = req.get("id").asText();
        mvc.perform(post("/api/v1/period-reopen-requests/" + requestId + "/decision").header("Authorization", closer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("approve", true))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SEGREGATION_OF_DUTIES"));
        // Chief accountants lack PERIOD_REOPEN; a finance manager decides.
        mvc.perform(post("/api/v1/period-reopen-requests/" + requestId + "/decision").header("Authorization", approver.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("approve", true))))
                .andExpect(status().isForbidden());
        Session manager = login(createUser("fm", "FINANCE_MANAGER"));
        mvc.perform(post("/api/v1/period-reopen-requests/" + requestId + "/decision").header("Authorization", manager.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("approve", true, "note", "Agreed"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.requestStatus").value("APPROVED"));
        assertThat(periods.findById(jan.getId()).orElseThrow().getStatus().name()).isEqualTo("OPEN");
    }

    @Test
    void softClosedPeriodAcceptsOnlyAdjustments() throws Exception {
        int year = newOpenYear();
        AccountingPeriod may = period(year, 5);
        mvc.perform(post("/api/v1/accounting-periods/" + may.getId() + "/soft-close").header("Authorization", approver.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", may.getVersion()))))
                .andExpect(status().isOk());
        JsonNode standard = createVoucher(preparer, voucher("STANDARD", LocalDate.of(year, 5, 2), utilitiesLines("5.00")));
        attachPdf(preparer, standard.get("id").asText());
        action(preparer, standard.get("id").asText(), "submit", standard.get("version").asLong(), null)
                .andExpect(status().isUnprocessableEntity());
        JsonNode adjusting = postThroughWorkflow(voucher("ADJUSTING", LocalDate.of(year, 5, 2), utilitiesLines("5.00")));
        assertThat(adjusting.get("status").asText()).isEqualTo("POSTED");
    }

    @Test
    void yearEndClosingZeroesNominalAccounts() throws Exception {
        int year = newOpenYear();
        postThroughWorkflow(voucher("STANDARD", LocalDate.of(year, 4, 1), List.of(
                line(account("PLC-1102"), "10000.00", "0"),
                line(account("PLC-4201"), null, customer(), "0", "10000.00"))));
        postThroughWorkflow(voucher("STANDARD", LocalDate.of(year, 6, 1), utilitiesLines("3500.00")));

        UUID fyId = period(year, 1).getFiscalYearId();
        JsonNode closing = body(mvc.perform(post("/api/v1/fiscal-years/" + fyId + "/closing-voucher")
                .header("Authorization", approver.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("surplusAccountId", account("PLC-3102"))))).andExpect(status().isCreated()).andReturn());
        assertThat(closing.get("voucherType").asText()).isEqualTo("CLOSING");
        assertThat(closing.get("periodName").asText()).isEqualTo("Adjustments " + year);
        assertThat(closing.get("totalDebit").asText()).isEqualTo("10000.00");
        String cid = closing.get("id").asText();
        closing = ok(action(reviewer, cid, "approve", closing.get("version").asLong(), null));
        closing = ok(action(poster, cid, "approve", closing.get("version").asLong(), null));
        Session chief3 = login(createUser("chief3", "CHIEF_ACCOUNTANT"));
        ok(postVoucher(chief3, cid, closing.get("version").asLong(), "close-" + UUID.randomUUID()));

        JsonNode tb = body(mvc.perform(get("/api/v1/general-ledger/trial-balance").param("periodId",
                period(year, 13).getId().toString()).header("Authorization", approver.bearer())).andReturn());
        for (JsonNode row : tb.get("rows")) {
            if (List.of("REVENUE", "EXPENSE").contains(row.get("accountType").asText())) {
                assertThat(row.get("closingDebit").asText()).isEqualTo("0.00");
                assertThat(row.get("closingCredit").asText()).isEqualTo("0.00");
            }
            if ("PLC-3102".equals(row.get("code").asText())) {
                assertThat(row.get("closingCredit").asText()).isEqualTo("6500.00");
            }
        }

        // Close every period, then the fiscal year.
        for (int n = 1; n <= 13; n++) {
            closePeriod(approver, period(year, n), withSharedDataWaivers("PRIOR_PERIODS_CLOSED"),
                    "Earlier test fiscal years are out of scope").andExpect(status().isOk());
        }
        mvc.perform(post("/api/v1/fiscal-years/" + fyId + "/close").header("Authorization", approver.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", 0))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));
    }

    /**
     * Module checks look at the whole shared test database, where other tests leave assets, bank accounts and
     * sub-ledger balances; they are waived here so these tests exercise the close mechanics only.
     */
    private static List<String> withSharedDataWaivers(String... codes) {
        List<String> out = new java.util.ArrayList<>(List.of(codes));
        out.addAll(List.of("BANK_RECONCILIATIONS", "DEPRECIATION_POSTED", "AP_RECONCILED", "AR_RECONCILED", "FA_RECONCILED",
                "INVENTORY_RECONCILED", "OPEN_ITEMS_AGED"));
        return out;
    }

    private org.springframework.test.web.servlet.ResultActions closePeriod(Session who, AccountingPeriod p, List<String> waive,
            String justification) throws Exception {
        long version = periods.findById(p.getId()).orElseThrow().getVersion();
        Map<String, Object> body = new HashMap<>();
        body.put("version", version);
        body.put("waivedChecks", waive);
        body.put("justification", justification);
        return mvc.perform(post("/api/v1/accounting-periods/" + p.getId() + "/close").header("Authorization", who.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(body)));
    }
}
