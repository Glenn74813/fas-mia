package com.miaa.cas.closing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.support.AbstractAccountingIT;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Close checklists per frequency, task completion by the responsible role, the schedule and the inbox. */
class CloseCalendarIT extends AbstractAccountingIT {

    @Test
    void checklistsCombineAutomaticChecksWithTasksTickedOffByTheirOwners() throws Exception {
        int year = newOpenYear();
        UUID fy = jdbc.queryForObject("SELECT id FROM fiscal_years WHERE fiscal_year = ?", UUID.class, year);
        String code = "IT-DAY-" + UUID.randomUUID().toString().substring(0, 6);

        // Task templates: chief accountants maintain them; codes are unique.
        Map<String, Object> task = new HashMap<>(Map.of("code", code, "name", "Match the day's clearing account items", "frequency", "DAY_END",
                "dueOffsetDays", 0, "responsiblePermission", "GL_CLEARING", "instructions", "Use Open items.", "sortOrder", 5));
        mvc.perform(post("/api/v1/close/tasks").header("Authorization", preparer.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(task))).andExpect(status().isForbidden());
        JsonNode created = body(mvc.perform(post("/api/v1/close/tasks").header("Authorization", approver.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(task))).andExpect(status().isCreated()).andReturn());
        String taskId = created.get("id").asText();
        mvc.perform(post("/api/v1/close/tasks").header("Authorization", approver.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(task))).andExpect(status().isConflict());

        // Day end: the automatic checks plus the task, open and completable by an accountant.
        LocalDate day = LocalDate.of(year, 3, 2);
        while (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
            day = day.plusDays(1);
        }
        JsonNode dayEnd = checklist(preparer, "DAY_END", day.toString());
        assertThat(dayEnd.get("checks").toString()).contains("COLLECTIONS_DEPOSITED", "COLLECTIONS_POSTED", "PAYMENTS_POSTED", "INTEGRATIONS_HEALTHY");
        JsonNode item = taskItem(dayEnd, taskId);
        assertThat(item.get("status").asText()).isEqualTo("OPEN");
        assertThat(item.get("canComplete").asBoolean()).isTrue();
        assertThat(item.get("dueDate").asText()).isEqualTo(day.toString());

        // Only holders of the responsible permission complete it, with a note, once per scope.
        Session clerk = login(createUser("clerk", "INVENTORY_CLERK"));
        assertThat(taskItem(checklist(clerk, "DAY_END", day.toString()), taskId).get("canComplete").asBoolean()).isFalse();
        complete(clerk, taskId, day.toString(), "DONE", "Cleared all items").andExpect(status().isForbidden());
        complete(preparer, taskId, day.toString(), "DONE", "ok").andExpect(status().isBadRequest());
        JsonNode done = body(complete(preparer, taskId, day.toString(), "DONE", "All clearing items matched").andExpect(status().isOk()).andReturn());
        assertThat(taskItem(done, taskId).get("status").asText()).isEqualTo("DONE");
        assertThat(taskItem(done, taskId).get("completedBy").asText()).isEqualTo(preparerUser.username());
        complete(preparer, taskId, day.toString(), "DONE", "Done twice").andExpect(status().isConflict());

        // Month end reuses the period-close checks (including the aged open items); quarter and year end add their own.
        JsonNode month = checklist(preparer, "MONTH_END", period(year, 3).getId().toString());
        assertThat(month.get("checks").toString()).contains("NO_UNPOSTED_VOUCHERS", "OPEN_ITEMS_AGED");
        assertThat(month.get("tasks").toString()).contains("PLC-MONTH-REVIEW");
        JsonNode quarter = checklist(preparer, "QUARTER_END", fy + ":Q1");
        assertThat(quarter.get("label").asText()).isEqualTo("Q1 " + year);
        assertThat(outcome(quarter, "QUARTER_PERIODS_CLOSED")).isEqualTo("FAIL");
        assertThat(quarter.get("checks").toString()).contains("BANK_RECONCILIATIONS", "WITHHOLDING_SCHEDULE");
        JsonNode yearEnd = checklist(preparer, "YEAR_END", fy.toString());
        assertThat(outcome(yearEnd, "YEAR_PERIODS_CLOSED")).isEqualTo("FAIL");
        assertThat(outcome(yearEnd, "CLOSING_ENTRY_POSTED")).isEqualTo("FAIL");
        assertThat(outcome(yearEnd, "FISCAL_YEAR_CLOSED")).isEqualTo("FAIL");
        mvc.perform(get("/api/v1/close/checklists").param("frequency", "QUARTER_END").param("scope", fy + ":Q7")
                .header("Authorization", preparer.bearer())).andExpect(status().isBadRequest());

        // The schedule: weekdays only for day-end tasks, completed occurrences shown as such.
        JsonNode schedule = body(mvc.perform(get("/api/v1/close/schedule").param("from", LocalDate.of(year, 3, 1).toString())
                .param("to", LocalDate.of(year, 3, 31).toString()).header("Authorization", preparer.bearer())).andExpect(status().isOk()).andReturn());
        int occurrences = 0;
        for (JsonNode s : schedule) {
            if (s.get("taskId").asText().equals(taskId)) {
                occurrences++;
                DayOfWeek dow = LocalDate.parse(s.get("dueDate").asText()).getDayOfWeek();
                assertThat(dow).isNotIn(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
                assertThat(s.get("status").asText()).isEqualTo(s.get("scopeKey").asText().equals(day.toString()) ? "DONE" : "OPEN");
            }
        }
        assertThat(occurrences).isBetween(21, 23);
        assertThat(schedule.toString()).contains("[PLACEHOLDER] Review accruals");
        mvc.perform(get("/api/v1/close/schedule").param("from", LocalDate.of(year, 1, 1).toString()).param("to", LocalDate.of(year, 6, 30).toString())
                .header("Authorization", preparer.bearer())).andExpect(status().isBadRequest());

        // Due and overdue tasks appear in the owner's inbox.
        JsonNode inbox = body(mvc.perform(get("/api/v1/inbox").header("Authorization", preparer.bearer())).andExpect(status().isOk()).andReturn());
        assertThat(inbox.get("items").toString()).contains("Match the day's clearing account items");

        // Deactivated tasks drop out of checklists, the schedule and the inbox.
        task.put("version", created.get("version").asLong());
        task.put("status", "INACTIVE");
        mvc.perform(put("/api/v1/close/tasks/" + taskId).header("Authorization", approver.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(task))).andExpect(status().isOk());
        assertThat(checklist(preparer, "DAY_END", day.toString()).get("tasks").toString()).doesNotContain(taskId);
        mvc.perform(put("/api/v1/close/tasks/" + taskId).header("Authorization", approver.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(task))).andExpect(status().isConflict());
    }

    private JsonNode checklist(Session who, String frequency, String scope) throws Exception {
        return body(mvc.perform(get("/api/v1/close/checklists").param("frequency", frequency).param("scope", scope)
                .header("Authorization", who.bearer())).andExpect(status().isOk()).andReturn());
    }

    private ResultActions complete(Session who, String taskId, String scope, String outcome, String note) throws Exception {
        return mvc.perform(post("/api/v1/close/tasks/" + taskId + "/complete").header("Authorization", who.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("scopeKey", scope, "outcome", outcome, "note", note))));
    }

    private static JsonNode taskItem(JsonNode checklist, String taskId) {
        for (JsonNode t : checklist.get("tasks")) {
            if (t.get("taskId").asText().equals(taskId)) {
                return t;
            }
        }
        throw new AssertionError("Task " + taskId + " not in " + checklist.get("tasks"));
    }

    private static String outcome(JsonNode checklist, String code) {
        for (JsonNode c : checklist.get("checks")) {
            if (c.get("code").asText().equals(code)) {
                return c.get("outcome").asText();
            }
        }
        throw new AssertionError(code + " not in " + checklist.get("checks"));
    }
}
