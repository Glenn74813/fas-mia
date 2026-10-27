package com.miaa.cas.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.audit.Actor;
import com.miaa.cas.generalledger.AccountMappingService;
import com.miaa.cas.tax.TaxCodeService;
import com.miaa.cas.tax.TaxCodeService.TaxCodeRequest;
import com.miaa.cas.treasury.BankAccountService;
import com.miaa.cas.treasury.BankAccountService.BankAccountRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

/** Adds payables configuration (placeholder tax codes, AP mapping, bank account) and AP/treasury roles. */
public abstract class AbstractPayablesIT extends AbstractAccountingIT {

    @Autowired protected TaxCodeService taxCodes;
    @Autowired protected AccountMappingService mappings;
    @Autowired protected BankAccountService banks;

    protected Session disbursementOfficer;
    protected Session financeManager;
    protected Session cashier;
    protected Session budgetOfficer;

    @BeforeEach
    void payablesFixture() throws Exception {
        tx.executeWithoutResult(s -> {
            if (jdbc.queryForObject("SELECT count(*) FROM tax_codes", Integer.class) == 0) {
                taxCodes.create(new TaxCodeRequest("T-EWT-1", "Test EWT 1%", "EWT", new BigDecimal("0.0100"),
                        account("PLC-2201")), true, Actor.SYSTEM);
                taxCodes.create(new TaxCodeRequest("T-EWT-2", "Test EWT 2%", "EWT", new BigDecimal("0.0200"),
                        account("PLC-2201")), true, Actor.SYSTEM);
                taxCodes.create(new TaxCodeRequest("T-VAT-IN", "Test input VAT 12%", "VAT_INPUT", new BigDecimal("0.1200"),
                        account("PLC-1204")), true, Actor.SYSTEM);
                mappings.set("AP_TRADE_PAYABLE", account("PLC-2101"), Actor.SYSTEM);
                banks.create(new BankAccountRequest("T-BANK-1", "Test bank", "Disbursements", "1234", account("PLC-1102")),
                        Actor.SYSTEM);
            }
        });
        disbursementOfficer = login(createUser("dvprep", "DISBURSEMENT_OFFICER"));
        financeManager = login(createUser("fm", "FINANCE_MANAGER"));
        cashier = login(createUser("cash", "CASHIER"));
        budgetOfficer = login(createUser("bgtcert", "BUDGET_OFFICER"));
    }

    /** The Budget Division's first step on a submitted DV (TOR Annex A): certified against the COB. */
    protected JsonNode budgetCertify(String dvId, long version) throws Exception {
        return ok(apAction(budgetOfficer, "disbursement-vouchers", dvId, "approve", version, "Covered by the COB"));
    }

    /** A second person approves the check the cashier prepared, so it can be released. */
    protected JsonNode approveCheck(String dvId, long version) throws Exception {
        return ok(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                "/api/v1/disbursement-vouchers/" + dvId + "/check-approval").header("Authorization", financeManager.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(java.util.Map.of("version", version)))));
    }

    protected UUID taxCode(String code) {
        return jdbc.queryForObject("SELECT id FROM tax_codes WHERE code = ?", UUID.class, code);
    }

    protected UUID bank() {
        return jdbc.queryForObject("SELECT id FROM bank_accounts WHERE code = 'T-BANK-1'", UUID.class);
    }

    protected JsonNode createSupplier(boolean vatRegistered) throws Exception {
        String code = "SUP" + System.nanoTime() % 10_000_000;
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", "Supplier " + code);
        body.put("vatRegistered", vatRegistered);
        body.put("defaultEwtCodeId", taxCode("T-EWT-1"));
        return body(mvc.perform(post("/api/v1/suppliers").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(body))).andExpect(status().isCreated()).andReturn());
    }

    protected Map<String, Object> invoice(String supplierId, String number, LocalDate date, List<Map<String, Object>> lines,
            boolean vat) {
        Map<String, Object> m = new HashMap<>();
        m.put("supplierId", supplierId);
        m.put("invoiceNumber", number);
        m.put("invoiceDate", date.toString());
        m.put("dueDate", date.plusDays(30).toString());
        m.put("accountingDate", date.toString());
        m.put("description", "Invoice " + number);
        m.put("vatCodeId", vat ? taxCode("T-VAT-IN") : null);
        m.put("lines", lines);
        return m;
    }

    protected Map<String, Object> invoiceLine(String accountCode, String amount) {
        Map<String, Object> m = new HashMap<>();
        m.put("accountId", account(accountCode));
        m.put("costCenterId", costCenter("PLC-CC-FIN"));
        m.put("netAmount", amount);
        return m;
    }

    protected ResultActions apAction(Session who, String path, String id, String action, long version, String comment)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("version", version);
        body.put("comment", comment);
        return mvc.perform(post("/api/v1/" + path + "/" + id + "/" + action).header("Authorization", who.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(body)));
    }

    protected void attach(Session who, String path, String id) throws Exception {
        mvc.perform(multipart("/api/v1/" + path + "/" + id + "/documents")
                .file(new MockMultipartFile("file", "support.pdf", "application/pdf", PDF))
                .header("Authorization", who.bearer())).andExpect(status().isCreated());
    }

    /** Registers, documents, submits, approves and posts an invoice; returns the posted view. */
    protected JsonNode postedInvoice(String supplierId, String number, LocalDate date, String amount) throws Exception {
        JsonNode inv = body(mvc.perform(post("/api/v1/accounts-payable/invoices").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(invoice(supplierId, number, date, List.of(invoiceLine("PLC-5203", amount)), false))))
                .andExpect(status().isCreated()).andReturn());
        String id = inv.get("id").asText();
        attach(preparer, "accounts-payable/invoices", id);
        inv = ok(apAction(preparer, "accounts-payable/invoices", id, "submit", inv.get("version").asLong(), null));
        inv = ok(apAction(approver, "accounts-payable/invoices", id, "approve", inv.get("version").asLong(), null));
        return ok(apAction(poster, "accounts-payable/invoices", id, "post", inv.get("version").asLong(), null));
    }
}
