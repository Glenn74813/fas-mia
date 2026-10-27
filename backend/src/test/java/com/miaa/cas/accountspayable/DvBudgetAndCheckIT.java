package com.miaa.cas.accountspayable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.support.AbstractPayablesIT;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * TOR Annex A: the Budget Division certifies in-process DVs against the Corporate Operating Budget, and the checks for
 * processed DVs are prepared and approved by two different people before release.
 */
class DvBudgetAndCheckIT extends AbstractPayablesIT {

    @Test
    void theBudgetDivisionCertifiesTheDvAgainstTheCob() throws Exception {
        int year = newOpenYear();
        UUID fy = period(year, 1).getFiscalYearId();
        approvedBudget(fy, "10000.00");
        String supplier = createSupplier(false).get("id").asText();
        String inv = postedInvoice(supplier, "BC-" + year, LocalDate.of(year, 3, 1), "5000.00").get("id").asText();
        // 1 % is withheld, so 4,950 is payable; this DV pays half of it.
        JsonNode dv = submittedDv(supplier, inv, "2475.00");
        String id = dv.get("id").asText();

        // The DV shows its budget line: half of the invoice's 5,000 expense; the invoice already counts as used.
        assertThat(dv.get("budget")).as(dv.get("budget").toString()).hasSize(1);
        assertThat(dv.get("currentStep").asText()).isEqualTo("BUDGET");
        JsonNode line = dv.get("budget").get(0);
        assertThat(line.get("accountCode").asText()).isEqualTo("PLC-5203");
        assertThat(line.get("costCenterCode").asText()).isEqualTo("PLC-CC-FIN");
        assertThat(new java.math.BigDecimal(line.get("charged").asText())).as(line.toString()).isEqualByComparingTo("2500.00");
        assertThat(line.get("status").asText()).isEqualTo("ALLOCATED");
        assertThat(new java.math.BigDecimal(line.get("allotment").asText())).isEqualByComparingTo("10000.00");
        assertThat(new java.math.BigDecimal(line.get("used").asText())).isEqualByComparingTo("5000.00");
        assertThat(new java.math.BigDecimal(line.get("available").asText())).isEqualByComparingTo("5000.00");

        // The budget officer can read the voucher and finds it in the inbox; others cannot take the step.
        JsonNode seen = body(mvc.perform(get("/api/v1/disbursement-vouchers/" + id).header("Authorization", budgetOfficer.bearer()))
                .andExpect(status().isOk()).andReturn());
        assertThat(seen.get("availableActions").toString()).contains("APPROVE");
        assertThat(inboxQueues(budgetOfficer)).contains("Disbursement vouchers to budget");
        apAction(reviewer, "disbursement-vouchers", id, "approve", dv.get("version").asLong(), null).andExpect(status().isForbidden());

        // Returned by the budget officer with a reason, resubmitted, then certified.
        dv = ok(apAction(budgetOfficer, "disbursement-vouchers", id, "return", dv.get("version").asLong(), "Charge the 2nd quarter allotment"));
        assertThat(dv.get("status").asText()).isEqualTo("RETURNED");
        dv = ok(apAction(disbursementOfficer, "disbursement-vouchers", id, "submit", dv.get("version").asLong(), null));
        dv = budgetCertify(id, dv.get("version").asLong());
        assertThat(dv.get("currentStep").asText()).isEqualTo("CERTIFY");
        assertThat(dv.get("decisions").toString()).contains("BUDGET");
    }

    @Test
    void aCheckIsPreparedByOnePersonAndApprovedByAnother() throws Exception {
        int year = newOpenYear();
        String supplier = createSupplier(false).get("id").asText();
        String inv = postedInvoice(supplier, "CK-" + year, LocalDate.of(year, 3, 1), "2000.00").get("id").asText();
        JsonNode dv = submittedDv(supplier, inv, "1980.00");
        String id = dv.get("id").asText();
        dv = budgetCertify(id, dv.get("version").asLong());
        dv = ok(apAction(reviewer, "disbursement-vouchers", id, "approve", dv.get("version").asLong(), "Certified"));
        dv = ok(apAction(financeManager, "disbursement-vouchers", id, "approve", dv.get("version").asLong(), "Approved"));

        String number = "CK" + System.nanoTime() % 1_000_000;
        dv = ok(instructCheck(id, dv.get("version").asLong(), number, year));
        assertThat(dv.at("/check/status").asText()).isEqualTo("PREPARED");
        assertThat(dv.at("/check/preparedBy").asText()).startsWith("cash");
        assertThat(dv.get("availableActions").toString()).doesNotContain("CONFIRM");

        // Not released before approval; the cashier cannot approve; nor can whoever prepared the check.
        confirm(id, dv.get("version").asLong(), year).andExpect(status().isConflict());
        checkApproval(cashier, id, dv.get("version").asLong()).andExpect(status().isForbidden());
        assertThat(inboxQueues(financeManager)).contains("Checks to approve");
        UUID cashierId = jdbc.queryForObject("SELECT prepared_by_id FROM check_register WHERE dv_id = ?::uuid", UUID.class, id);
        UUID managerId = jdbc.queryForObject("SELECT actor_id FROM approval_decisions WHERE document_id = ?::uuid "
                + "AND action = 'APPROVE' AND step_code = 'APPROVE'", UUID.class, id);
        jdbc.update("UPDATE check_register SET prepared_by_id = ? WHERE dv_id = ?::uuid", managerId, id);
        checkApproval(financeManager, id, dv.get("version").asLong()).andExpect(status().isForbidden());
        jdbc.update("UPDATE check_register SET prepared_by_id = ? WHERE dv_id = ?::uuid", cashierId, id);

        dv = approveCheck(id, dv.get("version").asLong());
        assertThat(dv.at("/check/status").asText()).isEqualTo("ISSUED");
        assertThat(dv.at("/check/approvedBy").asText()).startsWith("fm");
        assertThat(dv.get("availableActions").toString()).doesNotContain("APPROVE_CHECK");
        checkApproval(financeManager, id, dv.get("version").asLong()).andExpect(status().isConflict());
        dv = ok(confirm(id, dv.get("version").asLong(), year));
        assertThat(dv.get("status").asText()).isEqualTo("PAID");
        assertThat(dv.at("/check/status").asText()).isEqualTo("RELEASED");
        assertThat(dv.get("decisions").toString()).contains("APPROVE_CHECK");
    }

    @Test
    void aPreparedCheckCanBeVoided() throws Exception {
        int year = newOpenYear();
        String supplier = createSupplier(false).get("id").asText();
        String inv = postedInvoice(supplier, "CV-" + year, LocalDate.of(year, 3, 1), "800.00").get("id").asText();
        JsonNode dv = submittedDv(supplier, inv, "792.00");
        String id = dv.get("id").asText();
        dv = budgetCertify(id, dv.get("version").asLong());
        dv = ok(apAction(reviewer, "disbursement-vouchers", id, "approve", dv.get("version").asLong(), "Certified"));
        dv = ok(apAction(financeManager, "disbursement-vouchers", id, "approve", dv.get("version").asLong(), "Approved"));
        dv = ok(instructCheck(id, dv.get("version").asLong(), "CV" + System.nanoTime() % 1_000_000, year));
        dv = ok(mvc.perform(post("/api/v1/disbursement-vouchers/" + id + "/payment-void").header("Authorization", cashier.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", dv.get("version").asLong(),
                        "reason", "Wrong check stock")))));
        assertThat(dv.get("status").asText()).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT status FROM check_register WHERE dv_id = ?::uuid", String.class, id)).isEqualTo("VOIDED");
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode submittedDv(String supplier, String invoiceId, String amount) throws Exception {
        JsonNode dv = body(mvc.perform(post("/api/v1/disbursement-vouchers").header("Authorization", disbursementOfficer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("supplierId", supplier, "particulars", "Payment",
                        "allocations", List.of(Map.of("invoiceId", invoiceId, "amount", amount)))))).andExpect(status().isCreated()).andReturn());
        String id = dv.get("id").asText();
        attach(disbursementOfficer, "disbursement-vouchers", id);
        return ok(apAction(disbursementOfficer, "disbursement-vouchers", id, "submit", dv.get("version").asLong(), null));
    }

    private ResultActions instructCheck(String id, long version, String number, int year) throws Exception {
        return mvc.perform(post("/api/v1/disbursement-vouchers/" + id + "/payment-instruction").header("Authorization", cashier.bearer())
                .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", version,
                        "mode", "CHECK", "bankAccountId", bank(), "reference", number, "paymentDate", year + "-04-15"))));
    }

    private ResultActions confirm(String id, long version, int year) throws Exception {
        return mvc.perform(post("/api/v1/disbursement-vouchers/" + id + "/payment-confirmation").header("Authorization", cashier.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", version, "releasedOn", year + "-04-20"))));
    }

    private ResultActions checkApproval(Session who, String id, long version) throws Exception {
        return mvc.perform(post("/api/v1/disbursement-vouchers/" + id + "/check-approval").header("Authorization", who.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", version))));
    }

    private String inboxQueues(Session who) throws Exception {
        return body(mvc.perform(get("/api/v1/inbox").header("Authorization", who.bearer())).andExpect(status().isOk()).andReturn())
                .get("queues").toString();
    }

    private void approvedBudget(UUID fy, String amount) throws Exception {
        Session preparerOfBudget = login(createUser("bgprep", "BUDGET_OFFICER"));
        JsonNode b = body(mvc.perform(post("/api/v1/budgets").header("Authorization", preparerOfBudget.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("fiscalYearId", fy, "name", "COB"))))
                .andExpect(status().isCreated()).andReturn());
        String bid = b.get("id").asText();
        Map<String, Object> alloc = new HashMap<>();
        alloc.put("accountId", account("PLC-5203"));
        alloc.put("costCenterId", costCenter("PLC-CC-FIN"));
        alloc.put("amount", amount);
        b = ok(mvc.perform(put("/api/v1/budgets/" + bid + "/allocations").header("Authorization", preparerOfBudget.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", b.get("version").asLong(), "allocations", List.of(alloc))))));
        b = ok(mvc.perform(post("/api/v1/budgets/" + bid + "/submit").header("Authorization", preparerOfBudget.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", b.get("version").asLong())))));
        ok(mvc.perform(post("/api/v1/budgets/" + bid + "/approve").header("Authorization", financeManager.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", b.get("version").asLong())))));
    }
}
