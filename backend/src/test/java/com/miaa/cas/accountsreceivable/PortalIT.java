package com.miaa.cas.accountsreceivable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.support.AbstractReceivablesIT;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Object-level authorisation of the concessionaire portal: one customer's data never reaches another. */
class PortalIT extends AbstractReceivablesIT {

    @Test
    void concessionaireSeesOnlyTheirOwnPostedRecords() throws Exception {
        int year = newOpenYear();
        JsonNode a = createCustomer(null);
        JsonNode b = createCustomer(null);
        String aId = a.get("id").asText();
        String bId = b.get("id").asText();
        JsonNode aPosted = postedBilling(billing("INVOICE", aId, LocalDate.of(year, 6, 1), "PLC-4201", "2000.00", true, null));
        JsonNode aDraft = createBilling(billing("INVOICE", aId, LocalDate.of(year, 6, 2), "PLC-4201", "300.00", false, null));
        JsonNode bPosted = postedBilling(billing("INVOICE", bId, LocalDate.of(year, 6, 1), "PLC-4201", "9999.00", false, null));

        TestUser userA = concessionaire();
        Session portalA = login(userA);
        // Not linked yet: no data at all.
        mvc.perform(get("/api/v1/portal/account").header("Authorization", portalA.bearer())).andExpect(status().isForbidden());

        // Only concessionaire accounts can be linked; only customer managers can request; evidence is required.
        TestUser internal = createUser("int", "ACCOUNTANT");
        requestLink(preparer, aId, internal.username(), "Verified by phone with the lessee's manager").andExpect(status().isBadRequest());
        requestLink(approver, aId, userA.username(), "Verified by phone with the lessee's manager").andExpect(status().isForbidden());
        requestLink(preparer, aId, userA.username(), "short").andExpect(status().isBadRequest());
        JsonNode req = body(requestLink(preparer, aId, userA.username(), "Authorisation letter dated 20 Oct, signatory confirmed by call-back")
                .andExpect(status().isCreated()).andReturn());
        requestLink(preparer, bId, userA.username(), "Second request for the same account").andExpect(status().isConflict());
        // A request alone grants nothing.
        mvc.perform(get("/api/v1/portal/account").header("Authorization", portalA.bearer())).andExpect(status().isForbidden());

        // The requester cannot approve their own request, even with approval authority.
        Session both = login(createUser("both", "ACCOUNTANT", "CHIEF_ACCOUNTANT"));
        String bothReq = body(requestLink(both, bId, concessionaire().username(), "Verified in person at the commercial office")
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
        decide(both, bothReq, true).andExpect(status().isForbidden());
        decide(preparer, req.get("id").asText(), true).andExpect(status().isForbidden());
        JsonNode decided = body(decide(approver, req.get("id").asText(), true).andExpect(status().isOk()).andReturn());
        assertThat(decided.get("status").asText()).isEqualTo("APPROVED");
        decide(approver, req.get("id").asText(), true).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE template = 'portal-access-granted' AND recipient = ?",
                Integer.class, userA.email())).isEqualTo(1);
        mvc.perform(get("/api/v1/customers/" + aId + "/portal-users").header("Authorization", preparer.bearer()))
                .andExpect(jsonPath("$[0].username").value(userA.username()));
        // An account linked to one customer cannot be requested for another.
        requestLink(preparer, bId, userA.username(), "Attempt to move the account to another lessee").andExpect(status().isConflict());

        mvc.perform(get("/api/v1/portal/account").header("Authorization", portalA.bearer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.customerCode").value(a.get("code").asText()))
                .andExpect(jsonPath("$.openBalance").value("2240.00"));
        JsonNode list = body(mvc.perform(get("/api/v1/portal/billings").header("Authorization", portalA.bearer()))
                .andExpect(status().isOk()).andReturn());
        assertThat(list.get("totalItems").asInt()).isEqualTo(1);
        assertThat(list.get("items").get(0).get("id").asText()).isEqualTo(aPosted.get("id").asText());

        mvc.perform(get("/api/v1/portal/billings/" + aPosted.get("id").asText()).header("Authorization", portalA.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.grossAmount").value("2240.00"));
        // Another customer's invoice and one's own unposted draft are indistinguishable from non-existent ones.
        mvc.perform(get("/api/v1/portal/billings/" + bPosted.get("id").asText()).header("Authorization", portalA.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/portal/billings/" + aDraft.get("id").asText()).header("Authorization", portalA.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/portal/statement").param("from", year + "-01-01").param("to", year + "-12-31")
                .header("Authorization", portalA.bearer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.customerCode").value(a.get("code").asText()))
                .andExpect(jsonPath("$.closingBalance").value("2240.00"));

        // Portal accounts cannot use internal APIs, even by guessing ids.
        mvc.perform(get("/api/v1/customers/" + bId).header("Authorization", portalA.bearer())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/customers/" + bId + "/statement").param("from", year + "-01-01").param("to", year + "-12-31")
                .header("Authorization", portalA.bearer())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/accounts-receivable/billings/" + bPosted.get("id").asText())
                .header("Authorization", portalA.bearer())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/journal-vouchers").header("Authorization", portalA.bearer())).andExpect(status().isForbidden());
        // Internal staff have no portal identity.
        mvc.perform(get("/api/v1/portal/account").header("Authorization", preparer.bearer())).andExpect(status().isForbidden());

        // Unlinking removes access immediately.
        mvc.perform(delete("/api/v1/customers/" + aId + "/portal-users/" + userA.id()).header("Authorization", preparer.bearer()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/portal/billings").header("Authorization", portalA.bearer())).andExpect(status().isForbidden());
    }

    private ResultActions requestLink(Session who, String customerId, String username, String evidence) throws Exception {
        return mvc.perform(post("/api/v1/customers/" + customerId + "/portal-link-requests").header("Authorization", who.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("username", username, "evidence", evidence))));
    }

    private ResultActions decide(Session who, String requestId, boolean approve) throws Exception {
        return mvc.perform(post("/api/v1/portal-link-requests/" + requestId + "/decision").header("Authorization", who.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("approve", approve, "note", "Evidence checked"))));
    }

    private TestUser concessionaire() {
        TestUser u = createUser("conc", "CONCESSIONAIRE");
        jdbc.update("UPDATE users SET user_type = 'CONCESSIONAIRE' WHERE id = ?", u.id());
        return u;
    }
}
