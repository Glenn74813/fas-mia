package com.miaa.cas.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.audit.Actor;
import com.miaa.cas.tax.TaxCodeService.TaxCodeRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.MediaType;

/** Adds receivables configuration (output VAT code, AR control-account mappings) and AR helpers. */
public abstract class AbstractReceivablesIT extends AbstractPayablesIT {

    @BeforeEach
    void receivablesFixture() {
        tx.executeWithoutResult(s -> {
            if (jdbc.queryForObject("SELECT count(*) FROM tax_codes WHERE code = 'T-VAT-OUT'", Integer.class) == 0) {
                taxCodes.create(new TaxCodeRequest("T-VAT-OUT", "Test output VAT 12%", "VAT_OUTPUT", new BigDecimal("0.1200"),
                        account("PLC-2202")), true, Actor.SYSTEM);
                mappings.set("AR_TRADE_RECEIVABLE", account("PLC-1201"), Actor.SYSTEM);
                mappings.set("CASH_ON_HAND", account("PLC-1101"), Actor.SYSTEM);
                mappings.set("UNAPPLIED_COLLECTIONS", account("PLC-2303"), Actor.SYSTEM);
                mappings.set("CREDITABLE_WHT", account("PLC-1205"), Actor.SYSTEM);
            }
        });
    }

    protected JsonNode createCustomer(String creditLimit) throws Exception {
        return createCustomer(creditLimit, null);
    }

    protected JsonNode createCustomerNamed(String name) throws Exception {
        return createCustomer(null, name);
    }

    private JsonNode createCustomer(String creditLimit, String name) throws Exception {
        String code = "CUS" + System.nanoTime() % 10_000_000;
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", name == null ? "Concessionaire " + code : name);
        body.put("customerType", "CONCESSIONAIRE");
        body.put("tin", "123-456-789-00000");
        body.put("creditLimit", creditLimit);
        body.put("paymentTermsDays", 30);
        return body(mvc.perform(post("/api/v1/customers").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(body))).andExpect(status().isCreated()).andReturn());
    }

    protected Map<String, Object> billing(String kind, String customerId, LocalDate date, String accountCode, String amount,
            boolean vat, String relatedInvoiceId) {
        Map<String, Object> line = new HashMap<>();
        line.put("revenueAccountId", account(accountCode));
        line.put("costCenterId", costCenter("PLC-CC-OPS"));
        line.put("description", "Charge " + accountCode);
        line.put("netAmount", amount);
        Map<String, Object> m = new HashMap<>();
        m.put("kind", kind);
        m.put("customerId", customerId);
        m.put("relatedInvoiceId", relatedInvoiceId);
        m.put("documentDate", date.toString());
        m.put("description", kind + " " + amount);
        m.put("vatCodeId", vat ? taxCode("T-VAT-OUT") : null);
        m.put("lines", List.of(line));
        return m;
    }

    protected JsonNode createBilling(Map<String, Object> body) throws Exception {
        return body(mvc.perform(post("/api/v1/accounts-receivable/billings").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(body))).andExpect(status().isCreated()).andReturn());
    }

    /** Prepares, submits, approves and posts a billing document; memos get a justification attached. */
    protected JsonNode postedBilling(Map<String, Object> body) throws Exception {
        JsonNode d = createBilling(body);
        String id = d.get("id").asText();
        if (!"INVOICE".equals(body.get("kind"))) {
            attach(preparer, "accounts-receivable/billings", id);
        }
        d = ok(apAction(preparer, "accounts-receivable/billings", id, "submit", d.get("version").asLong(), null));
        d = ok(apAction(approver, "accounts-receivable/billings", id, "approve", d.get("version").asLong(), null));
        return ok(apAction(poster, "accounts-receivable/billings", id, "post", d.get("version").asLong(), null));
    }

    protected Map<String, Object> receipt(String customerId, LocalDate date, String amount, String tax,
            List<Map<String, Object>> allocations) {
        Map<String, Object> m = new HashMap<>();
        m.put("customerId", customerId);
        m.put("receiptDate", date.toString());
        m.put("paymentMethod", "CHECK");
        m.put("paymentReference", "CHK-" + System.nanoTime() % 1_000_000);
        m.put("amount", amount);
        m.put("taxWithheld", tax);
        m.put("allocations", allocations);
        return m;
    }

    protected static Map<String, Object> alloc(String documentId, String amount) {
        return Map.of("documentId", documentId, "amount", amount);
    }
}
