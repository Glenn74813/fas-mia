package com.miaa.cas.financialreporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.audit.Actor;
import com.miaa.cas.support.AbstractReceivablesIT;
import com.miaa.cas.treasury.BankAccountService.BankAccountRequest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

/** The operational report jobs: CDJ, collection summary, GL and SL, DCR, bank reconciliation statement, withholding schedule. */
class OperationalReportsIT extends AbstractReceivablesIT {

    @Autowired ReportJobService jobs;

    @Test
    void operationalReportsRenderFromPostedRecords() throws Exception {
        int year = newOpenYear();
        LocalDate billed = LocalDate.of(year, 4, 1);
        LocalDate collected = LocalDate.of(year, 4, 10);
        Session accountant = preparer;
        Session chief = approver;

        // Receivables: a posted invoice and a posted collection.
        JsonNode customer = createCustomer(null);
        String cid = customer.get("id").asText();
        String code = customer.get("code").asText();
        JsonNode inv = postedBilling(billing("INVOICE", cid, billed, "PLC-4201", "1000.00", false, null));
        JsonNode rc = body(mvc.perform(post("/api/v1/receipts").header("Authorization", cashier.bearer()).header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(receipt(cid, collected, "1000.00", "0", List.of(alloc(inv.get("id").asText(), "1000.00"))))))
                .andExpect(status().isCreated()).andReturn());
        rc = ok(mvc.perform(post("/api/v1/receipts/" + rc.get("id").asText() + "/post").header("Authorization", poster.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", rc.get("version").asLong())))));
        String receiptNumber = rc.get("receiptNumber").asText();

        // Payables: a posted invoice paid by check through a posted DV.
        JsonNode supplier = createSupplier(false);
        String sid = supplier.get("id").asText();
        JsonNode sinv = postedInvoice(sid, "OPR-" + UUID.randomUUID().toString().substring(0, 6), billed, "5000.00");
        String dvId = postedDv(sid, sinv.get("id").asText(), sinv.get("payableAmount").asText(), year);
        String dvNumber = jdbc.queryForObject("SELECT dv_number FROM disbursement_vouchers WHERE id = ?::uuid", String.class, dvId);
        String checkNumber = jdbc.queryForObject("SELECT check_number FROM check_register WHERE dv_id = ?::uuid", String.class, dvId);

        String from = year + "-01-01";
        String to = year + "-12-31";
        String cdj = csv(chief, "CASH_DISBURSEMENT_JOURNAL", Map.of("from", from, "to", to));
        assertThat(cdj).contains("Cash Disbursement Journal", dvNumber, checkNumber, supplier.get("name").asText());

        String summary = csv(accountant, "COLLECTION_SUMMARY", Map.of("from", from, "to", to));
        assertThat(summary).contains("Collection summary", collected.toString());

        String gl = csv(chief, "GENERAL_LEDGER", Map.of("from", from, "to", to, "accountId", account("PLC-1201").toString()));
        assertThat(gl).contains("General ledger", "PLC-1201", "Opening balance", "Closing balance");

        String slId = customer.get("subsidiaryAccountId").asText();
        String sl = csv(chief, "SUBSIDIARY_LEDGER", Map.of("from", from, "to", to, "subsidiaryAccountId", slId));
        assertThat(sl).contains("Subsidiary ledger", code);

        String dcr = csv(cashier, "DAILY_COLLECTION_REPORT", Map.of("date", collected.toString()));
        assertThat(dcr).contains("Daily collection report", receiptNumber);

        String wht = csv(chief, "WITHHOLDING_SCHEDULE", Map.of("year", String.valueOf(year), "quarter", "2"));
        assertThat(wht).contains("Expanded withholding tax schedule", supplier.get("name").asText());

        // Aging filtered to one customer.
        JsonNode other = createCustomer(null);
        postedBilling(billing("INVOICE", other.get("id").asText(), billed, "PLC-4201", "300.00", false, null));
        String aging = csv(chief, "AR_AGING", Map.of("asOf", to, "customerId", other.get("id").asText()));
        assertThat(aging).contains(other.get("code").asText()).doesNotContain(code);

        // Bank reconciliation statement for a fresh bank account.
        String bankCode = "OPR" + System.nanoTime() % 100000;
        UUID bankId = tx.execute(s -> banks.create(new BankAccountRequest(bankCode, "Ops test bank", "Operations", "4321",
                account("PLC-1102")), Actor.SYSTEM).id());
        JsonNode st = body(mvc.perform(multipart("/api/v1/bank-statements")
                .file(new MockMultipartFile("file", "statement.csv", "text/plain",
                        ("date,description,reference,amount\n" + collected + ",Deposit,REF1,1000.00\n").getBytes(StandardCharsets.UTF_8)))
                .param("bankAccountId", bankId.toString()).param("format", "CSV").param("openingBalance", "0.00")
                .param("closingBalance", "1000.00").header("Authorization", preparer.bearer())).andExpect(status().isCreated()).andReturn());
        JsonNode rec = body(mvc.perform(post("/api/v1/bank-statements/" + st.at("/summary/id").asText() + "/reconciliation")
                .header("Authorization", preparer.bearer())).andExpect(status().isCreated()).andReturn());
        String brs = csv(chief, "BANK_RECONCILIATION_STATEMENT", Map.of("reconciliationId", rec.get("id").asText()));
        assertThat(brs).contains("Bank reconciliation statement", bankCode, "Adjusted bank balance", "Adjusted book balance");

        // Each report keeps its module's permission: a support agent cannot export the general ledger.
        Session agent = login(createUser("opragent", "SUPPORT_AGENT"));
        request(agent, "GENERAL_LEDGER", Map.of("from", from, "to", to)).andExpect(status().isForbidden());
        request(chief, "GENERAL_LEDGER", Map.of("from", to, "to", from)).andExpect(status().isBadRequest());
        request(chief, "SUBSIDIARY_LEDGER", Map.of("from", from, "to", to)).andExpect(status().isBadRequest());
    }

    private String postedDv(String supplier, String invoiceId, String amount, int year) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("supplierId", supplier);
        body.put("particulars", "Payment of supplier invoice");
        body.put("allocations", List.of(Map.of("invoiceId", invoiceId, "amount", amount)));
        JsonNode dv = body(mvc.perform(post("/api/v1/disbursement-vouchers").header("Authorization", disbursementOfficer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(body))).andExpect(status().isCreated()).andReturn());
        String id = dv.get("id").asText();
        attach(disbursementOfficer, "disbursement-vouchers", id);
        dv = ok(apAction(disbursementOfficer, "disbursement-vouchers", id, "submit", dv.get("version").asLong(), null));
        dv = budgetCertify(id, dv.get("version").asLong());
        dv = ok(apAction(reviewer, "disbursement-vouchers", id, "approve", dv.get("version").asLong(), "Documents complete"));
        dv = ok(apAction(financeManager, "disbursement-vouchers", id, "approve", dv.get("version").asLong(), "Approved"));
        dv = ok(mvc.perform(post("/api/v1/disbursement-vouchers/" + id + "/payment-instruction").header("Authorization", cashier.bearer()).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", dv.get("version").asLong(), "mode", "CHECK",
                        "bankAccountId", bank(), "reference", "CHK" + System.nanoTime() % 1_000_000, "paymentDate", year + "-04-15")))));
        dv = approveCheck(id, dv.get("version").asLong());
        dv = ok(mvc.perform(post("/api/v1/disbursement-vouchers/" + id + "/payment-confirmation").header("Authorization", cashier.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", dv.get("version").asLong(), "releasedOn", year + "-04-20")))));
        ok(apAction(poster, "disbursement-vouchers", id, "post", dv.get("version").asLong(), null));
        return id;
    }

    @Test
    void theAbstractOfBillsListsAPeriodsPostedBillsByCustomerWithCreditMemosNegative() throws Exception {
        int year = newOpenYear();
        String cid = createCustomerNamed("Abstract Lessee " + year).get("id").asText();
        JsonNode inv1 = postedBilling(billing("INVOICE", cid, LocalDate.of(year, 6, 1), "PLC-4203", "10000.00", false, null));
        JsonNode inv2 = postedBilling(billing("INVOICE", cid, LocalDate.of(year, 6, 15), "PLC-4203", "2500.00", false, null));
        postedBilling(billing("CREDIT_MEMO", cid, LocalDate.of(year, 6, 20), "PLC-4203", "500.00", false, inv1.get("id").asText()));
        postedBilling(billing("INVOICE", cid, LocalDate.of(year, 7, 1), "PLC-4203", "999.00", false, null)); // outside the period
        String csv = csv(preparer, "ABSTRACT_OF_BILLS", Map.of("from", year + "-06-01", "to", year + "-06-30"));
        assertThat(csv).contains("Abstract of bills").contains("Abstract Lessee " + year)
                .contains(inv1.get("documentNumber").asText()).contains(inv2.get("documentNumber").asText())
                .contains("-500.00").contains("12000.00").contains("Total, 3 bill(s)").doesNotContain("999.00").contains("PLACEHOLDER layout");
    }

    private ResultActions request(Session who, String type, Map<String, String> params) throws Exception {
        return mvc.perform(post("/api/v1/report-jobs").header("Authorization", who.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("reportType", type, "format", "CSV", "parameters", params))));
    }

    private String csv(Session who, String type, Map<String, String> params) throws Exception {
        String id = body(request(who, type, params).andExpect(status().isCreated()).andReturn()).get("id").asText();
        jobs.processPending();
        assertThat(jdbc.queryForObject("SELECT status || coalesce(': ' || error, '') FROM report_jobs WHERE id = ?::uuid", String.class, id))
                .isEqualTo("DONE");
        return new String(mvc.perform(get("/api/v1/report-jobs/" + id + "/file").header("Authorization", who.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }
}
