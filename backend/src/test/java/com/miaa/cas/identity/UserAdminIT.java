package com.miaa.cas.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.support.AbstractIntegrationTest;
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
