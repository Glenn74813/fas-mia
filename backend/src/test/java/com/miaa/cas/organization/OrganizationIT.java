package com.miaa.cas.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.support.AbstractAccountingIT;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Organisation administration (FR-ORG-01): structure, a department's cost centre, and users' office assignment history. */
class OrganizationIT extends AbstractAccountingIT {

    @Test
    void officesDepartmentsAndAssignmentsKeepTheirHistory() throws Exception {
        Session admin = login(createUser("org.admin", "SYSTEM_ADMINISTRATOR"));
        String tag = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String orgId = jdbc.queryForObject("SELECT id::text FROM organizations WHERE code = 'AVTECH'", String.class);

        // Only organisation administrators change the structure; codes are validated and unique.
        send(preparer, post("/api/v1/organizations/" + orgId + "/offices"), Map.of("code", "X" + tag, "name", "Nope")).andExpect(status().isForbidden());
        send(admin, post("/api/v1/organizations/" + orgId + "/offices"), Map.of("code", "bad code!", "name", "Bad")).andExpect(status().isBadRequest());
        String office = body(send(admin, post("/api/v1/organizations/" + orgId + "/offices"), Map.of("code", "FIN" + tag, "name", "[PLACEHOLDER] Finance"))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
        send(admin, post("/api/v1/organizations/" + orgId + "/offices"), Map.of("code", "fin" + tag.toLowerCase(), "name", "Again"))
                .andExpect(status().isConflict());
        String office2 = body(send(admin, post("/api/v1/organizations/" + orgId + "/offices"), Map.of("code", "OPS" + tag, "name", "[PLACEHOLDER] Ops"))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
        Map<String, Object> dept = new HashMap<>(Map.of("code", "ACC" + tag, "name", "[PLACEHOLDER] Accounting"));
        dept.put("costCenterId", costCenter("PLC-CC-FIN").toString());
        String department = body(send(admin, post("/api/v1/offices/" + office + "/departments"), dept).andExpect(status().isCreated()).andReturn())
                .get("id").asText();

        JsonNode tree = body(mvc.perform(get("/api/v1/organizations").header("Authorization", admin.bearer())).andExpect(status().isOk()).andReturn());
        assertThat(tree.toString()).contains("FIN" + tag).contains("ACC" + tag).contains("PLC-CC-FIN");

        // Assignments: a transfer closes the previous one the day before; the history stays.
        TestUser user = createUser("org.user", "ACCOUNTANT");
        LocalDate start = LocalDate.of(2026, 1, 1);
        Map<String, Object> first = new HashMap<>(Map.of("officeId", office, "effectiveFrom", start.toString()));
        first.put("departmentId", department);
        send(admin, post("/api/v1/users/" + user.id() + "/office-assignments"), first).andExpect(status().isOk());
        send(admin, post("/api/v1/users/" + user.id() + "/office-assignments"), Map.of("officeId", office2, "departmentId", department,
                "effectiveFrom", "2026-07-01")).andExpect(status().isBadRequest());
        send(admin, post("/api/v1/users/" + user.id() + "/office-assignments"), Map.of("officeId", office2, "effectiveFrom", start.toString()))
                .andExpect(status().isBadRequest());
        JsonNode history = body(send(admin, post("/api/v1/users/" + user.id() + "/office-assignments"),
                Map.of("officeId", office2, "effectiveFrom", "2026-07-01")).andExpect(status().isOk()).andReturn());
        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("officeCode").asText()).isEqualTo("OPS" + tag);
        assertThat(history.get(0).get("effectiveTo").isNull()).isTrue();
        assertThat(history.get(1).get("effectiveTo").asText()).isEqualTo("2026-06-30");

        // An office with current users or active departments cannot be deactivated.
        long v2 = version(admin, office2);
        send(admin, put("/api/v1/offices/" + office2), Map.of("version", v2, "name", "[PLACEHOLDER] Ops", "status", "INACTIVE"))
                .andExpect(status().isConflict());
        long v1 = version(admin, office);
        send(admin, put("/api/v1/offices/" + office), Map.of("version", v1, "name", "[PLACEHOLDER] Finance", "status", "INACTIVE"))
                .andExpect(status().isConflict());
        ok(send(admin, post("/api/v1/office-assignments/" + history.get(0).get("id").asText() + "/end"), Map.of("effectiveTo", "2026-09-30")));
        ok(send(admin, put("/api/v1/offices/" + office2), Map.of("version", v2, "name", "[PLACEHOLDER] Ops", "status", "INACTIVE")));
        // Users read their own assignments; others need USER_VIEW.
        assertThat(body(mvc.perform(get("/api/v1/users/" + user.id() + "/office-assignments").header("Authorization", login(user).bearer()))
                .andExpect(status().isOk()).andReturn())).hasSize(2);
        mvc.perform(get("/api/v1/users/" + user.id() + "/office-assignments").header("Authorization", reviewer.bearer())).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'USER_OFFICE_ASSIGNED' AND resource_id = ?", Integer.class,
                user.id().toString())).isEqualTo(2);
    }

    private ResultActions send(Session who, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, Map<String, ?> body)
            throws Exception {
        return mvc.perform(req.header("Authorization", who.bearer()).contentType(MediaType.APPLICATION_JSON).content(toJson(body)));
    }

    private long version(Session admin, String officeId) throws Exception {
        JsonNode tree = body(mvc.perform(get("/api/v1/organizations").header("Authorization", admin.bearer())).andReturn());
        for (JsonNode org : tree) {
            for (JsonNode o : org.get("offices")) {
                if (o.get("id").asText().equals(officeId)) {
                    return o.get("version").asLong();
                }
            }
        }
        throw new AssertionError("No office " + officeId);
    }
}
