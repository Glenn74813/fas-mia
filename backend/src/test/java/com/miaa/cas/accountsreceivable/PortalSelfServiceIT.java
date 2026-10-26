package com.miaa.cas.accountsreceivable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.support.AbstractReceivablesIT;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

/** Portal PDFs and inquiries: own records only; a payment reference posts nothing; staff answer. */
class PortalSelfServiceIT extends AbstractReceivablesIT {

    @Test
    void concessionairesDownloadTheirOwnDocumentsAndRaiseInquiries() throws Exception {
        int year = newOpenYear();
        LocalDate date = LocalDate.of(year, 5, 2);
        JsonNode mine = createCustomer(null);
        JsonNode theirs = createCustomer(null);
        JsonNode myInvoice = postedBilling(billing("INVOICE", mine.get("id").asText(), date, "PLC-4201", "2000.00", false, null));
        JsonNode theirInvoice = postedBilling(billing("INVOICE", theirs.get("id").asText(), date, "PLC-4201", "900.00", false, null));
        Session me = concessionaire(mine.get("id").asText());
        Session other = concessionaire(theirs.get("id").asText());

        // PDFs of the concessionaire's own posted billing and statement; another customer's billing does not exist for them.
        byte[] pdf = mvc.perform(get("/api/v1/portal/billings/" + myInvoice.get("id").asText() + "/pdf").header("Authorization", me.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        mvc.perform(get("/api/v1/portal/billings/" + theirInvoice.get("id").asText() + "/pdf").header("Authorization", me.bearer()))
                .andExpect(status().isNotFound());
        byte[] statement = mvc.perform(get("/api/v1/portal/statement/pdf").param("from", year + "-01-01").param("to", year + "-12-31")
                .header("Authorization", me.bearer())).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(statement, 0, 5)).isEqualTo("%PDF-");
        mvc.perform(get("/api/v1/portal/billings/" + myInvoice.get("id").asText() + "/pdf").header("Authorization", preparer.bearer()))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'PORTAL_DOCUMENT_DOWNLOADED' AND resource_id = ?",
                Integer.class, myInvoice.get("id").asText())).isEqualTo(1);

        // A payment reference needs its payment details, and records nothing in the ledger.
        Map<String, Object> ref = new HashMap<>(Map.of("kind", "PAYMENT_REFERENCE", "subject", "Payment for May rent",
                "message", "Paid by bank transfer", "billingId", myInvoice.get("id").asText()));
        mvc.perform(post("/api/v1/portal/inquiries").header("Authorization", me.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(ref))).andExpect(status().isBadRequest());
        ref.putAll(Map.of("paymentDate", LocalDate.now().minusDays(1).toString(), "paymentAmount", "2000.00", "paymentMethod", "BANK_TRANSFER",
                "paymentReference", "BT-778812"));
        int receiptsBefore = jdbc.queryForObject("SELECT count(*) FROM receipts", Integer.class);
        JsonNode inq = body(mvc.perform(post("/api/v1/portal/inquiries").header("Authorization", me.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(ref))).andExpect(status().isCreated()).andReturn());
        assertThat(inq.get("reference").asText()).matches("INQ-\\d{4}-\\d{6}");
        assertThat(inq.get("status").asText()).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM receipts", Integer.class)).isEqualTo(receiptsBefore);
        String id = inq.get("id").asText();
        mvc.perform(multipart("/api/v1/portal/inquiries/" + id + "/documents").file(new MockMultipartFile("file", "proof.pdf",
                "application/pdf", PDF)).header("Authorization", me.bearer())).andExpect(status().isCreated());

        // Referring to another customer's billing, or reading another customer's inquiry, is refused as not found.
        ref.put("billingId", theirInvoice.get("id").asText());
        mvc.perform(post("/api/v1/portal/inquiries").header("Authorization", me.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(ref))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/portal/inquiries/" + id).header("Authorization", other.bearer())).andExpect(status().isNotFound());
        mvc.perform(multipart("/api/v1/portal/inquiries/" + id + "/documents").file(new MockMultipartFile("file", "x.pdf",
                "application/pdf", PDF)).header("Authorization", other.bearer())).andExpect(status().isForbidden());
        assertThat(body(mvc.perform(get("/api/v1/portal/inquiries").header("Authorization", other.bearer())).andExpect(status().isOk())
                .andReturn()).get("items").size()).isZero();

        // Staff see it, answer it (the concessionaire is e-mailed) and close it.
        JsonNode open = body(mvc.perform(get("/api/v1/portal-inquiries").param("status", "OPEN").header("Authorization", preparer.bearer()))
                .andExpect(status().isOk()).andReturn());
        assertThat(open.toString()).contains(inq.get("reference").asText());
        JsonNode staffView = body(mvc.perform(get("/api/v1/portal-inquiries/" + id).header("Authorization", preparer.bearer()))
                .andExpect(status().isOk()).andReturn());
        assertThat(staffView.get("documents").size()).isEqualTo(1);
        mvc.perform(post("/api/v1/portal-inquiries/" + id + "/response").header("Authorization", approver.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", staffView.get("version").asLong(),
                        "response", "Received; the cashier will record it.")))).andExpect(status().isForbidden());
        JsonNode answered = ok(mvc.perform(post("/api/v1/portal-inquiries/" + id + "/response").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", staffView.get("version").asLong(),
                        "response", "Received; the cashier will record it.")))));
        assertThat(answered.get("status").asText()).isEqualTo("ANSWERED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE template = 'portal-inquiry-answered' "
                + "AND payload::text LIKE ?", Integer.class, "%" + inq.get("reference").asText() + "%")).isEqualTo(1);
        JsonNode closed = ok(mvc.perform(post("/api/v1/portal-inquiries/" + id + "/close").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", answered.get("version").asLong())))));
        assertThat(closed.get("status").asText()).isEqualTo("CLOSED");
        JsonNode seen = body(mvc.perform(get("/api/v1/portal/inquiries/" + id).header("Authorization", me.bearer())).andExpect(status().isOk())
                .andReturn());
        assertThat(seen.get("response").asText()).contains("cashier");
    }

    private Session concessionaire(String customerId) throws Exception {
        TestUser u = createUser("pself", "CONCESSIONAIRE");
        jdbc.update("UPDATE users SET user_type = 'CONCESSIONAIRE', customer_id = ?::uuid WHERE id = ?", customerId, u.id());
        return login(u);
    }
}
