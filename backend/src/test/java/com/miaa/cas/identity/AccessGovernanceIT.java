package com.miaa.cas.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.support.AbstractReceivablesIT;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

/** Access governance (MS §9.3): maker-checker for financial access (C-19), approval limits (C-24), access reviews. */
@TestPropertySource(properties = "cas.access.financial-grant-approval=true")
class AccessGovernanceIT extends AbstractReceivablesIT {

    @Test
    void financialRolesWaitForASecondPersonsApproval() throws Exception {
        Session admin = login(createUser("ag.admin", "SYSTEM_ADMINISTRATOR"));
        TestUser approverUser = createUser("ag.fm", "FINANCE_MANAGER");
        Session approver = login(approverUser);

        // Creating a user with a financial role: the non-financial role applies, the financial one waits.
        String username = unique("ag.new");
        JsonNode created = body(mvc.perform(post("/api/v1/users").header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("username", username, "email", username + "@test.miaa.local",
                        "displayName", "Grant Test", "userType", "INTERNAL", "temporaryPassword", "Initial-Passw0rd-1",
                        "roleCodes", List.of("ACCOUNTANT", "SUPPORT_AGENT"))))).andExpect(status().isCreated()).andReturn());
        String userId = created.at("/user/id").asText();
        assertThat(created.at("/user/roles").toString()).contains("SUPPORT_AGENT").doesNotContain("ACCOUNTANT");
        JsonNode grant = created.get("pendingGrant");
        assertThat(grant.get("financialAdded").toString()).contains("ACCOUNTANT");
        String grantId = grant.get("id").asText();

        // The approver sees it in the inbox; the administrator can neither approve nor see approve actions.
        assertThat(body(mvc.perform(get("/api/v1/inbox").header("Authorization", approver.bearer())).andReturn()).toString()).contains(grantId);
        decide(admin, grantId, "approve", grant.get("version").asLong(), null).andExpect(status().isForbidden());
        JsonNode approved = ok(decide(approver, grantId, "approve", grant.get("version").asLong(), "Matches the HR appointment"));
        assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
        JsonNode user = body(mvc.perform(get("/api/v1/users/" + userId).header("Authorization", admin.bearer())).andReturn());
        assertThat(user.get("roles").toString()).contains("ACCOUNTANT").contains("SUPPORT_AGENT");

        // A later change adding a financial role waits whole; a rejection needs a reason and changes nothing.
        JsonNode change = body(mvc.perform(put("/api/v1/users/" + userId + "/roles").header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", user.get("version").asLong(),
                        "roleCodes", List.of("ACCOUNTANT", "INVENTORY_CLERK"))))).andExpect(status().isOk()).andReturn());
        JsonNode second = change.get("pendingGrant");
        assertThat(second.get("financialAdded").toString()).contains("INVENTORY_CLERK");
        assertThat(change.at("/user/roles").toString()).contains("SUPPORT_AGENT").doesNotContain("INVENTORY_CLERK");
        mvc.perform(put("/api/v1/users/" + userId + "/roles").header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("version", change.at("/user/version").asLong(), "roleCodes", List.of("ACCOUNTANT", "BUDGET_OFFICER")))))
                .andExpect(status().isConflict());
        decide(approver, second.get("id").asText(), "reject", second.get("version").asLong(), "no").andExpect(status().isBadRequest());
        assertThat(ok(decide(approver, second.get("id").asText(), "reject", second.get("version").asLong(), "Not in the job description"))
                .get("status").asText()).isEqualTo("REJECTED");

        // Nobody decides a grant of their own access.
        JsonNode own = body(mvc.perform(put("/api/v1/users/" + approverUser.id() + "/roles").header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", version(approverUser.id().toString(), admin),
                        "roleCodes", List.of("FINANCE_MANAGER", "INVENTORY_CLERK"))))).andExpect(status().isOk()).andReturn()).get("pendingGrant");
        decide(approver, own.get("id").asText(), "approve", own.get("version").asLong(), null).andExpect(status().isForbidden());
        ok(mvc.perform(post("/api/v1/role-grants/" + own.get("id").asText() + "/cancel").header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", own.get("version").asLong())))));
    }

    @Test
    void approvalLimitsBindApproversByRole() throws Exception {
        Session limitManager = login(createUser("ag.limits", "FINANCE_MANAGER"));
        Session admin = login(createUser("ag.admin2", "SYSTEM_ADMINISTRATOR"));
        int year = newOpenYear();
        String customer = createCustomer(null).get("id").asText();
        JsonNode bill = createBilling(billing("INVOICE", customer, LocalDate.of(year, 5, 1), "PLC-4201", "750.00", false, null));
        bill = ok(apAction(preparer, "accounts-receivable/billings", bill.get("id").asText(), "submit", bill.get("version").asLong(), null));
        String id = bill.get("id").asText();
        mvc.perform(put("/api/v1/approval-limits/CHIEF_ACCOUNTANT/AR_INVOICE").header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("maxAmount", "500.00")))).andExpect(status().isForbidden());
        try {
            ok(mvc.perform(put("/api/v1/approval-limits/CHIEF_ACCOUNTANT/AR_INVOICE").header("Authorization", limitManager.bearer())
                    .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("maxAmount", "500.00")))));
            // Above the limit: no approve action, not in the inbox, refused by the server.
            JsonNode view = body(mvc.perform(get("/api/v1/accounts-receivable/billings/" + id).header("Authorization", approver.bearer())).andReturn());
            assertThat(view.get("availableActions").toString()).doesNotContain("APPROVE");
            assertThat(body(mvc.perform(get("/api/v1/inbox").header("Authorization", approver.bearer())).andReturn()).toString()).doesNotContain(id);
            JsonNode refused = body(apAction(approver, "accounts-receivable/billings", id, "approve", bill.get("version").asLong(), null)
                    .andExpect(status().isForbidden()).andReturn());
            assertThat(refused.get("detail").asText()).contains("above your approval limit of 500.00");

            ok(mvc.perform(put("/api/v1/approval-limits/CHIEF_ACCOUNTANT/AR_INVOICE").header("Authorization", limitManager.bearer())
                    .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("maxAmount", "1000.00")))));
            assertThat(ok(apAction(approver, "accounts-receivable/billings", id, "approve", bill.get("version").asLong(), null))
                    .get("status").asText()).isEqualTo("APPROVED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'APPROVAL_LIMIT_SET' AND resource_id = ?",
                    Integer.class, "CHIEF_ACCOUNTANT/AR_INVOICE")).isGreaterThanOrEqualTo(2);
        } finally {
            jdbc.update("DELETE FROM approval_limits WHERE role_code = 'CHIEF_ACCOUNTANT' AND document_type = 'AR_INVOICE'");
        }
        mvc.perform(delete("/api/v1/approval-limits/CHIEF_ACCOUNTANT/AR_INVOICE").header("Authorization", limitManager.bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void anAccessReviewCycleClosesOnlyWhenEveryReviewAndChangeIsDone() throws Exception {
        jdbc.update("UPDATE access_review_cycles SET status = 'CLOSED', closed_by = 'test', closed_at = now() WHERE status = 'OPEN'");
        TestUser auditorUser = createUser("ag.aud", "INTERNAL_AUDITOR");
        Session auditor = login(auditorUser);
        Session admin = login(createUser("ag.admin3", "SYSTEM_ADMINISTRATOR"));
        TestUser target = createUser("ag.target", "ACCOUNTANT", "SUPPORT_AGENT");

        JsonNode cycle = body(mvc.perform(post("/api/v1/access-review-cycles").header("Authorization", auditor.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("name", "Q4 review", "dueDate", LocalDate.now().plusDays(14).toString()))))
                .andExpect(status().isCreated()).andReturn());
        String cycleId = cycle.get("id").asText();
        mvc.perform(post("/api/v1/access-review-cycles").header("Authorization", auditor.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("name", "Second", "dueDate", LocalDate.now().plusDays(14).toString())))).andExpect(status().isConflict());
        JsonNode detail = body(mvc.perform(get("/api/v1/access-review-cycles/" + cycleId).header("Authorization", auditor.bearer())).andReturn());
        JsonNode mine = item(detail, auditorUser.username());
        JsonNode theirs = item(detail, target.username());
        assertThat(theirs.get("financial").asBoolean()).isTrue();
        assertThat(mine.get("availableActions").toString()).doesNotContain("REVIEW");
        review(auditor, mine, "RETAIN", null).andExpect(status().isForbidden());
        review(auditor, theirs, "MODIFY", null).andExpect(status().isBadRequest());
        JsonNode reviewed = ok(review(auditor, theirs, "MODIFY", "Support role no longer needed"));
        assertThat(body(mvc.perform(get("/api/v1/inbox").header("Authorization", admin.bearer())).andReturn()).toString())
                .contains("Access review changes to apply");

        // Everyone else is retained (stand-in for the other reviewers); the change is still open, so the cycle stays open.
        jdbc.update("UPDATE access_reviews SET outcome = 'RETAIN', reviewer = 'test', reviewed_at = now() WHERE cycle_id = ?::uuid AND outcome IS NULL",
                cycleId);
        long cycleVersion = body(mvc.perform(get("/api/v1/access-review-cycles/" + cycleId).header("Authorization", auditor.bearer()))
                .andReturn()).at("/summary/version").asLong();
        mvc.perform(post("/api/v1/access-review-cycles/" + cycleId + "/close").header("Authorization", auditor.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", cycleVersion)))).andExpect(status().isConflict());

        // The administrator must change the roles before recording the action.
        mvc.perform(post("/api/v1/access-reviews/" + reviewed.get("id").asText() + "/action").header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", reviewed.get("version").asLong(),
                        "note", "Removed the support role")))).andExpect(status().isConflict());
        ok(mvc.perform(put("/api/v1/users/" + target.id() + "/roles").header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("version", version(target.id().toString(), admin), "roleCodes", List.of("ACCOUNTANT"))))));
        ok(mvc.perform(post("/api/v1/access-reviews/" + reviewed.get("id").asText() + "/action").header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", reviewed.get("version").asLong(),
                        "note", "Removed the support role")))));
        JsonNode closed = ok(mvc.perform(post("/api/v1/access-review-cycles/" + cycleId + "/close").header("Authorization", auditor.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", cycleVersion)))));
        assertThat(closed.get("status").asText()).isEqualTo("CLOSED");
    }

    private ResultActions decide(Session who, String id, String action, long version, String note) throws Exception {
        return mvc.perform(post("/api/v1/role-grants/" + id + "/" + action).header("Authorization", who.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(note == null ? Map.of("version", version) : Map.of("version", version, "note", note))));
    }

    private ResultActions review(Session who, JsonNode item, String outcome, String notes) throws Exception {
        return mvc.perform(post("/api/v1/access-reviews/" + item.get("id").asText() + "/review").header("Authorization", who.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(notes == null ? Map.of("version", item.get("version").asLong(), "outcome", outcome)
                        : Map.of("version", item.get("version").asLong(), "outcome", outcome, "notes", notes))));
    }

    private long version(String userId, Session admin) throws Exception {
        return body(mvc.perform(get("/api/v1/users/" + userId).header("Authorization", admin.bearer())).andReturn()).get("version").asLong();
    }

    private static JsonNode item(JsonNode detail, String username) {
        for (JsonNode i : detail.get("items")) {
            if (i.get("username").asText().equals(username)) {
                return i;
            }
        }
        throw new AssertionError("No review for " + username);
    }
}
