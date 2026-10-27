package com.miaa.cas.integrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.integrations.webhooks.WebhookDispatcher;
import com.miaa.cas.integrations.webhooks.WebhookTargets;
import com.miaa.cas.support.AbstractAccountingIT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;

/** Third-party access (TOR §4.6): OAuth 2.0 client credentials with read scopes, and signed webhooks with retries. */
class ApiAccessIT extends AbstractAccountingIT {

    @Autowired WebhookDispatcher dispatcher;

    @Test
    void anApiClientGetsAShortLivedTokenLimitedToItsScopesUntilRotatedOrRevoked() throws Exception {
        Session chief = login(createUser("apichief", "CHIEF_ACCOUNTANT"));
        mvc.perform(post("/api/v1/api-clients").header("Authorization", login(createUser("apiadm", "SYSTEM_ADMINISTRATOR")).bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("name", "FMIS reader", "scopes", List.of("LEDGER_VIEW")))))
                .andExpect(status().isForbidden());
        createClient(chief, List.of("JOURNAL_POST")).andExpect(status().isBadRequest());
        JsonNode created = body(createClient(chief, List.of("LEDGER_VIEW")).andExpect(status().isCreated()).andReturn());
        String clientId = created.at("/client/clientId").asText();
        String secret = created.get("clientSecret").asText();
        String id = created.at("/client/id").asText();
        // The secret is never listed again.
        assertThat(body(mvc.perform(get("/api/v1/api-clients").header("Authorization", chief.bearer())).andReturn()).toString())
                .contains(clientId).doesNotContain(secret);

        token(clientId, "wrong").andExpect(status().isUnauthorized());
        token("cas_unknown", secret).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("grant_type", "password"))
                .andExpect(status().isBadRequest());
        MockHttpServletResponse ok = token(clientId, secret).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(ok.getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode t = json.readTree(ok.getContentAsString());
        assertThat(t.get("token_type").asText()).isEqualTo("Bearer");
        assertThat(t.get("scope").asText()).isEqualTo("LEDGER_VIEW");
        String bearer = "Bearer " + t.get("access_token").asText();
        // HTTP Basic works too (RFC 6749 §2.3.1).
        mvc.perform(post("/api/v1/auth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("grant_type", "client_credentials")
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString((clientId + ":" + secret).getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isOk());

        // Read within its scope; nothing outside it, and never a write.
        mvc.perform(get("/api/v1/accounts").header("Authorization", bearer)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/suppliers").header("Authorization", bearer)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/journal-vouchers").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(voucher("STANDARD", LocalDate.of(newOpenYear(), 1, 5), utilitiesLines("10.00"))))).andExpect(status().isForbidden());

        // Rotation: the old secret stops working.
        JsonNode rotated = body(mvc.perform(post("/api/v1/api-clients/" + id + "/rotate-secret").header("Authorization", chief.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}")).andExpect(status().isOk()).andReturn());
        token(clientId, secret).andExpect(status().isUnauthorized());
        token(clientId, rotated.get("clientSecret").asText()).andExpect(status().isOk());

        // Revocation: its tokens stop working at once.
        mvc.perform(post("/api/v1/api-clients/" + id + "/revoke").header("Authorization", chief.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("reason", "Contract ended", "version", 1)))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/accounts").header("Authorization", bearer)).andExpect(status().isUnauthorized());
        token(clientId, rotated.get("clientSecret").asText()).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM security_events WHERE action = 'API_CLIENT_AUTH_FAILED' AND actor_username = ?",
                Long.class, "api:" + clientId)).isGreaterThanOrEqualTo(3);
    }

    @Test
    void postingAVoucherSendsASignedWebhookThatIsRetriedWhenTheReceiverFails() throws Exception {
        List<Map<String, String>> received = new CopyOnWriteArrayList<>();
        AtomicInteger reply = new AtomicInteger(204);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/hook", ex -> {
            received.add(Map.of("event", ex.getRequestHeaders().getFirst("X-CAS-Event"), "signature",
                    ex.getRequestHeaders().getFirst("X-CAS-Signature"), "body", new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            ex.sendResponseHeaders(reply.get(), -1);
            ex.close();
        });
        server.start();
        // The test database is shared between runs: only this test's subscription may be active.
        jdbc.update("UPDATE webhook_subscriptions SET status = 'PAUSED' WHERE status = 'ACTIVE'");
        try {
            Session chief = login(createUser("whchief", "CHIEF_ACCOUNTANT"));
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
            subscribe(chief, "ftp://example.com/x", List.of("JOURNAL_VOUCHER_POSTED")).andExpect(status().isBadRequest());
            subscribe(chief, url, List.of("NOT_AN_EVENT")).andExpect(status().isBadRequest());
            JsonNode sub = body(subscribe(chief, url, List.of("JOURNAL_VOUCHER_POSTED")).andExpect(status().isCreated()).andReturn());
            String secret = sub.get("signingSecret").asText();
            String subId = sub.at("/subscription/id").asText();
            drain();

            JsonNode posted = postThroughWorkflow(voucher("STANDARD", LocalDate.of(newOpenYear(), 2, 2), utilitiesLines("120.00")));
            drain();
            assertThat(dispatcher.deliverDue()).isEqualTo(1);
            assertThat(received).hasSize(1);
            Map<String, String> r = received.get(0);
            assertThat(r.get("event")).isEqualTo("JOURNAL_VOUCHER_POSTED");
            JsonNode payload = json.readTree(r.get("body"));
            assertThat(payload.at("/resource/id").asText()).isEqualTo(posted.get("id").asText());
            // The signature is HMAC-SHA-256 of "t.body" with the subscription's secret.
            String t = r.get("signature").replaceAll("^t=(\\d+),v1=.*$", "$1");
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            assertThat(r.get("signature")).endsWith("v1=" + HexFormat.of().formatHex(mac.doFinal((t + "." + r.get("body")).getBytes(StandardCharsets.UTF_8))));
            // Announced once.
            drain();
            assertThat(dispatcher.deliverDue()).isZero();

            // A failing receiver: the delivery waits and is retried later.
            reply.set(500);
            JsonNode ping = body(mvc.perform(post("/api/v1/webhooks/" + subId + "/test").header("Authorization", chief.bearer()))
                    .andExpect(status().isOk()).andReturn());
            assertThat(ping.get("status").asText()).isEqualTo("PENDING");
            assertThat(ping.get("attempts").asInt()).isEqualTo(1);
            assertThat(ping.get("lastStatus").asInt()).isEqualTo(500);
            assertThat(ping.get("nextAttemptAt").asText()).isNotEmpty();
            JsonNode log = body(mvc.perform(get("/api/v1/webhooks/" + subId + "/deliveries").header("Authorization", chief.bearer())).andReturn());
            assertThat(log.get("items")).hasSize(2);
        } finally {
            jdbc.update("UPDATE webhook_subscriptions SET status = 'PAUSED' WHERE status = 'ACTIVE'");
            server.stop(0);
        }
    }

    @Test
    void webhooksCannotTargetInternalAddressesInProduction() throws Exception {
        WebhookTargets strict = new WebhookTargets(false, new MockEnvironment());
        assertThatThrownBy(() -> strict.check("http://example.com/hook")).isInstanceOf(ApiException.class).hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> strict.check("https://127.0.0.1/hook")).hasMessageContaining("internal");
        assertThatThrownBy(() -> strict.check("https://169.254.169.254/latest")).hasMessageContaining("internal");
        assertThatThrownBy(() -> strict.check("https://10.1.2.3/hook")).hasMessageContaining("internal");
        assertThatThrownBy(() -> strict.check("https://100.64.0.1/hook")).hasMessageContaining("internal");
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        assertThatThrownBy(() -> new WebhookTargets(true, prod)).isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions createClient(Session who, List<String> scopes) throws Exception {
        return mvc.perform(post("/api/v1/api-clients").header("Authorization", who.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("name", "FMIS reader", "scopes", scopes))));
    }

    private ResultActions token(String clientId, String secret) throws Exception {
        return mvc.perform(post("/api/v1/auth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "client_credentials").param("client_id", clientId).param("client_secret", secret));
    }

    private ResultActions subscribe(Session who, String url, List<String> events) throws Exception {
        return mvc.perform(post("/api/v1/webhooks").header("Authorization", who.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("name", "Posting feed", "url", url, "events", events))));
    }

    /** Reads the audit trail up to its end. */
    private void drain() {
        long end = jdbc.queryForObject("SELECT coalesce(max(id), 0) FROM audit_events", Long.class);
        while (jdbc.queryForObject("SELECT last_audit_id FROM webhook_cursor", Long.class) < end) {
            dispatcher.enqueue();
        }
    }
}
