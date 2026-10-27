package com.miaa.cas.integrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.audit.Actor;
import com.miaa.cas.fixedassets.AssetSetupService;
import com.miaa.cas.fixedassets.FaDtos.CategoryRequest;
import com.miaa.cas.support.AbstractAccountingIT;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

/** Inbound interfaces (TOR §4.6.2, Annex D): files become batches; accepting one creates drafts; nothing is posted. */
class InboundInterfacesIT extends AbstractAccountingIT {

    @Autowired AssetSetupService assetSetup;

    @Test
    void aPayrollCsvBecomesADraftVoucherOnlyWhenAccepted() throws Exception {
        int year = newOpenYear();
        String csv = "account_code,cost_center_code,description,debit,credit\r\n"
                + "PLC-5201,PLC-CC-FIN,Salaries,\"125,000.00\",\r\n"
                + "PLC-1102,,Net pay,,125000.00\r\n";
        JsonNode b = body(sendCsv(preparer, "PAYROLL", csv, "PR-" + year + "-01", "period=" + year + "-01").andExpect(status().isCreated()).andReturn());
        assertThat(b.get("status").asText()).isEqualTo("VALIDATED");
        assertThat(b.get("rowCount").asInt()).isEqualTo(2);
        String id = b.get("id").asText();
        // The same file is taken once.
        sendCsv(preparer, "PAYROLL", csv, "PR-" + year + "-01", "period=" + year + "-01").andExpect(status().isConflict());

        JsonNode accepted = body(decide(preparer, id, "accept", Map.of("version", 0)).andExpect(status().isOk()).andReturn());
        assertThat(accepted.get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(accepted.get("resultType").asText()).isEqualTo("JOURNAL_VOUCHER");
        String voucher = accepted.get("resultIds").get(0).asText();
        JsonNode v = body(mvc.perform(get("/api/v1/journal-vouchers/" + voucher).header("Authorization", preparer.bearer())).andReturn());
        assertThat(v.get("status").asText()).isEqualTo("DRAFT");
        assertThat(v.get("accountingDate").asText()).isEqualTo(year + "-01-31");
        assertThat(new java.math.BigDecimal(v.get("totalDebit").asText())).isEqualByComparingTo("125000.00");
        assertThat(v.get("supportingReference").asText()).isEqualTo("Payroll interface PR-" + year + "-01");
        decide(preparer, id, "accept", Map.of("version", 1)).andExpect(status().isConflict());
    }

    @Test
    void invalidFilesAreKeptWithTheirProblemsAndCanBeRejected() throws Exception {
        String csv = "account_code,debit,credit\nNO-SUCH,100.00,\nPLC-1102,,90.00\n";
        JsonNode b = body(sendCsv(preparer, "PAYROLL", csv, "PR-BAD-" + System.nanoTime(), "accountingDate=2101-01-31")
                .andExpect(status().isCreated()).andReturn());
        assertThat(b.get("status").asText()).isEqualTo("INVALID");
        assertThat(b.get("errors").toString()).contains("NO-SUCH does not exist").contains("do not balance");
        decide(preparer, b.get("id").asText(), "accept", Map.of("version", 0)).andExpect(status().isConflict());
        assertThat(body(decide(preparer, b.get("id").asText(), "reject", Map.of("version", 0, "note", "Wrong accounts")).andReturn())
                .get("status").asText()).isEqualTo("REJECTED");
        // Someone without the interface's permission cannot send it.
        sendCsv(login(createUser("ifsupport", "SUPPORT_AGENT")), "PAYROLL", csv, "X", "").andExpect(status().isForbidden());
    }

    @Test
    void anFmisBudgetInJsonSetsTheDraftBudgetsAllocations() throws Exception {
        int year = newOpenYear();
        Session officer = login(createUser("ifbudget", "BUDGET_OFFICER"));
        String file = toJson(Map.of("reference", "FMIS-" + year, "fiscalYear", year, "lines", List.of(
                Map.of("accountCode", "PLC-5203", "costCenterCode", "PLC-CC-FIN", "amount", "500000.00"),
                Map.of("accountCode", "PLC-5201", "amount", "120000.00"))));
        JsonNode b = body(mvc.perform(post("/api/v1/interfaces/FMIS_BUDGET/batches").header("Authorization", officer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(file)).andExpect(status().isCreated()).andReturn());
        assertThat(b.get("status").asText()).as(b.get("errors").toString()).isEqualTo("VALIDATED");
        JsonNode accepted = body(decide(officer, b.get("id").asText(), "accept", Map.of("version", 0)).andExpect(status().isOk()).andReturn());
        JsonNode budget = body(mvc.perform(get("/api/v1/budgets/" + accepted.get("resultIds").get(0).asText())
                .header("Authorization", officer.bearer())).andReturn());
        assertThat(budget.get("status").asText()).isEqualTo("DRAFT");
        assertThat(new java.math.BigDecimal(budget.get("totalAllotment").asText())).isEqualByComparingTo("620000.00");
    }

    @Test
    void assetsInXmlBecomeDraftAssetsAndHostileXmlIsRefused() throws Exception {
        String cat = "T-IF-" + System.nanoTime() % 100000;
        tx.executeWithoutResult(s -> assetSetup.createCategory(new CategoryRequest(cat, "Interface test equipment", account("PLC-1402"),
                account("PLC-1409"), account("PLC-5301"), 36, null), true, Actor.SYSTEM));
        Session accountant = login(createUser("ifassets", "ACCOUNTANT"));
        String xml = "<assets reference=\"PROP-" + cat + "\"><offsetAccountCode>PLC-4203</offsetAccountCode>"
                + "<asset><categoryCode>" + cat + "</categoryCode><description>Baggage scanner</description><serialNumber>SN-1</serialNumber>"
                + "<acquisitionDate>2101-03-01</acquisitionDate><inServiceDate>2101-03-15</inServiceDate><cost>2500000.00</cost></asset>"
                + "<asset categoryCode=\"" + cat + "\" description=\"Radio set\" acquisitionDate=\"2101-03-01\" inServiceDate=\"2101-03-01\" cost=\"45000.00\"/>"
                + "</assets>";
        JsonNode b = body(mvc.perform(post("/api/v1/interfaces/ASSETS/batches").header("Authorization", accountant.bearer())
                .contentType(MediaType.APPLICATION_XML).content(xml)).andExpect(status().isCreated()).andReturn());
        assertThat(b.get("status").asText()).as(b.get("errors").toString()).isEqualTo("VALIDATED");
        assertThat(b.get("rowCount").asInt()).isEqualTo(2);
        JsonNode accepted = body(decide(accountant, b.get("id").asText(), "accept", Map.of("version", 0)).andExpect(status().isOk()).andReturn());
        assertThat(accepted.get("resultIds")).hasSize(2);
        assertThat(jdbc.queryForList("SELECT status FROM fixed_assets WHERE description IN ('Baggage scanner', 'Radio set') AND category_id = "
                + "(SELECT id FROM asset_categories WHERE code = ?)", String.class, cat)).hasSize(2).doesNotContain("ACTIVE");

        // An external entity is refused, not resolved.
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE a [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><assets reference=\"X\">"
                + "<asset><description>&x;</description></asset></assets>";
        JsonNode bad = body(mvc.perform(post("/api/v1/interfaces/ASSETS/batches").header("Authorization", accountant.bearer())
                .contentType(MediaType.APPLICATION_XML).content(xxe)).andExpect(status().isCreated()).andReturn());
        assertThat(bad.get("status").asText()).isEqualTo("INVALID");
        assertThat(bad.get("errors").toString()).contains("DOCTYPE").doesNotContain("root:");
    }

    @Test
    void anApiClientMaySendAFileButOnlyAPersonAcceptsIt() throws Exception {
        Session chief = login(createUser("ifchief", "CHIEF_ACCOUNTANT"));
        JsonNode created = body(mvc.perform(post("/api/v1/api-clients").header("Authorization", chief.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("name", "Payroll system", "scopes", List.of("INTERFACE_SUBMIT")))))
                .andExpect(status().isCreated()).andReturn());
        String token = json.readTree(mvc.perform(post("/api/v1/auth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "client_credentials").param("client_id", created.at("/client/clientId").asText())
                .param("client_secret", created.get("clientSecret").asText())).andReturn().getResponse().getContentAsString())
                .get("access_token").asText();
        Session client = new Session(token, null);
        String csv = "account_code,debit,credit\nPLC-5201,10.00,\nPLC-1102,,10.00\n";
        JsonNode b = body(sendCsv(client, "PAYROLL", csv, "PR-API-" + System.nanoTime(), "accountingDate=2101-01-31")
                .andExpect(status().isCreated()).andReturn());
        assertThat(b.get("receivedBy").asText()).startsWith("api:cas_");
        decide(client, b.get("id").asText(), "accept", Map.of("version", 0)).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE link = '/integrations/inbound' AND body LIKE ?", Long.class,
                "%" + b.get("sourceReference").asText() + "%")).isPositive();
        decide(preparer, b.get("id").asText(), "accept", Map.of("version", 0)).andExpect(status().isOk());

        // The screens upload the same way.
        mvc.perform(multipart("/api/v1/interfaces/PAYROLL/batches/upload").file(new MockMultipartFile("file", "payroll.txt", "text/plain",
                csv.getBytes(StandardCharsets.UTF_8))).header("Authorization", preparer.bearer())).andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions sendCsv(Session who, String type, String csv, String reference, String extra) throws Exception {
        String q = "?sourceReference=" + reference + (extra.isEmpty() ? "" : "&" + extra);
        return mvc.perform(post("/api/v1/interfaces/" + type + "/batches" + q).header("Authorization", who.bearer())
                .contentType("text/csv").content(csv));
    }

    private ResultActions decide(Session who, String id, String action, Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/v1/interfaces/batches/" + id + "/" + action).header("Authorization", who.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(body)));
    }
}
