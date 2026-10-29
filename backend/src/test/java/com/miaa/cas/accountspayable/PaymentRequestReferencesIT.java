package com.miaa.cas.accountspayable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.support.AbstractPayablesIT;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** C-32: Request for Payment and RIAR references are recorded on invoices and DVs, edited, searched and returned. */
class PaymentRequestReferencesIT extends AbstractPayablesIT {

    @Test
    void invoicesAndVouchersCarryTheirRequestForPaymentAndRiarReferences() throws Exception {
        int year = newOpenYear();
        String supplierId = createSupplier(false).get("id").asText();
        String number = "INV-RFP-" + System.nanoTime() % 1_000_000;
        Map<String, Object> body = invoice(supplierId, number, LocalDate.of(year, 4, 2), List.of(invoiceLine("PLC-5203", "800.00")), false);
        body.put("rfpReference", " RFP-2026-0101 ");
        body.put("riarReference", "RIAR-77");
        JsonNode inv = body(mvc.perform(post("/api/v1/accounts-payable/invoices").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(body))).andExpect(status().isCreated()).andReturn());
        assertThat(inv.get("rfpReference").asText()).isEqualTo("RFP-2026-0101");
        assertThat(inv.get("riarReference").asText()).isEqualTo("RIAR-77");

        // Editing can change or clear them; blank means none.
        body.put("version", inv.get("version").asLong());
        body.put("rfpReference", "RFP-2026-0102");
        body.put("riarReference", "  ");
        inv = body(mvc.perform(put("/api/v1/accounts-payable/invoices/" + inv.get("id").asText() + "?version=" + inv.get("version").asLong())
                .header("Authorization", preparer.bearer()).contentType(MediaType.APPLICATION_JSON).content(toJson(body)))
                .andExpect(status().isOk()).andReturn());
        assertThat(inv.get("rfpReference").asText()).isEqualTo("RFP-2026-0102");
        assertThat(inv.get("riarReference").isNull()).isTrue();

        // The invoice list finds the invoice by its Request for Payment number.
        JsonNode found = body(mvc.perform(get("/api/v1/accounts-payable/invoices").param("q", "rfp-2026-0102")
                .header("Authorization", preparer.bearer())).andExpect(status().isOk()).andReturn());
        assertThat(found.get("items").findValuesAsText("invoiceNumber")).contains(number);

        // A DV records the Request for Payment that asked for it.
        JsonNode posted = postedInvoice(supplierId, number + "-P", LocalDate.of(year, 4, 3), "500.00");
        Map<String, Object> dv = new HashMap<>();
        dv.put("supplierId", supplierId);
        dv.put("particulars", "Payment per request for payment");
        dv.put("allocations", List.of(Map.of("invoiceId", posted.get("id").asText(), "amount", posted.get("payableAmount").asText())));
        dv.put("rfpReference", "RFP-2026-0200");
        JsonNode created = body(mvc.perform(post("/api/v1/disbursement-vouchers").header("Authorization", disbursementOfficer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(dv))).andExpect(status().isCreated()).andReturn());
        assertThat(created.get("rfpReference").asText()).isEqualTo("RFP-2026-0200");

        dv.put("version", created.get("version").asLong());
        dv.put("rfpReference", "RFP-2026-0201");
        JsonNode updated = body(mvc.perform(put("/api/v1/disbursement-vouchers/" + created.get("id").asText())
                .header("Authorization", disbursementOfficer.bearer()).contentType(MediaType.APPLICATION_JSON).content(toJson(dv)))
                .andExpect(status().isOk()).andReturn());
        assertThat(updated.get("rfpReference").asText()).isEqualTo("RFP-2026-0201");

        // Longer than the column allows: refused, not truncated.
        body.put("rfpReference", "X".repeat(61));
        body.put("invoiceNumber", number + "-LONG");
        mvc.perform(post("/api/v1/accounts-payable/invoices").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(body))).andExpect(status().isBadRequest());
    }
}
