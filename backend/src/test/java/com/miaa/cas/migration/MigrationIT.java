package com.miaa.cas.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.support.AbstractReceivablesIT;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

class MigrationIT extends AbstractReceivablesIT {

    @Test
    void openingBalancesFlowFromTrialBalanceToReconciledOpenItems() throws Exception {
        int year = newOpenYear();
        LocalDate cutover = period(year, 1).getStartDate();
        JsonNode c1 = createCustomer(null);
        JsonNode c2 = createCustomer(null);
        JsonNode s1 = createSupplier(false);
        String c1Code = c1.get("code").asText();
        String c2Code = c2.get("code").asText();
        String s1Code = s1.get("code").asText();
        String tag = UUID.randomUUID().toString().substring(0, 8);

        // An unbalanced trial balance and one with an unknown account are kept, but cannot be applied.
        JsonNode bad = upload(preparer, "GL_OPENING", cutover, null, gl(slOf("customers", c1Code), slOf("customers", c2Code), slOf("suppliers", s1Code), "134999.00"));
        assertThat(bad.at("/summary/status").asText()).isEqualTo("INVALID");
        assertThat(bad.get("batchErrors").get(0).asText()).contains("do not equal");
        assertThat(bad.get("availableActions").toString()).doesNotContain("CREATE_VOUCHER");
        JsonNode unknown = upload(preparer, "GL_OPENING", cutover, null, "account_code,cost_center_code,subsidiary_code,description,debit,credit\n"
                + "NOPE-1,,,x,10.00,\nPLC-3102,,,x,,10.00\n");
        assertThat(unknown.get("errorRows").get(0).get("errors").get(0).asText()).contains("NOPE-1 does not exist");
        // Not in the first period of a year.
        JsonNode late = upload(preparer, "GL_OPENING", period(year, 2).getStartDate(), null,
                gl(slOf("customers", c1Code), slOf("customers", c2Code), slOf("suppliers", s1Code), "135000.00"));
        assertThat(late.get("batchErrors").toString()).contains("first period");

        // A balanced trial balance becomes a draft OPENING voucher prepared by the uploader, with the file attached.
        JsonNode glb = upload(preparer, "GL_OPENING", cutover, null, gl(slOf("customers", c1Code), slOf("customers", c2Code), slOf("suppliers", s1Code), "135000.00"));
        assertThat(glb.at("/summary/status").asText()).isEqualTo("VALIDATED");
        assertThat(glb.at("/summary/totalAmount").asText()).isEqualTo("150000.00");
        String glId = glb.at("/summary/id").asText();
        apply(approver, glId, glb.at("/summary/version").asLong()).andExpect(status().isForbidden());
        glb = ok(apply(preparer, glId, glb.at("/summary/version").asLong()));
        assertThat(glb.at("/summary/status").asText()).isEqualTo("APPLIED");
        assertThat(glb.at("/summary/journalVoucherStatus").asText()).isEqualTo("DRAFT");
        String voucherId = glb.at("/summary/journalVoucherId").asText();
        JsonNode v = body(mvc.perform(get("/api/v1/journal-vouchers/" + voucherId).header("Authorization", preparer.bearer()))
                .andExpect(status().isOk()).andReturn());
        assertThat(v.get("voucherType").asText()).isEqualTo("OPENING");
        assertThat(v.get("totalDebit").asText()).isEqualTo("150000.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM supporting_documents WHERE owner_type = 'JOURNAL_VOUCHER' AND owner_id = ?::uuid",
                Integer.class, voucherId)).isEqualTo(1);
        // The same batch cannot create a second voucher.
        apply(preparer, glId, glb.at("/summary/version").asLong()).andExpect(status().isConflict());

        // Approval and posting are the journal workflow's.
        v = ok(action(preparer, voucherId, "submit", v.get("version").asLong(), null));
        v = ok(action(reviewer, voucherId, "approve", v.get("version").asLong(), "Reviewed"));
        v = ok(action(approver, voucherId, "approve", v.get("version").asLong(), "Approved"));
        v = ok(postVoucher(poster, voucherId, v.get("version").asLong(), "key-" + UUID.randomUUID()));
        String entryNumber = jdbc.queryForObject("SELECT entry_number FROM journal_entries WHERE id = ?::uuid", String.class,
                v.get("postedEntryId").asText());

        // Open items: row errors are reported per row.
        JsonNode rowErrors = upload(preparer, "AR_OPEN_ITEMS", null, entryNumber, "customer_code,document_number,document_date,due_date,description,outstanding_amount\n"
                + c1Code + ",L-" + tag + "-1," + cutover.minusDays(10) + "," + cutover.plusDays(20) + ",Rent,30000.00\n"
                + c1Code + ",L-" + tag + "-1," + cutover.minusDays(10) + "," + cutover.plusDays(20) + ",Rent,1.00\n"
                + c2Code + ",L-" + tag + "-3," + cutover.plusDays(1) + "," + cutover.plusDays(20) + ",Rent,20000.00\n"
                + "NOBODY,L-" + tag + "-4," + cutover.minusDays(1) + "," + cutover.minusDays(5) + ",Rent,1000.5x\n");
        assertThat(rowErrors.at("/summary/status").asText()).isEqualTo("INVALID");
        String errs = rowErrors.get("errorRows").toString();
        assertThat(errs).contains("appears more than once").contains("after the cutover date").contains("NOBODY does not exist")
                .contains("due_date is before").contains("at most two decimals");

        // A batch that disagrees with the opening entry for one customer cannot be applied.
        String arMismatch = "customer_code,document_number,document_date,due_date,description,outstanding_amount\n"
                + c1Code + ",M-" + tag + "-1," + cutover.minusDays(40) + "," + cutover.minusDays(10) + ",Rent,10000.00\n"
                + c1Code + ",M-" + tag + "-2," + cutover.minusDays(10) + "," + cutover.plusDays(20) + ",Rent,20000.00\n"
                + c2Code + ",M-" + tag + "-3," + cutover.minusDays(10) + "," + cutover.plusDays(20) + ",Rent,19000.00\n";
        JsonNode mismatch = upload(preparer, "AR_OPEN_ITEMS", null, entryNumber, arMismatch);
        assertThat(mismatch.at("/summary/status").asText()).isEqualTo("VALIDATED");
        assertThat(mismatch.at("/reconciliation/reconciled").asBoolean()).isFalse();
        assertThat(mismatch.at("/reconciliation/differences/0/partyCode").asText()).isEqualTo(c2Code);
        assertThat(mismatch.at("/reconciliation/differences/0/difference").asText()).isEqualTo("-1000.00");
        apply(approver, mismatch.at("/summary/id").asText(), mismatch.at("/summary/version").asLong())
                .andExpect(status().isUnprocessableEntity());
        ok(mvc.perform(post("/api/v1/migration/batches/" + mismatch.at("/summary/id").asText() + "/cancel")
                .header("Authorization", preparer.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("version", mismatch.at("/summary/version").asLong(), "reason", "Customer 2 total corrected")))));

        // The corrected batch reconciles; the uploader cannot apply it, a second person can.
        String ar = arMismatch.replace("19000.00", "20000.00").replace("M-" + tag, "N-" + tag);
        JsonNode arb = upload(preparer, "AR_OPEN_ITEMS", null, entryNumber, ar);
        assertThat(arb.at("/reconciliation/reconciled").asBoolean()).isTrue();
        assertThat(arb.at("/reconciliation/controlAccountCode").asText()).isEqualTo("PLC-1201");
        assertThat(arb.at("/reconciliation/ledgerTotal").asText()).isEqualTo("50000.00");
        String arId = arb.at("/summary/id").asText();
        apply(preparer, arId, arb.at("/summary/version").asLong()).andExpect(status().isForbidden());
        arb = ok(apply(approver, arId, arb.at("/summary/version").asLong()));
        assertThat(arb.at("/summary/status").asText()).isEqualTo("APPLIED");
        assertThat(arb.at("/summary/appliedBy").asText()).isNotEqualTo(arb.at("/summary/uploadedBy").asText());
        UUID entryId = UUID.fromString(v.get("postedEntryId").asText());
        List<Map<String, Object>> docs = jdbc.queryForList("SELECT * FROM billing_documents WHERE migration_batch_id = ?::uuid ORDER BY document_number",
                arId);
        assertThat(docs).hasSize(3);
        assertThat(docs).allSatisfy(d -> {
            assertThat(d.get("status")).isEqualTo("POSTED");
            assertThat(d.get("source")).isEqualTo("MIGRATION");
            assertThat(d.get("posted_entry_id")).isEqualTo(entryId);
        });
        // Nothing was posted again: the opening entry is still the only entry carrying these balances.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_entries WHERE source_reference LIKE ?", Integer.class, "%" + tag + "%"))
                .isZero();

        // Neither the same file nor a second AR batch for the same opening entry can be applied again.
        mvc.perform(multipart("/api/v1/migration/batches").file(csv(ar)).param("kind", "AR_OPEN_ITEMS").param("description", "Again")
                .param("openingEntryNumber", entryNumber).header("Authorization", preparer.bearer())).andExpect(status().isConflict());
        mvc.perform(multipart("/api/v1/migration/batches").file(csv(ar.replace("N-" + tag, "P-" + tag))).param("kind", "AR_OPEN_ITEMS")
                .param("description", "Second").param("openingEntryNumber", entryNumber).header("Authorization", preparer.bearer()))
                .andExpect(status().isConflict());

        // A migrated invoice is collected like any other.
        String c1Invoice = docs.get(0).get("id").toString();
        JsonNode rc = body(mvc.perform(post("/api/v1/receipts").header("Authorization", cashier.bearer()).header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(receipt(c1.get("id").asText(), cutover.plusDays(5), "10000.00", "0", List.of(alloc(c1Invoice, "10000.00"))))))
                .andExpect(status().isCreated()).andReturn());
        rc = ok(mvc.perform(post("/api/v1/receipts/" + rc.get("id").asText() + "/post").header("Authorization", poster.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", rc.get("version").asLong())))));
        assertThat(jdbc.queryForObject("SELECT settled_amount FROM billing_documents WHERE id = ?::uuid", java.math.BigDecimal.class, c1Invoice))
                .isEqualByComparingTo("10000.00");

        // AP: the supplier's unpaid invoice reconciles with its credit balance and becomes payable.
        JsonNode apb = upload(preparer, "AP_OPEN_ITEMS", null, entryNumber, "supplier_code,invoice_number,invoice_date,due_date,description,outstanding_amount\n"
                + s1Code + ",SI-" + tag + "," + cutover.minusDays(15) + "," + cutover.plusDays(15) + ",\"Repairs, December\",15000.00\n");
        assertThat(apb.at("/reconciliation/reconciled").asBoolean()).isTrue();
        apb = ok(apply(approver, apb.at("/summary/id").asText(), apb.at("/summary/version").asLong()));
        Map<String, Object> si = jdbc.queryForMap("SELECT * FROM supplier_invoices WHERE migration_batch_id = ?::uuid", apb.at("/summary/id").asText());
        assertThat(si.get("status")).isEqualTo("POSTED");
        assertThat(si.get("description")).isEqualTo("Repairs, December");
        assertThat(si.get("payable_amount").toString()).isEqualTo("15000.00");
        assertThat(si.get("posted_entry_id")).isEqualTo(entryId);

        // Stored rows are immutable, and people without migration permissions see nothing.
        assertThatThrownBy(() -> jdbc.update("UPDATE migration_batch_rows SET data = '{}'::jsonb WHERE batch_id = ?::uuid", arId))
                .hasMessageContaining("immutable");
        mvc.perform(get("/api/v1/migration/batches").header("Authorization", cashier.bearer())).andExpect(status().isForbidden());
    }

    @Test
    void wrongHeaderOrMissingOpeningEntryIsRefusedOutright() throws Exception {
        mvc.perform(multipart("/api/v1/migration/batches").file(csv("code,amount\nX,1\n")).param("kind", "GL_OPENING")
                .param("description", "Bad").param("cutoverDate", "2026-01-01").header("Authorization", preparer.bearer()))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/migration/batches").file(csv("customer_code,document_number,document_date,due_date,description,outstanding_amount\n"))
                .param("kind", "AR_OPEN_ITEMS").param("description", "Bad").param("openingEntryNumber", "JEV-0000-000000")
                .header("Authorization", preparer.bearer())).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/migration/batches").file(csv("x\n")).param("kind", "GL_OPENING").param("description", "Bad")
                .param("cutoverDate", "2026-01-01").header("Authorization", cashier.bearer())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/migration/templates/AR_OPEN_ITEMS").header("Authorization", preparer.bearer()))
                .andExpect(status().isOk());
    }

    private String gl(String customerSl1, String customerSl2, String supplierSl, String equity) {
        return "# opening balances\naccount_code,cost_center_code,subsidiary_code,description,debit,credit\n"
                + "PLC-1102,,,Cash in bank,100000.00,\n"
                + "PLC-1201,," + customerSl1 + ",Receivable,30000.00,\n"
                + "PLC-1201,," + customerSl2 + ",Receivable,20000.00,\n"
                + "PLC-2101,," + supplierSl + ",Payable,,15000.00\n"
                + "PLC-3102,,,Accumulated surplus,," + equity + "\n";
    }

    /** The subsidiary account code of a customer or supplier. */
    private String slOf(String table, String code) {
        return jdbc.queryForObject("SELECT s.code FROM " + table + " p JOIN subsidiary_ledger_accounts s ON s.id = p.subsidiary_account_id "
                + "WHERE p.code = ?", String.class, code);
    }

    private JsonNode upload(Session who, String kind, LocalDate cutover, String entryNumber, String content) throws Exception {
        var req = multipart("/api/v1/migration/batches").file(csv(content)).param("kind", kind).param("description", "Test " + kind)
                .header("Authorization", who.bearer());
        if (cutover != null) {
            req.param("cutoverDate", cutover.toString());
        }
        if (entryNumber != null) {
            req.param("openingEntryNumber", entryNumber);
        }
        return body(mvc.perform(req).andExpect(status().isCreated()).andReturn());
    }

    private ResultActions apply(Session who, String id, long version) throws Exception {
        return mvc.perform(post("/api/v1/migration/batches/" + id + "/apply").header("Authorization", who.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", version))));
    }

    private static MockMultipartFile csv(String content) {
        return new MockMultipartFile("file", "batch-" + UUID.randomUUID() + ".csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }
}
