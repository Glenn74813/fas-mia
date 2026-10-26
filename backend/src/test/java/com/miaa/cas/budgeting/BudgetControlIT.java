package com.miaa.cas.budgeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.support.AbstractPayablesIT;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/** Budget control with the production policy (BLOCK). */
@TestPropertySource(properties = "cas.budget.enforcement=BLOCK")
class BudgetControlIT extends AbstractPayablesIT {

    @Test
    void reservationsConsumptionAndPostingAreHeldWithinTheAllotment() throws Exception {
        int year = newOpenYear();
        Session budgetOfficer = login(createUser("bo", "BUDGET_OFFICER"));
        UUID fyId = period(year, 1).getFiscalYearId();

        // Without an approved budget, expenditure on budget-controlled accounts is refused.
        JsonNode noBudget = createVoucher(preparer, voucher("STANDARD", LocalDate.of(year, 1, 5), utilitiesLines("10.00")));
        attachPdf(preparer, noBudget.get("id").asText());
        action(preparer, noBudget.get("id").asText(), "submit", noBudget.get("version").asLong(), null).andExpect(status().isOk());

        JsonNode b = body(mvc.perform(post("/api/v1/budgets").header("Authorization", budgetOfficer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("fiscalYearId", fyId, "name", "COB " + year))))
                .andExpect(status().isCreated()).andReturn());
        String bid = b.get("id").asText();
        Map<String, Object> alloc = new HashMap<>();
        alloc.put("accountId", account("PLC-5203"));
        alloc.put("costCenterId", costCenter("PLC-CC-FIN"));
        alloc.put("amount", "10000.00");
        Map<String, Object> util = new HashMap<>(alloc);
        util.put("accountId", account("PLC-5201"));
        b = body(mvc.perform(put("/api/v1/budgets/" + bid + "/allocations").header("Authorization", budgetOfficer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", b.get("version").asLong(),
                        "allocations", List.of(alloc, util))))).andExpect(status().isOk()).andReturn());
        b = ok(budgetAction(budgetOfficer, bid, "submit", b.get("version").asLong()));
        budgetAction(budgetOfficer, bid, "approve", b.get("version").asLong()).andExpect(status().isForbidden());
        b = ok(budgetAction(financeManager, bid, "approve", b.get("version").asLong()));
        assertThat(b.get("status").asText()).isEqualTo("APPROVED");

        String supplier = createSupplier(false).get("id").asText();
        JsonNode first = submittedInvoice(supplier, "B-1", year, "6000.00");
        JsonNode second = createInvoice(supplier, "B-2", year, "5000.00");
        attach(preparer, "accounts-payable/invoices", second.get("id").asText());
        apAction(preparer, "accounts-payable/invoices", second.get("id").asText(), "submit", second.get("version").asLong(), null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations[0]").value(org.hamcrest.Matchers.containsString("available 4000.00, required 5000.00")));

        // Returning the first invoice releases its reservation; the second then fits.
        apAction(approver, "accounts-payable/invoices", first.get("id").asText(), "return", first.get("version").asLong(),
                "Wrong cost centre").andExpect(status().isOk());
        JsonNode s2 = ok(apAction(preparer, "accounts-payable/invoices", second.get("id").asText(), "submit",
                second.get("version").asLong(), null));
        s2 = ok(apAction(approver, "accounts-payable/invoices", s2.get("id").asText(), "approve", s2.get("version").asLong(), null));
        ok(apAction(poster, "accounts-payable/invoices", s2.get("id").asText(), "post", s2.get("version").asLong(), null));

        JsonNode view = body(mvc.perform(get("/api/v1/budgets/" + bid).header("Authorization", financeManager.bearer())).andReturn());
        JsonNode line = view.get("allocations").get(0).get("accountCode").asText().equals("PLC-5203")
                ? view.get("allocations").get(0) : view.get("allocations").get(1);
        assertThat(line.get("reserved").asText()).isEqualTo("0.00");
        assertThat(line.get("actual").asText()).isEqualTo("5000.00");
        assertThat(line.get("available").asText()).isEqualTo("5000.00");
        assertThat(line.get("utilizationPercent").asText()).isEqualTo("50.0");

        // A manual voucher that would overspend is refused at posting.
        JsonNode jev = createVoucher(preparer, voucher("STANDARD", LocalDate.of(year, 5, 5), List.of(
                line(account("PLC-5203"), costCenter("PLC-CC-FIN"), null, "6000.00", "0"),
                line(account("PLC-1102"), "0", "6000.00"))));
        String jid = jev.get("id").asText();
        attachPdf(preparer, jid);
        jev = ok(action(preparer, jid, "submit", jev.get("version").asLong(), null));
        jev = ok(action(reviewer, jid, "approve", jev.get("version").asLong(), null));
        jev = ok(action(approver, jid, "approve", jev.get("version").asLong(), null));
        postVoucher(poster, jid, jev.get("version").asLong(), "budget-" + UUID.randomUUID())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations[0]").value(org.hamcrest.Matchers.containsString("Insufficient budget")));

        // A supplemental adjustment, decided by a second person, makes room.
        String allocId = line.get("id").asText();
        JsonNode adj = body(mvc.perform(post("/api/v1/budgets/" + bid + "/adjustments").header("Authorization", budgetOfficer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("toAllocationId", allocId, "amount", "1000.00",
                        "reason", "Supplemental for repairs")))).andExpect(status().isCreated()).andReturn());
        mvc.perform(post("/api/v1/budgets/adjustments/" + adj.get("id").asText() + "/decision").header("Authorization", financeManager.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("approve", true)))).andExpect(status().isOk());
        ok(postVoucher(poster, jid, jev.get("version").asLong(), "budget-" + UUID.randomUUID()));
    }

    private org.springframework.test.web.servlet.ResultActions budgetAction(Session who, String id, String action, long version)
            throws Exception {
        return mvc.perform(post("/api/v1/budgets/" + id + "/" + action).header("Authorization", who.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", version))));
    }

    private JsonNode createInvoice(String supplier, String number, int year, String amount) throws Exception {
        return body(mvc.perform(post("/api/v1/accounts-payable/invoices").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(invoice(supplier, number, LocalDate.of(year, 3, 1), List.of(invoiceLine("PLC-5203", amount)), false))))
                .andExpect(status().isCreated()).andReturn());
    }

    private JsonNode submittedInvoice(String supplier, String number, int year, String amount) throws Exception {
        JsonNode inv = createInvoice(supplier, number, year, amount);
        attach(preparer, "accounts-payable/invoices", inv.get("id").asText());
        return ok(apAction(preparer, "accounts-payable/invoices", inv.get("id").asText(), "submit", inv.get("version").asLong(), null));
    }
}
