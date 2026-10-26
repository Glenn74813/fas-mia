package com.miaa.cas.generalledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.support.AbstractAccountingIT;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

class ChartOfAccountsIT extends AbstractAccountingIT {

    @Test
    void placeholderChartIsFlaggedEverywhere() throws Exception {
        JsonNode chart = body(mvc.perform(get("/api/v1/accounts").header("Authorization", preparer.bearer()))
                .andExpect(status().isOk()).andReturn());
        assertThat(chart.get("activePlaceholderAccounts").asLong()).isGreaterThanOrEqualTo(50);
        for (JsonNode a : chart.get("accounts")) {
            if (a.get("code").asText().startsWith("PLC-")) {
                assertThat(a.get("placeholder").asBoolean()).isTrue();
                assertThat(a.get("name").asText()).startsWith("[PLACEHOLDER]");
            }
        }
    }

    @Test
    void importIsAllOrNothing() throws Exception {
        String suffix = String.valueOf(System.nanoTime() % 100000);
        String csv = "code,name,type,normalBalance,parentCode,postable,fsLineCode,requiresCostCenter,subsidiaryType,budgetControlled\n"
                + "T" + suffix + ",Test header,ASSET,DEBIT,,false,,false,,false\n"
                + "T" + suffix + "-1,Test leaf,ASSET,DEBIT,T" + suffix + ",true,,false,,false\n"
                + "T" + suffix + "-2,Bad parent,ASSET,DEBIT,NOPE,true,,false,,false\n";
        mvc.perform(multipart("/api/v1/accounts/import").file(new MockMultipartFile("file", "coa.csv", "text/csv", csv.getBytes()))
                .header("Authorization", approver.bearer()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].message").value("Parent account NOPE does not exist."));
        assertThat(accounts.findByCode("T" + suffix)).isEmpty();
    }

    @Test
    void structuralRulesAndPermissions() throws Exception {
        Map<String, Object> req = new HashMap<>();
        req.put("code", "X" + (System.nanoTime() % 100000));
        req.put("name", "Child of a postable account");
        req.put("accountType", "ASSET");
        req.put("parentCode", "PLC-1102");
        req.put("postable", true);
        mvc.perform(post("/api/v1/accounts").header("Authorization", approver.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(req)))
                .andExpect(status().isUnprocessableEntity());
        req.put("parentCode", "PLC-11");
        mvc.perform(post("/api/v1/accounts").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(req))).andExpect(status().isForbidden());
        JsonNode created = body(mvc.perform(post("/api/v1/accounts").header("Authorization", approver.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(req))).andExpect(status().isCreated()).andReturn());
        assertThat(created.get("placeholder").asBoolean()).isFalse();
        assertThat(created.get("normalBalance").asText()).isEqualTo("DEBIT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'ACCOUNT_CREATED' AND resource_id = ?",
                Long.class, created.get("code").asText())).isEqualTo(1);
    }

    @Test
    void usedAccountCannotChangeType() throws Exception {
        int year = newOpenYear();
        createVoucher(preparer, voucher("STANDARD", LocalDate.of(year, 1, 5), utilitiesLines("1.00")));
        JsonNode cash = body(mvc.perform(get("/api/v1/accounts/" + account("PLC-1102")).header("Authorization", approver.bearer()))
                .andReturn());
        Map<String, Object> update = new HashMap<>();
        update.put("version", cash.get("version").asLong());
        update.put("name", cash.get("name").asText());
        update.put("accountType", "LIABILITY");
        update.put("parentCode", null);
        update.put("postable", true);
        mvc.perform(put("/api/v1/accounts/" + account("PLC-1102")).header("Authorization", approver.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(update)))
                .andExpect(status().isUnprocessableEntity());
    }
}
