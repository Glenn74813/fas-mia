package com.miaa.cas.financialreporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.support.AbstractReceivablesIT;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Ad hoc reports (TOR Annex B.5): approved datasets, chosen columns, filters, grouping; saved, shared and exported. */
class AdHocReportIT extends AbstractReceivablesIT {

    @Autowired ReportJobService jobs;

    @Test
    void billingsAreGroupedAndTotalledByCustomerAndTheSavedReportExports() throws Exception {
        int year = newOpenYear();
        String name = "AdHoc Lessee " + year;
        String cid = createCustomerNamed(name).get("id").asText();
        postedBilling(billing("INVOICE", cid, LocalDate.of(year, 5, 2), "PLC-4203", "3000.00", false, null));
        postedBilling(billing("INVOICE", cid, LocalDate.of(year, 5, 9), "PLC-4203", "1500.50", false, null));
        Map<String, Object> def = Map.of("columns", List.of("customer", "gross"), "groupBy", List.of("customer"),
                "filters", List.of(Map.of("column", "documentDate", "op", "BETWEEN", "value", year + "-05-01", "value2", year + "-05-31"),
                        Map.of("column", "customer", "op", "EQ", "value", name)),
                "sort", List.of(Map.of("column", "gross", "descending", true)));

        JsonNode r = body(preview(preparer, "AR_BILLINGS", def).andExpect(status().isOk()).andReturn());
        assertThat(r.get("rows")).hasSize(1);
        assertThat(r.at("/rows/0/0").asText()).isEqualTo(name);
        assertThat(new BigDecimal(r.at("/rows/0/1").asText())).isEqualByComparingTo("4500.50");
        assertThat(r.at("/rows/0/2").asInt()).isEqualTo(2);
        assertThat(r.at("/columns/1/label").asText()).isEqualTo("Total gross");

        // Only the catalogue's columns and operators; values are bound, never spliced into SQL.
        preview(preparer, "AR_BILLINGS", Map.of("columns", List.of("customer; DROP TABLE users"))).andExpect(status().isBadRequest());
        preview(preparer, "AR_BILLINGS", Map.of("columns", List.of("customer"), "filters",
                List.of(Map.of("column", "customer", "op", "GT", "value", "x")))).andExpect(status().isBadRequest());
        JsonNode none = body(preview(preparer, "AR_BILLINGS", Map.of("columns", List.of("customer"), "filters",
                List.of(Map.of("column", "customer", "op", "CONTAINS", "value", "' OR 1=1 --")))).andExpect(status().isOk()).andReturn());
        assertThat(none.get("rows")).isEmpty();
        preview(preparer, "AR_BILLINGS", Map.of("columns", List.of("documentNumber", "gross"), "groupBy", List.of("customer")))
                .andExpect(status().isBadRequest());

        // Saved and shared: others with the dataset's permission see it; a budget officer (payables, no receivables) does not.
        JsonNode saved = body(mvc.perform(post("/api/v1/ad-hoc-reports").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("name", "May billings " + year, "dataset", "AR_BILLINGS",
                        "definition", def, "shared", true)))).andExpect(status().isCreated()).andReturn());
        String id = saved.get("id").asText();
        assertThat(body(mvc.perform(get("/api/v1/ad-hoc-reports").header("Authorization", approver.bearer())).andReturn()).toString())
                .contains("May billings " + year);
        Session budget = login(createUser("ahbudget", "BUDGET_OFFICER"));
        assertThat(body(mvc.perform(get("/api/v1/ad-hoc-reports").header("Authorization", budget.bearer())).andExpect(status().isOk())
                .andReturn()).toString()).doesNotContain("May billings " + year);
        assertThat(body(mvc.perform(get("/api/v1/ad-hoc-reports/datasets").header("Authorization", budget.bearer())).andReturn()).toString())
                .contains("AP_INVOICES").doesNotContain("AR_BILLINGS");

        // Exported through the report job.
        job(preparer, Map.of("definitionId", id, "dataset", "LEDGER_LINES")).andExpect(status().isBadRequest());
        String jobId = body(job(preparer, Map.of("definitionId", id, "dataset", "AR_BILLINGS")).andExpect(status().isCreated()).andReturn())
                .get("id").asText();
        jobs.processPending();
        String csv = new String(mvc.perform(get("/api/v1/report-jobs/" + jobId + "/file").header("Authorization", preparer.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(csv).contains("May billings " + year).contains(name).contains("4500.50").contains("Total");
    }

    @Test
    void ledgerLinesAreSummedByAccountForAPeriod() throws Exception {
        int year = newOpenYear();
        postThroughWorkflow(voucher("STANDARD", LocalDate.of(year, 8, 4), utilitiesLines("120.00")));
        postThroughWorkflow(voucher("STANDARD", LocalDate.of(year, 8, 20), utilitiesLines("80.00")));
        JsonNode r = body(preview(preparer, "LEDGER_LINES", Map.of("columns", List.of("accountCode", "debit", "credit"),
                "groupBy", List.of("accountCode"), "filters", List.of(Map.of("column", "accountingDate", "op", "BETWEEN",
                        "value", year + "-08-01", "value2", year + "-08-31")))).andExpect(status().isOk()).andReturn());
        Map<String, JsonNode> byAccount = new java.util.HashMap<>();
        r.get("rows").forEach(row -> byAccount.put(row.get(0).asText(), row));
        assertThat(new BigDecimal(byAccount.get("PLC-5201").get(1).asText())).isEqualByComparingTo("200.00");
        assertThat(new BigDecimal(byAccount.get("PLC-1102").get(2).asText())).isEqualByComparingTo("200.00");
        assertThat(new BigDecimal(r.at("/totals/1").asText())).isEqualByComparingTo(new BigDecimal(r.at("/totals/2").asText()));
    }

    private ResultActions preview(Session who, String dataset, Map<String, Object> def) throws Exception {
        return mvc.perform(post("/api/v1/ad-hoc-reports/preview").header("Authorization", who.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("dataset", dataset, "definition", def))));
    }

    private ResultActions job(Session who, Map<String, String> params) throws Exception {
        return mvc.perform(post("/api/v1/report-jobs").header("Authorization", who.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("reportType", "AD_HOC", "format", "CSV", "parameters", params))));
    }
}
