package com.miaa.cas.generalledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.support.AbstractAccountingIT;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Open-item clearing (FR-GL-17): manual and automatic clearing, reversal, and the aged-items close check. */
class ClearingIT extends AbstractAccountingIT {

    @Test
    void openItemsClearManuallyAndAutomaticallyAndClearingsReverse() throws Exception {
        int year = newOpenYear();
        UUID account = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO chart_of_accounts (id, code, name, account_type, normal_balance, parent_id, postable, status, effective_from,
                    fs_line_code, placeholder, created_at, created_by, updated_at, updated_by)
                SELECT ?, ?, '[TEST] Clearing account', account_type, normal_balance, parent_id, TRUE, 'ACTIVE', effective_from, fs_line_code,
                    TRUE, now(), 'test', now(), 'test' FROM chart_of_accounts WHERE code = 'PLC-1101'""", account,
                "PLC-OI-" + UUID.randomUUID().toString().substring(0, 8));

        // Only postable asset/liability accounts, and only account administrators, may switch open-item management on.
        mvc.perform(put("/api/v1/accounts/" + account + "/open-item-managed").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"managed\":true}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/accounts/" + account("PLC-5201") + "/open-item-managed").header("Authorization", approver.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"managed\":true}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/open-items/accounts/" + account + "/items").header("Authorization", preparer.bearer()))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/v1/accounts/" + account + "/open-item-managed").header("Authorization", approver.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"managed\":true}")).andExpect(status().isNoContent());

        LocalDate d = LocalDate.of(year, 2, 10);
        postLine(account, d, "Advance REF-1", "100.00", "0");
        postLine(account, d.plusDays(3), "advance ref-1", "0", "100.00");
        postLine(account, d.plusDays(4), "Deposit in transit", "40.00", "0");
        postLine(account, d.plusDays(5), "Bank credit", "0", "40.00");

        List<String> lines = openLines(account, 4);
        JsonNode accounts = body(mvc.perform(get("/api/v1/open-items/accounts").header("Authorization", preparer.bearer())).andReturn());
        assertThat(accounts.toString()).contains(account.toString());

        // Items that do not net to zero cannot clear each other.
        JsonNode bad = body(clear(account, List.of(lines.get(0), lines.get(2)), "Mismatch").andExpect(status().isUnprocessableEntity()).andReturn());
        assertThat(bad.get("detail").asText()).contains("do not net to zero");

        // By reference: the same description (case-insensitive) netting to zero; preview first, then apply.
        JsonNode preview = autoClear(account, "REFERENCE", false);
        assertThat(preview).hasSize(1);
        assertThat(openLines(account, 4)).hasSize(4);
        autoClear(account, "REFERENCE", true);
        assertThat(openLines(account, 2)).hasSize(2);

        // By amount: an equal and opposite pair.
        assertThat(autoClear(account, "AMOUNT", true)).hasSize(1);
        assertThat(openLines(account, 0)).isEmpty();
        JsonNode history = body(mvc.perform(get("/api/v1/open-items/accounts/" + account + "/clearings").header("Authorization", preparer.bearer()))
                .andReturn());
        assertThat(history).hasSize(2);
        String amountClearing = history.get(0).get("id").asText();
        assertThat(history.get(0).get("rule").asText()).isEqualTo("AMOUNT");

        // A reversal needs a reason and re-opens the items; they can then be cleared manually.
        mvc.perform(post("/api/v1/clearings/" + amountClearing + "/reverse").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("reason", "no")))).andExpect(status().isBadRequest());
        JsonNode reversed = body(mvc.perform(post("/api/v1/clearings/" + amountClearing + "/reverse").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("reason", "Matched the wrong bank credit"))))
                .andExpect(status().isOk()).andReturn());
        assertThat(reversed.get("status").asText()).isEqualTo("REVERSED");
        List<String> reopened = openLines(account, 2);
        assertThat(reopened).hasSize(2);
        JsonNode manual = body(clear(account, reopened, "Matched to the bank statement of " + d.plusDays(5)).andExpect(status().isCreated()).andReturn());
        assertThat(manual.get("rule").asText()).isEqualTo("MANUAL");
        assertThat(manual.get("entryNumbers")).hasSize(2);
        clear(account, reopened, "Again").andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'GL_ITEMS_CLEARED' AND details::text LIKE ?",
                Long.class, "%" + account + "%")).isEqualTo(3);
    }

    private void postLine(UUID account, LocalDate date, String description, String debit, String credit) throws Exception {
        List<Map<String, Object>> lines = new ArrayList<>();
        Map<String, Object> l = line(account, debit, credit);
        l.put("description", description);
        lines.add(l);
        lines.add(line(account("PLC-1102"), credit, debit));
        postThroughWorkflow(voucher("STANDARD", date, lines));
    }

    private List<String> openLines(UUID account, int expected) throws Exception {
        JsonNode page = body(mvc.perform(get("/api/v1/open-items/accounts/" + account + "/items").header("Authorization", preparer.bearer()))
                .andExpect(status().isOk()).andReturn());
        List<String> ids = new ArrayList<>();
        page.get("items").forEach(i -> ids.add(i.get("lineId").asText()));
        assertThat(page.get("totalItems").asLong()).isEqualTo(expected);
        return ids;
    }

    private ResultActions clear(UUID account, List<String> lineIds, String note) throws Exception {
        return mvc.perform(post("/api/v1/open-items/accounts/" + account + "/clearings").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("lineIds", lineIds, "note", note))));
    }

    private JsonNode autoClear(UUID account, String rule, boolean apply) throws Exception {
        return body(mvc.perform(post("/api/v1/open-items/accounts/" + account + "/auto-clear").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("rule", rule, "apply", apply)))).andExpect(status().isOk()).andReturn());
    }
}
