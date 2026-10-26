package com.miaa.cas.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.audit.Actor;
import com.miaa.cas.support.AbstractAccountingIT;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

class DocumentsAndRecurringIT extends AbstractAccountingIT {

    @Autowired
    RecurringJournalService recurring;

    @Test
    void uploadsAreValidatedAndDownloadsAudited() throws Exception {
        int year = newOpenYear();
        JsonNode v = createVoucher(preparer, voucher("STANDARD", LocalDate.of(year, 1, 5), utilitiesLines("1.00")));
        String id = v.get("id").asText();
        mvc.perform(multipart("/api/v1/journal-vouchers/" + id + "/documents")
                .file(new MockMultipartFile("file", "invoice.pdf", "application/pdf", "MZ\u0090 not a pdf".getBytes()))
                .header("Authorization", preparer.bearer()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FILE_REJECTED"));
        mvc.perform(multipart("/api/v1/journal-vouchers/" + id + "/documents")
                .file(new MockMultipartFile("file", "run.exe", "application/pdf", PDF))
                .header("Authorization", preparer.bearer())).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/journal-vouchers/" + id + "/documents")
                .file(new MockMultipartFile("file", "../../etc/invoice.pdf", "application/pdf", PDF))
                .header("Authorization", reviewer.bearer())).andExpect(status().isForbidden());
        JsonNode doc = body(mvc.perform(multipart("/api/v1/journal-vouchers/" + id + "/documents")
                .file(new MockMultipartFile("file", "../../etc/invoice.pdf", "application/pdf", PDF))
                .header("Authorization", preparer.bearer())).andExpect(status().isCreated()).andReturn());
        assertThat(doc.get("fileName").asText()).isEqualTo("invoice.pdf");
        assertThat(doc.get("scanStatus").asText()).isEqualTo("NOT_SCANNED");
        assertThat(doc.get("sha256").asText()).hasSize(64);

        mvc.perform(get("/api/v1/documents/" + doc.get("id").asText() + "/content").header("Authorization", approver.bearer()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'DOCUMENT_DOWNLOADED' "
                + "AND resource_id = ?", Long.class, id)).isEqualTo(1);
    }

    @Test
    void recurringTemplatesGenerateDraftsThatStillNeedApproval() throws Exception {
        int year = newOpenYear();
        Map<String, Object> req = new HashMap<>();
        req.put("name", "Monthly utilities accrual " + year);
        req.put("description", "Accrue utilities");
        req.put("frequency", "MONTHLY");
        req.put("dayOfMonth", 25);
        req.put("firstRunDate", year + "-01-25");
        req.put("lines", utilitiesLines("4200.00"));
        JsonNode t = body(mvc.perform(post("/api/v1/recurring-journals").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(req))).andExpect(status().isCreated()).andReturn());
        assertThat(t.get("total").asText()).isEqualTo("4200.00");

        var result = tx.execute(s -> recurring.runDue(LocalDate.of(year, 3, 1), Actor.SYSTEM));
        assertThat(result.generated()).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT status FROM journal_vouchers WHERE recurring_journal_id = ?::uuid",
                String.class, t.get("id").asText())).containsOnly("DRAFT");
        assertThat(jdbc.queryForObject("SELECT next_run_date FROM recurring_journals WHERE id = ?::uuid", java.sql.Date.class,
                t.get("id").asText()).toLocalDate()).isEqualTo(LocalDate.of(year, 3, 25));
    }
}
