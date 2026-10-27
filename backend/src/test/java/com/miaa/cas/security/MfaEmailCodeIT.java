package com.miaa.cas.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.support.AbstractIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** E-mail one-time codes as an alternative second factor (TOR §4.2). */
class MfaEmailCodeIT extends AbstractIntegrationTest {

    @Test
    void anEmailedCodeSignsInOnce() throws Exception {
        TestUser user = createUser("otpmail", "ACCOUNTANT");
        JsonNode step = passwordStep(user.username(), user.password());
        assertThat(step.get("methods").toString()).contains("TOTP").contains("EMAIL");
        String token = step.get("mfaToken").asText();

        JsonNode sent = body(send(token).andExpect(status().isOk()).andReturn());
        assertThat(sent.get("sentTo").asText()).startsWith(user.email().substring(0, 1) + "***@");
        String code = lastCode(user.email());
        assertThat(code).matches("\\d{6}");
        // Only a hash is stored with the step.
        assertThat(jdbc.queryForObject("SELECT code_hash FROM mfa_email_codes WHERE user_id = ?", String.class, user.id()))
                .startsWith("$argon2id$").doesNotContain(code);

        JsonNode tokens = body(verify(token, code).andExpect(status().isOk()).andReturn());
        assertThat(tokens.get("accessToken").asText()).isNotBlank();
        assertThat(countSecurityEvents(user.id(), "MFA_EMAIL_CODE_USED")).isEqualTo(1);
        verify(token, code).andExpect(status().isUnauthorized());
    }

    @Test
    void codesAreBoundToTheirStepLimitedAndCountedAsFailures() throws Exception {
        TestUser user = createUser("otpbound", "ACCOUNTANT");
        String first = passwordStep(user.username(), user.password()).get("mfaToken").asText();
        send(first).andExpect(status().isOk());
        String code = lastCode(user.email());
        // A second request within a minute is refused.
        send(first).andExpect(status().isTooManyRequests());
        // The code belongs to its own sign-in step.
        String second = passwordStep(user.username(), user.password()).get("mfaToken").asText();
        verify(second, code).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT failed_login_count FROM users WHERE id = ?", Integer.class, user.id())).isEqualTo(1);

        // Five wrong tries spend the code: even the right code then fails.
        jdbc.update("UPDATE mfa_email_codes SET attempts = 4 WHERE user_id = ?", user.id());
        verify(first, code.equals("000000") ? "111111" : "000000").andExpect(status().isUnauthorized());
        verify(first, code).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyEnrolledUsersMayUseTheEmailAlternative() throws Exception {
        TestUser user = createUser("otpnew", false, "ACCOUNTANT");
        JsonNode step = passwordStep(user.username(), user.password());
        assertThat(step.get("status").asText()).isEqualTo("MFA_ENROLLMENT_REQUIRED");
        assertThat(step.get("methods").toString()).doesNotContain("EMAIL");
        send(step.get("mfaToken").asText()).andExpect(status().isUnauthorized());
    }

    private ResultActions send(String mfaToken) throws Exception {
        return mvc.perform(post("/api/v1/auth/mfa/email/send").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("mfaToken", mfaToken))));
    }

    private ResultActions verify(String mfaToken, String code) throws Exception {
        return mvc.perform(post("/api/v1/auth/mfa/email/verify").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("mfaToken", mfaToken, "code", code))));
    }

    private String lastCode(String email) {
        return jdbc.queryForObject("SELECT payload->>'code' FROM notification_outbox WHERE recipient = ? AND template = 'mfa-email-code' "
                + "ORDER BY created_at DESC LIMIT 1", String.class, email);
    }
}
