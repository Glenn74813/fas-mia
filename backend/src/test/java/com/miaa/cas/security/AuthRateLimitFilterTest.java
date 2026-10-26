package com.miaa.cas.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaa.cas.security.ratelimit.AuthRateLimitFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthRateLimitFilterTest {

    @Test
    void limitsAuthPostsPerClient() throws Exception {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(TestProperties.defaults(3, true), new ObjectMapper());
        for (int i = 0; i < 3; i++) {
            assertThat(call(filter, "POST", "/api/v1/auth/login", "10.0.0.1").getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse limited = call(filter, "POST", "/api/v1/auth/login", "10.0.0.1");
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isNotBlank();
        assertThat(limited.getContentAsString()).contains("RATE_LIMITED");
        // Other clients and non-auth endpoints are unaffected.
        assertThat(call(filter, "POST", "/api/v1/auth/login", "10.0.0.2").getStatus()).isEqualTo(200);
        assertThat(call(filter, "GET", "/api/v1/users", "10.0.0.1").getStatus()).isEqualTo(200);
    }

    @Test
    void canBeDisabled() throws Exception {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(TestProperties.defaults(1, false), new ObjectMapper());
        for (int i = 0; i < 5; i++) {
            assertThat(call(filter, "POST", "/api/v1/auth/login", "10.0.0.1").getStatus()).isEqualTo(200);
        }
    }

    private static MockHttpServletResponse call(AuthRateLimitFilter filter, String method, String uri, String ip)
            throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        req.setRemoteAddr(ip);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res;
    }
}
