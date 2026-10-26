package com.miaa.cas.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.support.AbstractAccountingIT;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class JournalWorkflowIT extends AbstractAccountingIT {

    @Test
    void fullLifecycleWithSegregationOfDuties() throws Exception {
        int year = newOpenYear();
        JsonNode v = createVoucher(preparer, voucher("STANDARD", LocalDate.of(year, 3, 15), utilitiesLines("12500.50")));
        String id = v.get("id").asText();
        assertThat(v.get("status").asText()).isEqualTo("DRAFT");
        assertThat(v.get("totalDebit").asText()).isEqualTo("12500.50");
        assertThat(v.get("availableActions").toString()).contains("SUBMIT", "EDIT", "UPLOAD");

        // Supporting document is mandatory for STANDARD vouchers.
        action(preparer, id, "submit", v.get("version").asLong(), null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations[0]").value("Attach at least one supporting document."));
        attachPdf(preparer, id);
        v = ok(action(preparer, id, "submit", v.get("version").asLong(), "Please review"));
        assertThat(v.get("status").asText()).isEqualTo("SUBMITTED");
        assertThat(v.at("/workflow/0/state").asText()).isEqualTo("CURRENT");

        // Documents freeze once submitted.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart("/api/v1/journal-vouchers/" + id + "/documents")
                .file(new org.springframework.mock.web.MockMultipartFile("file", "late.pdf", "application/pdf", PDF))
                .header("Authorization", preparer.bearer())).andExpect(status().isConflict());

        // The approval queue shows the voucher to the reviewer but not to the preparer.
        mvc.perform(get("/api/v1/journal-vouchers/approval-queue").header("Authorization", reviewer.bearer()))
                .andExpect(jsonPath("$[?(@.id == '" + id + "')]").exists());

        long version = v.get("version").asLong();
        v = ok(action(reviewer, id, "approve", version, "Amounts agree to invoice"));
        assertThat(v.get("status").asText()).isEqualTo("SUBMITTED");
        assertThat(v.at("/workflow/1/state").asText()).isEqualTo("CURRENT");
        action(reviewer, id, "approve", v.get("version").asLong(), null).andExpect(status().isForbidden());

        v = ok(action(approver, id, "approve", v.get("version").asLong(), "Approved"));
        assertThat(v.get("status").asText()).isEqualTo("APPROVED");

        // Posting: not by the preparer, and an Idempotency-Key is required.
        postVoucher(preparer, id, v.get("version").asLong(), "key-preparer-1").andExpect(status().isForbidden());
        postVoucher(poster, id, v.get("version").asLong(), null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        JsonNode posted = ok(postVoucher(poster, id, v.get("version").asLong(), "key-lifecycle-0001"));
        assertThat(posted.get("status").asText()).isEqualTo("POSTED");
        assertThat(posted.get("voucherNumber").asText()).isEqualTo("JEV-" + year + "-000001");

        // A network retry with the same key returns the original result; a new key cannot post twice.
        JsonNode replay = ok(postVoucher(poster, id, v.get("version").asLong(), "key-lifecycle-0001"));
        assertThat(replay.get("voucherNumber").asText()).isEqualTo(posted.get("voucherNumber").asText());
        postVoucher(poster, id, posted.get("version").asLong(), "key-lifecycle-0002")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(Matchers.containsString("already been posted")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_entries WHERE voucher_id = ?::uuid", Long.class, id))
                .isEqualTo(1);

        // Decision log is complete and append-only.
        List<String> actions = jdbc.queryForList("SELECT action FROM approval_decisions WHERE document_id = ?::uuid "
                + "ORDER BY decided_at", String.class, id);
        assertThat(actions).containsExactly("SUBMIT", "APPROVE", "APPROVE", "POST");

        // Drill-down from the entry back to the voucher.
        String entryId = posted.get("postedEntryId").asText();
        mvc.perform(get("/api/v1/general-ledger/entries/" + entryId).header("Authorization", approver.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.voucherId").value(id))
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.totalDebit").value("12500.50"));
    }

    @Test
    void returnedVoucherRestartsTheApprovalCycle() throws Exception {
        int year = newOpenYear();
        JsonNode v = createVoucher(preparer, voucher("STANDARD", LocalDate.of(year, 2, 1), utilitiesLines("100.00")));
        String id = v.get("id").asText();
        attachPdf(preparer, id);
        v = ok(action(preparer, id, "submit", v.get("version").asLong(), null));
        v = ok(action(reviewer, id, "approve", v.get("version").asLong(), null));
        action(approver, id, "return", v.get("version").asLong(), "x").andExpect(status().isBadRequest());
        v = ok(action(approver, id, "return", v.get("version").asLong(), "Wrong cost centre"));
        assertThat(v.get("status").asText()).isEqualTo("RETURNED");

        Map<String, Object> edit = new HashMap<>(voucher("STANDARD", LocalDate.of(year, 2, 1), List.of(
                line(account("PLC-5201"), costCenter("PLC-CC-OPS"), null, "100.00", "0"),
                line(account("PLC-1102"), "0", "100.00"))));
        edit.put("version", v.get("version").asLong());
        v = body(mvc.perform(put("/api/v1/journal-vouchers/" + id).header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(edit))).andExpect(status().isOk()).andReturn());
        assertThat(v.get("status").asText()).isEqualTo("DRAFT");
        assertThat(v.at("/lines/0/costCenterCode").asText()).isEqualTo("PLC-CC-OPS");

        v = ok(action(preparer, id, "submit", v.get("version").asLong(), "Corrected"));
        // New cycle: the reviewer from the earlier cycle may review again.
        v = ok(action(reviewer, id, "approve", v.get("version").asLong(), null));
        assertThat(v.at("/workflow/1/state").asText()).isEqualTo("CURRENT");
    }

    @Test
    void rejectionIsFinal() throws Exception {
        int year = newOpenYear();
        JsonNode v = createVoucher(preparer, voucher("STANDARD", LocalDate.of(year, 4, 1), utilitiesLines("50.00")));
        String id = v.get("id").asText();
        attachPdf(preparer, id);
        v = ok(action(preparer, id, "submit", v.get("version").asLong(), null));
        v = ok(action(reviewer, id, "reject", v.get("version").asLong(), "Duplicate of an earlier voucher"));
        assertThat(v.get("status").asText()).isEqualTo("REJECTED");
        action(preparer, id, "submit", v.get("version").asLong(), null).andExpect(status().isConflict());
        action(preparer, id, "cancel", v.get("version").asLong(), null).andExpect(status().isConflict());
    }

    @Test
    void staleVersionIsRejected() throws Exception {
        int year = newOpenYear();
        JsonNode v = createVoucher(preparer, voucher("STANDARD", LocalDate.of(year, 4, 1), utilitiesLines("75.00")));
        attachPdf(preparer, v.get("id").asText());
        action(preparer, v.get("id").asText(), "submit", v.get("version").asLong() + 7, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    void onlyThePreparerEditsAndAdministratorsHaveNoAccess() throws Exception {
        int year = newOpenYear();
        JsonNode v = createVoucher(preparer, voucher("STANDARD", LocalDate.of(year, 5, 5), utilitiesLines("10.00")));
        action(reviewer, v.get("id").asText(), "cancel", v.get("version").asLong(), null).andExpect(status().isForbidden());
        Session admin = login(createUser("sysadm", "SYSTEM_ADMINISTRATOR"));
        mvc.perform(get("/api/v1/journal-vouchers").header("Authorization", admin.bearer())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/accounts").header("Authorization", admin.bearer())).andExpect(status().isForbidden());
    }
}
