package com.miaa.cas.security;

import com.miaa.cas.config.CasProperties;
import java.time.Duration;
import java.util.List;

/** Hand-built configuration for unit tests that do not start a Spring context. */
public final class TestProperties {

    private TestProperties() {
    }

    public static CasProperties defaults() {
        return defaults(20, true);
    }

    public static CasProperties defaults(int authRequestsPerMinute, boolean rateLimitEnabled) {
        return withLimits(authRequestsPerMinute, rateLimitEnabled, 600, 120);
    }

    public static CasProperties withLimits(int authRequestsPerMinute, boolean rateLimitEnabled, int apiPerMinute, int anonymousPerMinute) {
        return new CasProperties("http://localhost:5173",
                new CasProperties.Jwt("https://cas.test", Duration.ofMinutes(10), Duration.ofMinutes(5), null, null, null,
                        true),
                new CasProperties.Session(Duration.ofHours(8), Duration.ofSeconds(10), "CAS_REFRESH", "/api/v1/auth", true),
                new CasProperties.Lockout(5, Duration.ofMinutes(15)),
                new CasProperties.Password(12, 128, Duration.ofMinutes(30)),
                new CasProperties.RateLimit(rateLimitEnabled, authRequestsPerMinute, apiPerMinute, anonymousPerMinute),
                new CasProperties.Mfa("MIAA CAS"),
                new CasProperties.Outbox(false, 8),
                new CasProperties.Bootstrap(null, null, null),
                null, List.of());
    }
}
