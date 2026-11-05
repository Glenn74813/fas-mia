package com.miaa.cas.security.auth;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Accounts that sign in with their password alone. MFA remains mandatory for everyone else (FR-AUTH-07), and every
 * sign-in without a code is a security event.
 * <ul>
 * <li>DEVELOPMENT ONLY: {@code cas.mfa.dev-bypass-users}, set only in application-dev.yml and refused at startup unless
 * the dev profile is explicitly active and the prod profile is not.</li>
 * <li>DEMONSTRATION ONLY: {@code cas.mfa.demo-exempt-users}, the demo accounts of a public demonstration, set by the
 * demo start script. Empty by default, so a production server never skips the code. An entry ending in {@code *} is a
 * username prefix; an entry of {@code *} alone is refused, so the list always names accounts.</li>
 * </ul>
 */
@Component
public class DevMfaBypass {

    private static final Logger log = LoggerFactory.getLogger(DevMfaBypass.class);

    private final Set<String> users;
    private final Set<String> demoUsers;

    public DevMfaBypass(@Value("${cas.mfa.dev-bypass-users:}") List<String> users,
            @Value("${cas.mfa.demo-exempt-users:}") List<String> demoUsers, Environment env) {
        this.users = normalise(users);
        this.demoUsers = normalise(demoUsers);
        // "dev" must be explicitly active: spring.profiles.default is dev, and a server started without any profile
        // must never skip two-step verification.
        check(this.users, java.util.Arrays.asList(env.getActiveProfiles()).contains("dev"), env.acceptsProfiles(Profiles.of("prod")));
        checkDemo(this.demoUsers);
        if (!this.users.isEmpty()) {
            log.warn("DEV: two-step verification is BYPASSED for {}; never use this outside a developer workstation", this.users);
        }
        if (!this.demoUsers.isEmpty()) {
            log.warn("DEMO: two-step verification is OFF for {}; for a demonstration with demo data only", this.demoUsers);
        }
    }

    private static Set<String> normalise(List<String> names) {
        return names.stream().map(u -> u.trim().toLowerCase(Locale.ROOT)).filter(u -> !u.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    static void check(Set<String> users, boolean dev, boolean prod) {
        if (!users.isEmpty() && (prod || !dev)) {
            throw new IllegalStateException("cas.mfa.dev-bypass-users is only allowed with the dev profile and never with prod.");
        }
    }

    static void checkDemo(Set<String> demoUsers) {
        if (demoUsers.stream().anyMatch(u -> u.replace("*", "").isEmpty())) {
            throw new IllegalStateException("cas.mfa.demo-exempt-users must name accounts or username prefixes, not every account.");
        }
    }

    public boolean applies(String username) {
        return setting(username) != null;
    }

    /** The setting that lets this user sign in without a code, or null when the user needs one. */
    String setting(String username) {
        if (username == null) {
            return null;
        }
        String u = username.toLowerCase(Locale.ROOT);
        if (users.contains(u)) {
            return "cas.mfa.dev-bypass-users";
        }
        boolean demo = demoUsers.stream().anyMatch(d -> d.endsWith("*") ? u.startsWith(d.substring(0, d.length() - 1)) : u.equals(d));
        return demo ? "cas.mfa.demo-exempt-users" : null;
    }
}
