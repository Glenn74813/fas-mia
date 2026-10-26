package com.miaa.cas.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.Filter;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ServerTimingFilterTest {

    private final Filter filter = new ServerTimingFilter().serverTimingRegistration().getFilter();

    @Test
    void apiResponsesCarryTheServerSideDurationAndKeepTheirBody() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/journal-vouchers");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, (rq, rs) -> {
            rs.setContentType("application/json");
            rs.getOutputStream().write("{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
        });
        assertThat(res.getHeader(ServerTimingFilter.HEADER)).matches("app;dur=\\d+\\.\\d");
        assertThat(res.getContentAsString()).isEqualTo("{\"ok\":true}");
    }

    @Test
    void nonApiRequestsAreLeftAlone() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, (rq, rs) -> rs.getWriter().write("UP"));
        assertThat(res.getHeader(ServerTimingFilter.HEADER)).isNull();
        assertThat(res.getContentAsString()).isEqualTo("UP");
    }
}
