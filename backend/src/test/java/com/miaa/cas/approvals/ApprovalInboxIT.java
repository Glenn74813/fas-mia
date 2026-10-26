package com.miaa.cas.approvals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.support.AbstractReceivablesIT;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** One inbox across modules (never the caller's own documents) and in-app notifications of decisions to the preparer. */
class ApprovalInboxIT extends AbstractReceivablesIT {

    @Test
    void theInboxShowsOnlyStepsTheCallerMayTakeAndThePreparerHearsTheOutcome() throws Exception {
        int year = newOpenYear();
        String customer = createCustomer(null).get("id").asText();
        JsonNode first = createBilling(billing("INVOICE", customer, LocalDate.of(year, 4, 1), "PLC-4201", "1200.00", false, null));
        JsonNode second = createBilling(billing("INVOICE", customer, LocalDate.of(year, 4, 2), "PLC-4201", "800.00", false, null));
        first = ok(apAction(preparer, "accounts-receivable/billings", first.get("id").asText(), "submit", first.get("version").asLong(), null));
        second = ok(apAction(preparer, "accounts-receivable/billings", second.get("id").asText(), "submit", second.get("version").asLong(), null));
        String id = first.get("id").asText();

        // The approver sees both billings as "Approve" items linking to the billing page; the preparer sees neither.
        JsonNode inbox = inbox(approver);
        JsonNode item = find(inbox, id);
        assertThat(item).isNotNull();
        assertThat(item.get("task").asText()).isEqualTo("Approve");
        assertThat(item.get("queue").asText()).isEqualTo("Billings to approve");
        assertThat(item.get("link").asText()).isEqualTo("/receivables/billings/" + id);
        assertThat(item.get("amount").asText()).isEqualTo("1200.00");
        assertThat(find(inbox, second.get("id").asText())).isNotNull();
        assertThat(inbox.get("queues").toString()).contains("Billings to approve");
        assertThat(find(inbox(preparer), id)).isNull();
        // Search narrows the inbox on the server (before the per-source limit), by customer name among others.
        String customerName = item.get("counterparty").asText();
        JsonNode found = body(mvc.perform(get("/api/v1/inbox").param("q", customerName.substring(customerName.length() - 6))
                .header("Authorization", approver.bearer())).andExpect(status().isOk()).andReturn());
        assertThat(found.get("items").size()).isEqualTo(2);
        assertThat(body(mvc.perform(get("/api/v1/inbox").param("q", "no-such-document-%_")
                .header("Authorization", approver.bearer())).andReturn()).get("items").size()).isZero();

        // Approval moves it to the posters' queue and tells the preparer.
        JsonNode approved = ok(apAction(approver, "accounts-receivable/billings", id, "approve", first.get("version").asLong(), null));
        assertThat(find(inbox(approver), id).get("queue").asText()).isEqualTo("Documents to post");
        assertThat(find(inbox(poster), id).get("task").asText()).isEqualTo("Post");
        ok(apAction(approver, "accounts-receivable/billings", second.get("id").asText(), "return", second.get("version").asLong(),
                "Wrong billing period"));
        ok(apAction(poster, "accounts-receivable/billings", id, "post", approved.get("version").asLong(), null));
        assertThat(find(inbox(poster), id)).isNull();

        JsonNode mine = notifications(preparer, false);
        List<String> titles = new ArrayList<>();
        mine.get("items").forEach(n -> titles.add(n.get("title").asText()));
        assertThat(titles).anyMatch(t -> t.startsWith("Billing") && t.contains(" was approved by "))
                .anyMatch(t -> t.contains(" was returned by "))
                .anyMatch(t -> t.contains(" was posted by "));
        assertThat(mine.get("items").toString()).contains("Wrong billing period").contains("/receivables/billings/" + id);
        assertThat(unread(preparer)).isEqualTo(3);
        // Nobody is notified of their own action.
        assertThat(notifications(approver, false).get("items").toString()).doesNotContain("/receivables/billings/" + id);

        // Each user reads and marks only their own notifications.
        String nid = mine.get("items").get(0).get("id").asText();
        mvc.perform(post("/api/v1/notifications/" + nid + "/read").header("Authorization", approver.bearer())).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/notifications/" + nid + "/read").header("Authorization", preparer.bearer())).andExpect(status().isNoContent());
        assertThat(unread(preparer)).isEqualTo(2);
        assertThat(notifications(preparer, true).get("totalItems").asLong()).isEqualTo(2);
        ok(mvc.perform(post("/api/v1/notifications/read-all").header("Authorization", preparer.bearer())));
        assertThat(unread(preparer)).isZero();

        // Concessionaires have no inbox.
        TestUser conc = createUser("ibconc", "CONCESSIONAIRE");
        jdbc.update("UPDATE users SET user_type = 'CONCESSIONAIRE', customer_id = ?::uuid WHERE id = ?", customer, conc.id());
        mvc.perform(get("/api/v1/inbox").header("Authorization", login(conc).bearer())).andExpect(status().isForbidden());
    }

    @Test
    void everySourceRunsForAUserHoldingEveryInternalRole() throws Exception {
        String[] roles = jdbc.queryForList("SELECT code FROM roles WHERE code <> 'CONCESSIONAIRE'", String.class).toArray(String[]::new);
        JsonNode all = inbox(login(createUser("iball", roles)));
        assertThat(all.get("truncated").isBoolean()).isTrue();
        all.get("items").forEach(i -> assertThat(i.get("link").asText()).startsWith("/"));
    }

    private JsonNode inbox(Session who) throws Exception {
        return body(mvc.perform(get("/api/v1/inbox").header("Authorization", who.bearer())).andExpect(status().isOk()).andReturn());
    }

    private JsonNode notifications(Session who, boolean unreadOnly) throws Exception {
        return body(mvc.perform(get("/api/v1/notifications").param("unreadOnly", String.valueOf(unreadOnly))
                .header("Authorization", who.bearer())).andExpect(status().isOk()).andReturn());
    }

    private long unread(Session who) throws Exception {
        return body(mvc.perform(get("/api/v1/notifications/unread-count").header("Authorization", who.bearer())).andExpect(status().isOk())
                .andReturn()).get("unread").asLong();
    }

    private static JsonNode find(JsonNode inbox, String id) {
        for (JsonNode i : inbox.get("items")) {
            if (i.get("id").asText().equals(id)) {
                return i;
            }
        }
        return null;
    }
}
