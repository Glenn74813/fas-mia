package com.miaa.cas.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.security.mfa.Totp;
import com.miaa.cas.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.commons.codec.binary.Base32;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Recovery codes for a lost authenticator: issued once at enrolment, single use, and withdrawn on reset. */
class MfaRecoveryIT extends AbstractIntegrationTest {

    private record Enrolment(String accessToken, List<String> codes) {
    }

    @Test
    void enrolmentShowsTenCodesOnceAndStoresOnlyHashes() throws Exception {
        TestUser user = createUser("rcenrol", false, "ACCOUNTANT");
        Enrolment e = enrol(passwordStep(user.username(), user.password()).get("mfaToken").asText());

        assertThat(e.codes()).hasSize(10).doesNotHaveDuplicates()
                .allSatisfy(c -> assertThat(c).matches("[A-HJ-NP-Z2-9]{5}-[A-HJ-NP-Z2-9]{5}"));
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + e.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.recoveryCodesRemaining").value(10));
        List<String> hashes = jdbc.queryForList("SELECT code_hash FROM mfa_recovery_codes WHERE user_id = ?", String.class, user.id());
        assertThat(hashes).hasSize(10).allSatisfy(h -> assertThat(h).startsWith("$argon2id$"));
        assertThat(String.join(" ", hashes)).doesNotContain(e.codes().get(0).replace("-", ""));
    }

    @Test
    void recoveryCodeReplacesTheAuthenticatorAndRequiresANewOne() throws Exception {
        TestUser user = createUser("rcuse", false, "ACCOUNTANT");
        Enrolment first = enrol(passwordStep(user.username(), user.password()).get("mfaToken").asText());

        JsonNode step = passwordStep(user.username(), user.password());
        assertThat(step.get("status").asText()).isEqualTo("MFA_REQUIRED");
        // Typed in lower case without the hyphen: still accepted.
        String typed = first.codes().get(3).replace("-", "").toLowerCase(Locale.ROOT);
        JsonNode recovered = body(mvc.perform(post("/api/v1/auth/mfa/recovery").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("mfaToken", step.get("mfaToken").asText(), "recoveryCode", typed))))
                .andExpect(status().isOk()).andReturn());
        assertThat(recovered.get("status").asText()).isEqualTo("MFA_ENROLLMENT_REQUIRED");
        assertThat(recovered.has("accessToken")).isFalse();

        // The old session and authenticator are withdrawn.
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + first.accessToken()))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mfa_methods WHERE user_id = ? AND status = 'ACTIVE'",
                Long.class, user.id())).isZero();
        assertThat(countSecurityEvents(user.id(), "MFA_RECOVERY_CODE_USED")).isEqualTo(1);

        // Enrolling the new authenticator signs in and replaces the remaining codes.
        Enrolment second = enrol(recovered.get("mfaToken").asText());
        assertThat(second.codes()).hasSize(10).doesNotContainAnyElementsOf(first.codes());
        JsonNode next = passwordStep(user.username(), user.password());
        mvc.perform(post("/api/v1/auth/mfa/recovery").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("mfaToken", next.get("mfaToken").asText(), "recoveryCode", first.codes().get(5)))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aSpentOrWrongCodeIsRefusedAndCountsTowardsLockout() throws Exception {
        TestUser user = createUser("rcspent", false, "ACCOUNTANT");
        Enrolment e = enrol(passwordStep(user.username(), user.password()).get("mfaToken").asText());
        String code = e.codes().get(0);
        JsonNode recovered = body(mvc.perform(post("/api/v1/auth/mfa/recovery").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("mfaToken", passwordStep(user.username(), user.password()).get("mfaToken").asText(),
                        "recoveryCode", code)))).andExpect(status().isOk()).andReturn());
        assertThat(recovered.get("status").asText()).isEqualTo("MFA_ENROLLMENT_REQUIRED");

        // The enrolment was not completed, so the account has no authenticator: the password step asks to enrol,
        // and that token cannot be used for a recovery code.
        String enrolToken = passwordStep(user.username(), user.password()).get("mfaToken").asText();
        mvc.perform(post("/api/v1/auth/mfa/recovery").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("mfaToken", enrolToken, "recoveryCode", e.codes().get(1)))))
                .andExpect(status().isUnauthorized());

        TestUser other = createUser("rcwrong", true, "ACCOUNTANT");
        String verifyToken = passwordStep(other.username(), other.password()).get("mfaToken").asText();
        mvc.perform(post("/api/v1/auth/mfa/recovery").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("mfaToken", verifyToken, "recoveryCode", "AAAAA-BBBBB"))))
                .andExpect(status().isUnauthorized());
        assertThat(countSecurityEvents(other.id(), "MFA_RECOVERY_FAILED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT failed_login_count FROM users WHERE id = ?", Integer.class, other.id()))
                .isEqualTo(1);
    }

    @Test
    void newCodesNeedACurrentAuthenticatorCode() throws Exception {
        TestUser user = createUser("rcregen", "ACCOUNTANT");
        Session session = login(user);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", session.bearer()))
                .andExpect(jsonPath("$.recoveryCodesRemaining").value(0));

        mvc.perform(post("/api/v1/auth/recovery-codes").header("Authorization", session.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("code", "000000"))))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/recovery-codes").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("code", "000000")))).andExpect(status().isUnauthorized());

        allowCodeReuse(user.id());
        JsonNode codes = body(mvc.perform(post("/api/v1/auth/recovery-codes").header("Authorization", session.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("code", currentCode(user.totpSecret())))))
                .andExpect(status().isOk()).andReturn());
        assertThat(codes.get("codes")).hasSize(10);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", session.bearer()))
                .andExpect(jsonPath("$.recoveryCodesRemaining").value(10));
    }

    @Test
    void administratorResetWithdrawsTheCodes() throws Exception {
        TestUser user = createUser("rcreset", false, "ACCOUNTANT");
        enrol(passwordStep(user.username(), user.password()).get("mfaToken").asText());
        Session admin = login(createUser("rcadmin", "SYSTEM_ADMINISTRATOR"));

        mvc.perform(post("/api/v1/users/{id}/mfa/reset", user.id()).header("Authorization", admin.bearer()))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM mfa_recovery_codes WHERE user_id = ? AND used_at IS NULL AND revoked_at IS NULL
                """, Long.class, user.id())).isZero();
    }

    private Enrolment enrol(String mfaToken) throws Exception {
        JsonNode enrol = body(mvc.perform(post("/api/v1/auth/mfa/totp/enroll").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("mfaToken", mfaToken)))).andExpect(status().isOk()).andReturn());
        byte[] secret = new Base32().decode(enrol.get("secret").asText());
        JsonNode tokens = body(mvc.perform(post("/api/v1/auth/mfa/totp/activate").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("mfaToken", mfaToken, "code", Totp.code(secret, Totp.stepAt(Instant.now()))))))
                .andExpect(status().isOk()).andReturn());
        List<String> codes = new ArrayList<>();
        tokens.get("recoveryCodes").forEach(c -> codes.add(c.asText()));
        return new Enrolment(tokens.get("accessToken").asText(), codes);
    }
}
