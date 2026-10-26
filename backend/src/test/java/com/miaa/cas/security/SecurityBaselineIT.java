package com.miaa.cas.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miaa.cas.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class SecurityBaselineIT extends AbstractIntegrationTest {

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void protectedEndpointsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/v1/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.correlationId").exists());
        mvc.perform(get("/internal/anything")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/users").header("Authorization", "Bearer not.a.jwt")).andExpect(status().isUnauthorized());
    }

    @Test
    void securityHeadersPresent() throws Exception {
        mvc.perform(get("/actuator/health").secure(true))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().exists("Content-Security-Policy"))
                .andExpect(header().string("Strict-Transport-Security", "max-age=31536000 ; includeSubDomains"));
        // The performance kit's Server-Timing header is off unless explicitly enabled.
        mvc.perform(get("/api/v1/auth/csrf").secure(true)).andExpect(header().doesNotExist("Server-Timing"));
    }

    @Test
    void correlationIdEchoed() throws Exception {
        mvc.perform(get("/actuator/health").header("X-Request-Id", "test-correlation-1234"))
                .andExpect(header().string("X-Request-Id", "test-correlation-1234"));
        mvc.perform(get("/actuator/health").header("X-Request-Id", "bad id with spaces"))
                .andExpect(header().string("X-Request-Id", org.hamcrest.Matchers.not("bad id with spaces")));
    }

    @Test
    void openApiAvailable() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/auth/login']").exists())
                .andExpect(jsonPath("$.components.securitySchemes.bearer.scheme").value("bearer"));
    }

    @Test
    void noSessionCookie() throws Exception {
        TestUser user = createUser("stateless", "ACCOUNTANT");
        MvcResult r = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(java.util.Map.of("identifier", user.username(), "password", user.password()))))
                .andExpect(status().isOk()).andReturn();
        assertThat(r.getResponse().getCookie("JSESSIONID")).isNull();
        assertThat(r.getRequest().getSession(false)).isNull();
    }

    @Test
    void malformedJsonIsAProblem() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
