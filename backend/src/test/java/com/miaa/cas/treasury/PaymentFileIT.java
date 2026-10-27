package com.miaa.cas.treasury;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.support.AbstractPayablesIT;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class PaymentFileIT extends AbstractPayablesIT {

    @Test
    void verifiedBankDetailsAreRequiredAndTheFileInstructsAdaPayments() throws Exception {
        int year = newOpenYear();
        String supplier = createSupplier(false).get("id").asText();
        String dvId = approvedDv(supplier, year, "PF-1");

        // No bank account yet: the voucher is listed but blocked.
        assertThat(eligible(dvId).get("blockedReason").asText()).contains("no bank account");
        JsonNode acct = body(addBank(preparer, supplier, "0012-3456-7890").andExpect(status().isCreated()).andReturn());
        assertThat(acct.get("accountNumberMasked").asText()).isEqualTo("****7890");
        assertThat(acct.toString()).doesNotContain("001234567890");
        assertThat(eligible(dvId).get("blockedReason").asText()).contains("awaits verification");
        createBatch(cashier, year, dvId).andExpect(status().isUnprocessableEntity());

        // The person who entered the details cannot verify them; a verifier who cannot release payments does.
        Session both = login(createUser("both", "ACCOUNTANT", "CHIEF_ACCOUNTANT"));
        String byBoth = body(addBank(both, supplier, "5555-6666-7777").andExpect(status().isCreated()).andReturn()).get("id").asText();
        verify(both, byBoth).andExpect(status().isForbidden());
        // Entering new details withdrew the earlier, unverified ones.
        assertThat(jdbc.queryForObject("SELECT status FROM supplier_bank_accounts WHERE id = ?::uuid", String.class, acct.get("id").asText()))
                .isEqualTo("INACTIVE");
        ok(verify(approver, byBoth));
        assertThat(eligible(dvId).get("blockedReason").isNull()).isTrue();

        // The cashier issues the ADA instruction through the file; the DV now awaits release confirmation.
        createBatch(preparer, year, dvId).andExpect(status().isForbidden());
        JsonNode batch = body(createBatch(cashier, year, dvId).andExpect(status().isCreated()).andReturn());
        assertThat(batch.get("batchNumber").asText()).matches("PAY-" + year + "-\\d{6}");
        assertThat(batch.get("itemCount").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status || ' ' || payment_mode || ' ' || payment_reference FROM disbursement_vouchers "
                + "WHERE id = ?::uuid", String.class, dvId)).isEqualTo("PAYMENT_INSTRUCTED ADA " + batch.get("batchNumber").asText() + "-1");
        String file = new String(mvc.perform(get("/api/v1/payment-files/" + batch.get("id").asText() + "/file")
                .header("Authorization", cashier.bearer())).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(),
                StandardCharsets.UTF_8);
        assertThat(file).contains("CAS-GENERIC-PAYMENT-FILE").contains("\"555566667777\"").contains("Not a bank-specific layout");
        assertThat(jdbc.queryForObject("SELECT download_count FROM payment_batches WHERE id = ?::uuid", Integer.class,
                batch.get("id").asText())).isEqualTo(1);
        // The same voucher cannot be paid twice.
        createBatch(cashier, year, dvId).andExpect(status().isUnprocessableEntity());
        // Account numbers are stored encrypted.
        byte[] stored = jdbc.queryForObject("SELECT account_number_enc FROM supplier_bank_accounts WHERE id = ?::uuid", byte[].class, byBoth);
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("555566667777");
    }

    @Test
    void birEisChannelIsDisabledByDefault() throws Exception {
        JsonNode channels = body(mvc.perform(get("/api/v1/integrations/channels").header("Authorization", approver.bearer()))
                .andExpect(status().isOk()).andReturn());
        assertThat(channels.toString()).contains("BIR_EIS").contains("Disabled (awaiting BIR specification");
        mvc.perform(post("/api/v1/integrations/bir-eis/documents").header("Authorization", approver.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("documentType", "AR_BILLING", "documentId",
                        java.util.UUID.randomUUID())))).andExpect(status().is4xxClientError());
    }

    // ------------------------------------------------------------------ helpers

    private String approvedDv(String supplier, int year, String number) throws Exception {
        String inv = postedInvoice(supplier, number, LocalDate.of(year, 4, 1), "5000.00").get("id").asText();
        JsonNode dv = body(mvc.perform(post("/api/v1/disbursement-vouchers").header("Authorization", disbursementOfficer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("supplierId", supplier, "particulars", "Payment",
                        "allocations", List.of(Map.of("invoiceId", inv, "amount", "4950.00")))))).andExpect(status().isCreated()).andReturn());
        String id = dv.get("id").asText();
        attach(disbursementOfficer, "disbursement-vouchers", id);
        dv = ok(apAction(disbursementOfficer, "disbursement-vouchers", id, "submit", dv.get("version").asLong(), null));
        dv = budgetCertify(id, dv.get("version").asLong());
        dv = ok(apAction(reviewer, "disbursement-vouchers", id, "approve", dv.get("version").asLong(), "Certified"));
        ok(apAction(financeManager, "disbursement-vouchers", id, "approve", dv.get("version").asLong(), "Approved"));
        return id;
    }

    private JsonNode eligible(String dvId) throws Exception {
        JsonNode list = body(mvc.perform(get("/api/v1/payment-files/eligible").header("Authorization", cashier.bearer()))
                .andExpect(status().isOk()).andReturn());
        for (JsonNode e : list) {
            if (e.get("dvId").asText().equals(dvId)) {
                return e;
            }
        }
        throw new AssertionError("DV not listed");
    }

    private ResultActions addBank(Session who, String supplier, String number) throws Exception {
        return mvc.perform(post("/api/v1/suppliers/" + supplier + "/bank-accounts").header("Authorization", who.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("bankName", "Test Bank", "accountName", "Supplier Inc.",
                        "accountNumber", number))));
    }

    private ResultActions verify(Session who, String id) throws Exception {
        return mvc.perform(post("/api/v1/supplier-bank-accounts/" + id + "/verification").header("Authorization", who.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("approve", true, "note", "Confirmed by call-back to supplier"))));
    }

    private ResultActions createBatch(Session who, int year, String dvId) throws Exception {
        return mvc.perform(post("/api/v1/payment-files").header("Authorization", who.bearer()).header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("bankAccountId", bank(), "paymentDate", year + "-04-20", "dvIds", List.of(dvId)))));
    }
}
