package com.miaa.cas.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.support.AbstractIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class UserAdminIT extends AbstractIntegrationTest {

    private TestUser adminUser;
    private Session admin;

    @BeforeEach
    void signInAdmin() throws Exception {
        adminUser = createUser("admin", "SYSTEM_ADMINISTRATOR");
        admin = login(adminUser);
    }

    @Test
    void createUserAuditsAndRequiresPasswordChange() throws Exception {
        String username = unique("newuser");
        JsonNode created = body(mvc.perform(post("/api/v1/users").header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("username", username,
                        "email", username + "@test.miaa.local", "displayName", "New User", "userType", "INTERNAL",
                        "temporaryPassword", "Initial-Passw0rd-1", "roleCodes", List.of("ACCOUNTANT")))))
                .andExpect(status().isCreated()).andReturn());
        assertThat(created.at("/user/mustChangePassword").asBoolean()).isTrue();
        assertThat(created.at("/user/roles/0").asText()).isEqualTo("ACCOUNTANT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'USER_CREATED' AND resource_id = ?",
                Long.class, created.at("/user/id").asText())).isEqualTo(1);
    }

    @Test
    void externalAuditorAccessIsTimeLimited() throws Exception {
        String username = unique("extaud");
        Map<String, Object> req = new HashMap<>(Map.of("username", username, "email", username + "@auditor.test", "displayName", "COA Auditor",
                "userType", "EXTERNAL_AUDITOR", "temporaryPassword", "Initial-Passw0rd-1", "roleCodes", List.of("EXTERNAL_AUDITOR")));
        createUser(req).andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("expiry")));
        req.put("accessExpiresAt", Instant.now().minus(1, ChronoUnit.DAYS).toString());
        createUser(req).andExpect(status().isBadRequest());
        Instant until = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        req.put("accessExpiresAt", until.toString());
        JsonNode created = body(createUser(req).andExpect(status().isCreated()).andReturn()).get("user");
        assertThat(Instant.parse(created.get("accessExpiresAt").asText())).isEqualTo(until);
        String id = created.get("id").asText();

        // The expiry cannot be removed, and a new expiry must lie ahead; extending it is audited.
        update(id, created.get("version").asLong(), null).andExpect(status().isBadRequest());
        Instant later = until.plus(30, ChronoUnit.DAYS);
        JsonNode extended = body(update(id, created.get("version").asLong(), later.toString()).andExpect(status().isOk()).andReturn());
        assertThat(Instant.parse(extended.get("accessExpiresAt").asText())).isEqualTo(later);
        assertThat(jdbc.queryForObject("SELECT details::text FROM audit_events WHERE action = 'USER_UPDATED' AND resource_id = ?",
                String.class, id)).contains(until.toString()).contains(later.toString());

        // Once it has passed, sign-in fails; other details can still be corrected without moving the expiry.
        Instant past = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        jdbc.update("UPDATE users SET access_expires_at = ?, must_change_password = false WHERE id = ?::uuid",
                java.sql.Timestamp.from(past), id);
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("identifier", username, "password", "Initial-Passw0rd-1")))).andExpect(status().isUnauthorized());
        long version = body(mvc.perform(get("/api/v1/users/" + id).header("Authorization", admin.bearer())).andReturn()).get("version").asLong();
        update(id, version, past.toString()).andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions createUser(Map<String, Object> req) throws Exception {
        return mvc.perform(post("/api/v1/users").header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON).content(toJson(req)));
    }

    private org.springframework.test.web.servlet.ResultActions update(String id, long version, String expiry) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("version", version, "email", "coa." + id.substring(0, 8) + "@auditor.test",
                "displayName", "COA Auditor"));
        body.put("accessExpiresAt", expiry);
        return mvc.perform(put("/api/v1/users/" + id).header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(body)));
    }

    @Test
    void duplicateUsernameRejected() throws Exception {
        TestUser existing = createUser("dup", "ACCOUNTANT");
        mvc.perform(post("/api/v1/users").header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("username", existing.username(), "email", "other." + existing.email(),
                        "displayName", "Dup", "userType", "INTERNAL", "temporaryPassword", "Initial-Passw0rd-1",
                        "roleCodes", List.of()))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"));
    }

    @Test
    void invalidPayloadReturnsFieldErrors() throws Exception {
        mvc.perform(post("/api/v1/users").header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("username", "Bad Name!", "email", "not-an-email", "displayName", "",
                        "userType", "INTERNAL", "temporaryPassword", "x", "roleCodes", List.of()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field == 'email')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'username')]").exists());
    }

    @Test
    void listUsersPaged() throws Exception {
        createUser("pagex", "ACCOUNTANT");
        createUser("pagex", "ACCOUNTANT");
        mvc.perform(get("/api/v1/users").param("q", "pagex").param("size", "1").param("sort", "-username")
                .header("Authorization", admin.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
        // No filters at all (the SPA's default view) and a status-only filter.
        mvc.perform(get("/api/v1/users").header("Authorization", admin.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(org.hamcrest.Matchers.greaterThan(2)));
        mvc.perform(get("/api/v1/users").param("status", "INACTIVE").header("Authorization", admin.bearer()))
                .andExpect(status().isOk());
        // LIKE wildcards in the search term are matched literally.
        mvc.perform(get("/api/v1/users").param("q", "%").header("Authorization", admin.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(0));
        mvc.perform(get("/api/v1/users").param("sort", "passwordHash").header("Authorization", admin.bearer()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/users").param("size", "1000").header("Authorization", admin.bearer()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void staleVersionRejected() throws Exception {
        TestUser target = createUser("stale", "ACCOUNTANT");
        mvc.perform(put("/api/v1/users/" + target.id()).header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("version", 0, "email", target.email(), "displayName", "Renamed"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(put("/api/v1/users/" + target.id()).header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("version", 0, "email", target.email(), "displayName", "Again"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    void sodConflictBlocksAssignment() throws Exception {
        TestUser target = createUser("sod", "ACCOUNTANT");
        // ACCOUNTANT (JOURNAL_CREATE) + CHIEF_ACCOUNTANT (JOURNAL_POST) violates maker/poster separation.
        mvc.perform(put("/api/v1/users/" + target.id() + "/roles").header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("version", 0, "roleCodes", List.of("ACCOUNTANT", "CHIEF_ACCOUNTANT")))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOD_CONFLICT"))
                .andExpect(jsonPath("$.conflicts[?(@.permissionA == 'JOURNAL_CREATE' && @.permissionB == 'JOURNAL_POST')]")
                        .exists());
        // Technical administration + financial authority is never allowed.
        mvc.perform(put("/api/v1/users/" + target.id() + "/roles").header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("version", 0, "roleCodes", List.of("SYSTEM_ADMINISTRATOR", "ACCOUNTANT")))))
                .andExpect(status().isConflict());
        assertThat(assignments.roleCodes(target.id())).containsExactly("ACCOUNTANT");

        mvc.perform(put("/api/v1/users/" + target.id() + "/roles").header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("version", 0, "roleCodes", List.of("ACCOUNTANT", "BUDGET_OFFICER")))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.version").value(1));
    }

    @Test
    void cannotChangeOwnRoles() throws Exception {
        mvc.perform(put("/api/v1/users/" + adminUser.id() + "/roles").header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("version", 0, "roleCodes", List.of("SYSTEM_ADMINISTRATOR", "INTERNAL_AUDITOR")))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SELF_MODIFICATION_FORBIDDEN"));
    }

    @Test
    void revokeSessionsInvalidatesAccessToken() throws Exception {
        TestUser target = createUser("revoke", "ACCOUNTANT");
        Session targetSession = login(target);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", targetSession.bearer())).andExpect(status().isOk());
        mvc.perform(post("/api/v1/users/" + target.id() + "/sessions/revoke").header("Authorization", admin.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sessionsRevoked").value(1));
        mvc.perform(get("/api/v1/auth/me").header("Authorization", targetSession.bearer()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deactivatedUserCannotSignIn() throws Exception {
        TestUser target = createUser("deact", "ACCOUNTANT");
        Session targetSession = login(target);
        mvc.perform(post("/api/v1/users/" + target.id() + "/deactivate").header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", 0))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INACTIVE"));
        mvc.perform(get("/api/v1/auth/me").header("Authorization", targetSession.bearer()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("identifier", target.username(), "password", target.password()))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void mfaResetForcesReEnrolment() throws Exception {
        TestUser target = createUser("mfareset", "ACCOUNTANT");
        mvc.perform(post("/api/v1/users/" + target.id() + "/mfa/reset").header("Authorization", admin.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mfaEnrolled").value(false));
        assertThat(passwordStep(target.username(), target.password()).get("status").asText())
                .isEqualTo("MFA_ENROLLMENT_REQUIRED");
    }

    @Test
    void nonAdministratorIsDenied() throws Exception {
        TestUser accountant = createUser("acct", "ACCOUNTANT");
        Session session = login(accountant);
        mvc.perform(get("/api/v1/users").header("Authorization", session.bearer()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        assertThat(countSecurityEvents(accountant.id(), "ACCESS_DENIED")).isEqualTo(1);
    }
}
