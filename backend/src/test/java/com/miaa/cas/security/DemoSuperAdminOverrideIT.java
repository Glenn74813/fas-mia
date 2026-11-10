package com.miaa.cas.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import com.miaa.cas.support.AbstractAccountingIT;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DemoSuperAdminOverrideIT extends AbstractAccountingIT {

    @AfterEach
    void overridesOff() {
        CurrentActor.enableDemoOverrides(false);
    }

    private Session superAdmin() throws Exception {
        List<UUID> existing = jdbc.queryForList("SELECT id FROM roles WHERE code = ?", UUID.class, CurrentActor.DEMO_SUPERADMIN_ROLE);
        UUID roleId = existing.isEmpty() ? UUID.randomUUID() : existing.get(0);
        if (existing.isEmpty()) {
            jdbc.update("INSERT INTO roles (id, code, name, description, system_role, created_by, updated_by) "
                    + "VALUES (?, ?, 'Demo Super Administrator', 'test', TRUE, 'test', 'test')", roleId, CurrentActor.DEMO_SUPERADMIN_ROLE);
        }
        jdbc.update("INSERT INTO role_permissions (role_id, permission_code) SELECT ?, code FROM permissions "
                + "WHERE code <> 'PORTAL_SELF_VIEW' ON CONFLICT DO NOTHING", roleId);
        return login(createUser("super", CurrentActor.DEMO_SUPERADMIN_ROLE));
    }

    @Test
    void theDemoSuperAdministratorTakesEveryStepOfItsOwnVoucherOnlyWhenTheOverrideIsOn() throws Exception {
        Session sa = superAdmin();
        int year = newOpenYear();
        JsonNode v = createVoucher(sa, voucher("STANDARD", LocalDate.of(year, 3, 15), utilitiesLines("4200.00")));
        String id = v.get("id").asText();
        attachPdf(sa, id);
        v = ok(action(sa, id, "submit", v.get("version").asLong(), null));

        // Off (the production default): the usual four-eyes rule applies to this role too.
        action(sa, id, "approve", v.get("version").asLong(), null)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SEGREGATION_OF_DUTIES"));

        CurrentActor.enableDemoOverrides(true);
        v = ok(action(sa, id, "approve", v.get("version").asLong(), "Review"));
        assertThat(v.get("availableActions").toString()).contains("APPROVE");
        v = ok(action(sa, id, "approve", v.get("version").asLong(), "Approve"));
        assertThat(v.get("status").asText()).isEqualTo("APPROVED");
        JsonNode posted = ok(postVoucher(sa, id, v.get("version").asLong(), key()));
        assertThat(posted.get("status").asText()).isEqualTo("POSTED");

        // Everyone else keeps the rule: the preparer of another voucher still cannot approve it.
        JsonNode other = createVoucher(preparer, voucher("STANDARD", LocalDate.of(year, 3, 16), utilitiesLines("10.00")));
        attachPdf(preparer, other.get("id").asText());
        other = ok(action(preparer, other.get("id").asText(), "submit", other.get("version").asLong(), null));
        action(preparer, other.get("id").asText(), "approve", other.get("version").asLong(), null).andExpect(status().isForbidden());
    }
}
