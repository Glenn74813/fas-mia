package com.miaa.cas.financialreporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.config.dev.DevPayablesSeeder;
import com.miaa.cas.financialreporting.ReportDtos.TemplateLineRequest;
import com.miaa.cas.financialreporting.ReportDtos.TemplateRequest;
import com.miaa.cas.support.AbstractAccountingIT;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Movement statements (gap 14): a direct-method cash flow statement and a statement of changes in equity built from
 * the placeholder templates. Opening balances include other tests' years, so the checks are the year's movements,
 * which belong to this test alone, and the templates' own reconciliations.
 */
class MovementStatementsIT extends AbstractAccountingIT {

    @org.springframework.beans.factory.annotation.Autowired ReportJobService jobs;

    @Test
    void cashFlowAndChangesInEquityClassifyTheYearsMovementsAndReconcile() throws Exception {
        int year = newOpenYear();
        String scf = activeTemplate(2, "SCF-" + year);
        String sce = activeTemplate(3, "SCE-" + year);

        // Revenue collected in cash, an expense paid, and an equity contribution received.
        postThroughWorkflow(voucher("STANDARD", LocalDate.of(year, 2, 3), List.of(
                line(account("PLC-1102"), "1000.00", "0"), line(account("PLC-4203"), "0", "1000.00"))));
        postThroughWorkflow(voucher("STANDARD", LocalDate.of(year, 2, 10), utilitiesLines("300.00")));
        postThroughWorkflow(voucher("STANDARD", LocalDate.of(year, 3, 1), List.of(
                line(account("PLC-1102"), "500.00", "0"), line(account("PLC-3101"), "0", "500.00"))));
        String mar = period(year, 3).getId().toString();

        JsonNode cf = statement(scf, mar);
        assertThat(cf.get("statementType").asText()).isEqualTo("SCF");
        assertThat(amount(cf, "COLL")).isEqualTo("1000.00");
        assertThat(amount(cf, "PAYS")).isEqualTo("-300.00");
        assertThat(amount(cf, "NOP")).isEqualTo("700.00");
        assertThat(amount(cf, "EQF")).isEqualTo("500.00");
        assertThat(amount(cf, "OTHER")).isEqualTo("0.00");
        assertThat(amount(cf, "NET")).isEqualTo("1200.00");
        // Opening cash plus the year's net movement equals closing cash.
        assertThat(cf.get("balanced").asBoolean()).isTrue();
        assertThat(new BigDecimal(amount(cf, "CLOSE")).subtract(new BigDecimal(amount(cf, "OPEN")))).isEqualByComparingTo("1200.00");
        assertThat(cf.get("unclassified")).isEmpty();
        assertThat(cf.get("placeholderData").asBoolean()).isTrue();

        // February alone: the equity contribution of March is not yet in.
        JsonNode feb = statement(scf, period(year, 2).getId().toString());
        assertThat(amount(feb, "NET")).isEqualTo("700.00");
        assertThat(amount(feb, "EQF")).isEqualTo("0.00");

        JsonNode eq = statement(sce, mar);
        assertThat(amount(eq, "SUR")).isEqualTo("700.00");
        assertThat(amount(eq, "OTH")).isEqualTo("500.00");
        assertThat(amount(eq, "CLOSED")).isEqualTo("0.00");
        assertThat(new BigDecimal(amount(eq, "END")).subtract(new BigDecimal(amount(eq, "OPEN")))).isEqualByComparingTo("1200.00");
        assertThat(eq.get("balanced").asBoolean()).isTrue();

        // A template that classifies only collections lists every other part of the change instead of dropping it.
        TemplateRequest partial = new TemplateRequest("SCF-PART-" + year, "Collections only", "SCF", null, List.of(
                new TemplateLineRequest("COLL", "Collections", "MOVEMENT", List.of("PLC-SFP-CASH"), List.of(), 1, 0, List.of("PLC-SFPERF-REV"))));
        activate(created(approver, partial).get("id").asText());
        JsonNode p = statement("SCF-PART-" + year, mar);
        assertThat(amount(p, "COLL")).isEqualTo("1000.00");
        assertThat(p.get("unclassified").toString()).contains("PLC-SFPERF-EXP", "-300.00", "PLC-SFP-EQ", "500.00");
        assertThat(p.get("notes").toString()).contains("not classified");

        // The export carries the statement like any other.
        JsonNode job = body(mvc.perform(post("/api/v1/report-jobs").header("Authorization", approver.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(java.util.Map.of("reportType", "FINANCIAL_STATEMENT", "format", "CSV", "parameters",
                        java.util.Map.of("template", scf, "periodId", mar))))).andExpect(status().isCreated()).andReturn());
        jobs.processPending();
        String csv = new String(mvc.perform(get("/api/v1/report-jobs/" + job.get("id").asText() + "/file").header("Authorization", approver.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).contains("Cash Flow Statement", "Collections from customers and other revenue", "1000.00", "1200.00");
    }

    @Test
    void movementTemplatesAreValidated() throws Exception {
        String suffix = "-" + System.nanoTime() % 100000;
        refused(new TemplateRequest("V1" + suffix, "x", "SCF", null, List.of(
                new TemplateLineRequest("A", "Cash", "LINE", List.of("PLC-SFP-CASH"), List.of(), 1, 0))), "not LINE");
        refused(new TemplateRequest("V2" + suffix, "x", "SFPERF", null, List.of(
                new TemplateLineRequest("A", "Cash", "MOVEMENT", List.of("PLC-SFP-CASH"), List.of(), 1, 0))), "movement statements");
        refused(new TemplateRequest("V3" + suffix, "x", "SCF", null, List.of(
                cash("A", List.of("PLC-SFPERF-REV")), cash("B", List.of("PLC-SFPERF-REV")))), "counted twice");
        refused(new TemplateRequest("V4" + suffix, "x", "SCF", null, List.of(
                cash("A", List.of()), cash("B", List.of("PLC-SFPERF-REV")))), "counted twice");
        refused(new TemplateRequest("V5" + suffix, "x", "SCF", null, List.of(
                cash("A", List.of("*", "PLC-SFPERF-REV")))), "stands alone");
        refused(new TemplateRequest("V6" + suffix, "x", "SCF", null, List.of(
                cash("A", List.of("PLC-SFP-CASH")))), "one of its own codes");
        refused(new TemplateRequest("V7" + suffix, "x", "SCF", null, List.of(
                new TemplateLineRequest("A", "Cash", "OPENING", List.of(), List.of(), 1, 0))), "balance it shows");
    }

    // ------------------------------------------------------------------ helpers

    private static TemplateLineRequest cash(String code, List<String> counterparts) {
        return new TemplateLineRequest(code, code, "MOVEMENT", List.of("PLC-SFP-CASH"), List.of(), 1, 0, counterparts);
    }

    private void refused(TemplateRequest t, String message) throws Exception {
        JsonNode r = body(mvc.perform(post("/api/v1/report-templates").header("Authorization", approver.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(t))).andExpect(status().isBadRequest()).andReturn());
        assertThat(r.toString()).contains(message);
    }

    private String activeTemplate(int index, String code) throws Exception {
        TemplateRequest base = DevPayablesSeeder.placeholderTemplates().get(index);
        activate(created(approver, new TemplateRequest(code, base.name(), base.statementType(), base.balanceCheck(), base.lines()))
                .get("id").asText());
        return code;
    }

    private JsonNode created(Session who, Object body) throws Exception {
        return body(mvc.perform(post("/api/v1/report-templates").header("Authorization", who.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(body))).andExpect(status().isCreated()).andReturn());
    }

    private void activate(String id) throws Exception {
        mvc.perform(post("/api/v1/report-templates/" + id + "/activate").header("Authorization", poster.bearer())).andExpect(status().isOk());
    }

    private JsonNode statement(String template, String periodId) throws Exception {
        return body(mvc.perform(get("/api/v1/financial-statements").param("template", template).param("periodId", periodId)
                .header("Authorization", approver.bearer())).andExpect(status().isOk()).andReturn());
    }

    private static String amount(JsonNode statement, String code) {
        for (JsonNode l : statement.get("lines")) {
            if (l.get("code").asText().equals(code)) {
                return l.get("current").asText();
            }
        }
        throw new AssertionError(code);
    }
}
