package com.miaa.cas.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.miaa.cas.security.password.PasswordConfig;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordHashingTest {

    private final PasswordEncoder encoder = new PasswordConfig().passwordEncoder();

    @Test
    void usesSaltedArgon2id() {
        String a = encoder.encode("Same-Password-123");
        String b = encoder.encode("Same-Password-123");
        assertThat(a).startsWith("$argon2id$").isNotEqualTo(b);
        assertThat(encoder.matches("Same-Password-123", a)).isTrue();
        assertThat(encoder.matches("Other-Password-123", a)).isFalse();
    }
}
