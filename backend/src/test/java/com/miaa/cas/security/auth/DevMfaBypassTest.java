package com.miaa.cas.security.auth;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    void demoExemptionsNameAccountsAndWorkWithProd() {
        org.springframework.mock.env.MockEnvironment prod = new org.springframework.mock.env.MockEnvironment();
        prod.setActiveProfiles("prod");
        DevMfaBypass bypass = new DevMfaBypass(java.util.List.of(), java.util.List.of("demo.*", " SuperAdmin.Demo "), prod);
        assertThat(bypass.setting("demo.accountant.1")).isEqualTo("cas.mfa.demo-exempt-users");
        assertThat(bypass.applies("superadmin.demo")).isTrue();
        assertThat(bypass.applies("admin.dev")).isFalse();
        assertThat(bypass.applies("demonstrator")).isFalse();
        assertThat(bypass.applies(null)).isFalse();
        assertThatThrownBy(() -> DevMfaBypass.checkDemo(Set.of("*"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new DevMfaBypass(java.util.List.of(), java.util.List.of("demo.*", "**"), prod))
                .isInstanceOf(IllegalStateException.class);
    }
}
