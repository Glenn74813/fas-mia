package com.miaa.cas.integrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import com.miaa.cas.support.AbstractAccountingIT;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

/**
 * A General Payroll (one row per employee, one column per earning or deduction) previewed and imported through the
 * column mapping. Made-up employees only.
 */
class GeneralPayrollIT extends AbstractAccountingIT {

    @Test
    void generalPayrollIsPreviewedAndBookedThroughTheColumnMapping() throws Exception {
        int year = newOpenYear();
        String run = String.valueOf(System.nanoTime() % 1_000_000);
        String e1 = "T" + run + "-1";
        String e2 = "T" + run + "-2";
        String csv = "Employee no.,Name,Position,Basic salary,PERA,Gross,Withholding tax,GSIS life and retirement premium,Overtime,"
                + "Total deductions,Net pay,Remarks\r\n"
                + e1 + ",\"Test, One\",Clerk,\"30,000.00\",2000,32000,1500.25,2700,500,4200.25,28299.75,\r\n"
                + e2 + ",\"Test, Two\",Analyst,45000,2000,47000,4000,4050,0,8050,38950,on leave 1 day\r\n"
                + ",TOTAL,,75000,4000,79000,5500.25,6750,500,12250.25,67249.75,\r\n";
        String date = year + "-03-15";
        String cc = costCenter("PLC-CC-FIN").toString();

        // The test chart is the placeholder one, so the chief accountant points the columns at its accounts first.
        mapping("Basic salary", "DEBIT", "PLC-5101");
        mapping("PERA", "DEBIT", "PLC-5102");
        mapping("Withholding tax", "CREDIT", "PLC-2201");
        mapping("GSIS life and retirement premium", "CREDIT", "PLC-2103");
        mapping("Net pay", "CREDIT", "PLC-2102");
        // Only the chief accountant (account administration) maintains the mapping.
        mvc.perform(put("/api/v1/payroll/column-mappings").header("Authorization", preparer.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("label", "Gross", "treatment", "IGNORE")))).andExpect(status().isForbidden());

        // Overtime is not mapped yet, the employees have no subsidiary accounts, and no cost centre is given.
        JsonNode p = body(preview(csv, date, null).andExpect(status().isOk()).andReturn());
        assertThat(p.get("problems").toString()).contains("Column \\\"Overtime\\\" is not in the column mapping")
                .contains("No active employee subsidiary account with code " + e1).contains("Choose a cost centre");

        mapping("Overtime", "DEBIT", "PLC-5101");
        for (String e : new String[] {e1, e2}) {
            mvc.perform(post("/api/v1/subsidiary-accounts").header("Authorization", approver.bearer()).contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(Map.of("slType", "EMPLOYEE", "code", e, "name", "Employee " + e)))).andExpect(status().isCreated());
        }
        p = body(preview(csv, date, cc).andExpect(status().isOk()).andReturn());
        assertThat(p.get("problems")).isEmpty();
        assertThat(p.get("employees").asInt()).isEqualTo(2);
        // Debits: salaries 75,000 + overtime 500 and PERA 4,000; credits: tax 5,500.25, GSIS 6,750, net pay per employee.
        assertThat(new BigDecimal(p.get("totalDebit").asText())).isEqualByComparingTo("79500.00");
        assertThat(new BigDecimal(p.get("totalCredit").asText())).isEqualByComparingTo("79500.00");
        assertThat(p.get("lines")).hasSize(6);
        assertThat(p.get("lines").toString()).contains("\"employeeNumber\":\"" + e1 + "\"").contains("\"amount\":\"28299.75\"");

        JsonNode imported = body(importFile(csv, date, cc, "PR-" + run).andExpect(status().isCreated()).andReturn());
        String voucherId = imported.get("journalVoucherId").asText();
        JsonNode v = body(mvc.perform(get("/api/v1/journal-vouchers/" + voucherId).header("Authorization", preparer.bearer()))
                .andExpect(status().isOk()).andReturn());
        assertThat(v.get("status").asText()).isEqualTo("DRAFT");
        assertThat(new BigDecimal(v.get("totalDebit").asText())).isEqualByComparingTo("79500.00");
        assertThat(v.get("documents")).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'PAYROLL_GENERAL_IMPORTED' AND resource_id = ?",
                Long.class, voucherId)).isEqualTo(1);

        // The same file cannot be booked twice.
        JsonNode again = body(importFile(csv, date, cc, null).andExpect(status().isBadRequest()).andReturn());
        assertThat(again.toString()).contains("already imported");
    }

    private void mapping(String label, String treatment, String accountCode) throws Exception {
        JsonNode all = body(mvc.perform(get("/api/v1/payroll/column-mappings").header("Authorization", approver.bearer()))
                .andExpect(status().isOk()).andReturn());
        Map<String, Object> req = new HashMap<>();
        req.put("label", label);
        req.put("treatment", treatment);
        req.put("accountId", accountCode == null ? null : account(accountCode));
        for (JsonNode m : all) {
            if (m.get("columnKey").asText().equals(label.toLowerCase())) {
                req.put("version", m.get("version").asLong());
            }
        }
        mvc.perform(put("/api/v1/payroll/column-mappings").header("Authorization", approver.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(req))).andExpect(status().isOk());
    }

    private ResultActions preview(String csv, String date, String costCenterId) throws Exception {
        var req = multipart("/api/v1/payroll/general-payroll/preview").file(file(csv)).param("accountingDate", date);
        if (costCenterId != null) {
            req.param("costCenterId", costCenterId);
        }
        return mvc.perform(req.header("Authorization", preparer.bearer()));
    }

    private ResultActions importFile(String csv, String date, String costCenterId, String reference) throws Exception {
        var req = multipart("/api/v1/payroll/general-payroll").file(file(csv)).param("accountingDate", date).param("costCenterId", costCenterId);
        if (reference != null) {
            req.param("reference", reference);
        }
        return mvc.perform(req.header("Authorization", preparer.bearer()));
    }

    private static MockMultipartFile file(String csv) {
        return new MockMultipartFile("file", "general-payroll-test.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
    }
}
