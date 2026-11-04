package com.miaa.cas.tax;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.support.AbstractReceivablesIT;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

/** BIR lists: the QAP and 1604-E files in the RMC 7-2021 structures, the SAWT from customers' certificates, and the SLSP. */
class BirAlphalistIT extends AbstractReceivablesIT {

    private MockHttpServletResponse fetch(Session who, String path, int year, Integer quarter) throws Exception {
        var req = get("/api/v1/bir/" + path).param("year", String.valueOf(year)).header("Authorization", who.bearer());
        if (quarter != null) {
            req = req.param("quarter", String.valueOf(quarter));
        }
        return mvc.perform(req).andReturn().getResponse();
    }

    private JsonNode list(Session who, String path, int year, Integer quarter) throws Exception {
        MockHttpServletResponse r = fetch(who, path, year, quarter);
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        return json.readTree(r.getContentAsString());
    }

    private static JsonNode find(JsonNode rows, String field, String value) {
        for (JsonNode r : rows) {
            if (r.get(field).asText().equals(value)) {
                return r;
            }
        }
        throw new AssertionError(value + " is not listed");
    }

    private static java.math.BigDecimal dec(JsonNode n) {
        return new java.math.BigDecimal(n.asText());
    }

    private static List<String> lines(MockHttpServletResponse r) {
        return List.of(new String(r.getContentAsByteArray(), StandardCharsets.US_ASCII).split("\r\n"));
    }

    private static String cellText(byte[] xlsx, String sheet) throws Exception {
        StringBuilder all = new StringBuilder();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            DataFormatter f = new DataFormatter();
            for (Row row : wb.getSheet(sheet)) {
                row.forEach(c -> all.append(f.formatCellValue(c)).append('|'));
            }
        }
        return all.toString();
    }

    @Test
    void qapAndAnnualAlphalistFollowTheBirFileStructure() throws Exception {
        int year = newOpenYear();
        JsonNode supplier = createSupplier(false);
        String id = supplier.get("id").asText();
        postedInvoice(id, "INV-" + System.nanoTime(), LocalDate.of(year, 2, 10), "10000.00");

        JsonNode qap = list(approver, "qap", year, 1);
        assertThat(find(qap.get("rows"), "supplierId", id).get("missing").toString()).contains("TIN", "supplier type", "ATC of the tax code");
        assertThat(qap.get("ready").asBoolean()).isFalse();
        assertThat(fetch(approver, "qap.dat", year, 1).getStatus()).isBetween(400, 499);

        String tin = "111-222-" + String.format("%03d", System.nanoTime() % 1000);
        Map<String, Object> details = new HashMap<>();
        details.put("version", supplier.get("version").asLong());
        details.put("name", supplier.get("name").asText());
        details.put("vatRegistered", false);
        details.put("defaultEwtCodeId", supplier.get("defaultEwtCodeId").asText());
        details.put("status", "ACTIVE");
        details.put("tin", tin);
        details.put("payeeType", "INDIVIDUAL");
        supplier = body(mvc.perform(put("/api/v1/suppliers/" + id).header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(details))).andExpect(status().isOk()).andReturn());
        assertThat(find(list(approver, "qap", year, 1).get("rows"), "supplierId", id).get("missing").toString())
                .contains("last and first name").doesNotContain("TIN");
        details.put("version", supplier.get("version").asLong());
        details.put("lastName", "Dela Cruz");
        details.put("firstName", "Juan");
        details.put("middleName", "Santos");
        mvc.perform(put("/api/v1/suppliers/" + id).header("Authorization", preparer.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(details))).andExpect(status().isOk());

        List<Map<String, Object>> saved = jdbc.queryForList("SELECT id, tin, rdo_code FROM organizations");
        jdbc.update("UPDATE tax_codes SET atc = 'WI158' WHERE code = 'T-EWT-1'");
        jdbc.update("UPDATE organizations SET tin = '987-654-321', rdo_code = '039'");
        try {
            String org = jdbc.queryForObject("SELECT name FROM organizations WHERE status = 'ACTIVE' ORDER BY code LIMIT 1", String.class);
            String payee = tin.replace("-", "");
            qap = list(approver, "qap", year, 1);
            assertThat(qap.get("ready").asBoolean()).as(qap.toString()).isTrue();

            MockHttpServletResponse dat = fetch(approver, "qap.dat", year, 1);
            assertThat(dat.getStatus()).isEqualTo(200);
            assertThat(dat.getHeader("Content-Disposition")).contains("9876543210000" + "03" + year + "1601EQ.DAT");
            assertThat(lines(dat)).containsExactly(
                    "HQAP,H1601EQ,987654321,0000,\"" + org + "\",03/" + year + ",039",
                    "D1,1601EQ,1," + payee + ",0000,\"\",\"Dela Cruz\",\"Juan\",\"Santos\",03/" + year + ",WI158,1.00,10000.00,100.00",
                    "C1,1601EQ,987654321,0000,03/" + year + ",10000.00,100.00");

            MockHttpServletResponse annual = fetch(approver, "1604e.dat", year, null);
            assertThat(annual.getHeader("Content-Disposition")).contains("9876543210000" + "1231" + year + "1604E.DAT");
            assertThat(lines(annual)).containsExactly(
                    "H1604E,987654321,0000,12/31/" + year,
                    "D3,1604E,987654321,0000,12/31/" + year + ",1," + payee + ",0000,\"\",\"Dela Cruz\",\"Juan\",\"Santos\",WI158,10000.00,1.00,100.00",
                    "C3,1604E,987654321,0000,12/" + year + ",100.00");

            MockHttpServletResponse xlsx = fetch(approver, "qap.xlsx", year, 1);
            assertThat(xlsx.getStatus()).isEqualTo(200);
            assertThat(cellText(xlsx.getContentAsByteArray(), "1601EQ")).contains("Dela Cruz", "WI158", "10,000.00", "Total (1 entry)");
        } finally {
            for (Map<String, Object> o : saved) {
                jdbc.update("UPDATE organizations SET tin = ?, rdo_code = ? WHERE id = ?", o.get("tin"), o.get("rdo_code"), o.get("id"));
            }
            jdbc.update("UPDATE tax_codes SET atc = NULL WHERE code = 'T-EWT-1'");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'BIR_LIST_GENERATED' AND resource_id LIKE ?",
                Integer.class, "%" + year)).isEqualTo(3);

        // The same invoice on the summary list of purchases: no VAT code, so exempt or non-VAT.
        JsonNode slp = find(list(approver, "slp", year, 1).get("rows"), "partyId", id);
        assertThat(dec(slp.get("exempt"))).isEqualByComparingTo("10000.00");
        assertThat(dec(slp.get("taxable"))).isEqualByComparingTo("0");
        assertThat(slp.get("documents").asInt()).isEqualTo(1);
    }

    @Test
    void sawtListsTheCertificatesCustomersGave() throws Exception {
        int year = newOpenYear();
        String cid = createCustomer(null).get("id").asText();
        String inv = postedBilling(billing("INVOICE", cid, LocalDate.of(year, 4, 1), "PLC-4201", "10000.00", true, null)).get("id").asText();
        JsonNode rc = body(mvc.perform(post("/api/v1/receipts").header("Authorization", cashier.bearer()).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(receipt(cid, LocalDate.of(year, 4, 20), "11000.00", "200.00", List.of(alloc(inv, "11200.00"))))))
                .andExpect(status().isCreated()).andReturn());
        String rid = rc.get("id").asText();
        assertThat(rc.get("availableActions").toString()).contains("CERTIFICATE");
        ok(mvc.perform(post("/api/v1/receipts/" + rid + "/post").header("Authorization", poster.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", rc.get("version").asLong())))));

        JsonNode sawt = list(approver, "sawt", year, 2);
        assertThat(sawt.get("rows")).isEmpty();
        assertThat(dec(find(sawt.get("uncertified"), "receiptId", rid).get("taxWithheld"))).isEqualByComparingTo("200.00");

        String url = "/api/v1/receipts/" + rid + "/withholding-certificate";
        Map<String, Object> cert = new HashMap<>(Map.of("atc", "XX999", "incomePayment", "10000.00", "receivedOn", LocalDate.of(year, 5, 5).toString()));
        assertThat(mvc.perform(put(url).header("Authorization", cashier.bearer()).contentType(MediaType.APPLICATION_JSON).content(toJson(cert)))
                .andReturn().getResponse().getStatus()).isBetween(400, 499);
        cert.put("atc", "WC158");
        cert.put("incomePayment", "150.00");
        assertThat(mvc.perform(put(url).header("Authorization", cashier.bearer()).contentType(MediaType.APPLICATION_JSON).content(toJson(cert)))
                .andReturn().getResponse().getStatus()).isBetween(400, 499);
        cert.put("incomePayment", "9000.00");
        mvc.perform(put(url).header("Authorization", cashier.bearer()).contentType(MediaType.APPLICATION_JSON).content(toJson(cert)))
                .andExpect(status().isOk());
        cert.put("atc", "WC160");
        cert.put("incomePayment", "10000.00");
        JsonNode view = body(mvc.perform(put(url).header("Authorization", cashier.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(cert))).andExpect(status().isOk()).andReturn());
        assertThat(view.get("certificate").get("atc").asText()).isEqualTo("WC160");
        assertThat(view.get("status").asText()).isEqualTo("POSTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'RECEIPT_2307_RECORDED' AND resource_id = ?",
                Integer.class, rid)).isEqualTo(2);

        sawt = list(approver, "sawt", year, 2);
        assertThat(sawt.get("uncertified")).isEmpty();
        JsonNode row = find(sawt.get("rows"), "customerId", cid);
        assertThat(row.get("atc").asText()).isEqualTo("WC160");
        assertThat(dec(row.get("incomePayment"))).isEqualByComparingTo("10000.00");
        assertThat(dec(row.get("taxWithheld"))).isEqualByComparingTo("200.00");
        assertThat(dec(row.get("taxRate"))).isEqualByComparingTo("0.02");
        assertThat(cellText(fetch(approver, "sawt.xlsx", year, 2).getContentAsByteArray(), "SAWT")).contains("WC160", "200.00");

        JsonNode sls = find(list(approver, "sls", year, 2).get("rows"), "partyId", cid);
        assertThat(dec(sls.get("taxable"))).isEqualByComparingTo("10000.00");
        assertThat(dec(sls.get("vat"))).isEqualByComparingTo("1200.00");
        assertThat(dec(sls.get("gross"))).isEqualByComparingTo("11200.00");

        Session outsider = login(createUser("nobir", "SUPPORT_AGENT"));
        assertThat(fetch(outsider, "sawt", year, 2).getStatus()).isEqualTo(403);
        assertThat(fetch(outsider, "qap", year, 2).getStatus()).isEqualTo(403);
    }
}
