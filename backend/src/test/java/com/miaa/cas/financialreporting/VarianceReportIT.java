package com.miaa.cas.financialreporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.support.AbstractPayablesIT;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Variance analysis: budget to date against posted actuals to a chosen period, per allocation. */
class VarianceReportIT extends AbstractPayablesIT {

    @Autowired ReportJobService jobs;

    @Test
    void varianceToAChosenPeriodWithFavourableAndUnfavourableLines() throws Exception {
        int year = newOpenYear();
        UUID fy = period(year, 1).getFiscalYearId();
        Session budgetOfficer = login(createUser("vbo", "BUDGET_OFFICER"));
        // Allotments: utilities for Finance 12,000 and Operations 6,000; repairs for the whole account 1,200.
        budget(budgetOfficer, fy, List.of(alloc("PLC-5201", "PLC-CC-FIN", "12000.00"), alloc("PLC-5201", "PLC-CC-OPS", "6000.00"),
                alloc("PLC-5202", null, "1200.00")));

        postSpending(year, 1, "PLC-5201", "PLC-CC-FIN", "2500.00");
        postSpending(year, 1, "PLC-5201", "PLC-CC-OPS", "1000.00");
        postSpending(year, 3, "PLC-5201", "PLC-CC-FIN", "1500.00");
        postSpending(year, 2, "PLC-5202", "PLC-CC-FIN", "200.00");
        postSpending(year, 2, "PLC-5202", "PLC-CC-OPS", "90.00");
        postSpending(year, 2, "PLC-5203", "PLC-CC-OPS", "700.00"); // budget-controlled, but no allocation
        postSpending(year, 4, "PLC-5201", "PLC-CC-FIN", "9000.00"); // after the chosen period: not counted

        String csv = csv(financeManager, Map.of("fiscalYearId", fy.toString(), "throughPeriod", "3"));
        // Finance utilities: 3/12 of 12,000 = 3,000 budgeted, 4,000 spent: 1,000 over, -33.3 %.
        assertThat(csv).containsPattern("PLC-5201 [^\"]*Utilities / PLC-CC-FIN\",12000.00,3000.00,4000.00,-1000.00,-33.3,8000.00,\"Unfavourable\"");
        // Operations utilities: 1,500 budgeted, 1,000 spent.
        assertThat(csv).containsPattern("PLC-5201 [^\"]*Utilities / PLC-CC-OPS\",6000.00,1500.00,1000.00,500.00,33.3,5000.00,\"Favourable\"");
        // Repairs on every cost centre: 300 budgeted, 290 spent: 3.3 % under, within the default 10 % threshold.
        assertThat(csv).contains(",1200.00,300.00,290.00,10.00,3.3,910.00,\"Within threshold\"");
        assertThat(csv).containsPattern("PLC-5203 [^\"]*/ PLC-CC-OPS\",0.00,0.00,700.00,-700.00,,-700.00,\"No allocation\"");
        assertThat(csv).contains("\"Total expenses\",19200.00,4800.00,5990.00,-1190.00,-24.8,13210.00,\"Unfavourable\"");
        assertThat(csv).contains("Periods 1–3").contains("Proposed layout until AVTECH confirms its budget definitions (C-10)");

        // Through period 2, with a 30 % threshold and Finance only: 2,000 budgeted, 2,500 spent is -25 %, within threshold.
        String fin = csv(financeManager, Map.of("fiscalYearId", fy.toString(), "throughPeriod", "2", "thresholdPercent", "30",
                "costCenterId", costCenter("PLC-CC-FIN").toString()));
        assertThat(fin).contains(",12000.00,2000.00,2500.00,-500.00,-25.0,9500.00,\"Within threshold\"");
        assertThat(fin).doesNotContain("PLC-CC-OPS").contains("Cost centre PLC-CC-FIN");

        // Through the adjustment period the whole allotment is the budget to date.
        assertThat(csv(financeManager, Map.of("fiscalYearId", fy.toString(), "throughPeriod", "13")))
                .contains(",12000.00,12000.00,13000.00,-1000.00,-8.3,-1000.00,\"Within threshold\"");
    }

    @Test
    void parametersAndPermissionAreChecked() throws Exception {
        int year = newOpenYear();
        UUID fy = period(year, 1).getFiscalYearId();
        Session budgetOfficer = login(createUser("vbo2", "BUDGET_OFFICER"));
        budget(budgetOfficer, fy, List.of(alloc("PLC-5201", "PLC-CC-FIN", "1000.00")));

        request(financeManager, Map.of("fiscalYearId", fy.toString())).andExpect(status().isBadRequest());
        request(financeManager, Map.of("fiscalYearId", fy.toString(), "throughPeriod", "14")).andExpect(status().isBadRequest());
        request(financeManager, Map.of("fiscalYearId", fy.toString(), "throughPeriod", "3", "thresholdPercent", "150"))
                .andExpect(status().isBadRequest());
        request(financeManager, Map.of("fiscalYearId", UUID.randomUUID().toString(), "throughPeriod", "3"))
                .andExpect(status().isBadRequest());
        Session clerk = login(createUser("vclerk", "INVENTORY_CLERK"));
        request(clerk, Map.of("fiscalYearId", fy.toString(), "throughPeriod", "3")).andExpect(status().isForbidden());
        request(budgetOfficer, Map.of("fiscalYearId", fy.toString(), "throughPeriod", "3")).andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, String> alloc(String account, String costCenter, String amount) {
        Map<String, String> m = new HashMap<>();
        m.put("account", account);
        m.put("costCenter", costCenter);
        m.put("amount", amount);
        return m;
    }

    private void budget(Session budgetOfficer, UUID fy, List<Map<String, String>> allocations) throws Exception {
        JsonNode b = body(mvc.perform(post("/api/v1/budgets").header("Authorization", budgetOfficer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("fiscalYearId", fy, "name", "Variance test"))))
                .andExpect(status().isCreated()).andReturn());
        List<Map<String, Object>> body = new ArrayList<>();
        for (Map<String, String> a : allocations) {
            Map<String, Object> m = new HashMap<>();
            m.put("accountId", account(a.get("account")));
            m.put("costCenterId", a.get("costCenter") == null ? null : costCenter(a.get("costCenter")));
            m.put("amount", a.get("amount"));
            body.add(m);
        }
        ok(mvc.perform(put("/api/v1/budgets/" + b.get("id").asText() + "/allocations").header("Authorization", budgetOfficer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", b.get("version").asLong(), "allocations", body)))));
    }

    private void postSpending(int year, int month, String account, String costCenter, String amount) throws Exception {
        List<Map<String, Object>> lines = new ArrayList<>();
        lines.add(line(account(account), costCenter(costCenter), null, amount, "0"));
        lines.add(line(account("PLC-1102"), "0", amount));
        postThroughWorkflow(voucher("STANDARD", LocalDate.of(year, month, 10), lines));
    }

    private ResultActions request(Session who, Map<String, String> params) throws Exception {
        return mvc.perform(post("/api/v1/report-jobs").header("Authorization", who.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("reportType", "VARIANCE_ANALYSIS", "format", "CSV", "parameters", params))));
    }

    private String csv(Session who, Map<String, String> params) throws Exception {
        String id = body(request(who, params).andExpect(status().isCreated()).andReturn()).get("id").asText();
        jobs.processPending();
        assertThat(jdbc.queryForObject("SELECT status || coalesce(': ' || error, '') FROM report_jobs WHERE id = ?::uuid", String.class, id))
                .isEqualTo("DONE");
        return new String(mvc.perform(get("/api/v1/report-jobs/" + id + "/file").header("Authorization", who.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }
}
