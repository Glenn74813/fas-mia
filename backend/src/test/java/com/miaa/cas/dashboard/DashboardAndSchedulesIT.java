package com.miaa.cas.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.financialreporting.ReportScheduleService;
import com.miaa.cas.support.AbstractReceivablesIT;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class DashboardAndSchedulesIT extends AbstractReceivablesIT {

    @Autowired ReportScheduleService schedules;

    @Test
    void eachRoleSeesItsOwnSectionsAndWork() throws Exception {
        int year = newOpenYear();
        JsonNode customer = createCustomer(null);
        JsonNode draft = createBilling(billing("INVOICE", customer.get("id").asText(), LocalDate.of(year, 3, 1), "PLC-4201", "750.00", false, null));
        ok(apAction(preparer, "accounts-receivable/billings", draft.get("id").asText(), "submit", draft.get("version").asLong(), null));

        JsonNode chief = body(mvc.perform(get("/api/v1/dashboard").header("Authorization", approver.bearer())).andExpect(status().isOk()).andReturn());
        assertThat(chief.get("receivables").isNull()).isFalse();
        assertThat(chief.at("/receivables/collections").size()).isEqualTo(6);
        assertThat(chief.get("payables").isNull()).isFalse();
        assertThat(chief.get("workQueue").toString()).contains("Billings to approve");
        // The preparer does not see their own billing as work.
        JsonNode prep = body(mvc.perform(get("/api/v1/dashboard").header("Authorization", preparer.bearer())).andExpect(status().isOk()).andReturn());
        assertThat(prep.get("workQueue").toString()).doesNotContain("Billings to approve");

        // A support agent sees no financial figures at all.
        Session agent = login(createUser("dagent", "SUPPORT_AGENT"));
        JsonNode a = body(mvc.perform(get("/api/v1/dashboard").header("Authorization", agent.bearer())).andExpect(status().isOk()).andReturn());
        assertThat(a.get("receivables").isNull()).isTrue();
        assertThat(a.get("payables").isNull()).isTrue();
        assertThat(a.get("cash").isNull()).isTrue();
        assertThat(a.get("budget").isNull()).isTrue();

        // Concessionaires have the portal, not the internal dashboard.
        TestUser conc = createUser("dconc", "CONCESSIONAIRE");
        jdbc.update("UPDATE users SET user_type = 'CONCESSIONAIRE', customer_id = ?::uuid WHERE id = ?", customer.get("id").asText(), conc.id());
        mvc.perform(get("/api/v1/dashboard").header("Authorization", login(conc).bearer())).andExpect(status().isForbidden());
    }

    @Test
    void schedulesQueueJobsWithResolvedDatesAndStopWhenThePermissionIsGone() throws Exception {
        TestUser owner = createUser("sched", "CHIEF_ACCOUNTANT");
        Session s = login(owner);
        mvc.perform(post("/api/v1/report-schedules").header("Authorization", s.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("name", "Bad", "reportType", "COLLECTION_SUMMARY", "format", "XLSX",
                        "parameters", Map.of("from", "{nonsense}", "to", "{today}"), "frequency", "DAILY", "runHour", 6))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/report-schedules").header("Authorization", s.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("name", "Weekly", "reportType", "COLLECTION_SUMMARY", "format", "XLSX",
                        "parameters", Map.of("from", "{monthStart}", "to", "{today}"), "frequency", "WEEKLY", "runHour", 6))))
                .andExpect(status().isBadRequest());
        JsonNode created = body(mvc.perform(post("/api/v1/report-schedules").header("Authorization", s.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("name", "Monthly collections", "reportType", "COLLECTION_SUMMARY",
                        "format", "XLSX", "parameters", Map.of("from", "{prevMonthStart}", "to", "{prevMonthEnd}"), "frequency", "MONTHLY",
                        "runHour", 7, "dayOfMonth", 1)))).andExpect(status().isCreated()).andReturn());
        ZonedDateTime next = Instant.parse(created.get("nextRunAt").asText()).atZone(ZoneId.of("Asia/Manila"));
        assertThat(next.getDayOfMonth()).isEqualTo(1);
        assertThat(next.getHour()).isEqualTo(7);
        String id = created.get("id").asText();

        // Due now: an ordinary job is queued for the owner with the dates resolved.
        jdbc.update("UPDATE report_schedules SET next_run_at = now() - interval '1 minute' WHERE id = ?::uuid", id);
        schedules.runDue();
        Map<String, Object> job = jdbc.queryForMap("SELECT j.requested_by_id, j.parameters::text AS p FROM report_schedules r "
                + "JOIN report_jobs j ON j.id = r.last_job_id WHERE r.id = ?::uuid", id);
        assertThat(job.get("requested_by_id")).isEqualTo(owner.id());
        LocalDate prev = LocalDate.now(ZoneId.of("Asia/Manila")).minusMonths(1);
        assertThat((String) job.get("p")).contains(prev.withDayOfMonth(1).toString()).doesNotContain("{prev");
        assertThat(Instant.parse(body(mvc.perform(get("/api/v1/report-schedules").header("Authorization", s.bearer())).andReturn())
                .get(0).get("nextRunAt").asText())).isAfter(Instant.now());

        // The owner loses the permission: the next run suspends the schedule instead of running it.
        jdbc.update("DELETE FROM user_roles WHERE user_id = ?", owner.id());
        jdbc.update("UPDATE report_schedules SET next_run_at = now() - interval '1 minute' WHERE id = ?::uuid", id);
        schedules.runDue();
        Map<String, Object> after = jdbc.queryForMap("SELECT status, status_reason FROM report_schedules WHERE id = ?::uuid", id);
        assertThat(after.get("status")).isEqualTo("SUSPENDED");
        assertThat((String) after.get("status_reason")).contains("AR_VIEW");

        mvc.perform(delete("/api/v1/report-schedules/" + id).header("Authorization", login(createUser("sched2", "CHIEF_ACCOUNTANT")).bearer()))
                .andExpect(status().isNotFound());
    }
}
