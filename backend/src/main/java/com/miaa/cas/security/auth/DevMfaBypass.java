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
 * DEVELOPMENT ONLY: users named in {@code cas.mfa.dev-bypass-users} sign in with their password alone. Empty by
 * default, set only in application-dev.yml, and refused at startup unless the dev profile is explicitly active and the
 * prod profile is not. Every bypassed sign-in is a security event. MFA remains mandatory for everyone else (FR-AUTH-07).
 */
@Component
public class DevMfaBypass {

    private static final Logger log = LoggerFactory.getLogger(DevMfaBypass.class);

    private final Set<String> users;

    public DevMfaBypass(@Value("${cas.mfa.dev-bypass-users:}") List<String> users, Environment env) {
        this.users = users.stream().map(u -> u.trim().toLowerCase(Locale.ROOT)).filter(u -> !u.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        // "dev" must be explicitly active: spring.profiles.default is dev, and a server started without any profile
        // must never skip two-step verification.
        check(this.users, java.util.Arrays.asList(env.getActiveProfiles()).contains("dev"), env.acceptsProfiles(Profiles.of("prod")));
        if (!this.users.isEmpty()) {
            log.warn("DEV: two-step verification is BYPASSED for {}; never use this outside a developer workstation", this.users);
        }
    }

    static void check(Set<String> users, boolean dev, boolean prod) {
        if (!users.isEmpty() && (prod || !dev)) {
            throw new IllegalStateException("cas.mfa.dev-bypass-users is only allowed with the dev profile and never with prod.");
        }
    }

    public boolean applies(String username) {
        return username != null && users.contains(username.toLowerCase(Locale.ROOT));
    }
}
