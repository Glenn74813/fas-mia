package com.miaa.cas.treasury;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

class TreasuryIT extends AbstractReceivablesIT {

    @Test
    void collectionsAreDepositedOnceAndReportedOnTheDcr() throws Exception {
        int year = newOpenYear();
        LocalDate day = LocalDate.of(year, 3, 5);
        String receipt = postedReceipt(year, day, "8000.00").get("id").asText();

        JsonNode undeposited = body(mvc.perform(get("/api/v1/treasury/undeposited-receipts").header("Authorization", cashier.bearer()))
                .andExpect(status().isOk()).andReturn());
        assertThat(undeposited.toString()).contains(receipt);

        JsonNode dep = body(deposit(cashier, bank(), day, receipt).andExpect(status().isCreated()).andReturn());
        assertThat(dep.get("depositNumber").asText()).matches("DEP-" + year + "-\\d{6}");
        assertThat(dep.get("amount").asText()).isEqualTo("8000.00");
        deposit(cashier, bank(), day, receipt).andExpect(status().isUnprocessableEntity());
        // Accounting posts it; the cashier cannot.
        String depId = dep.get("id").asText();
        mvc.perform(post("/api/v1/deposits/" + depId + "/post").header("Authorization", cashier.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", dep.get("version").asLong()))))
                .andExpect(status().isForbidden());
        dep = ok(mvc.perform(post("/api/v1/deposits/" + depId + "/post").header("Authorization", poster.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", dep.get("version").asLong())))));
        assertThat(dep.get("status").asText()).isEqualTo("POSTED");
        JsonNode entry = body(mvc.perform(get("/api/v1/general-ledger/entries/" + dep.get("postedEntryId").asText())
                .header("Authorization", approver.bearer())).andExpect(status().isOk()).andReturn());
        Map<String, JsonNode> lines = new HashMap<>();
        entry.get("lines").forEach(l -> lines.put(l.get("accountCode").asText(), l));
        assertThat(lines.get("PLC-1102").get("debit").asText()).isEqualTo("8000.00");
        assertThat(lines.get("PLC-1101").get("credit").asText()).isEqualTo("8000.00");

        JsonNode dcr = body(mvc.perform(get("/api/v1/treasury/daily-collection-report").param("date", day.toString())
                .header("Authorization", cashier.bearer())).andExpect(status().isOk()).andReturn());
        JsonNode line = null;
        for (JsonNode l : dcr.get("lines")) {
            if (l.get("depositNumber").asText().equals(dep.get("depositNumber").asText())) {
                line = l;
            }
        }
        assertThat(line).isNotNull();
        assertThat(line.get("amount").asText()).isEqualTo("8000.00");
        assertThat(dcr.get("totals").toString()).contains("CHECK");
    }

    @Test
    void statementImportIsValidatedAndReconciliationNeedsASecondPerson() throws Exception {
        int year = newOpenYear();
        UUID bankId = tx.execute(s -> banks.create(new BankAccountRequest("RB" + (System.nanoTime() % 100000), "Recon test bank",
                "Collections", "7788", account("PLC-1103")), Actor.SYSTEM).id());

        // Book side for April: a 10,000 deposit, and a 4,950 check paid through a DV.
        String receipt = postedReceipt(year, LocalDate.of(year, 4, 8), "10000.00").get("id").asText();
        JsonNode dep = body(deposit(cashier, bankId, LocalDate.of(year, 4, 10), receipt).andExpect(status().isCreated()).andReturn());
        ok(mvc.perform(post("/api/v1/deposits/" + dep.get("id").asText() + "/post").header("Authorization", poster.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", dep.get("version").asLong())))));
        String check = "77" + System.nanoTime() % 100000;
        payByCheck(year, bankId, check);

        // April statement: the deposit (no reference), the check clearing ten days later, and a bank charge.
        String april = camt(year, "0099887788", "4900.00",
                entry(year + "-04-10", "10000.00", "CRDT", null, "CASH DEPOSIT"),
                entry(year + "-04-25", "4950.00", "DBIT", check, "CHECK PAID"),
                entry(year + "-04-30", "150.00", "DBIT", null, "SERVICE CHARGE"));
        importFile(bankId, "CAMT053", april.replace("0099887788", "0099881111"), null, null).andExpect(status().isUnprocessableEntity());
        importFile(bankId, "CAMT053", april.replace(">4900.00<", ">4901.00<"), null, null).andExpect(status().isUnprocessableEntity());
        JsonNode st1 = body(importFile(bankId, "CAMT053", april, null, null).andExpect(status().isCreated()).andReturn());
        importFile(bankId, "CAMT053", april, null, null).andExpect(status().isConflict());
        String overlapping = "date,description,amount\n" + year + "-04-29,Overlap,1.00\n" + year + "-05-02,Overlap,-1.00\n";
        importFile(bankId, "CSV", overlapping, "4900.00", "4900.00").andExpect(status().isConflict());
        String st1Id = st1.at("/summary/id").asText();

        JsonNode rec = body(mvc.perform(post("/api/v1/bank-statements/" + st1Id + "/reconciliation")
                .header("Authorization", preparer.bearer())).andExpect(status().isCreated()).andReturn());
        String recId = rec.get("id").asText();
        JsonNode auto = ok(mvc.perform(post("/api/v1/bank-reconciliations/" + recId + "/auto-match").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", rec.get("version").asLong())))));
        // Deposit by amount and date; the check by its number despite clearing outside the date window.
        assertThat(auto.get("matched").asInt()).isEqualTo(2);
        rec = auto.get("reconciliation");
        assertThat(rec.toString()).contains("\"rule\":\"REFERENCE\"").contains("\"rule\":\"AMOUNT_DATE\"");
        JsonNode f = rec.get("figures");
        assertThat(f.get("bankBalance").asText()).isEqualTo("4900.00");
        assertThat(f.get("bookBalance").asText()).isEqualTo("5050.00");
        assertThat(f.get("unrecordedDebits").asText()).isEqualTo("150.00");
        assertThat(f.get("difference").asText()).isEqualTo("0.00");

        // May statement cannot be reconciled before April is approved.
        String may = "date,description,reference,amount\n" + year + "-05-31,Interest credit,INT05,1000.00\n";
        String st2Id = body(importFile(bankId, "CSV", may, "4900.00", "5900.00").andExpect(status().isCreated()).andReturn())
                .at("/summary/id").asText();
        mvc.perform(post("/api/v1/bank-statements/" + st2Id + "/reconciliation").header("Authorization", preparer.bearer()))
                .andExpect(status().isUnprocessableEntity());

        rec = ok(recAction(preparer, recId, "submit", rec.get("version").asLong(), null));
        assertThat(rec.get("status").asText()).isEqualTo("SUBMITTED");
        recAction(preparer, recId, "approve", rec.get("version").asLong(), null).andExpect(status().isForbidden());
        recAction(cashier, recId, "approve", rec.get("version").asLong(), null).andExpect(status().isForbidden());
        rec = ok(recAction(financeManager, recId, "approve", rec.get("version").asLong(), "Reviewed"));
        assertThat(rec.get("status").asText()).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT status FROM check_register WHERE check_number = ?", String.class, check))
                .isEqualTo("CLEARED");
        String someMatch = rec.get("matches").get(0).get("id").asText();
        mvc.perform(delete("/api/v1/bank-reconciliations/" + recId + "/matches/" + someMatch).param("version",
                rec.get("version").asText()).header("Authorization", preparer.bearer())).andExpect(status().isConflict());

        // May: the April charge is booked by journal voucher and matched to April's bank line; interest stays unrecorded.
        postThroughWorkflow(voucher("STANDARD", LocalDate.of(year, 5, 2), List.of(
                line(account("PLC-5201"), costCenter("PLC-CC-FIN"), null, "150.00", "0"), line(account("PLC-1103"), "0", "150.00"))));
        JsonNode rec2 = body(mvc.perform(post("/api/v1/bank-statements/" + st2Id + "/reconciliation")
                .header("Authorization", preparer.bearer())).andExpect(status().isCreated()).andReturn());
        String rec2Id = rec2.get("id").asText();
        String chargeLine = null;
        String interestLine = null;
        for (JsonNode l : rec2.get("unmatchedStatementLines")) {
            if (l.get("amount").asText().equals("-150.00")) {
                chargeLine = l.get("id").asText();
            } else {
                interestLine = l.get("id").asText();
            }
        }
        String jevLine = rec2.get("unmatchedLedgerLines").get(0).get("journalLineId").asText();
        assertThat(rec2.get("unmatchedLedgerLines")).hasSize(1);
        match(rec2Id, rec2.get("version").asLong(), List.of(interestLine), List.of(jevLine)).andExpect(status().isUnprocessableEntity());
        rec2 = ok(match(rec2Id, rec2.get("version").asLong(), List.of(chargeLine), List.of(jevLine)));
        assertThat(rec2.at("/figures/unrecordedCredits").asText()).isEqualTo("1000.00");
        assertThat(rec2.at("/figures/difference").asText()).isEqualTo("0.00");
        assertThat(rec2.get("matches")).hasSize(1);
        // A match made in this draft can still be undone.
        String m2 = rec2.get("matches").get(0).get("id").asText();
        rec2 = ok(mvc.perform(delete("/api/v1/bank-reconciliations/" + rec2Id + "/matches/" + m2).param("version",
                rec2.get("version").asText()).header("Authorization", preparer.bearer())));
        assertThat(rec2.at("/figures/difference").asText()).isEqualTo("0.00");

        JsonNode cash = body(mvc.perform(get("/api/v1/treasury/cash-position").param("asOf", year + "-05-31")
                .header("Authorization", financeManager.bearer())).andExpect(status().isOk()).andReturn());
        JsonNode mine = null;
        for (JsonNode b : cash.get("banks")) {
            if (b.get("bankAccountId").asText().equals(bankId.toString())) {
                mine = b;
            }
        }
        assertThat(mine).isNotNull();
        assertThat(mine.get("bookBalance").asText()).isEqualTo("4900.00");
        assertThat(mine.get("lastReconciledStatementEnd").asText()).isEqualTo(year + "-04-30");
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode postedReceipt(int year, LocalDate date, String amount) throws Exception {
        String cid = createCustomer(null).get("id").asText();
        JsonNode inv = postedBilling(billing("INVOICE", cid, LocalDate.of(year, 1, 10), "PLC-4203", amount, false, null));
        JsonNode r = body(mvc.perform(post("/api/v1/receipts").header("Authorization", cashier.bearer()).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(receipt(cid, date, amount, "0",
                        List.of(alloc(inv.get("id").asText(), amount)))))).andExpect(status().isCreated()).andReturn());
        return ok(mvc.perform(post("/api/v1/receipts/" + r.get("id").asText() + "/post").header("Authorization", poster.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", r.get("version").asLong())))));
    }

    private void payByCheck(int year, UUID bankId, String check) throws Exception {
        String supplier = createSupplier(false).get("id").asText();
        String inv = postedInvoice(supplier, "RC-" + check, LocalDate.of(year, 4, 1), "5000.00").get("id").asText();
        JsonNode dv = body(mvc.perform(post("/api/v1/disbursement-vouchers").header("Authorization", disbursementOfficer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("supplierId", supplier, "particulars", "Payment",
                        "allocations", List.of(Map.of("invoiceId", inv, "amount", "4950.00")))))).andExpect(status().isCreated()).andReturn());
        String id = dv.get("id").asText();
        attach(disbursementOfficer, "disbursement-vouchers", id);
        dv = ok(apAction(disbursementOfficer, "disbursement-vouchers", id, "submit", dv.get("version").asLong(), null));
        dv = ok(apAction(reviewer, "disbursement-vouchers", id, "approve", dv.get("version").asLong(), "Certified"));
        dv = ok(apAction(financeManager, "disbursement-vouchers", id, "approve", dv.get("version").asLong(), "Approved"));
        dv = ok(mvc.perform(post("/api/v1/disbursement-vouchers/" + id + "/payment-instruction").header("Authorization", cashier.bearer()).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", dv.get("version").asLong(), "mode", "CHECK",
                        "bankAccountId", bankId, "reference", check, "paymentDate", year + "-04-15")))));
        dv = ok(mvc.perform(post("/api/v1/disbursement-vouchers/" + id + "/payment-confirmation").header("Authorization", cashier.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", dv.get("version").asLong(),
                        "releasedOn", year + "-04-15")))));
        ok(apAction(poster, "disbursement-vouchers", id, "post", dv.get("version").asLong(), null));
    }

    private ResultActions deposit(Session who, UUID bankId, LocalDate date, String receiptId) throws Exception {
        return mvc.perform(post("/api/v1/deposits").header("Authorization", who.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("bankAccountId", bankId, "depositDate", date.toString(), "receiptIds", List.of(receiptId)))));
    }

    private ResultActions importFile(UUID bankId, String format, String content, String opening, String closing) throws Exception {
        var req = multipart("/api/v1/bank-statements")
                .file(new MockMultipartFile("file", "statement." + format.toLowerCase(), "text/plain",
                        content.getBytes(StandardCharsets.UTF_8)))
                .param("bankAccountId", bankId.toString()).param("format", format).header("Authorization", preparer.bearer());
        if (opening != null) {
            req = req.param("openingBalance", opening).param("closingBalance", closing);
        }
        return mvc.perform(req);
    }

    private ResultActions recAction(Session who, String id, String action, long version, String comment) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("version", version);
        body.put("comment", comment);
        return mvc.perform(post("/api/v1/bank-reconciliations/" + id + "/" + action).header("Authorization", who.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(body)));
    }

    private ResultActions match(String id, long version, List<String> statementLines, List<String> ledgerLines) throws Exception {
        return mvc.perform(post("/api/v1/bank-reconciliations/" + id + "/matches").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", version, "statementLineIds", statementLines,
                        "journalLineIds", ledgerLines))));
    }

    private static String camt(int year, String account, String closing, String... entries) {
        return "<?xml version=\"1.0\"?><Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:camt.053.001.02\"><BkToCstmrStmt><Stmt>"
                + "<Id>APR" + year + "</Id><FrToDt><FrDtTm>" + year + "-04-01T00:00:00</FrDtTm><ToDtTm>" + year
                + "-04-30T23:59:59</ToDtTm></FrToDt><Acct><Id><Othr><Id>" + account + "</Id></Othr></Id></Acct>"
                + bal("OPBD", "0.00", year + "-04-01") + bal("CLBD", closing, year + "-04-30") + String.join("", entries)
                + "</Stmt></BkToCstmrStmt></Document>";
    }

    private static String bal(String code, String amount, String date) {
        return "<Bal><Tp><CdOrPrtry><Cd>" + code + "</Cd></CdOrPrtry></Tp><Amt Ccy=\"PHP\">" + amount
                + "</Amt><CdtDbtInd>CRDT</CdtDbtInd><Dt><Dt>" + date + "</Dt></Dt></Bal>";
    }

    private static String entry(String date, String amount, String indicator, String ref, String info) {
        return "<Ntry><Amt Ccy=\"PHP\">" + amount + "</Amt><CdtDbtInd>" + indicator + "</CdtDbtInd><Sts>BOOK</Sts><BookgDt><Dt>"
                + date + "</Dt></BookgDt>" + (ref == null ? "" : "<AcctSvcrRef>" + ref + "</AcctSvcrRef>") + "<AddtlNtryInf>" + info
                + "</AddtlNtryInf></Ntry>";
    }
}
