package com.miaa.cas.integrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.integrations.IntegrationAdapter.Outcome;
import com.miaa.cas.support.AbstractReceivablesIT;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {"cas.integrations.bir-eis.mode=LOOPBACK", "cas.integrations.retry-base=PT0S",
        "cas.integrations.max-attempts=3", "cas.integrations.poll-interval=PT1H"})
@Import(IntegrationsIT.ScriptedAdapterConfig.class)
class IntegrationsIT extends AbstractReceivablesIT {

    /** A channel whose outcomes the test scripts. */
    static class ScriptedAdapter implements IntegrationAdapter {
        final Deque<Outcome> outcomes = new ArrayDeque<>();
        int calls;

        @Override public String channel() { return "TEST"; }
        @Override public String mode() { return "scripted"; }
        @Override public boolean simulated() { return true; }
        @Override public void checkEnabled() { }

        @Override
        public synchronized Result send(Transmission t) {
            calls++;
            Outcome o = outcomes.isEmpty() ? Outcome.SENT : outcomes.poll();
            return new Result(o, "test://", o == Outcome.REJECTED ? 400 : 200, o + " on attempt " + t.attempt(), "REF-" + t.attempt());
        }
    }

    @TestConfiguration
    static class ScriptedAdapterConfig {
        @Bean
        ScriptedAdapter scriptedAdapter() {
            return new ScriptedAdapter();
        }
    }

    @Autowired TransmissionService transmissions;
    @Autowired ScriptedAdapter adapter;

    @Test
    void retriesWithBackoffThenDeadLettersAndIsIdempotentPerKey() throws Exception {
        jdbc.update("DELETE FROM alert_throttle WHERE alert_key = 'integration:TEST'");
        TestUser manager = createUser("intmgr", "CHIEF_ACCOUNTANT");
        String alerts = "SELECT count(*) FROM notifications WHERE category = 'ALERT' AND title = 'Integration TEST needs attention' AND user_id = ?";
        Map<String, Object> payload = Map.of("hello", "world");
        String key = "k-" + UUID.randomUUID();
        UUID doc = UUID.randomUUID();
        TransmissionService.TransmissionView t = asChief(() -> transmissions.enqueue("TEST", "TEST_DOC", doc, key, payload));
        assertThat(asChief(() -> transmissions.enqueue("TEST", "TEST_DOC", doc, key, payload)).id()).isEqualTo(t.id());

        // Two transient failures, then delivered on the third attempt.
        adapter.outcomes.addAll(List.of(Outcome.RETRYABLE, Outcome.RETRYABLE, Outcome.ACKNOWLEDGED));
        transmissions.processDue();
        transmissions.processDue();
        transmissions.processDue();
        JsonNode v = view(t.id());
        assertThat(v.get("status").asText()).isEqualTo("ACKNOWLEDGED");
        assertThat(v.get("attempts").asInt()).isEqualTo(3);
        assertThat(v.get("attemptLog")).hasSize(3);
        assertThat(v.get("externalReference").asText()).isEqualTo("REF-3");
        assertThat(transmissions.processDue()).isZero();

        // Failing on every attempt ends DEAD at the limit; a person can retry it.
        TransmissionService.TransmissionView d = asChief(() -> transmissions.enqueue("TEST", "TEST_DOC", UUID.randomUUID(), "d-" + UUID.randomUUID(), payload));
        adapter.outcomes.addAll(List.of(Outcome.RETRYABLE, Outcome.RETRYABLE, Outcome.RETRYABLE));
        transmissions.processDue();
        transmissions.processDue();
        transmissions.processDue();
        assertThat(view(d.id()).get("status").asText()).isEqualTo("DEAD");
        // Integration managers are alerted in the app and by e-mail.
        assertThat(jdbc.queryForObject(alerts, Integer.class, manager.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE template = 'operational-alert' AND recipient = ?",
                Integer.class, manager.username() + "@test.miaa.local")).isEqualTo(1);
        mvc.perform(post("/api/v1/integrations/transmissions/" + d.id() + "/retry").header("Authorization", preparer.bearer()))
                .andExpect(status().isForbidden());
        ok(mvc.perform(post("/api/v1/integrations/transmissions/" + d.id() + "/retry").header("Authorization", approver.bearer())));
        transmissions.processDue();
        assertThat(view(d.id()).get("status").asText()).isEqualTo("SENT");

        // A rejection is final until someone looks; it can be cancelled with a reason.
        TransmissionService.TransmissionView r = asChief(() -> transmissions.enqueue("TEST", "TEST_DOC", UUID.randomUUID(), "r-" + UUID.randomUUID(), payload));
        adapter.outcomes.add(Outcome.REJECTED);
        transmissions.processDue();
        assertThat(view(r.id()).get("status").asText()).isEqualTo("REJECTED");
        // Within the channel's quiet hour the rejection is counted for the next alert, not sent again.
        assertThat(jdbc.queryForObject(alerts, Integer.class, manager.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT suppressed FROM alert_throttle WHERE alert_key = 'integration:TEST'", Integer.class)).isEqualTo(1);
        ok(mvc.perform(post("/api/v1/integrations/transmissions/" + r.id() + "/cancel").header("Authorization", approver.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("reason", "Duplicate of an earlier send")))));
        assertThat(view(r.id()).get("status").asText()).isEqualTo("CANCELLED");

        // A worker that died mid-send leaves SENDING; it is re-queued and delivered.
        TransmissionService.TransmissionView s = asChief(() -> transmissions.enqueue("TEST", "TEST_DOC", UUID.randomUUID(), "s-" + UUID.randomUUID(), payload));
        jdbc.update("UPDATE integration_transmissions SET status = 'SENDING', updated_at = now() - interval '1 hour' WHERE id = ?", s.id());
        transmissions.recoverStuck();
        transmissions.processDue();
        assertThat(view(s.id()).get("status").asText()).isEqualTo("SENT");
    }

    @Test
    void postedBillingIsQueuedForBirEisAsTheCanonicalDocumentAndLoopedBack() throws Exception {
        int year = newOpenYear();
        String cid = createCustomer(null).get("id").asText();
        JsonNode inv = postedBilling(billing("INVOICE", cid, LocalDate.of(year, 2, 1), "PLC-4201", "1000.00", true, null));
        JsonNode draft = createBilling(billing("INVOICE", cid, LocalDate.of(year, 2, 2), "PLC-4201", "10.00", false, null));

        queue(preparer, "AR_BILLING", inv.get("id").asText()).andExpect(status().isForbidden());
        queue(approver, "AR_BILLING", draft.get("id").asText()).andExpect(status().isConflict());
        JsonNode t = body(queue(approver, "AR_BILLING", inv.get("id").asText()).andExpect(status().isCreated()).andReturn());
        JsonNode again = body(queue(approver, "AR_BILLING", inv.get("id").asText()).andExpect(status().isCreated()).andReturn());
        assertThat(again.get("id").asText()).isEqualTo(t.get("id").asText());
        JsonNode p = t.get("payload");
        assertThat(p.get("schema").asText()).isEqualTo("CAS-CANONICAL-SALES-DOCUMENT");
        assertThat(p.get("notice").asText()).contains("not the BIR EIS schema");
        assertThat(p.get("documentNumber").asText()).isEqualTo(inv.get("documentNumber").asText());
        assertThat(p.get("grossAmount").asText()).isEqualTo("1120.00");
        assertThat(t.get("simulated").asBoolean()).isTrue();

        transmissions.processDue();
        JsonNode v = view(UUID.fromString(t.get("id").asText()));
        assertThat(v.get("status").asText()).isEqualTo("ACKNOWLEDGED");
        assertThat(v.get("externalReference").asText()).startsWith("SIMULATED-");

        JsonNode channels = body(mvc.perform(get("/api/v1/integrations/channels").header("Authorization", approver.bearer()))
                .andExpect(status().isOk()).andReturn());
        assertThat(channels.toString()).contains("Loopback simulation");
    }

    // ------------------------------------------------------------------ helpers

    private org.springframework.test.web.servlet.ResultActions queue(Session who, String type, String id) throws Exception {
        return mvc.perform(post("/api/v1/integrations/bir-eis/documents").header("Authorization", who.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("documentType", type, "documentId", id))));
    }

    private JsonNode view(UUID id) throws Exception {
        return body(mvc.perform(get("/api/v1/integrations/transmissions/" + id).header("Authorization", approver.bearer()))
                .andExpect(status().isOk()).andReturn());
    }

    /** Runs service calls that read the current user (as a chief accountant). */
    private <T> T asChief(java.util.concurrent.Callable<T> c) throws Exception {
        var ctx = org.springframework.security.core.context.SecurityContextHolder.getContext();
        var user = users.findAll().stream().filter(u -> u.getUsername().startsWith("appr")).findFirst().orElseThrow();
        ctx.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                new com.miaa.cas.security.CasPrincipal(user.getId(), user.getUsername(), UUID.randomUUID()), null, List.of()));
        try {
            return c.call();
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }
}
