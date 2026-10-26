package com.miaa.cas.security.owasp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.support.AbstractIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * SQL injection (MS §9, §18; OWASP A03): every free-text search, filter and sort parameter, and the sign-in identifier,
 * is sent classic injection payloads. None may cause a server error, widen the result set or change the schema.
 * Complements the SpotBugs/FindSecBugs review of dynamic SQL (spotbugs-exclude.xml).
 */
class SqlInjectionIT extends AbstractIntegrationTest {

    static final List<String> PAYLOADS = List.of(
            "' OR '1'='1",
            "' OR 1=1 --",
            "%' OR '%'='",
            "'; DROP TABLE users; --",
            "') UNION SELECT username, password_hash, NULL FROM users --",
            "1; SELECT pg_sleep(5)",
            "\\'; DELETE FROM audit_events; --",
            "_%");

    @Test
    void searchFilterAndSortParametersTreatInjectionPayloadsAsData() throws Exception {
        Session auditor = login(createUser("sqlaud", "INTERNAL_AUDITOR"));
        Session admin = login(createUser("sqladm", "SYSTEM_ADMINISTRATOR"));
        Session accountant = login(createUser("sqlacc", "ACCOUNTANT"));
        long users = count("users");
        long auditEvents = count("audit_events");

        record Probe(Session who, String path, String param) {
        }
        List<Probe> probes = List.of(
                new Probe(admin, "/api/v1/users", "q"),
                new Probe(admin, "/api/v1/users", "status"),
                new Probe(auditor, "/api/v1/audit-events", "action"),
                new Probe(auditor, "/api/v1/audit-events", "resourceType"),
                new Probe(auditor, "/api/v1/audit-events", "resourceId"),
                new Probe(auditor, "/api/v1/security-events", "action"),
                new Probe(auditor, "/api/v1/journal-vouchers", "q"),
                new Probe(auditor, "/api/v1/journal-vouchers", "status"),
                new Probe(auditor, "/api/v1/suppliers", "q"),
                new Probe(auditor, "/api/v1/customers", "q"),
                new Probe(auditor, "/api/v1/accounts-payable/invoices", "q"),
                new Probe(auditor, "/api/v1/accounts-receivable/billings", "q"),
                new Probe(auditor, "/api/v1/fixed-assets", "q"),
                new Probe(auditor, "/api/v1/inventory/items", "q"),
                new Probe(auditor, "/api/v1/subsidiary-accounts", "q"),
                new Probe(auditor, "/api/v1/tickets", "status"),
                new Probe(accountant, "/api/v1/inbox", "q"));
        for (Probe p : probes) {
            for (String payload : PAYLOADS) {
                MvcResult r = mvc.perform(get(p.path()).param(p.param(), payload).header("Authorization", p.who().bearer())).andReturn();
                int status = r.getResponse().getStatus();
                assertThat(status).as("%s?%s=%s", p.path(), p.param(), payload).isIn(200, 400);
                if (status == 200) {
                    JsonNode body = body(r);
                    JsonNode items = body.has("items") ? body.get("items") : body;
                    // No record contains these strings, so a "match" would mean the payload changed the query.
                    assertThat(items.size()).as("%s?%s=%s returned rows", p.path(), p.param(), payload).isZero();
                }
            }
        }

        // ORDER BY is a classic blind spot: sort keys are whitelisted.
        for (String payload : List.of("username; DROP TABLE users", "(SELECT 1)", "-password_hash", "createdAt,(select pg_sleep(5))")) {
            int status = mvc.perform(get("/api/v1/users").param("sort", payload).header("Authorization", admin.bearer())).andReturn()
                    .getResponse().getStatus();
            assertThat(status).as("sort=%s", payload).isIn(200, 400);
        }
        for (String payload : List.of("entry_number; DROP TABLE journal_entries", "(SELECT 1)")) {
            int status = mvc.perform(get("/api/v1/journal-vouchers").param("sort", payload).header("Authorization", auditor.bearer())).andReturn()
                    .getResponse().getStatus();
            assertThat(status).as("sort=%s", payload).isIn(200, 400);
        }

        // The sign-in identifier: injection never authenticates and never errors.
        for (String payload : PAYLOADS) {
            int status = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(Map.of("identifier", payload, "password", payload)))).andReturn().getResponse().getStatus();
            assertThat(status).as("login %s", payload).isIn(400, 401, 429);
        }

        assertThat(count("users")).isEqualTo(users);
        assertThat(count("audit_events")).isGreaterThanOrEqualTo(auditEvents);
        assertThat(jdbc.queryForObject("SELECT to_regclass('public.journal_entries') IS NOT NULL", Boolean.class)).isTrue();
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }
}
