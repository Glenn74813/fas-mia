package com.miaa.cas.accountspayable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.support.AbstractPayablesIT;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class PayablesIT extends AbstractPayablesIT {

    @Test
    void invoiceIsTaxedApprovedAndPostedToTheLedger() throws Exception {
        int year = newOpenYear();
        String supplier = createSupplier(true).get("id").asText();
        JsonNode inv = body(mvc.perform(post("/api/v1/accounts-payable/invoices").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(invoice(supplier, "SI-0001", LocalDate.of(year, 3, 10),
                        List.of(invoiceLine("PLC-5203", "6000.00"), invoiceLine("PLC-5204", "4000.00")), true))))
                .andExpect(status().isCreated()).andReturn());
        // Net 10,000.00; input VAT 12% = 1,200.00; EWT 1% (supplier default) = 100.00; payable 11,100.00.
        assertThat(inv.get("netAmount").asText()).isEqualTo("10000.00");
        assertThat(inv.get("vatAmount").asText()).isEqualTo("1200.00");
        assertThat(inv.get("ewtAmount").asText()).isEqualTo("100.00");
        assertThat(inv.get("payableAmount").asText()).isEqualTo("11100.00");
        String id = inv.get("id").asText();

        apAction(preparer, "accounts-payable/invoices", id, "submit", inv.get("version").asLong(), null)
                .andExpect(status().isUnprocessableEntity());
        attach(preparer, "accounts-payable/invoices", id);
        inv = ok(apAction(preparer, "accounts-payable/invoices", id, "submit", inv.get("version").asLong(), null));
        apAction(reviewer, "accounts-payable/invoices", id, "approve", inv.get("version").asLong(), null)
                .andExpect(status().isForbidden());
        inv = ok(apAction(approver, "accounts-payable/invoices", id, "approve", inv.get("version").asLong(), "OK"));
        assertThat(inv.get("status").asText()).isEqualTo("APPROVED");
        inv = ok(apAction(poster, "accounts-payable/invoices", id, "post", inv.get("version").asLong(), null));
        assertThat(inv.get("status").asText()).isEqualTo("POSTED");

        JsonNode entry = body(mvc.perform(get("/api/v1/general-ledger/entries/" + inv.get("postedEntryId").asText())
                .header("Authorization", approver.bearer())).andExpect(status().isOk()).andReturn());
        assertThat(entry.get("voucherType").asText()).isEqualTo("SUBLEDGER");
        assertThat(entry.get("totalDebit").asText()).isEqualTo("11200.00");
        JsonNode apLine = entry.get("lines").get(entry.get("lines").size() - 1);
        assertThat(apLine.get("accountCode").asText()).isEqualTo("PLC-2101");
        assertThat(apLine.get("credit").asText()).isEqualTo("11100.00");
        assertThat(apLine.get("subsidiaryCode").asText()).startsWith("SUP");

        // Sub-ledger entries can only be corrected from their source document.
        mvc.perform(post("/api/v1/journal-entries/" + inv.get("postedEntryId").asText() + "/reversal")
                .header("Authorization", approver.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("accountingDate", year + "-03-11", "adjustmentPeriod", false, "reason", "Not allowed here"))))
                .andExpect(status().isUnprocessableEntity());

        mvc.perform(get("/api/v1/accounts-payable/withholding-schedule").param("year", String.valueOf(year)).param("quarter", "1")
                .header("Authorization", approver.bearer()))
                .andExpect(jsonPath("$.rows[0].taxCode").value("T-EWT-1"))
                .andExpect(jsonPath("$.rows[0].month3Base").value("10000.00"))
                .andExpect(jsonPath("$.rows[0].taxWithheld").value("100.00"));
    }

    @Test
    void duplicateInvoiceNumbersAreRefused() throws Exception {
        int year = newOpenYear();
        String supplier = createSupplier(false).get("id").asText();
        var body = invoice(supplier, "DUP-77", LocalDate.of(year, 2, 1), List.of(invoiceLine("PLC-5203", "500.00")), false);
        mvc.perform(post("/api/v1/accounts-payable/invoices").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(body))).andExpect(status().isCreated());
        body.put("invoiceNumber", "dup-77");
        mvc.perform(post("/api/v1/accounts-payable/invoices").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(body)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"));
        // Input VAT cannot be claimed from a supplier that is not VAT-registered.
        var vatBody = invoice(supplier, "VAT-1", LocalDate.of(year, 2, 1), List.of(invoiceLine("PLC-5203", "500.00")), true);
        mvc.perform(post("/api/v1/accounts-payable/invoices").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(vatBody))).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void disbursementSeparatesAuthorisationPaymentAndPosting() throws Exception {
        int year = newOpenYear();
        String supplier = createSupplier(false).get("id").asText();
        JsonNode inv = postedInvoice(supplier, "PAY-1", LocalDate.of(year, 4, 1), "5000.00");
        assertThat(inv.get("payableAmount").asText()).isEqualTo("4950.00");
        String invId = inv.get("id").asText();

        // Over-allocation is refused.
        mvc.perform(post("/api/v1/disbursement-vouchers").header("Authorization", disbursementOfficer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(dv(supplier, invId, "4950.01"))))
                .andExpect(status().isUnprocessableEntity());
        JsonNode dv = body(mvc.perform(post("/api/v1/disbursement-vouchers").header("Authorization", disbursementOfficer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(dv(supplier, invId, "4950.00"))))
                .andExpect(status().isCreated()).andReturn());
        String id = dv.get("id").asText();
        // A second voucher cannot commit the same balance.
        mvc.perform(post("/api/v1/disbursement-vouchers").header("Authorization", disbursementOfficer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(dv(supplier, invId, "1.00"))))
                .andExpect(status().isUnprocessableEntity());

        attach(disbursementOfficer, "disbursement-vouchers", id);
        dv = ok(apAction(disbursementOfficer, "disbursement-vouchers", id, "submit", dv.get("version").asLong(), null));
        assertThat(dv.get("currentStep").asText()).isEqualTo("BUDGET");
        // The Budget Division certifies first; a reviewer cannot take that step.
        apAction(reviewer, "disbursement-vouchers", id, "approve", dv.get("version").asLong(), null).andExpect(status().isForbidden());
        dv = budgetCertify(id, dv.get("version").asLong());
        assertThat(dv.get("currentStep").asText()).isEqualTo("CERTIFY");
        dv = ok(apAction(reviewer, "disbursement-vouchers", id, "approve", dv.get("version").asLong(), "Documents complete"));
        assertThat(dv.get("currentStep").asText()).isEqualTo("APPROVE");
        apAction(reviewer, "disbursement-vouchers", id, "approve", dv.get("version").asLong(), null).andExpect(status().isForbidden());
        dv = ok(apAction(financeManager, "disbursement-vouchers", id, "approve", dv.get("version").asLong(), "Approved for payment"));
        assertThat(dv.get("status").asText()).isEqualTo("APPROVED");
        assertThat(dv.get("dvNumber").asText()).matches("DV-\\d{4}-\\d{6}");

        // Approval alone is not payment: the cashier issues the check, can void it, and confirms release.
        String check = "CHK" + System.nanoTime() % 1_000_000;
        dv = ok(instruct(cashier, id, dv.get("version").asLong(), check, year));
        assertThat(dv.get("status").asText()).isEqualTo("PAYMENT_INSTRUCTED");
        dv = ok(mvc.perform(post("/api/v1/disbursement-vouchers/" + id + "/payment-void").header("Authorization", cashier.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", dv.get("version").asLong(),
                        "reason", "Check misprinted")))));
        assertThat(dv.get("status").asText()).isEqualTo("APPROVED");
        instruct(cashier, id, dv.get("version").asLong(), check, year).andExpect(status().isConflict());
        dv = ok(instruct(cashier, id, dv.get("version").asLong(), check + "B", year));
        dv = approveCheck(id, dv.get("version").asLong());
        dv = ok(mvc.perform(post("/api/v1/disbursement-vouchers/" + id + "/payment-confirmation")
                .header("Authorization", cashier.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("version", dv.get("version").asLong(), "releasedOn", year + "-04-20")))));
        assertThat(dv.get("status").asText()).isEqualTo("PAID");
        apAction(cashier, "disbursement-vouchers", id, "post", dv.get("version").asLong(), null).andExpect(status().isForbidden());
        dv = ok(apAction(poster, "disbursement-vouchers", id, "post", dv.get("version").asLong(), null));
        assertThat(dv.get("status").asText()).isEqualTo("POSTED");

        assertThat(jdbc.queryForObject("SELECT status FROM check_register WHERE check_number = ?", String.class, check))
                .isEqualTo("VOIDED");
        assertThat(jdbc.queryForObject("SELECT status FROM check_register WHERE check_number = ?", String.class, check + "B"))
                .isEqualTo("RELEASED");
        assertThat(jdbc.queryForObject("SELECT paid_amount FROM supplier_invoices WHERE id = ?::uuid", BigDecimal.class, invId))
                .isEqualByComparingTo("4950.00");
        List<String> actions = jdbc.queryForList("SELECT action FROM approval_decisions WHERE document_id = ?::uuid ORDER BY decided_at",
                String.class, id);
        assertThat(actions).containsExactly("SUBMIT", "APPROVE", "APPROVE", "APPROVE", "INSTRUCT", "VOID", "INSTRUCT", "APPROVE_CHECK",
                "CONFIRM", "POST");

        JsonNode recon = body(mvc.perform(get("/api/v1/accounts-payable/reconciliation").header("Authorization", approver.bearer()))
                .andExpect(status().isOk()).andReturn());
        assertThat(recon.get("reconciled").asBoolean()).isTrue();
        JsonNode aging = body(mvc.perform(get("/api/v1/accounts-payable/aging").param("asOf", year + "-04-10")
                .header("Authorization", approver.bearer())).andReturn());
        assertThat(aging.toString()).contains("4950.00");
    }

    @Test
    void cashierCannotPrepareAndAccountantCannotRelease() throws Exception {
        mvc.perform(get("/api/v1/disbursement-vouchers").header("Authorization", cashier.bearer())).andExpect(status().isOk());
        mvc.perform(post("/api/v1/disbursement-vouchers").header("Authorization", cashier.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(dv(UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(), "1.00")))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/disbursement-vouchers/00000000-0000-0000-0000-000000000000/payment-confirmation")
                .header("Authorization", preparer.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("version", 0, "releasedOn", "2026-01-01")))).andExpect(status().isForbidden());
    }

    private Map<String, Object> dv(String supplier, String invoiceId, String amount) {
        Map<String, Object> m = new HashMap<>();
        m.put("supplierId", supplier);
        m.put("particulars", "Payment of supplier invoice");
        m.put("allocations", List.of(Map.of("invoiceId", invoiceId, "amount", amount)));
        return m;
    }

    private org.springframework.test.web.servlet.ResultActions instruct(Session who, String id, long version, String check,
            int year) throws Exception {
        return mvc.perform(post("/api/v1/disbursement-vouchers/" + id + "/payment-instruction").header("Authorization", who.bearer()).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", version, "mode", "CHECK",
                        "bankAccountId", bank(), "reference", check, "paymentDate", year + "-04-15"))));
    }
}
