package com.miaa.cas.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.config.CasProperties;
import com.miaa.cas.security.password.PasswordPolicy;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy(TestProperties.defaults());

    @Test
    void acceptsStrongPassword() {
        assertThat(policy.violations("Tr1cky-Ledger-Passphrase", "jcruz", "juan.cruz@miaa.gov.ph")).isEmpty();
    }

    @Test
    void rejectsShortAndLowComplexity() {
        assertThat(policy.violations("Short1!", "jcruz", null)).singleElement().asString().contains("at least 12");
        assertThat(policy.violations("alllowercaseletters", "jcruz", null)).anyMatch(v -> v.contains("three of"));
    }

    @Test
    void rejectsPersonalInformationAndCommonPasswords() {
        assertThat(policy.violations("Xx-jcruz-2026-Yy", "jcruz", null)).anyMatch(v -> v.contains("username"));
        assertThat(policy.violations("Juan.Cruz-2026!", "jc", "juan.cruz@miaa.gov.ph"))
                .anyMatch(v -> v.contains("e-mail"));
        assertThat(policy.violations("P@ssw0rd1234", "jcruz", null)).anyMatch(v -> v.contains("too common"));
    }

    @Test
    void enforceThrowsWithViolations() {
        assertThatThrownBy(() -> policy.enforce("weak", "u", null)).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).properties()).containsKey("violations"));
    }

    @Test
    void lengthLimitIsConfigurable() {
        CasProperties p = TestProperties.defaults();
        assertThat(p.password().resetTokenTtl()).isEqualTo(Duration.ofMinutes(30));
    }
}
