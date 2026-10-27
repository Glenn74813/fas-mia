package com.miaa.cas.dataexport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.support.AbstractPayablesIT;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;

/** Full data export (TOR §3.6): two people, one snapshot, every table and document, secrets left out, checksums. */
class DataExportIT extends AbstractPayablesIT {

    @Autowired DataExportService exports;

    @Test
    void anApprovedExportHoldsEveryTableAndDocumentButNoSignInSecrets() throws Exception {
        // Something to find: a voucher with its supporting PDF, and a supplier bank account.
        JsonNode v = createVoucher(preparer, voucher("STANDARD", LocalDate.of(newOpenYear(), 2, 3), utilitiesLines("100.00")));
        attachPdf(preparer, v.get("id").asText());
        String supplier = createSupplier(false).get("id").asText();
        UUID bankId = UUID.randomUUID();
        jdbc.update("INSERT INTO supplier_bank_accounts (id, supplier_id, bank_name, account_name, account_number_enc, account_number_last4, "
                    + "status, entered_by_id, entered_by, entered_at) VALUES (?, ?::uuid, 'Test Bank', 'Payee', ?, '6789', 'PENDING', ?, 'x', now())",
                    bankId, supplier, encryptor.encrypt("001234506789".getBytes(StandardCharsets.UTF_8), "supplier-bank-account:" + bankId),
                    preparerUser.id());

        Session admin = login(createUser("dxadmin", "SYSTEM_ADMINISTRATOR"));
        Session manager = login(createUser("dxfm", "FINANCE_MANAGER"));
        mvc.perform(get("/api/v1/data-exports").header("Authorization", preparer.bearer())).andExpect(status().isForbidden());
        request(admin, "short").andExpect(status().isBadRequest());
        JsonNode req = body(request(admin, "Annual handover copy for the records office").andExpect(status().isCreated()).andReturn());
        String id = req.get("id").asText();
        assertThat(req.get("status").asText()).isEqualTo("REQUESTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE link = '/admin/data-exports' AND user_id = "
                + "(SELECT id FROM users WHERE username = ?)", Long.class, username(manager))).isEqualTo(1);

        // Two people: the requester cannot approve; a rejection needs a reason.
        decide(admin, id, true, null, 0).andExpect(status().isForbidden());
        decide(manager, id, false, null, 0).andExpect(status().isBadRequest());
        assertThat(body(decide(manager, id, true, "Approved for the records office", 0).andExpect(status().isOk()).andReturn())
                .get("status").asText()).isEqualTo("APPROVED");
        decide(manager, id, true, null, 1).andExpect(status().isConflict());

        assertThat(exports.processPending()).isEqualTo(1);
        JsonNode done = body(mvc.perform(get("/api/v1/data-exports").header("Authorization", admin.bearer())).andReturn()).get("items").get(0);
        assertThat(done.get("id").asText()).isEqualTo(id);
        assertThat(done.get("status").asText()).isEqualTo("DONE");

        // Only the requester downloads it.
        mvc.perform(get("/api/v1/data-exports/" + id + "/download").header("Authorization", manager.bearer())).andExpect(status().isNotFound());
        MockHttpServletResponse res = mvc.perform(get("/api/v1/data-exports/" + id + "/download").header("Authorization", admin.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(res.getContentType()).isEqualTo("application/zip");
        assertThat(res.getHeader("Content-Disposition")).contains(done.get("fileName").asText());
        byte[] zip = res.getContentAsByteArray();
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(zip))).isEqualTo(done.get("sha256").asText());
        assertThat((long) zip.length).isEqualTo(done.get("sizeBytes").asLong());

        Map<String, byte[]> entries = unzip(zip);
        // Every listed checksum matches its entry.
        String sums = new String(entries.get("SHA256SUMS"), StandardCharsets.UTF_8);
        for (String line : sums.split("\n")) {
            String[] parts = line.split("  ", 2);
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(entries.get(parts[1])))).as(parts[1])
                    .isEqualTo(parts[0]);
        }
        assertThat(sums).contains("tables/journal_vouchers.csv", "tables/audit_events.csv", "dictionary.json", "manifest.json");

        String users = new String(entries.get("tables/users.csv"), StandardCharsets.UTF_8);
        assertThat(users.lines().findFirst().orElseThrow()).contains("username").doesNotContain("password_hash");
        assertThat(users).contains(username(admin));
        assertThat(entries).doesNotContainKeys("tables/refresh_tokens.csv", "tables/mfa_recovery_codes.csv", "tables/document_contents.csv");
        assertThat(new String(entries.get("tables/mfa_methods.csv"), StandardCharsets.UTF_8).lines().findFirst().orElseThrow())
                .doesNotContain("secret_ciphertext");
        assertThat(new String(entries.get("tables/supplier_bank_account_numbers.csv"), StandardCharsets.UTF_8))
                .contains(bankId + ",001234506789");
        String docId = jdbc.queryForObject("SELECT id::text FROM supporting_documents WHERE owner_id = ?::uuid", String.class,
                v.get("id").asText());
        assertThat(entries.get("documents/" + docId + "/invoice.pdf")).isEqualTo(PDF);

        JsonNode manifest = json.readTree(entries.get("manifest.json"));
        assertThat(manifest.get("approvedBy").asText()).isEqualTo(username(manager));
        assertThat(manifest.get("excludedTables").toString()).contains("refresh_tokens");
        assertThat(manifest.at("/auditChain/0/intact").asBoolean()).isTrue();
        long vouchers = jdbc.queryForObject("SELECT count(*) FROM journal_vouchers", Long.class);
        manifest.get("tables").forEach(t -> {
            if ("journal_vouchers".equals(t.get("table").asText())) {
                assertThat(t.get("rows").asLong()).isEqualTo(vouchers);
            }
        });
        assertThat(json.readTree(entries.get("dictionary.json")).at("/journal_vouchers/columns").size()).isPositive();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE resource_id = ? AND action IN "
                + "('DATA_EXPORT_REQUESTED', 'DATA_EXPORT_APPROVED', 'DATA_EXPORT_GENERATED', 'DATA_EXPORT_DOWNLOADED')", Long.class, id))
                .isEqualTo(4);

        // Once expired, the file is gone.
        jdbc.update("UPDATE data_exports SET expires_at = now() - interval '1 minute' WHERE id = ?::uuid", id);
        exports.expire();
        mvc.perform(get("/api/v1/data-exports/" + id + "/download").header("Authorization", admin.bearer())).andExpect(status().isConflict());
    }

    @Test
    void documentNamesCannotEscapeTheirFolder() {
        assertThat(DataExportService.safeName("../../etc/passwd")).doesNotContain("/").doesNotStartWith(".").endsWith("etc_passwd");
        assertThat(DataExportService.safeName("a\\b:c.pdf")).isEqualTo("a_b_c.pdf");
        assertThat(DataExportService.safeName("  ")).isEqualTo("document");
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions request(Session who, String purpose) throws Exception {
        return mvc.perform(post("/api/v1/data-exports").header("Authorization", who.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("purpose", purpose))));
    }

    private ResultActions decide(Session who, String id, boolean approve, String note, long version) throws Exception {
        Map<String, Object> b = new HashMap<>();
        b.put("approve", approve);
        b.put("note", note);
        b.put("version", version);
        return mvc.perform(post("/api/v1/data-exports/" + id + "/decision").header("Authorization", who.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(b)));
    }

    private String username(Session s) throws Exception {
        return body(mvc.perform(get("/api/v1/auth/me").header("Authorization", s.bearer())).andReturn()).get("username").asText();
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
