package com.miaa.cas.accountspayable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.support.AbstractPayablesIT;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

/** BIR Form 2307: a draft until the supplier, organisation and tax code are complete, then a clean certificate. */
class Form2307IT extends AbstractPayablesIT {

    private MockHttpServletResponse form(Session who, String supplierId, int year) throws Exception {
        return mvc.perform(get("/api/v1/accounts-payable/suppliers/" + supplierId + "/form-2307").param("year", String.valueOf(year))
                .param("quarter", "1").header("Authorization", who.bearer())).andReturn().getResponse();
    }

    private static String text(byte[] pdf) throws Exception {
        try (PdfReader reader = new PdfReader(pdf)) {
            return new PdfTextExtractor(reader).getTextFromPage(1);
        }
    }

    private JsonNode row(int year, String supplierId) throws Exception {
        JsonNode schedule = body(mvc.perform(get("/api/v1/accounts-payable/withholding-schedule").param("year", String.valueOf(year))
                .param("quarter", "1").header("Authorization", approver.bearer())).andExpect(status().isOk()).andReturn());
        for (JsonNode r : schedule.get("rows")) {
            if (r.get("supplierId").asText().equals(supplierId)) {
                return r;
            }
        }
        throw new AssertionError("Supplier not in the schedule");
    }

    @Test
    void draftUntilEveryDetailIsThenACleanCertificate() throws Exception {
        int year = newOpenYear();
        JsonNode supplier = createSupplier(true);
        String id = supplier.get("id").asText();
        postedInvoice(id, "INV-" + System.nanoTime(), LocalDate.of(year, 3, 10), "10000.00");

        // Nothing but a name yet: the schedule says what is missing and the form is a draft.
        assertThat(row(year, id).get("missing").toString()).contains("supplier TIN", "supplier address", "supplier ZIP code", "supplier type", "ATC of the tax code");
        MockHttpServletResponse draft = form(approver, id, year);
        assertThat(draft.getStatus()).isEqualTo(200);
        assertThat(draft.getContentType()).isEqualTo(MediaType.APPLICATION_PDF_VALUE);
        assertThat(draft.getHeader("Content-Disposition")).contains("-DRAFT.pdf");
        assertThat(text(draft.getContentAsByteArray())).contains(supplier.get("name").asText(), "10,000.00", "100.00", "DRAFT");

        // A ZIP code that is not four digits is refused.
        Map<String, Object> details = new HashMap<>();
        details.put("version", supplier.get("version").asLong());
        details.put("name", supplier.get("name").asText());
        details.put("vatRegistered", true);
        details.put("defaultEwtCodeId", supplier.get("defaultEwtCodeId").asText());
        details.put("status", "ACTIVE");
        details.put("tin", "123-456-" + String.format("%03d", System.nanoTime() % 1000) + "-" + String.format("%05d", System.nanoTime() % 100000));
        details.put("address", "1 Rizal Avenue, Pasay City");
        details.put("payeeType", "CORPORATION");
        details.put("zipCode", "13");
        String supplierUrl = "/api/v1/suppliers/" + id;
        mvc.perform(put(supplierUrl).header("Authorization", preparer.bearer()).contentType(MediaType.APPLICATION_JSON).content(toJson(details)))
                .andExpect(status().isBadRequest());
        details.put("zipCode", "1300");
        mvc.perform(put(supplierUrl).header("Authorization", preparer.bearer()).contentType(MediaType.APPLICATION_JSON).content(toJson(details)))
                .andExpect(status().isOk());

        Session chief = login(createUser("chief2307", "CHIEF_ACCOUNTANT"));
        String atcUrl = "/api/v1/tax-codes/" + taxCode("T-EWT-1") + "/atc";
        mvc.perform(put(atcUrl).header("Authorization", chief.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("atc", "not-an-atc")))).andExpect(status().isBadRequest());
        mvc.perform(put(atcUrl).header("Authorization", cashier.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("atc", "wc158")))).andExpect(status().isForbidden());
        mvc.perform(put(atcUrl).header("Authorization", chief.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("atc", "XX999")))).andExpect(status().isUnprocessableEntity());
        mvc.perform(put(atcUrl).header("Authorization", chief.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("atc", "wi158")))).andExpect(status().isOk());
        assertThat(row(year, id).get("missing").toString()).contains("WI158 is not");
        mvc.perform(put(atcUrl).header("Authorization", chief.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("atc", "wc158")))).andExpect(status().isOk());

        List<Map<String, Object>> saved = new ArrayList<>(jdbc.queryForList("SELECT id, tin, address, zip_code FROM organizations"));
        jdbc.update("UPDATE organizations SET tin = '987-654-321', address = '2 Ayala Avenue, Makati City', zip_code = '1226'");
        try {
            assertThat(row(year, id).get("missing")).isEmpty();
            MockHttpServletResponse clean = form(approver, id, year);
            assertThat(clean.getHeader("Content-Disposition")).doesNotContain("DRAFT");
            assertThat(text(clean.getContentAsByteArray())).contains("WC158", "1 Rizal Avenue", "2 Ayala Avenue").doesNotContain("DRAFT");
        } finally {
            for (Map<String, Object> org : saved) {
                jdbc.update("UPDATE organizations SET tin = ?, address = ?, zip_code = ? WHERE id = ?", org.get("tin"), org.get("address"),
                        org.get("zip_code"), org.get("id"));
            }
            jdbc.update("UPDATE tax_codes SET atc = NULL WHERE code = 'T-EWT-1'");
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'BIR_2307_GENERATED' AND resource_id = ?",
                Integer.class, supplier.get("code").asText())).isEqualTo(2);

        // The whole quarter as a ZIP, with this supplier's certificate in it.
        byte[] zip = mvc.perform(get("/api/v1/accounts-payable/form-2307").param("year", String.valueOf(year)).param("quarter", "1")
                .header("Authorization", approver.bearer())).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                names.add(e.getName());
            }
        }
        assertThat(names).anyMatch(n -> n.startsWith("BIR2307-" + supplier.get("code").asText() + "-" + year + "Q1"));
    }

    @Test
    void theCatalogueHoldsExactlyTheCodesOnTheForm() throws Exception {
        Set<String> onForm = new TreeSet<>();
        try (InputStream in = getClass().getResourceAsStream("/forms/bir-2307-jan-2018.pdf"); PdfReader reader = new PdfReader(in)) {
            Matcher m = Pattern.compile("W[A-Z][0-9]{3}").matcher(new PdfTextExtractor(reader).getTextFromPage(2));
            while (m.find()) {
                onForm.add(m.group());
            }
        }
        JsonNode catalogue = body(mvc.perform(get("/api/v1/tax-codes/atc-catalogue").header("Authorization", approver.bearer()))
                .andExpect(status().isOk()).andReturn());
        Set<String> inCatalogue = new TreeSet<>();
        for (JsonNode a : catalogue) {
            inCatalogue.add(a.get("atc").asText());
            if (a.get("atc").asText().startsWith("WI")) {
                assertThat(a.get("payeeType").asText()).isEqualTo("INDIVIDUAL");
            } else if (a.get("atc").asText().startsWith("WC")) {
                assertThat(a.get("payeeType").asText()).isEqualTo("CORPORATION");
            }
        }
        assertThat(inCatalogue).hasSize(133).isEqualTo(onForm);
    }

    @Test
    void aSupplierWithoutWithholdingHasNoCertificate() throws Exception {
        int year = newOpenYear();
        assertThat(form(approver, createSupplier(true).get("id").asText(), year).getStatus()).isBetween(400, 499);
    }

    @Test
    void needsPayablesAccess() throws Exception {
        Session outsider = login(createUser("nowht", "SUPPORT_AGENT"));
        assertThat(form(outsider, createSupplier(true).get("id").asText(), newOpenYear()).getStatus()).isEqualTo(403);
    }
}
