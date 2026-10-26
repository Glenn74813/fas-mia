package com.miaa.cas.security.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

class DevMfaBypassTest {

    @Test
    void bypassIsAllowedOnlyWithTheDevProfileAndNeverWithProd() {
        Set<String> users = Set.of("admin.dev");
        assertThatCode(() -> DevMfaBypass.check(users, true, false)).doesNotThrowAnyException();
        assertThatThrownBy(() -> DevMfaBypass.check(users, false, false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DevMfaBypass.check(users, true, true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DevMfaBypass.check(users, false, true)).isInstanceOf(IllegalStateException.class);
        // No users configured: nothing is bypassed, so any profile is fine.
        assertThatCode(() -> DevMfaBypass.check(Set.of(), false, true)).doesNotThrowAnyException();
    }
}
