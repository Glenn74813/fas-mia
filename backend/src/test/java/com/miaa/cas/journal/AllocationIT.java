package com.miaa.cas.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.support.AbstractAccountingIT;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Overhead allocation (TOR §3.5.1, B.7, B.8): a cycle moves a period's sender balance to receivers by a key, as a draft voucher. */
class AllocationIT extends AbstractAccountingIT {

    @Test
    void aCycleSpreadsTheSendersBalanceByItsKeyIntoOneDraftVoucherPerPeriod() throws Exception {
        int year = newOpenYear();
        String tag = String.valueOf(System.nanoTime() % 100000);
        UUID terminal = UUID.randomUUID();
        jdbc.update("INSERT INTO cost_centers (id, code, name, status, placeholder, created_at, created_by) VALUES (?, ?, 'Terminal ops', 'ACTIVE', "
                + "TRUE, now(), 'test')", terminal, "T-CC-" + tag);
        // 1,000.00 of utilities charged to the finance cost centre in March.
        postThroughWorkflow(voucher("STANDARD", LocalDate.of(year, 3, 10), utilitiesLines("1000.00")));
        UUID march = period(year, 3).getId();

        Map<String, Object> percentRule = rule("PERCENT", Map.of(costCenter("PLC-CC-OPS"), "60", terminal, "30"));
        cycle(preparer, "ALLOC-" + tag, List.of(percentRule)).andExpect(status().isBadRequest()); // 90 %, not 100
        cycle(preparer, "ALLOC-" + tag, List.of(rule("QUANTITY", Map.of(costCenter("PLC-CC-FIN"), "1", terminal, "2"))))
                .andExpect(status().isBadRequest()); // the sender cannot receive
        cycle(login(createUser("alsupport", "SUPPORT_AGENT")), "ALLOC-" + tag, List.of(rule("QUANTITY", Map.of(terminal, "1"))))
                .andExpect(status().isForbidden());

        // Floor area: operations 1, terminal 2.
        JsonNode c = body(cycle(preparer, "ALLOC-" + tag, List.of(rule("QUANTITY", Map.of(costCenter("PLC-CC-OPS"), "1", terminal, "2"))))
                .andExpect(status().isCreated()).andReturn());
        String id = c.get("id").asText();
        JsonNode preview = body(mvc.perform(get("/api/v1/allocation-cycles/" + id + "/preview?periodId=" + march)
                .header("Authorization", preparer.bearer())).andExpect(status().isOk()).andReturn());
        Map<String, BigDecimal[]> byCc = new HashMap<>();
        preview.get("lines").forEach(l -> byCc.put(l.get("costCenterCode").asText(),
                new BigDecimal[] {new BigDecimal(l.get("debit").asText()), new BigDecimal(l.get("credit").asText())}));
        assertThat(byCc.get("PLC-CC-FIN")[1]).isEqualByComparingTo("1000.00");
        assertThat(byCc.get("PLC-CC-OPS")[0]).isEqualByComparingTo("333.33");
        assertThat(byCc.get("T-CC-" + tag)[0]).isEqualByComparingTo("666.67");

        JsonNode run = body(mvc.perform(post("/api/v1/allocation-cycles/" + id + "/runs").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("periodId", march)))).andExpect(status().isCreated()).andReturn());
        assertThat(run.get("voucherStatus").asText()).isEqualTo("DRAFT");
        JsonNode v = body(mvc.perform(get("/api/v1/journal-vouchers/" + run.get("voucherId").asText()).header("Authorization", preparer.bearer()))
                .andReturn());
        assertThat(v.get("accountingDate").asText()).isEqualTo(year + "-03-31");
        assertThat(new BigDecimal(v.get("totalDebit").asText())).isEqualByComparingTo("1000.00");
        assertThat(v.get("lines")).hasSize(3);
        // Once per period while that voucher stands.
        mvc.perform(post("/api/v1/allocation-cycles/" + id + "/runs").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("periodId", march)))).andExpect(status().isConflict());
        // Nothing to allocate in April.
        mvc.perform(post("/api/v1/allocation-cycles/" + id + "/runs").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("periodId", period(year, 4).getId())))).andExpect(status().isConflict());
    }

    private Map<String, Object> rule(String key, Map<UUID, String> receivers) {
        return Map.of("description", "Utilities by floor area", "senderAccountId", account("PLC-5201"), "senderCostCenterId",
                costCenter("PLC-CC-FIN"), "keyType", key, "receivers", receivers.entrySet().stream()
                        .map(e -> Map.of("costCenterId", e.getKey(), "weight", e.getValue())).toList());
    }

    private ResultActions cycle(Session who, String code, List<Map<String, Object>> rules) throws Exception {
        return mvc.perform(post("/api/v1/allocation-cycles").header("Authorization", who.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("code", code, "name", "Utilities overhead", "rules", rules))));
    }
}
