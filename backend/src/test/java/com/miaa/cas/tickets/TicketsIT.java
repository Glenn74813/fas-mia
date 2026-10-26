package com.miaa.cas.tickets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.support.AbstractIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

class TicketsIT extends AbstractIntegrationTest {

    @Autowired TicketSlaMonitor monitor;

    Session reporter;
    Session agent;
    Session manager;
    Session other;
    TestUser agentUser;

    @BeforeEach
    void users() throws Exception {
        reporter = login(createUser("rep", "ACCOUNTANT"));
        agentUser = createUser("agent", "SUPPORT_AGENT");
        agent = login(agentUser);
        manager = login(createUser("fm", "FINANCE_MANAGER"));
        other = login(createUser("cash", "CASHIER"));
        Session admin = login(createUser("sa", "SYSTEM_ADMINISTRATOR"));
        mvc.perform(post("/api/v1/sla-policies").header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("contractPhase", "WARRANTY", "ticketType", "INCIDENT", "priority", "P2", "responseMinutes", 30,
                        "resolutionMinutes", 120, "businessHours", false)))).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/sla-policies").header("Authorization", reporter.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("contractPhase", "WARRANTY", "ticketType", "INCIDENT", "priority", "P1", "responseMinutes", 1,
                        "resolutionMinutes", 1, "businessHours", false)))).andExpect(status().isForbidden());
    }

    @Test
    void incidentRunsAgainstItsSlaWithPausesAndEscalation() throws Exception {
        JsonNode t = created(reporter, "INCIDENT", "P2", "Trial balance export fails");
        String id = t.get("id").asText();
        assertThat(t.get("ticketNumber").asText()).matches("TKT-\\d{4}-\\d{6}");
        assertThat(t.at("/sla/policy").asText()).contains("resolve 120 min (24x7)");
        Instant created = Instant.parse(t.get("createdAt").asText());
        assertThat(Instant.parse(t.at("/sla/resolutionDueAt").asText())).isEqualTo(created.plus(120, ChronoUnit.MINUTES));

        // Others cannot even see it; the reporter cannot act as support.
        mvc.perform(get("/api/v1/tickets/" + id).header("Authorization", other.bearer())).andExpect(status().isNotFound());
        act(reporter, t, "ACKNOWLEDGE", null).andExpect(status().isForbidden());
        t = ok(act(agent, t, "ACKNOWLEDGE", "Looking into it"));
        assertThat(t.at("/sla/firstResponseAt").isNull()).isFalse();
        Map<String, Object> assign = action(t, "ASSIGN", null);
        assign.put("assignee", agentUser.username());
        t = ok(mvc.perform(post("/api/v1/tickets/" + id + "/actions").header("Authorization", agent.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(assign))));
        t = ok(act(agent, t, "START", null));

        // An hour on hold does not count against resolution.
        act(agent, t, "HOLD", null).andExpect(status().isBadRequest());
        t = ok(act(agent, t, "HOLD", "Waiting for the requester's sample file"));
        Instant dueBefore = Instant.parse(t.at("/sla/resolutionDueAt").asText());
        jdbc.update("UPDATE tickets SET on_hold_since = now() - interval '60 minutes' WHERE id = ?::uuid", id);
        t = ok(act(reporter, t, "RESUME", "File attached"));
        long shifted = ChronoUnit.MINUTES.between(dueBefore, Instant.parse(t.at("/sla/resolutionDueAt").asText()));
        assertThat(shifted).isBetween(59L, 61L);

        // Past the deadline: breach is recorded once, escalated by e-mail to the assignee, hidden from the requester.
        jdbc.update("UPDATE tickets SET resolution_due_at = now() - interval '1 minute' WHERE id = ?::uuid", id);
        assertThat(monitor.check()).isGreaterThanOrEqualTo(1);
        monitor.check();
        JsonNode staffView = body(mvc.perform(get("/api/v1/tickets/" + id).header("Authorization", agent.bearer())).andReturn());
        assertThat(staffView.at("/sla/resolutionBreached").asBoolean()).isTrue();
        assertThat(staffView.at("/sla/escalationLevel").asInt()).isEqualTo(2);
        assertThat(staffView.get("events").toString()).contains("RESOLUTION_BREACH");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_events WHERE ticket_id = ?::uuid AND to_value = 'RESOLUTION_BREACH'",
                Integer.class, id)).isEqualTo(1);
        JsonNode reporterView = body(mvc.perform(get("/api/v1/tickets/" + id).header("Authorization", reporter.bearer())).andReturn());
        assertThat(reporterView.get("events").toString()).doesNotContain("RESOLUTION_BREACH");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE template = 'ticket-sla-escalation' AND recipient = ?",
                Integer.class, agentUser.email())).isGreaterThanOrEqualTo(1);

        // Resolved, closed by the requester; the breach stays on record.
        t = ok(act(agent, staffView, "RESOLVE", "Export fixed and re-run"));
        t = ok(act(reporter, t, "CLOSE", null));
        assertThat(t.get("status").asText()).isEqualTo("CLOSED");
        assertThat(t.at("/sla/resolutionBreached").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tickets WHERE id = ?::uuid AND resolution_breached", Integer.class, id)).isEqualTo(1);

        // A ticket close to its deadline is flagged at risk before it breaches.
        JsonNode risky = created(reporter, "INCIDENT", "P2", "Slow ledger screen");
        jdbc.update("UPDATE tickets SET resolution_due_at = now() + interval '10 minutes' WHERE id = ?::uuid", risky.get("id").asText());
        monitor.check();
        assertThat(jdbc.queryForObject("SELECT at_risk_notified AND NOT resolution_breached FROM tickets WHERE id = ?::uuid", Boolean.class,
                risky.get("id").asText())).isTrue();

        JsonNode summary = body(mvc.perform(get("/api/v1/tickets/sla-summary").param("from", LocalDate.now().minusDays(1).toString())
                .param("to", LocalDate.now().plusDays(1).toString()).header("Authorization", agent.bearer())).andExpect(status().isOk()).andReturn());
        assertThat(summary.toString()).contains("\"resolutionBreached\"");
    }

    @Test
    void changeRequestNeedsApprovalStagingValidationAndReleaseNotes() throws Exception {
        JsonNode t = created(reporter, "CHANGE_REQUEST", "P3", "Add a column to the aging report");
        assertThat(t.at("/sla/policy").isNull()).isTrue();
        t = ok(act(agent, t, "ACKNOWLEDGE", null));
        assignTo(t, agentUser.username());
        t = body(mvc.perform(get("/api/v1/tickets/" + t.get("id").asText()).header("Authorization", agent.bearer())).andReturn());
        act(agent, t, "START", null).andExpect(status().isConflict());

        // The rollback plan is required to submit, and is shown to the approver.
        act(agent, t, "SUBMIT_FOR_APPROVAL", "Two hours of work, report layout only").andExpect(status().isBadRequest());
        Map<String, Object> submit = action(t, "SUBMIT_FOR_APPROVAL", "Two hours of work, report layout only");
        submit.put("rollbackPlan", "Redeploy the previous report template (release 1.4.2)");
        t = ok(send(agent, t, submit));
        assertThat(t.get("status").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(t.at("/change/rollbackPlan").asText()).contains("previous report template");
        act(agent, t, "APPROVE", "ok").andExpect(status().isForbidden());
        t = ok(act(manager, t, "APPROVE", "Approved for next release"));
        t = ok(act(agent, t, "START", null));

        // No release before staging validation, and never validated by the assignee.
        Map<String, Object> resolve = action(t, "RESOLVE", "Deployed to production");
        resolve.put("releaseNotes", "The AP aging report now shows the supplier TIN column.");
        resolve.put("releaseReference", "1.4.3");
        send(agent, t, resolve).andExpect(status().isConflict());
        assertThat(t.get("availableActions").toString()).doesNotContain("RESOLVE");
        act(agent, t, "VALIDATE_STAGING", "Looks fine to me").andExpect(status().isForbidden());
        act(reporter, t, "VALIDATE_STAGING", "ok").andExpect(status().isBadRequest());
        t = ok(act(reporter, t, "VALIDATE_STAGING", "Checked on staging with the March data; totals agree"));
        assertThat(t.at("/change/stagingValidatedBy").asText()).isNotBlank();

        // Release notes are required at release.
        send(agent, t, action(t, "RESOLVE", "Deployed to production")).andExpect(status().isBadRequest());
        resolve.put("version", t.get("version").asLong());
        t = ok(send(agent, t, resolve));
        assertThat(t.get("status").asText()).isEqualTo("RESOLVED");
        assertThat(t.at("/change/releaseReference").asText()).isEqualTo("1.4.3");
        assertThat(t.get("events").toString()).contains("ROLLBACK_PLAN", "STAGING_VALIDATED", "RELEASED");
        // History is append-only.
        String id = t.get("id").asText();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("UPDATE ticket_events SET comment = 'x' WHERE ticket_id = ?::uuid",
                id)).hasMessageContaining("append-only");
    }

    @Test
    void ticketsCarrySeverityCategoryAndAttachmentsAndCloseOnlyOnTheRequestersConfirmation() throws Exception {
        // Severity and an active category are required.
        mvc.perform(post("/api/v1/tickets").header("Authorization", reporter.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("ticketType", "INCIDENT", "priority", "P2", "title", "x", "description", "y"))))
                .andExpect(status().isBadRequest());
        Session admin = login(createUser("sa2", "SYSTEM_ADMINISTRATOR"));
        JsonNode retired = body(mvc.perform(post("/api/v1/ticket-categories").header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("code", "IT-" + System.nanoTime() % 100_000_000L,
                        "name", "Retired category", "sortOrder", 99)))).andExpect(status().isCreated()).andReturn());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/ticket-categories/" + retired.get("id").asText())
                .header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("name", "Retired category", "sortOrder", 99, "status", "INACTIVE")))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/ticket-categories").header("Authorization", reporter.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("code", "NOPE", "name", "x", "sortOrder", 1)))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/tickets").header("Authorization", reporter.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("ticketType", "INCIDENT", "priority", "P2", "severity", "S2", "categoryId", retired.get("id").asText(),
                        "title", "x", "description", "y")))).andExpect(status().isBadRequest());

        JsonNode t = created(reporter, "INCIDENT", "P2", "Voucher print is blank");
        String id = t.get("id").asText();
        assertThat(t.get("severity").asText()).isEqualTo("S2");
        assertThat(t.get("category").asText()).contains("Application defect");

        // The requester attaches a screenshot; others cannot; it shows in the history.
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        mvc.perform(multipart("/api/v1/tickets/" + id + "/documents").file(new MockMultipartFile("file", "screen.png", "image/png", png))
                .header("Authorization", other.bearer())).andExpect(status().isNotFound());
        t = body(mvc.perform(multipart("/api/v1/tickets/" + id + "/documents").file(new MockMultipartFile("file", "screen.png", "image/png", png))
                .header("Authorization", reporter.bearer())).andExpect(status().isCreated()).andReturn());
        assertThat(t.get("documents")).hasSize(1);
        assertThat(t.get("events").toString()).contains("ATTACHMENT", "screen.png");

        // Support reclassifies with a reason.
        t = ok(act(agent, t, "ACKNOWLEDGE", null));
        Map<String, Object> classify = action(t, "CLASSIFY", "Blocks month-end printing");
        classify.put("severity", "S1");
        t = ok(send(agent, t, classify));
        assertThat(t.get("severity").asText()).isEqualTo("S1");
        JsonNode list = body(mvc.perform(get("/api/v1/tickets").param("all", "true").param("severity", "S1").header("Authorization", agent.bearer()))
                .andReturn());
        assertThat(list.toString()).contains(id);

        // Support resolves but cannot close; the requester confirms with a rating.
        t = ok(act(agent, t, "RESOLVE", "Printer template fixed"));
        act(agent, t, "CLOSE", null).andExpect(status().isForbidden());
        Map<String, Object> close = action(t, "CLOSE", "Prints correctly now");
        close.put("satisfaction", 4);
        t = ok(send(reporter, t, close));
        assertThat(t.get("closureConfirmed").asBoolean()).isTrue();
        assertThat(t.get("satisfaction").asInt()).isEqualTo(4);
        mvc.perform(multipart("/api/v1/tickets/" + id + "/documents").file(new MockMultipartFile("file", "late.png", "image/png", png))
                .header("Authorization", reporter.bearer())).andExpect(status().isConflict());

        // A resolution nobody confirms closes itself after the reopen window, marked unconfirmed.
        JsonNode quiet = created(reporter, "SERVICE_REQUEST", "P3", "New report user");
        quiet = ok(act(agent, quiet, "ACKNOWLEDGE", null));
        quiet = ok(act(agent, quiet, "RESOLVE", "User added to the report group"));
        jdbc.update("UPDATE tickets SET resolved_at = now() - interval '8 days' WHERE id = ?::uuid", quiet.get("id").asText());
        monitor.poll();
        JsonNode after = body(mvc.perform(get("/api/v1/tickets/" + quiet.get("id").asText()).header("Authorization", reporter.bearer())).andReturn());
        assertThat(after.get("status").asText()).isEqualTo("CLOSED");
        assertThat(after.get("closureConfirmed").asBoolean()).isFalse();
        assertThat(after.get("events").toString()).contains("Closed automatically");
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode created(Session who, String type, String priority, String title) throws Exception {
        String category = jdbc.queryForObject("SELECT id::text FROM ticket_categories WHERE code = 'PLC-DEFECT'", String.class);
        return body(mvc.perform(post("/api/v1/tickets").header("Authorization", who.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("ticketType", type, "priority", priority, "severity", "S2", "categoryId", category, "title", title,
                        "description", title + " — details"))))
                .andExpect(status().isCreated()).andReturn());
    }

    private void assignTo(JsonNode t, String username) throws Exception {
        Map<String, Object> assign = action(t, "ASSIGN", null);
        assign.put("assignee", username);
        ok(send(agent, t, assign));
    }

    private ResultActions send(Session who, JsonNode t, Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/v1/tickets/" + t.get("id").asText() + "/actions").header("Authorization", who.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(body)));
    }

    private static Map<String, Object> action(JsonNode t, String action, String comment) {
        Map<String, Object> m = new HashMap<>();
        m.put("version", t.get("version").asLong());
        m.put("action", action);
        m.put("comment", comment);
        return m;
    }

    private ResultActions act(Session who, JsonNode t, String action, String comment) throws Exception {
        return mvc.perform(post("/api/v1/tickets/" + t.get("id").asText() + "/actions").header("Authorization", who.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(action(t, action, comment))));
    }

    private JsonNode ok(ResultActions r) throws Exception {
        return body(r.andExpect(status().isOk()).andReturn());
    }
}
