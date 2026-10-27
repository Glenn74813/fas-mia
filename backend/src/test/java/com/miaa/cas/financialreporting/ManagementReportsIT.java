package com.miaa.cas.financialreporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.support.AbstractPayablesIT;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class ManagementReportsIT extends AbstractPayablesIT {

    @Autowired ReportJobService jobs;

    @Test
    void managementReportsExportWithTheirModulePermissions() throws Exception {
        int year = newOpenYear();
        UUID fy = period(year, 1).getFiscalYearId();
        Session budgetOfficer = login(createUser("bo", "BUDGET_OFFICER"));
        approvedBudget(budgetOfficer, fy, "10000.00");
        String supplier = createSupplier(false).get("id").asText();
        JsonNode inv = postedInvoice(supplier, "MR-" + year, LocalDate.of(year, 3, 1), "2000.00");
        String supplierCode = jdbc.queryForObject("SELECT code FROM suppliers WHERE id = ?::uuid", String.class, supplier);

        // Budget vs actual: allotment 10,000, actual 2,000 posted to the same account and cost centre.
        String bva = done(request(financeManager, "BUDGET_VS_ACTUAL", "CSV", Map.of("fiscalYearId", fy.toString())));
        String csv = text(financeManager, bva);
        assertThat(csv).contains("PLC-5203").contains("10000.00,0.00,2000.00,8000.00,20.0");

        // AP aging as XLSX: the open payable appears under its supplier.
        String aging = done(request(preparer, "AP_AGING", "XLSX", Map.of("asOf", year + "-03-15")));
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(file(preparer, aging)))) {
            Double total = null;
            for (Row r : wb.getSheetAt(0)) {
                if (r.getCell(0) != null && r.getCell(0).getStringCellValue().startsWith(supplierCode)) {
                    total = r.getCell(6).getNumericCellValue();
                }
            }
            assertThat(total).isEqualTo(Double.parseDouble(inv.get("payableAmount").asText()));
        }
        assertThat(done(request(approver, "PPE_SCHEDULE", "PDF", Map.of("from", year + "-01-01", "to", year + "-12-31")))).isNotBlank();
        assertThat(done(request(approver, "INVENTORY_STOCK", "CSV", Map.of()))).isNotBlank();

        // Each report needs its own module's permission.
        request(disbursementOfficer, "AR_AGING", "CSV", Map.of("asOf", year + "-03-15")).andExpect(status().isForbidden());
        request(preparer, "AUDIT_TRAIL", "CSV", Map.of("from", year + "-01-01", "to", year + "-12-31")).andExpect(status().isForbidden());
        // (Budget officers read payables since TOR T4, to certify DVs; a support agent has no payables access.)
        request(login(createUser("rsupport", "SUPPORT_AGENT")), "AP_AGING", "CSV", Map.of("asOf", "not-a-date")).andExpect(status().isForbidden());
        request(preparer, "AP_AGING", "CSV", Map.of("asOf", "not-a-date")).andExpect(status().isBadRequest());
    }

    @Test
    void auditTrailExportCarriesRowHashesAndChainVerification() throws Exception {
        Session auditor = login(createUser("ia", "INTERNAL_AUDITOR"));
        String today = LocalDate.now(java.time.ZoneId.of("Asia/Manila")).toString();
        String id = done(request(auditor, "AUDIT_TRAIL", "CSV", Map.of("from", today, "to", today, "action", "REPORT_REQUESTED")));
        String csv = text(auditor, id);
        assertThat(csv).contains("Hash chain verified intact at export");
        // The request for this very export is in it, with its row hash.
        String row = csv.lines().filter(l -> l.contains("\"REPORT_REQUESTED\"") && l.contains(id)).findFirst().orElseThrow();
        assertThat(row).containsPattern("\"[0-9a-f]{64}\"$");
        String sec = done(request(auditor, "AUDIT_TRAIL", "XLSX", Map.of("from", today, "to", today, "table", "SECURITY")));
        assertThat(file(auditor, sec)).isNotEmpty();
        // The export itself is audited, and only the requester can fetch it.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'REPORT_REQUESTED' AND resource_id = ?",
                Integer.class, id)).isEqualTo(1);
        mvc.perform(get("/api/v1/report-jobs/" + id + "/file").header("Authorization", approver.bearer())).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions request(Session who, String type, String format, Map<String, String> params) throws Exception {
        return mvc.perform(post("/api/v1/report-jobs").header("Authorization", who.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("reportType", type, "format", format, "parameters", params))));
    }

    private String done(ResultActions requested) throws Exception {
        String id = body(requested.andExpect(status().isCreated()).andReturn()).get("id").asText();
        jobs.processPending();
        String status = jdbc.queryForObject("SELECT status || coalesce(': ' || error, '') FROM report_jobs WHERE id = ?::uuid", String.class, id);
        assertThat(status).isEqualTo("DONE");
        return id;
    }

    private byte[] file(Session who, String id) throws Exception {
        return mvc.perform(get("/api/v1/report-jobs/" + id + "/file").header("Authorization", who.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }

    private String text(Session who, String id) throws Exception {
        return new String(file(who, id), StandardCharsets.UTF_8);
    }

    private void approvedBudget(Session budgetOfficer, UUID fy, String amount) throws Exception {
        JsonNode b = body(mvc.perform(post("/api/v1/budgets").header("Authorization", budgetOfficer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("fiscalYearId", fy, "name", "COB"))))
                .andExpect(status().isCreated()).andReturn());
        String bid = b.get("id").asText();
        Map<String, Object> alloc = new HashMap<>();
        alloc.put("accountId", account("PLC-5203"));
        alloc.put("costCenterId", costCenter("PLC-CC-FIN"));
        alloc.put("amount", amount);
        b = ok(mvc.perform(put("/api/v1/budgets/" + bid + "/allocations").header("Authorization", budgetOfficer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", b.get("version").asLong(), "allocations", List.of(alloc))))));
        b = ok(mvc.perform(post("/api/v1/budgets/" + bid + "/submit").header("Authorization", budgetOfficer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", b.get("version").asLong())))));
        ok(mvc.perform(post("/api/v1/budgets/" + bid + "/approve").header("Authorization", financeManager.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", b.get("version").asLong())))));
    }
}
