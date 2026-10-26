package com.miaa.cas.security.auth;

import com.miaa.cas.config.CasProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/** Builds the HttpOnly refresh-token cookie, scoped to the auth endpoints only. */
@Component
public class RefreshCookies {

    private final CasProperties.Session cfg;
    private final Clock clock;

    public RefreshCookies(CasProperties properties, Clock clock) {
        this.cfg = properties.session();
        this.clock = clock;
    }

    public String name() {
        return cfg.refreshCookieName();
    }

    public ResponseCookie issue(String token, Instant expiresAt) {
        return base(token).maxAge(Duration.between(Instant.now(clock), expiresAt)).build();
    }

    public ResponseCookie clear() {
        return base("").maxAge(0).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(cfg.refreshCookieName(), value)
                .httpOnly(true)
                .secure(cfg.cookieSecure())
                .sameSite("Strict")
                .path(cfg.refreshCookiePath());
    }
}
