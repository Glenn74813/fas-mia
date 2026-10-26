package com.miaa.cas.security.owasp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miaa.cas.support.AbstractIntegrationTest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.apache.commons.codec.binary.Base32;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * Sensitive data in logs (MS §9.5, §18; OWASP A09). Runs sign-in (failed and successful, with MFA), token refresh,
 * password recovery and the entry of a supplier's TIN and bank account with application and security logging at
 * DEBUG, then checks the captured console output for every secret involved.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {"logging.level.com.miaa.cas=DEBUG", "logging.level.org.springframework.security=DEBUG"})
class SensitiveLogIT extends AbstractIntegrationTest {

    /** The levels are global to the JVM: put them back so later tests do not log at DEBUG. */
    @AfterEach
    void restoreLogLevels() {
        LoggingSystem logging = LoggingSystem.get(getClass().getClassLoader());
        logging.setLogLevel("com.miaa.cas", null);
        logging.setLogLevel("org.springframework.security", null);
    }

    @Test
    void secretsAndPersonalDataNeverReachTheLogs(CapturedOutput output) throws Exception {
        // The capture works and the masking layout is in place.
        LoggerFactory.getLogger(SensitiveLogIT.class).warn("sentinel password={} for juan.delacruz@miaa.gov.ph", "Sentinel-Secret-77");
        assertThat(output.getOut() + output.getErr()).contains("sentinel password=[REDACTED]").doesNotContain("Sentinel-Secret-77");

        TestUser user = createUser("logs", "ACCOUNTANT");
        String wrong = "Wr0ng-Pa55word-xyz!";
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("identifier", user.username(), "password", wrong)))).andExpect(status().isUnauthorized());
        Session session = login(user);
        String totp = new Base32().encodeToString(user.totpSecret()).replace("=", "");

        mvc.perform(post("/api/v1/auth/password/forgot").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("identifier", user.email())))).andExpect(status().isAccepted());
        String resetUrl = jdbc.queryForObject("SELECT payload->>'resetUrl' FROM notification_outbox WHERE recipient = ? AND template = 'PASSWORD_RESET'",
                String.class, user.email());
        String resetToken = resetUrl.substring(resetUrl.indexOf("#token=") + 7);
        String newPassword = "Reset-Secret-Value-42";
        mvc.perform(post("/api/v1/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("token", resetToken, "newPassword", newPassword)))).andExpect(status().isNoContent());

        String tin = "912-345-678-00000";
        String bankAccount = "0012-3456-7890-1234";
        Session accountant = login(createUser("logsap", "ACCOUNTANT"));
        String supplier = body(mvc.perform(post("/api/v1/suppliers").header("Authorization", accountant.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("code", "LOG-" + System.nanoTime() % 1_000_000, "name", "Log Test Supplier", "tin", tin, "vatRegistered", true))))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
        mvc.perform(post("/api/v1/suppliers/" + supplier + "/bank-accounts").header("Authorization", accountant.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("bankName", "Test Bank", "accountName", "Log Test Supplier",
                        "accountNumber", bankAccount)))).andExpect(status().isCreated());

        String logs = output.getOut() + output.getErr();
        assertThat(logs).as("captured output").isNotBlank();
        List<String> secrets = List.of(user.password(), wrong, newPassword, totp, resetToken, session.accessToken(),
                session.refreshCookie() == null ? "" : session.refreshCookie().getValue(), accountant.accessToken(),
                tin, tin.replace("-", ""), bankAccount, bankAccount.replace("-", ""),
                Base64.getEncoder().encodeToString(user.totpSecret()));
        for (String s : secrets) {
            if (!s.isEmpty()) {
                assertThat(logs).as("log output contains a secret").doesNotContain(s);
            }
        }
    }
}
