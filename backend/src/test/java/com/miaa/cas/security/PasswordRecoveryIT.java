package com.miaa.cas.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miaa.cas.support.AbstractIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class PasswordRecoveryIT extends AbstractIntegrationTest {

    @Test
    void resetWithSingleUseToken() throws Exception {
        TestUser user = createUser("forgot", "ACCOUNTANT");
        mvc.perform(post("/api/v1/auth/password/forgot").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("identifier", user.email())))).andExpect(status().isAccepted());

        List<String> payloads = jdbc.queryForList(
                "SELECT payload->>'resetUrl' FROM notification_outbox WHERE recipient = ? AND template = 'PASSWORD_RESET'",
                String.class, user.email());
        assertThat(payloads).hasSize(1);
        String token = payloads.get(0).substring(payloads.get(0).indexOf("#token=") + 7);

        mvc.perform(post("/api/v1/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("token", token, "newPassword", "Reset-Secret-Value-9"))))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("token", token, "newPassword", "Another-Secret-Value-9"))))
                .andExpect(status().isUnauthorized());

        assertThat(passwordStep(user.username(), "Reset-Secret-Value-9").get("status").asText()).isEqualTo("MFA_REQUIRED");
    }

    @Test
    void unknownAccountDoesNotRevealExistence() throws Exception {
        mvc.perform(post("/api/v1/auth/password/forgot").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("identifier", "ghost@nowhere.example")))).andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE recipient = 'ghost@nowhere.example'",
                Long.class)).isZero();
    }
}
