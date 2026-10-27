package com.miaa.cas.dataexport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.support.AbstractAccountingIT;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Records retention (TOR §4.5): 10-year schedule, archive of a closed fiscal year, legal holds; no disposal. */
class RecordsRetentionIT extends AbstractAccountingIT {

    @Autowired DataExportService exports;

    @Test
    void aClosedYearIsArchivedWithOnlyItsOwnRecordsAndTheArchiveIsSealedInTheAuditTrail() throws Exception {
        int year = newOpenYear();
        int other = newOpenYear();
        JsonNode posted = postThroughWorkflow(voucher("STANDARD", LocalDate.of(year, 3, 10), utilitiesLines("250.00")));
        JsonNode draft = createVoucher(preparer, voucher("STANDARD", LocalDate.of(year, 4, 2), utilitiesLines("75.00")));
        attachPdf(preparer, draft.get("id").asText());
        JsonNode elsewhere = createVoucher(preparer, voucher("STANDARD", LocalDate.of(other, 3, 10), utilitiesLines("90.00")));
        String fy = jdbc.queryForObject("SELECT id::text FROM fiscal_years WHERE fiscal_year = ?", String.class, year);

        Session admin = login(createUser("rtadmin", "SYSTEM_ADMINISTRATOR"));
        Session manager = login(createUser("rtfm", "FINANCE_MANAGER"));
        requestArchive(admin, fy).andExpect(status().isConflict());
        jdbc.update("UPDATE fiscal_years SET status = 'CLOSED', closed_at = now(), closed_by = 'test' WHERE id = ?::uuid", fy);

        String id = body(requestArchive(admin, fy).andExpect(status().isCreated()).andReturn()).get("id").asText();
        decide(manager, id).andExpect(status().isOk());
        exports.processPending();
        JsonNode done = body(mvc.perform(get("/api/v1/data-exports").header("Authorization", admin.bearer())).andReturn()).get("items").get(0);
        assertThat(done.get("kind").asText()).isEqualTo("ARCHIVE");
        assertThat(done.get("fiscalYear").asInt()).isEqualTo(year);
        assertThat(done.get("status").asText()).isEqualTo("DONE");
        assertThat(done.get("fileName").asText()).startsWith("miaa-cas-archive-FY" + year + "-");

        Map<String, byte[]> zip = unzip(mvc.perform(get("/api/v1/data-exports/" + id + "/download").header("Authorization", admin.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        String vouchers = text(zip, "tables/journal_vouchers.csv");
        assertThat(vouchers).contains(posted.get("id").asText(), draft.get("id").asText()).doesNotContain(elsewhere.get("id").asText());
        assertThat(text(zip, "tables/journal_entries.csv")).contains(posted.get("postedEntryId").asText());
        assertThat(text(zip, "tables/accounting_periods.csv").lines().count()).isEqualTo(14); // header and 13 periods
        assertThat(zip).containsKeys("tables/chart_of_accounts.csv", "tables/users.csv", "tables/audit_events.csv", "SHA256SUMS")
                .doesNotContainKeys("tables/supplier_bank_account_numbers.csv", "tables/refresh_tokens.csv", "tables/tickets.csv");
        String doc = jdbc.queryForObject("SELECT id::text FROM supporting_documents WHERE owner_id = ?::uuid", String.class,
                draft.get("id").asText());
        assertThat(zip.get("documents/" + doc + "/invoice.pdf")).isEqualTo(PDF);
        JsonNode manifest = json.readTree(zip.get("manifest.json"));
        assertThat(manifest.get("kind").asText()).isEqualTo("ARCHIVE");
        assertThat(manifest.get("retainUntil").asText()).isEqualTo((year + 10) + "-12-31");

        // The archive's checksum is in the hash-chained audit trail against the fiscal year.
        assertThat(jdbc.queryForObject("SELECT details->>'sha256' FROM audit_events WHERE action = 'FISCAL_YEAR_ARCHIVED' AND resource_id = ?",
                String.class, fy)).isEqualTo(done.get("sha256").asText());

        // The schedule shows the year, its retention date and its archive.
        Session auditor = login(createUser("rtaud", "INTERNAL_AUDITOR"));
        JsonNode schedule = body(mvc.perform(get("/api/v1/records-retention").header("Authorization", auditor.bearer()))
                .andExpect(status().isOk()).andReturn());
        assertThat(schedule.get("retentionYears").asInt()).isEqualTo(10);
        assertThat(schedule.get("disposal").asText()).contains("C-05");
        JsonNode y = yearOf(schedule, year);
        assertThat(y.get("retainUntil").asText()).isEqualTo((year + 10) + "-12-31");
        assertThat(y.get("retentionElapsed").asBoolean()).isFalse();
        assertThat(y.get("archives").get(0).get("sha256").asText()).isEqualTo(done.get("sha256").asText());
        mvc.perform(get("/api/v1/records-retention").header("Authorization", preparer.bearer())).andExpect(status().isForbidden());
    }

    @Test
    void legalHoldsArePlacedAndReleasedWithReasonsByTheChiefAccountant() throws Exception {
        int year = newOpenYear();
        String fy = jdbc.queryForObject("SELECT id::text FROM fiscal_years WHERE fiscal_year = ?", String.class, year);
        Session chief = login(createUser("rtchief", "CHIEF_ACCOUNTANT"));
        Session admin = login(createUser("rtadm2", "SYSTEM_ADMINISTRATOR"));

        hold(admin, fy, "COA audit observation memo").andExpect(status().isForbidden());
        hold(chief, fy, "short").andExpect(status().isBadRequest());
        JsonNode h = body(hold(chief, fy, "COA audit observation memo pending").andExpect(status().isCreated()).andReturn());
        assertThat(h.get("fiscalYear").asInt()).isEqualTo(year);
        assertThat(yearOf(schedule(chief), year).get("onHold").asBoolean()).isTrue();

        String hid = h.get("id").asText();
        release(chief, hid, "", 0).andExpect(status().isBadRequest());
        JsonNode released = body(release(chief, hid, "Observation closed by COA letter", 0).andExpect(status().isOk()).andReturn());
        assertThat(released.get("releasedBy").asText()).startsWith("rtchief");
        release(chief, hid, "Observation closed by COA letter", 1).andExpect(status().isConflict());
        assertThat(yearOf(schedule(chief), year).get("onHold").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE resource_id = ? AND action IN ('LEGAL_HOLD_PLACED', "
                + "'LEGAL_HOLD_RELEASED')", Long.class, hid)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions requestArchive(Session who, String fy) throws Exception {
        return mvc.perform(post("/api/v1/data-exports").header("Authorization", who.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("purpose", "Archive of the closed fiscal year", "fiscalYearId", fy))));
    }

    private ResultActions decide(Session who, String id) throws Exception {
        return mvc.perform(post("/api/v1/data-exports/" + id + "/decision").header("Authorization", who.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("approve", true, "version", 0))));
    }

    private ResultActions hold(Session who, String fy, String reason) throws Exception {
        return mvc.perform(post("/api/v1/records-retention/holds").header("Authorization", who.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("fiscalYearId", fy, "reason", reason, "reference", "AOM 2026-01"))));
    }

    private ResultActions release(Session who, String id, String note, long version) throws Exception {
        Map<String, Object> b = new HashMap<>();
        b.put("note", note);
        b.put("version", version);
        return mvc.perform(post("/api/v1/records-retention/holds/" + id + "/release").header("Authorization", who.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(b)));
    }

    private JsonNode schedule(Session who) throws Exception {
        return body(mvc.perform(get("/api/v1/records-retention").header("Authorization", who.bearer())).andExpect(status().isOk()).andReturn());
    }

    private static JsonNode yearOf(JsonNode schedule, int year) {
        for (JsonNode y : schedule.get("years")) {
            if (y.get("fiscalYear").asInt() == year) {
                return y;
            }
        }
        throw new AssertionError("Fiscal year " + year + " not in the schedule");
    }

    private static String text(Map<String, byte[]> zip, String path) {
        assertThat(zip).containsKey(path);
        return new String(zip.get(path), StandardCharsets.UTF_8);
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws Exception {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                out.put(e.getName(), in.readAllBytes());
            }
        }
        return out;
    }
}
