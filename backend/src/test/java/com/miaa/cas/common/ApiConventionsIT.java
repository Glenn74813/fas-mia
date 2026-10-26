package com.miaa.cas.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.support.AbstractReceivablesIT;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** API conventions (MS §6.3, §8): idempotent money-moving requests, paged collections and announced deprecations. */
class ApiConventionsIT extends AbstractReceivablesIT {

    @Test
    void aRetriedReceiptIsRecordedOnceAndAKeyCannotBeReusedForAnotherRequest() throws Exception {
        int year = newOpenYear();
        String cid = createCustomer(null).get("id").asText();
        Map<String, Object> body = receipt(cid, LocalDate.of(year, 2, 3), "500.00", "0", List.of());
        String k = key();
        int before = jdbc.queryForObject("SELECT count(*) FROM receipts WHERE customer_id = ?::uuid", Integer.class, cid);

        JsonNode first = body(record(k, body).andExpect(status().isCreated()).andReturn());
        JsonNode retry = body(record(k, body).andExpect(status().isCreated()).andReturn());
        assertThat(retry.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(retry.get("receiptNumber").asText()).isEqualTo(first.get("receiptNumber").asText());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM receipts WHERE customer_id = ?::uuid", Integer.class, cid)).isEqualTo(before + 1);

        // The same key with a different amount is refused; no key at all is refused before anything happens.
        record(k, receipt(cid, LocalDate.of(year, 2, 3), "501.00", "0", List.of())).andExpect(status().isUnprocessableEntity());
        record(null, body).andExpect(status().isBadRequest());
        record("short", body).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM receipts WHERE customer_id = ?::uuid", Integer.class, cid)).isEqualTo(before + 1);
        // A key belongs to its user: another cashier's identical key is a separate request.
        Session other = login(createUser("cash2", "CASHIER"));
        JsonNode theirs = body(mvc.perform(post("/api/v1/receipts").header("Authorization", other.bearer()).header("Idempotency-Key", k)
                .contentType(MediaType.APPLICATION_JSON).content(toJson(body))).andExpect(status().isCreated()).andReturn());
        assertThat(theirs.get("id").asText()).isNotEqualTo(first.get("id").asText());
    }

    @Test
    void paymentInstructionsPaymentFilesAndApplicationsRequireAKey() throws Exception {
        String id = UUID.randomUUID().toString();
        mvc.perform(post("/api/v1/disbursement-vouchers/" + id + "/payment-instruction").header("Authorization", cashier.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", 0, "mode", "ADA", "bankAccountId", id,
                        "reference", "ADA-1", "paymentDate", LocalDate.now().toString())))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/payment-files").header("Authorization", cashier.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("bankAccountId", id, "paymentDate", LocalDate.now().toString(), "dvIds", List.of(id)))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/receipts/" + id + "/applications").header("Authorization", poster.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", 0, "accountingDate", LocalDate.now().toString(),
                        "allocations", List.of(Map.of("documentId", id, "amount", "1.00")))))).andExpect(status().isBadRequest());
    }

    @Test
    void collectionsArePagedAndSearchedOnTheServer() throws Exception {
        String tag = "Paged" + UUID.randomUUID().toString().substring(0, 6);
        for (int i = 0; i < 3; i++) {
            createCustomerNamed(tag + " Customer " + i);
        }
        JsonNode p0 = page("/api/v1/customers", Map.of("q", tag.toLowerCase(), "size", "2"));
        assertThat(p0.get("totalItems").asLong()).isEqualTo(3);
        assertThat(p0.get("totalPages").asInt()).isEqualTo(2);
        assertThat(p0.get("items")).hasSize(2);
        JsonNode p1 = page("/api/v1/customers", Map.of("q", tag, "size", "2", "page", "1"));
        assertThat(p1.get("items")).hasSize(1);
        assertThat(p1.get("items").get(0).get("name").asText()).isEqualTo(tag + " Customer 2");
        // Each customer's subsidiary account is searchable by ledger type.
        JsonNode sl = page("/api/v1/subsidiary-accounts", Map.of("slType", "CUSTOMER", "q", tag));
        assertThat(sl.get("totalItems").asLong()).isEqualTo(3);
        assertThat(page("/api/v1/subsidiary-accounts", Map.of("slType", "SUPPLIER", "q", tag)).get("totalItems").asLong()).isZero();
        String slId = sl.get("items").get(0).get("id").asText();
        assertThat(body(mvc.perform(get("/api/v1/subsidiary-accounts/" + slId).header("Authorization", approver.bearer()))
                .andExpect(status().isOk()).andReturn()).get("slType").asText()).isEqualTo("CUSTOMER");
        // Wildcards in a search are literal.
        assertThat(page("/api/v1/customers", Map.of("q", "%")).get("items").toString()).doesNotContain(tag);

        mvc.perform(get("/api/v1/customers").param("size", "201").header("Authorization", preparer.bearer())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/customers").param("page", "-1").header("Authorization", preparer.bearer())).andExpect(status().isBadRequest());
        assertThat(page("/api/v1/deposits", Map.of()).get("size").asInt()).isEqualTo(25);
    }

    @Test
    void aDeprecatedEndpointAnnouncesItsSunsetAndSuccessor() throws Exception {
        mvc.perform(get("/api/v1/journal-vouchers/approval-queue").header("Authorization", approver.bearer()))
                .andExpect(status().isOk())
                .andExpect(header().string("Deprecation", "@" + LocalDate.parse("2026-10-23").atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond()))
                .andExpect(header().string("Sunset", "Fri, 30 Apr 2027 00:00:00 GMT"))
                .andExpect(header().string("Link", "</api/v1/inbox>; rel=\"successor-version\""));
        mvc.perform(get("/api/v1/inbox").header("Authorization", approver.bearer())).andExpect(header().doesNotExist("Deprecation"));
    }

    private org.springframework.test.web.servlet.ResultActions record(String key, Map<String, Object> body) throws Exception {
        var req = post("/api/v1/receipts").header("Authorization", cashier.bearer()).contentType(MediaType.APPLICATION_JSON).content(toJson(body));
        return mvc.perform(key == null ? req : req.header("Idempotency-Key", key));
    }

    private JsonNode page(String path, Map<String, String> params) throws Exception {
        var req = get(path).header("Authorization", approver.bearer());
        params.forEach(req::param);
        return body(mvc.perform(req).andExpect(status().isOk()).andReturn());
    }
}
