package com.miaa.cas.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaa.cas.security.ratelimit.ApiRateLimitFilter;
import com.miaa.cas.security.ratelimit.ApiRateLimiter;
import com.miaa.cas.support.AbstractIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** The general API limit is counted in the database, so it is one limit for all instances. */
class ApiRateLimitIT extends AbstractIntegrationTest {

    @Autowired ObjectMapper mapper;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void eachUserAndEachAnonymousAddressHasItsOwnLimit() throws Exception {
        // A fixed clock keeps every call in one window (a minute boundary would otherwise reset the count mid-test).
        ApiRateLimiter limiter = new ApiRateLimiter(jdbc, Clock.fixed(Instant.now().truncatedTo(ChronoUnit.MINUTES).plusSeconds(30),
                ZoneOffset.UTC));
        ApiRateLimitFilter filter = new ApiRateLimitFilter(limiter, TestProperties.withLimits(20, true, 3, 2), mapper);
        // Two filter instances stand in for two application instances sharing the database.
        ApiRateLimitFilter other = new ApiRateLimitFilter(limiter, TestProperties.withLimits(20, true, 3, 2), mapper);
        signIn(UUID.randomUUID());
        assertThat(call(filter, "/api/v1/dashboard").getStatus()).isEqualTo(200);
        assertThat(call(other, "/api/v1/dashboard").getStatus()).isEqualTo(200);
        MockHttpServletResponse third = call(filter, "/api/v1/dashboard");
        assertThat(third.getStatus()).isEqualTo(200);
        assertThat(third.getHeader("RateLimit-Limit")).isEqualTo("3");
        assertThat(third.getHeader("RateLimit-Remaining")).isEqualTo("0");
        MockHttpServletResponse refused = call(other, "/api/v1/dashboard");
        assertThat(refused.getStatus()).isEqualTo(429);
        assertThat(Long.parseLong(refused.getHeader("Retry-After"))).isBetween(1L, 60L);
        assertThat(refused.getContentAsString()).contains("Too many requests");

        // Another user is unaffected.
        signIn(UUID.randomUUID());
        assertThat(call(filter, "/api/v1/dashboard").getStatus()).isEqualTo(200);

        // Unauthenticated callers are limited per address, more strictly.
        SecurityContextHolder.clearContext();
        String addr = "10.9." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250);
        assertThat(call(filter, "/api/v1/auth/csrf", addr, "GET").getStatus()).isEqualTo(200);
        assertThat(call(filter, "/api/v1/auth/csrf", addr, "GET").getStatus()).isEqualTo(200);
        assertThat(call(filter, "/api/v1/auth/csrf", addr, "GET").getStatus()).isEqualTo(429);
        // Sign-in POSTs are left to the stricter per-address authentication limit; non-API paths are not counted.
        assertThat(call(filter, "/api/v1/auth/login", addr, "POST").getStatus()).isEqualTo(200);
        assertThat(call(filter, "/actuator/health", addr, "GET").getHeader("RateLimit-Limit")).isNull();
    }

    private static void signIn(UUID userId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CasPrincipal(userId, "rl.user", UUID.randomUUID()), null, List.of()));
    }

    private static MockHttpServletResponse call(ApiRateLimitFilter f, String path) throws Exception {
        return call(f, path, "127.0.0.1", "GET");
    }

    private static MockHttpServletResponse call(ApiRateLimitFilter f, String path, String addr, String method) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        req.setRemoteAddr(addr);
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(req, res, new MockFilterChain());
        return res;
    }
}
