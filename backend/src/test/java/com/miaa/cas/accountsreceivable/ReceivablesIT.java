package com.miaa.cas.accountsreceivable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.support.AbstractReceivablesIT;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class ReceivablesIT extends AbstractReceivablesIT {

    @Test
    void invoiceIsApprovedPostedCollectedAndReconciled() throws Exception {
        int year = newOpenYear();
        JsonNode reconBefore = recon();
        JsonNode customer = createCustomer("5000.00");
        String cid = customer.get("id").asText();
        JsonNode inv = createBilling(billing("INVOICE", cid, LocalDate.of(year, 3, 1), "PLC-4201", "10000.00", true, null));
        assertThat(inv.get("vatAmount").asText()).isEqualTo("1200.00");
        assertThat(inv.get("grossAmount").asText()).isEqualTo("11200.00");
        assertThat(inv.get("dueDate").asText()).isEqualTo(year + "-03-31");
        assertThat(inv.get("documentNumber").isNull()).isTrue();
        String id = inv.get("id").asText();

        inv = ok(apAction(preparer, "accounts-receivable/billings", id, "submit", inv.get("version").asLong(), null));
        // The credit limit is exceeded: approvers see a warning (policy C-27), not a block.
        assertThat(inv.get("warnings").toString()).contains("credit limit");
        apAction(reviewer, "accounts-receivable/billings", id, "approve", inv.get("version").asLong(), null)
                .andExpect(status().isForbidden());
        inv = ok(apAction(approver, "accounts-receivable/billings", id, "approve", inv.get("version").asLong(), null));
        apAction(preparer, "accounts-receivable/billings", id, "post", inv.get("version").asLong(), null)
                .andExpect(status().isForbidden());
        inv = ok(apAction(poster, "accounts-receivable/billings", id, "post", inv.get("version").asLong(), null));
        assertThat(inv.get("status").asText()).isEqualTo("POSTED");
        assertThat(inv.get("documentNumber").asText()).matches("BI-" + year + "-\\d{6}");

        JsonNode entry = entry(inv.get("postedEntryId").asText());
        assertThat(entry.get("totalDebit").asText()).isEqualTo("11200.00");
        Map<String, JsonNode> lines = byAccount(entry);
        assertThat(lines.get("PLC-1201").get("debit").asText()).isEqualTo("11200.00");
        assertThat(lines.get("PLC-1201").get("subsidiaryCode").asText()).isEqualTo(customer.get("code").asText());
        // Concession rent carries the customer sub-ledger as the chart requires.
        assertThat(lines.get("PLC-4201").get("credit").asText()).isEqualTo("10000.00");
        assertThat(lines.get("PLC-4201").get("subsidiaryCode").asText()).isEqualTo(customer.get("code").asText());
        assertThat(lines.get("PLC-2202").get("credit").asText()).isEqualTo("1200.00");

        // Billing staff cannot record collections and collectors cannot bill.
        mvc.perform(post("/api/v1/accounts-receivable/billings").header("Authorization", cashier.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(billing("INVOICE", cid, LocalDate.of(year, 3, 1), "PLC-4201", "1.00", false, null))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/receipts").header("Authorization", preparer.bearer()).header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(receipt(cid, LocalDate.of(year, 3, 20), "1.00", "0", List.of())))).andExpect(status().isForbidden());

        // Over-application is refused; an overpayment becomes an unapplied collection.
        mvc.perform(post("/api/v1/receipts").header("Authorization", cashier.bearer()).header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(receipt(cid, LocalDate.of(year, 3, 20), "12000.00", "0", List.of(alloc(id, "11200.01"))))))
                .andExpect(status().isUnprocessableEntity());
        JsonNode rc = body(mvc.perform(post("/api/v1/receipts").header("Authorization", cashier.bearer()).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(receipt(cid, LocalDate.of(year, 3, 20), "11976.00", "224.00", List.of(alloc(id, "11200.00"))))))
                .andExpect(status().isCreated()).andReturn());
        assertThat(rc.get("receiptNumber").asText()).matches("RCPT-" + year + "-\\d{6}");
        assertThat(rc.get("unappliedAmount").asText()).isEqualTo("1000.00");
        String rid = rc.get("id").asText();
        // The same balance cannot be committed twice while the first receipt awaits posting.
        mvc.perform(post("/api/v1/receipts").header("Authorization", cashier.bearer()).header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(receipt(cid, LocalDate.of(year, 3, 21), "5.00", "0", List.of(alloc(id, "5.00"))))))
                .andExpect(status().isUnprocessableEntity());

        mvc.perform(post("/api/v1/receipts/" + rid + "/post").header("Authorization", cashier.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", rc.get("version").asLong()))))
                .andExpect(status().isForbidden());
        rc = ok(mvc.perform(post("/api/v1/receipts/" + rid + "/post").header("Authorization", poster.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", rc.get("version").asLong())))));
        assertThat(rc.get("status").asText()).isEqualTo("POSTED");
        lines = byAccount(entry(rc.get("postedEntryId").asText()));
        assertThat(lines.get("PLC-1101").get("debit").asText()).isEqualTo("11976.00");
        assertThat(lines.get("PLC-1205").get("debit").asText()).isEqualTo("224.00");
        assertThat(lines.get("PLC-1201").get("credit").asText()).isEqualTo("11200.00");
        assertThat(lines.get("PLC-2303").get("credit").asText()).isEqualTo("1000.00");

        JsonNode posted = body(mvc.perform(get("/api/v1/accounts-receivable/billings/" + id)
                .header("Authorization", preparer.bearer())).andExpect(status().isOk()).andReturn());
        assertThat(posted.get("openBalance").asText()).isEqualTo("0.00");

        JsonNode st = body(mvc.perform(get("/api/v1/customers/" + cid + "/statement").param("from", year + "-01-01")
                .param("to", year + "-12-31").header("Authorization", preparer.bearer())).andExpect(status().isOk()).andReturn());
        assertThat(st.get("openingBalance").asText()).isEqualTo("0.00");
        assertThat(st.get("lines")).hasSize(2);
        assertThat(st.get("closingBalance").asText()).isEqualTo("-1000.00");
        assertThat(st.get("unappliedCollections").asText()).isEqualTo("1000.00");

        // Postings through the sub-ledger keep the control accounts reconciled.
        JsonNode reconAfter = recon();
        assertThat(reconAfter.at("/receivables/difference").asText()).isEqualTo(reconBefore.at("/receivables/difference").asText());
        assertThat(reconAfter.at("/unappliedCollections/difference").asText())
                .isEqualTo(reconBefore.at("/unappliedCollections/difference").asText());
        assertThat(new BigDecimal(reconAfter.at("/unappliedCollections/subledgerBalance").asText())
                .subtract(new BigDecimal(reconBefore.at("/unappliedCollections/subledgerBalance").asText())))
                .isEqualByComparingTo("1000.00");

        // The unapplied amount is later applied to a new invoice.
        JsonNode inv2 = postedBilling(billing("INVOICE", cid, LocalDate.of(year, 4, 1), "PLC-4203", "1500.00", false, null));
        String id2 = inv2.get("id").asText();
        mvc.perform(post("/api/v1/receipts/" + rid + "/applications").header("Authorization", poster.bearer()).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", rc.get("version").asLong(),
                        "accountingDate", year + "-04-02", "allocations", List.of(alloc(id2, "1000.01"))))))
                .andExpect(status().isUnprocessableEntity());
        rc = ok(mvc.perform(post("/api/v1/receipts/" + rid + "/applications").header("Authorization", poster.bearer()).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", rc.get("version").asLong(),
                        "accountingDate", year + "-04-02", "allocations", List.of(alloc(id2, "1000.00")))))));
        assertThat(rc.get("unappliedAmount").asText()).isEqualTo("0.00");
        assertThat(rc.get("allocations")).hasSize(2);
        mvc.perform(get("/api/v1/customers/" + cid + "/open-items").header("Authorization", cashier.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].openBalance").value("500.00"));
        JsonNode reconFinal = recon();
        assertThat(reconFinal.at("/receivables/difference").asText()).isEqualTo(reconBefore.at("/receivables/difference").asText());
    }

    @Test
    void creditMemoNeedsJustificationAndCannotExceedTheOpenBalance() throws Exception {
        int year = newOpenYear();
        String cid = createCustomer(null).get("id").asText();
        JsonNode inv = postedBilling(billing("INVOICE", cid, LocalDate.of(year, 2, 1), "PLC-4203", "5000.00", false, null));
        String invId = inv.get("id").asText();

        JsonNode big = createBilling(billing("CREDIT_MEMO", cid, LocalDate.of(year, 2, 10), "PLC-4203", "6000.00", false, invId));
        String bigId = big.get("id").asText();
        apAction(preparer, "accounts-receivable/billings", bigId, "submit", big.get("version").asLong(), null)
                .andExpect(status().isUnprocessableEntity());
        attach(preparer, "accounts-receivable/billings", bigId);
        apAction(preparer, "accounts-receivable/billings", bigId, "submit", big.get("version").asLong(), null)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers
                        .containsString("exceeds")));

        JsonNode memo = postedBilling(billing("CREDIT_MEMO", cid, LocalDate.of(year, 2, 10), "PLC-4203", "1000.00", false, invId));
        assertThat(memo.get("documentNumber").asText()).matches("CM-" + year + "-\\d{6}");
        Map<String, JsonNode> lines = byAccount(entry(memo.get("postedEntryId").asText()));
        assertThat(lines.get("PLC-4203").get("debit").asText()).isEqualTo("1000.00");
        assertThat(lines.get("PLC-1201").get("credit").asText()).isEqualTo("1000.00");

        inv = body(mvc.perform(get("/api/v1/accounts-receivable/billings/" + invId).header("Authorization", preparer.bearer()))
                .andExpect(status().isOk()).andReturn());
        assertThat(inv.get("openBalance").asText()).isEqualTo("4000.00");
        // Aging before the memo shows the full amount; after, the reduced one.
        mvc.perform(get("/api/v1/accounts-receivable/aging").param("asOf", year + "-02-05").param("customerId", cid)
                .header("Authorization", preparer.bearer())).andExpect(jsonPath("$.totals.total").value("5000.00"));
        mvc.perform(get("/api/v1/accounts-receivable/aging").param("asOf", year + "-04-15").param("customerId", cid)
                .header("Authorization", preparer.bearer()))
                .andExpect(jsonPath("$.totals.total").value("4000.00"))
                .andExpect(jsonPath("$.rows[0].days31to60").value("4000.00"));
    }

    @Test
    void contractBillingRunIsIdempotentPerPeriod() throws Exception {
        int year = newOpenYear();
        String cid = createCustomer(null).get("id").asText();
        String number = "K-" + System.nanoTime() % 1_000_000;
        Map<String, Object> contract = new HashMap<>();
        contract.put("contractNumber", number);
        contract.put("description", "Retail space lease");
        contract.put("startDate", year + "-01-15");
        contract.put("endDate", year + "-06-30");
        contract.put("monthlyAmount", "50000.00");
        contract.put("revenueAccountId", account("PLC-4201"));
        contract.put("vatCodeId", taxCode("T-VAT-OUT"));
        mvc.perform(post("/api/v1/customers/" + cid + "/contracts").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(contract))).andExpect(status().isCreated());

        JsonNode run = run(year + "-05", year + "-05-01");
        JsonNode mine = null;
        for (JsonNode c : run.get("created")) {
            if (c.get("billingPeriod").asText().equals(year + "-05") && c.get("customerName").asText().endsWith(
                    jdbc.queryForObject("SELECT code FROM customers WHERE id = ?::uuid", String.class, cid))) {
                mine = c;
            }
        }
        assertThat(mine).isNotNull();
        assertThat(mine.get("grossAmount").asText()).isEqualTo("56000.00");
        assertThat(mine.get("status").asText()).isEqualTo("DRAFT");
        assertThat(run(year + "-05", year + "-05-01").get("skipped").toString()).contains(number + ": already billed");
        // Months outside the contract term are not billed.
        assertThat(run(year + "-07", year + "-07-01").get("created").toString()).doesNotContain(number);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM billing_documents d JOIN customer_contracts k ON k.id = d.contract_id "
                + "WHERE k.contract_number = ?", Integer.class, number)).isEqualTo(1);
    }

    @Test
    void cancelledReceiptKeepsItsNumber() throws Exception {
        int year = newOpenYear();
        String cid = createCustomer(null).get("id").asText();
        JsonNode r1 = body(mvc.perform(post("/api/v1/receipts").header("Authorization", cashier.bearer()).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(receipt(cid, LocalDate.of(year, 5, 5), "100.00", "0",
                        List.of())))).andExpect(status().isCreated()).andReturn());
        JsonNode cancelled = ok(mvc.perform(post("/api/v1/receipts/" + r1.get("id").asText() + "/cancel")
                .header("Authorization", cashier.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("version", r1.get("version").asLong(), "reason", "Wrong customer")))));
        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");
        JsonNode r2 = body(mvc.perform(post("/api/v1/receipts").header("Authorization", cashier.bearer()).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(receipt(cid, LocalDate.of(year, 5, 5), "100.00", "0",
                        List.of())))).andExpect(status().isCreated()).andReturn());
        long n1 = Long.parseLong(r1.get("receiptNumber").asText().substring(10));
        long n2 = Long.parseLong(r2.get("receiptNumber").asText().substring(10));
        assertThat(n2).isEqualTo(n1 + 1);
        mvc.perform(post("/api/v1/receipts/" + r1.get("id").asText() + "/post").header("Authorization", poster.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", cancelled.get("version").asLong()))))
                .andExpect(status().isConflict());
    }

    private JsonNode run(String period, String date) throws Exception {
        return ok(mvc.perform(post("/api/v1/accounts-receivable/billing-runs").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("billingPeriod", period, "documentDate", date)))));
    }

    private JsonNode recon() throws Exception {
        return body(mvc.perform(get("/api/v1/accounts-receivable/reconciliation").header("Authorization", approver.bearer()))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode entry(String entryId) throws Exception {
        return body(mvc.perform(get("/api/v1/general-ledger/entries/" + entryId).header("Authorization", approver.bearer()))
                .andExpect(status().isOk()).andReturn());
    }

    private static Map<String, JsonNode> byAccount(JsonNode entry) {
        Map<String, JsonNode> m = new HashMap<>();
        entry.get("lines").forEach(l -> m.put(l.get("accountCode").asText(), l));
        return m;
    }
}
