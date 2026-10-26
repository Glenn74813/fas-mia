package com.miaa.cas.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miaa.cas.support.AbstractIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/** The edge-proxy gate: /api only through the edge worker, and the edge's client address in the audit trail. */
@TestPropertySource(properties = "cas.proxy.shared-secret=test-edge-secret-0123456789")
class ProxyGatewayIT extends AbstractIntegrationTest {

    static final String SECRET = "test-edge-secret-0123456789";

    @Test
    void apiCallsWithoutTheEdgeSecretAreRefused() throws Exception {
        mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/auth/csrf").header("X-CAS-Proxy-Secret", "wrong")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/auth/csrf").header("X-CAS-Proxy-Secret", SECRET)).andExpect(status().isOk());
        // Health checks are not part of the API and stay reachable for the orchestrator.
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void theClientAddressReportedByTheEdgeIsRecorded() throws Exception {
        TestUser user = createUser("edge", "ACCOUNTANT");
        mvc.perform(post("/api/v1/auth/login").header("X-CAS-Proxy-Secret", SECRET).header("X-CAS-Client-IP", "203.0.113.7")
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("identifier", user.username(), "password", "wrong-password-1A!"))))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT client_ip FROM security_events WHERE actor_user_id = ? ORDER BY id DESC LIMIT 1",
                String.class, user.id())).isEqualTo("203.0.113.7");

        // A malformed address is ignored rather than recorded.
        mvc.perform(post("/api/v1/auth/login").header("X-CAS-Proxy-Secret", SECRET).header("X-CAS-Client-IP", "<script>")
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("identifier", user.username(), "password", "wrong-password-1A!"))))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT client_ip FROM security_events WHERE actor_user_id = ? ORDER BY id DESC LIMIT 1",
                String.class, user.id())).isNotEqualTo("<script>");
    }
}
