package com.miaa.cas.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.security.mfa.Totp;
import com.miaa.cas.support.AbstractIntegrationTest;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Map;
import org.apache.commons.codec.binary.Base32;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class AuthFlowIT extends AbstractIntegrationTest {

    @Test
    void fullLoginMfaRefreshLogout() throws Exception {
        TestUser user = createUser("enrol", false, "ACCOUNTANT");

        JsonNode step = passwordStep(user.username(), user.password());
        assertThat(step.get("status").asText()).isEqualTo("MFA_ENROLLMENT_REQUIRED");
        String mfaToken = step.get("mfaToken").asText();

        JsonNode enrol = body(mvc.perform(post("/api/v1/auth/mfa/totp/enroll").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("mfaToken", mfaToken)))).andExpect(status().isOk()).andReturn());
        assertThat(enrol.get("otpauthUri").asText()).startsWith("otpauth://totp/AVTECH%20CAS:" + user.username());
        byte[] secret = new Base32().decode(enrol.get("secret").asText());

        MvcResult activated = mvc.perform(post("/api/v1/auth/mfa/totp/activate").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("mfaToken", mfaToken, "code", Totp.code(secret, Totp.stepAt(Instant.now()))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.mfaEnrolled").value(true))
                .andExpect(jsonPath("$.user.permissions[?(@ == 'JOURNAL_CREATE')]").exists())
                .andReturn();
        String setCookie = activated.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(h -> h.startsWith("CAS_REFRESH=")).findFirst().orElseThrow();
        assertThat(setCookie).contains("CAS_REFRESH=").contains("HttpOnly").contains("SameSite=Strict")
                .contains("Path=/api/v1/auth");
        String access = body(activated).get("accessToken").asText();
        Cookie refresh = activated.getResponse().getCookie("CAS_REFRESH");

        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value(user.username()));

        Object[] csrf = csrf();
        MvcResult refreshed = mvc.perform(post("/api/v1/auth/refresh").cookie(refresh, (Cookie) csrf[0])
                .header("X-XSRF-TOKEN", csrf[1])).andExpect(status().isOk()).andReturn();
        Cookie rotated = refreshed.getResponse().getCookie("CAS_REFRESH");
        assertThat(rotated.getValue()).isNotEqualTo(refresh.getValue());

        mvc.perform(post("/api/v1/auth/logout").cookie(rotated, (Cookie) csrf[0]).header("X-XSRF-TOKEN", csrf[1]))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/refresh").cookie(rotated, (Cookie) csrf[0]).header("X-XSRF-TOKEN", csrf[1]))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isUnauthorized());
        assertThat(countSecurityEvents(user.id(), "LOGIN_SUCCEEDED")).isEqualTo(1);
    }

    @Test
    void concurrentEnrolmentRequestsAreSerialised() throws Exception {
        TestUser user = createUser("dblenrol", false, "ACCOUNTANT");
        String mfaToken = passwordStep(user.username(), user.password()).get("mfaToken").asText();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        java.util.List<java.util.concurrent.Future<MvcResult>> results = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            results.add(pool.submit(() -> mvc.perform(post("/api/v1/auth/mfa/totp/enroll")
                    .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("mfaToken", mfaToken)))).andReturn()));
        }
        java.util.Set<String> secrets = new java.util.HashSet<>();
        for (java.util.concurrent.Future<MvcResult> r : results) {
            MvcResult res = r.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            secrets.add(body(res).get("secret").asText());
        }
        pool.shutdown();
        // Every response for one challenge carries the same secret, so whichever the page shows works.
        assertThat(secrets).hasSize(1);
        byte[] secret = new Base32().decode(secrets.iterator().next());
        mvc.perform(post("/api/v1/auth/mfa/totp/activate").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("mfaToken", mfaToken, "code", Totp.code(secret, Totp.stepAt(Instant.now()))))))
                .andExpect(status().isOk());
        // Once activated, the next sign-in asks for a code instead of re-enrolling.
        String nextToken = passwordStep(user.username(), user.password()).get("status").asText();
        assertThat(nextToken).isEqualTo("MFA_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mfa_methods WHERE user_id = ?", Long.class, user.id()))
                .isEqualTo(1);
    }

    @Test
    void newChallengeGetsFreshSecret() throws Exception {
        TestUser user = createUser("freshsecret", false, "ACCOUNTANT");
        String first = passwordStep(user.username(), user.password()).get("mfaToken").asText();
        String secret1 = body(mvc.perform(post("/api/v1/auth/mfa/totp/enroll").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("mfaToken", first)))).andReturn()).get("secret").asText();
        String second = passwordStep(user.username(), user.password()).get("mfaToken").asText();
        String secret2 = body(mvc.perform(post("/api/v1/auth/mfa/totp/enroll").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("mfaToken", second)))).andReturn()).get("secret").asText();
        assertThat(secret2).isNotEqualTo(secret1);
    }

    @Test
    void refreshTokenReuseRevokesSession() throws Exception {
        TestUser user = createUser("reuse", "ACCOUNTANT");
        Session session = login(user);
        Object[] csrf = csrf();
        Cookie second = refresh(session.refreshCookie(), csrf, 200);
        Cookie third = refresh(second, csrf, 200);

        // The first token's successor has itself been used, so replaying it signals theft:
        // the whole session is revoked, including the newest token and the access token.
        refresh(session.refreshCookie(), csrf, 401);
        refresh(third, csrf, 401);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", session.bearer())).andExpect(status().isUnauthorized());
        assertThat(countSecurityEvents(user.id(), "REFRESH_TOKEN_REUSE_DETECTED")).isEqualTo(1);
    }

    @Test
    void lostRotationResponseIsToleratedOnceWithinGrace() throws Exception {
        TestUser user = createUser("grace", "ACCOUNTANT");
        Session session = login(user);
        Object[] csrf = csrf();
        refresh(session.refreshCookie(), csrf, 200); // response "lost": client keeps the old cookie
        Cookie recovered = refresh(session.refreshCookie(), csrf, 200);
        assertThat(countSecurityEvents(user.id(), "REFRESH_TOKEN_GRACE_REUSE")).isEqualTo(1);
        refresh(recovered, csrf, 200);
        // A second replay of the original token is no longer tolerated.
        refresh(session.refreshCookie(), csrf, 401);
        assertThat(countSecurityEvents(user.id(), "REFRESH_TOKEN_REUSE_DETECTED")).isEqualTo(1);
    }

    private Cookie refresh(Cookie refreshCookie, Object[] csrf, int expectedStatus) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/refresh").cookie(refreshCookie, (Cookie) csrf[0])
                .header("X-XSRF-TOKEN", csrf[1])).andExpect(status().is(expectedStatus)).andReturn();
        return r.getResponse().getCookie("CAS_REFRESH");
    }

    @Test
    void refreshRequiresCsrf() throws Exception {
        Session session = login(createUser("csrf", "ACCOUNTANT"));
        mvc.perform(post("/api/v1/auth/refresh").cookie(session.refreshCookie())).andExpect(status().isForbidden());
        Object[] csrf = csrf();
        mvc.perform(post("/api/v1/auth/refresh").cookie(session.refreshCookie(), (Cookie) csrf[0])
                .header("X-XSRF-TOKEN", "forged-value")).andExpect(status().isForbidden());
        Cookie xsrf = (Cookie) csrf[0];
        assertThat(xsrf.getSecure()).isTrue();
        assertThat(xsrf.isHttpOnly()).isFalse(); // the SPA must read it to echo it in X-XSRF-TOKEN
        assertThat(xsrf.getAttribute("SameSite")).isEqualTo("Strict");
    }

    @Test
    void lockoutAfterRepeatedFailures() throws Exception {
        TestUser user = createUser("lock", "ACCOUNTANT");
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(Map.of("identifier", user.username(), "password", "Wrong-Password-123"))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
        }
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("identifier", user.username(), "password", user.password()))))
                .andExpect(status().isUnauthorized());
        assertThat(users.findById(user.id()).orElseThrow().getLockedUntil()).isAfter(Instant.now());
        assertThat(countSecurityEvents(user.id(), "ACCOUNT_LOCKED")).isEqualTo(1);
    }

    @Test
    void mfaCodeCannotBeReplayed() throws Exception {
        TestUser user = createUser("replay", "ACCOUNTANT");
        allowCodeReuse(user.id());
        String code = currentCode(user.totpSecret());
        String token1 = passwordStep(user.username(), user.password()).get("mfaToken").asText();
        mvc.perform(post("/api/v1/auth/mfa/verify").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("mfaToken", token1, "code", code)))).andExpect(status().isOk());
        String token2 = passwordStep(user.username(), user.password()).get("mfaToken").asText();
        mvc.perform(post("/api/v1/auth/mfa/verify").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("mfaToken", token2, "code", code))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_MFA_INVALID"));
    }

    @Test
    void mfaTokenIsNotAnAccessToken() throws Exception {
        TestUser user = createUser("mfatok", "SYSTEM_ADMINISTRATOR");
        String mfaToken = passwordStep(user.username(), user.password()).get("mfaToken").asText();
        mvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + mfaToken))
                .andExpect(status().isUnauthorized()).andExpect(header().exists("WWW-Authenticate"));
    }

    @Test
    void unknownAccountGetsGenericError() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("identifier", "nobody.here", "password", "Whatever-123!"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(
                        "Sign-in failed. Check your credentials or contact the system administrator."));
    }

    @Test
    void temporaryPasswordRestrictsAccess() throws Exception {
        Session admin = login(createUser("tmpadm", "SYSTEM_ADMINISTRATOR"));
        String username = unique("tmpuser");
        mvc.perform(post("/api/v1/users").header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("username", username, "email", username + "@test.miaa.local",
                        "displayName", "Temp User", "userType", "INTERNAL", "temporaryPassword", "Temp-Passw0rd-2026",
                        "roleCodes", java.util.List.of("INTERNAL_AUDITOR")))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.user.mustChangePassword").value(true));

        String mfaToken = passwordStep(username, "Temp-Passw0rd-2026").get("mfaToken").asText();
        JsonNode enrol = body(mvc.perform(post("/api/v1/auth/mfa/totp/enroll").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("mfaToken", mfaToken)))).andReturn());
        byte[] secret = new Base32().decode(enrol.get("secret").asText());
        JsonNode tokens = body(mvc.perform(post("/api/v1/auth/mfa/totp/activate").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("mfaToken", mfaToken, "code", Totp.code(secret, Totp.stepAt(Instant.now()))))))
                .andExpect(status().isOk()).andReturn());
        String bearer = "Bearer " + tokens.get("accessToken").asText();
        assertThat(tokens.at("/user/mustChangePassword").asBoolean()).isTrue();
        assertThat(tokens.at("/user/permissions").size()).isZero();

        mvc.perform(get("/api/v1/audit-events").header("Authorization", bearer)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/password/change").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("currentPassword", "Temp-Passw0rd-2026", "newPassword", "Brand-New-Secret-77"))))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/audit-events").header("Authorization", bearer)).andExpect(status().isOk());
    }

    @Test
    void weakPasswordRejectedOnChange() throws Exception {
        Session session = login(createUser("weak", "ACCOUNTANT"));
        mvc.perform(post("/api/v1/auth/password/change").header("Authorization", session.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("currentPassword", PASSWORD, "newPassword", "short"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_POLICY_VIOLATION"));
    }
}
