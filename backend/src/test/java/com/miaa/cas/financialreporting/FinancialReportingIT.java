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
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class FinancialReportingIT extends AbstractAccountingIT {

    @Autowired ReportJobService jobs;

    @Test
    void statementsFollowTheTemplateBalanceAndExport() throws Exception {
        int year = newOpenYear();
        String sfp = activeTemplate(0, "SFP-" + year);
        String perf = activeTemplate(1, "PERF-" + year);

        postThroughWorkflow(voucher("STANDARD", LocalDate.of(year, 2, 3), List.of(
                line(account("PLC-1102"), "1000.00", "0"), line(account("PLC-4203"), "0", "1000.00"))));
        postThroughWorkflow(voucher("STANDARD", LocalDate.of(year, 2, 10), utilitiesLines("300.00")));
        String feb = period(year, 2).getId().toString();

        // Performance: exactly this year's activity.
        JsonNode p = statement(perf, feb);
        assertThat(amount(p, "REV")).isEqualTo("1000.00");
        assertThat(amount(p, "EXP")).isEqualTo("300.00");
        assertThat(amount(p, "NET")).isEqualTo("700.00");
        assertThat(p.get("unmapped")).isEmpty();
        assertThat(p.get("placeholderData").asBoolean()).isTrue();

        // Position: assets equal liabilities and equity because the ledger balances; unclosed results are in equity.
        JsonNode s = statement(sfp, feb);
        assertThat(s.get("balanced").asBoolean()).isTrue();
        assertThat(amount(s, "TA")).isEqualTo(amount(s, "TLE"));
        assertThat(s.get("unmapped")).isEmpty();

        // A template that leaves a statement line code out shows the amounts it would drop.
        TemplateRequest partial = new TemplateRequest("PART-" + year, "Revenue only", "SFPERF", null, List.of(
                new TemplateLineRequest("REV", "Revenue", "LINE", List.of("PLC-SFPERF-REV"), List.of(), -1, 0)));
        String partialId = created(post("/api/v1/report-templates"), approver, partial).get("id").asText();
        ok(mvc.perform(post("/api/v1/report-templates/" + partialId + "/activate").header("Authorization", poster.bearer())));
        JsonNode u = statement("PART-" + year, feb);
        assertThat(u.get("unmapped").toString()).contains("PLC-5201").contains("300.00");

        // Templates are validated: no double counting, and a position statement needs its surplus line.
        TemplateRequest twice = new TemplateRequest("BAD-" + year, "Bad", "SFPERF", null, List.of(
                new TemplateLineRequest("A", "A", "LINE", List.of("PLC-SFPERF-REV"), List.of(), -1, 0),
                new TemplateLineRequest("B", "B", "LINE", List.of("PLC-SFPERF-REV"), List.of(), -1, 0)));
        mvc.perform(post("/api/v1/report-templates").header("Authorization", approver.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(twice))).andExpect(status().isBadRequest());
        TemplateRequest noSurplus = new TemplateRequest("BAD2-" + year, "Bad", "SFP", null, List.of(
                new TemplateLineRequest("A", "Cash", "LINE", List.of("PLC-SFP-CASH"), List.of(), 1, 0)));
        mvc.perform(post("/api/v1/report-templates").header("Authorization", approver.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(noSurplus))).andExpect(status().isBadRequest());

        // Exports run as background jobs; only the requester may download.
        mvc.perform(post("/api/v1/report-jobs").header("Authorization", preparer.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(job("FINANCIAL_STATEMENT", "CSV", perf, feb)))).andExpect(status().isForbidden());
        String csvJob = created(post("/api/v1/report-jobs"), approver, job("FINANCIAL_STATEMENT", "CSV", sfp, feb)).get("id").asText();
        String xlsxJob = created(post("/api/v1/report-jobs"), approver, job("FINANCIAL_STATEMENT", "XLSX", perf, feb)).get("id").asText();
        String pdfJob = created(post("/api/v1/report-jobs"), approver, job("FINANCIAL_STATEMENT", "PDF", sfp, feb)).get("id").asText();
        String tbJob = created(post("/api/v1/report-jobs"), approver, job("TRIAL_BALANCE", "CSV", null, feb)).get("id").asText();
        jobs.processPending();
        for (String id : List.of(csvJob, xlsxJob, pdfJob, tbJob)) {
            JsonNode j = body(mvc.perform(get("/api/v1/report-jobs/" + id).header("Authorization", approver.bearer()))
                    .andExpect(status().isOk()).andReturn());
            assertThat(j.get("status").asText()).as(id).isEqualTo("DONE");
            assertThat(j.get("sha256").asText()).hasSize(64);
        }
        String csv = new String(download(csvJob), StandardCharsets.UTF_8);
        assertThat(csv).contains("Total liabilities and equity").contains("PLACEHOLDER");
        assertThat(new String(download(pdfJob), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(new String(download(tbJob), StandardCharsets.UTF_8)).contains("The trial balance is balanced.");
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(download(xlsxJob)))) {
            Sheet sh = wb.getSheetAt(0);
            Double revenue = null;
            for (Row r : sh) {
                if (r.getCell(0) != null && "Revenue".equals(r.getCell(0).getStringCellValue())) {
                    revenue = r.getCell(1).getNumericCellValue();
                }
            }
            assertThat(revenue).isEqualTo(1000.0);
        }
        mvc.perform(get("/api/v1/report-jobs/" + csvJob + "/file").header("Authorization", poster.bearer())).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'REPORT_DOWNLOADED' AND resource_id = ?",
                Integer.class, csvJob)).isEqualTo(1);
    }

    @Test
    void activationNeedsASecondAdministrator() throws Exception {
        TemplateRequest t = new TemplateRequest("SOD-" + System.nanoTime() % 100000, "Revenue", "SFPERF", null, List.of(
                new TemplateLineRequest("REV", "Revenue", "LINE", List.of("PLC-SFPERF-REV"), List.of(), -1, 0)));
        String id = created(post("/api/v1/report-templates"), approver, t).get("id").asText();
        mvc.perform(post("/api/v1/report-templates/" + id + "/activate").header("Authorization", approver.bearer()))
                .andExpect(status().isForbidden());
        JsonNode v = ok(mvc.perform(post("/api/v1/report-templates/" + id + "/activate").header("Authorization", poster.bearer())));
        assertThat(v.get("status").asText()).isEqualTo("ACTIVE");
        // A new version supersedes it only once activated.
        String v2 = created(post("/api/v1/report-templates"), approver, t).get("id").asText();
        ok(mvc.perform(post("/api/v1/report-templates/" + v2 + "/activate").header("Authorization", poster.bearer())));
        assertThat(body(mvc.perform(get("/api/v1/report-templates/" + id).header("Authorization", approver.bearer())).andReturn())
                .get("status").asText()).isEqualTo("RETIRED");
    }

    // ------------------------------------------------------------------ helpers

    private String activeTemplate(int index, String code) throws Exception {
        TemplateRequest base = DevPayablesSeeder.placeholderTemplates().get(index);
        TemplateRequest t = new TemplateRequest(code, base.name(), base.statementType(), base.balanceCheck(), new ArrayList<>(base.lines()));
        String id = created(post("/api/v1/report-templates"), approver, t).get("id").asText();
        ok(mvc.perform(post("/api/v1/report-templates/" + id + "/activate").header("Authorization", poster.bearer())));
        return code;
    }

    private JsonNode created(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, Session who, Object body)
            throws Exception {
        return body(mvc.perform(req.header("Authorization", who.bearer()).contentType(MediaType.APPLICATION_JSON).content(toJson(body)))
                .andExpect(status().isCreated()).andReturn());
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

    private static Map<String, Object> job(String type, String format, String template, String periodId) {
        Map<String, String> p = new java.util.HashMap<>();
        p.put("periodId", periodId);
        if (template != null) {
            p.put("template", template);
        }
        return Map.of("reportType", type, "format", format, "parameters", p);
    }

    private byte[] download(String id) throws Exception {
        return mvc.perform(get("/api/v1/report-jobs/" + id + "/file").header("Authorization", approver.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }
}
