package com.miaa.cas.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.support.AbstractIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class RoleAdminIT extends AbstractIntegrationTest {

    private Session admin;

    @BeforeEach
    void signInAdmin() throws Exception {
        admin = login(createUser("radmin", "SYSTEM_ADMINISTRATOR"));
    }

    @Test
    void seededAdministratorRoleHasNoFinancialPermissions() throws Exception {
        JsonNode role = body(mvc.perform(get("/api/v1/roles/SYSTEM_ADMINISTRATOR").header("Authorization", admin.bearer()))
                .andExpect(status().isOk()).andReturn());
        List<String> financial = jdbc.queryForList("SELECT code FROM permissions WHERE financial", String.class);
        role.get("permissionCodes").forEach(p -> assertThat(financial).doesNotContain(p.asText()));
        assertThat(role.get("permissionCodes").toString()).contains("USER_ADMINISTER");
    }

    @Test
    void createRoleAndChangePermissionsWithVersioning() throws Exception {
        String code = ("TEST_ROLE_" + System.nanoTime()).toUpperCase();
        mvc.perform(post("/api/v1/roles").header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("code", code, "name", "Test role", "permissionCodes", List.of("LEDGER_VIEW")))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.role.version").value(0));
        mvc.perform(put("/api/v1/roles/" + code + "/permissions").header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("version", 0, "permissionCodes", List.of("LEDGER_VIEW", "FINANCIAL_REPORT_VIEW")))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role.version").value(1))
                .andExpect(jsonPath("$.role.permissionCodes.length()").value(2));
        mvc.perform(put("/api/v1/roles/" + code + "/permissions").header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("version", 0, "permissionCodes", List.of("LEDGER_VIEW")))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
        mvc.perform(put("/api/v1/roles/" + code + "/permissions").header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("version", 1, "permissionCodes", List.of("NOT_A_PERMISSION")))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void permissionChangeCheckedAgainstHolders() throws Exception {
        String code = ("HOLDER_ROLE_" + System.nanoTime()).toUpperCase();
        mvc.perform(post("/api/v1/roles").header("Authorization", admin.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("code", code, "name", "Holder role", "permissionCodes", List.of("LEDGER_VIEW")))))
                .andExpect(status().isCreated());
        TestUser holder = createUser("holder", "ACCOUNTANT", code);
        // Adding JOURNAL_POST would give an ACCOUNTANT (JOURNAL_CREATE) posting authority.
        mvc.perform(put("/api/v1/roles/" + code + "/permissions").header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("version", 0, "permissionCodes", List.of("LEDGER_VIEW", "JOURNAL_POST")))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOD_CONFLICT"))
                .andExpect(jsonPath("$.affectedUserIds[0]").value(holder.id().toString()));
    }

    @Test
    void cannotEditPermissionsOfOwnRole() throws Exception {
        JsonNode role = body(mvc.perform(get("/api/v1/roles/SYSTEM_ADMINISTRATOR").header("Authorization", admin.bearer()))
                .andReturn());
        mvc.perform(put("/api/v1/roles/SYSTEM_ADMINISTRATOR/permissions").header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("version", role.get("version").asLong(),
                        "permissionCodes", List.of("USER_VIEW", "AUDIT_LOG_VIEW")))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SELF_MODIFICATION_FORBIDDEN"));
    }

    @Test
    void permissionCatalogAndSodRulesAreReadable() throws Exception {
        mvc.perform(get("/api/v1/permissions").header("Authorization", admin.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.code == 'JOURNAL_POST')].financial").value(true));
        mvc.perform(get("/api/v1/sod-rules").header("Authorization", admin.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.permissionA == 'DISBURSEMENT_PREPARE' && @.permissionB == 'PAYMENT_APPROVE')].severity").value("BLOCK"));
    }
}
