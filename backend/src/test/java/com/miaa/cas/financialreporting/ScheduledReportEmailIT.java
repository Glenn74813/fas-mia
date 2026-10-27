package com.miaa.cas.financialreporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.support.AbstractReceivablesIT;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** Report delivery: the owner of a schedule is e-mailed (a link, never the file) when its run finishes or fails. */
class ScheduledReportEmailIT extends AbstractReceivablesIT {

    @Autowired ReportScheduleService schedules;
    @Autowired ReportJobService jobs;

    @Test
    void aFinishedScheduledRunEmailsTheOwnerALinkOnly() throws Exception {
        TestUser owner = createUser("mailsched", "CHIEF_ACCOUNTANT");
        Session s = login(owner);
        JsonNode schedule = create(s, null);
        assertThat(schedule.get("emailWhenReady").asBoolean()).isTrue();

        UUID job = run(schedule);
        assertThat(jdbc.queryForObject("SELECT schedule_id FROM report_jobs WHERE id = ?", UUID.class, job))
                .isEqualTo(UUID.fromString(schedule.get("id").asText()));
        jobs.processPending();

        List<Map<String, Object>> mail = mail(owner.email());
        assertThat(mail).hasSize(1);
        assertThat(mail.get(0).get("template")).isEqualTo("scheduled-report-ready");
        String payload = (String) mail.get(0).get("payload");
        assertThat(payload).contains("Monthly collections").contains(".xlsx").contains("collection summary");
        // The message never carries the report's content: only names and the retention period.
        assertThat(payload).doesNotContain("content").doesNotContain("sha256");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'REPORT_EMAIL_QUEUED' AND resource_id = ?",
                Integer.class, job.toString())).isEqualTo(1);

        // An ordinary (unscheduled) job sends no e-mail.
        mvc.perform(post("/api/v1/report-jobs").header("Authorization", s.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("reportType", "COLLECTION_SUMMARY", "format", "CSV",
                        "parameters", Map.of("from", "2026-01-01", "to", "2026-01-31"))))).andExpect(status().isCreated());
        jobs.processPending();
        assertThat(mail(owner.email())).hasSize(1);
    }

    @Test
    void theOwnerCanTurnTheEmailOffAndAFailureIsReported() throws Exception {
        TestUser owner = createUser("mailoff", "CHIEF_ACCOUNTANT");
        Session s = login(owner);
        JsonNode schedule = create(s, false);
        assertThat(schedule.get("emailWhenReady").asBoolean()).isFalse();
        run(schedule);
        jobs.processPending();
        assertThat(mail(owner.email())).isEmpty();

        // Turned on again with the current version (each run bumps it); a stale version is refused.
        long version = jdbc.queryForObject("SELECT version FROM report_schedules WHERE id = ?::uuid", Long.class, schedule.get("id").asText());
        JsonNode on = body(mvc.perform(put("/api/v1/report-schedules/" + schedule.get("id").asText() + "/email")
                .header("Authorization", s.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("emailWhenReady", true, "version", version))))
                .andExpect(status().isOk()).andReturn());
        assertThat(on.get("emailWhenReady").asBoolean()).isTrue();
        mvc.perform(put("/api/v1/report-schedules/" + schedule.get("id").asText() + "/email").header("Authorization", s.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("emailWhenReady", false, "version", schedule.get("version").asLong()))))
                .andExpect(status().isConflict());
        // Another user cannot change it.
        mvc.perform(put("/api/v1/report-schedules/" + schedule.get("id").asText() + "/email")
                .header("Authorization", login(createUser("mailother", "CHIEF_ACCOUNTANT")).bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("emailWhenReady", false, "version", on.get("version").asLong())))).andExpect(status().isNotFound());

        UUID job = run(on);
        jdbc.update("UPDATE report_jobs SET parameters = '{\"from\": \"not-a-date\", \"to\": \"2026-01-31\"}'::jsonb WHERE id = ?", job);
        jobs.processPending();
        assertThat(jdbc.queryForObject("SELECT status FROM report_jobs WHERE id = ?", String.class, job)).isEqualTo("FAILED");
        List<Map<String, Object>> mail = mail(owner.email());
        assertThat(mail).hasSize(1);
        assertThat(mail.get(0).get("template")).isEqualTo("scheduled-report-failed");
    }

    private JsonNode create(Session s, Boolean email) throws Exception {
        Map<String, Object> req = new HashMap<>(Map.of("name", "Monthly collections", "reportType", "COLLECTION_SUMMARY", "format", "XLSX",
                "parameters", Map.of("from", "{prevMonthStart}", "to", "{prevMonthEnd}"), "frequency", "MONTHLY", "runHour", 7, "dayOfMonth", 1));
        if (email != null) {
            req.put("emailWhenReady", email);
        }
        return body(mvc.perform(post("/api/v1/report-schedules").header("Authorization", s.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(req))).andExpect(status().isCreated()).andReturn());
    }

    /** Makes the schedule due, runs it, and returns the queued job. */
    private UUID run(JsonNode schedule) {
        String id = schedule.get("id").asText();
        jdbc.update("UPDATE report_schedules SET next_run_at = now() - interval '1 minute' WHERE id = ?::uuid", id);
        schedules.runDue();
        return jdbc.queryForObject("SELECT last_job_id FROM report_schedules WHERE id = ?::uuid", UUID.class, id);
    }

    private List<Map<String, Object>> mail(String recipient) {
        return jdbc.queryForList("SELECT template, payload::text AS payload FROM notification_outbox WHERE recipient = ? ORDER BY created_at",
                recipient);
    }
}
