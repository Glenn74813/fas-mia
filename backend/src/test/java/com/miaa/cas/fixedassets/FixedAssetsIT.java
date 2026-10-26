package com.miaa.cas.fixedassets;

import com.miaa.cas.generalledger.web.LedgerDtos.AccountRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.audit.Actor;
import com.miaa.cas.fixedassets.FaDtos.CategoryRequest;
import com.miaa.cas.support.AbstractPayablesIT;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * One scenario on purpose: depreciation runs cover every active asset in the database, so the whole
 * life cycle runs in a single fiscal year with assets only this test creates.
 */
class FixedAssetsIT extends AbstractPayablesIT {

    @Autowired AssetSetupService setup;

    @Test
    void assetLifeCycleFromAcquisitionToDisposalKeepsRegisterAndLedgerInStep() throws Exception {
        int year = newOpenYear();
        String catCode = "T-FA-" + System.nanoTime() % 100000;
        UUID category = tx.execute(s -> {
            mappings.set("FA_DISPOSAL_GAIN", account("PLC-4204"), Actor.SYSTEM);
            mappings.set("FA_DISPOSAL_LOSS", account("PLC-5302"), Actor.SYSTEM);
            return setup.createCategory(new CategoryRequest(catCode, "Test equipment", account("PLC-1402"), account("PLC-1409"),
                    account("PLC-5301"), 36, null), true, Actor.SYSTEM).id();
        });
        JsonNode reconBefore = recon();

        // Acquisition through a posted supplier invoice charged to the asset account.
        String supplier = createSupplier(false).get("id").asText();
        Map<String, Object> line = new HashMap<>();
        line.put("accountId", account("PLC-1402"));
        line.put("netAmount", "36000.00");
        JsonNode inv = body(mvc.perform(post("/api/v1/accounts-payable/invoices").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(invoice(supplier, "FA-" + catCode, LocalDate.of(year, 1, 10),
                        List.of(line), false)))).andExpect(status().isCreated()).andReturn());
        String invId = inv.get("id").asText();
        attach(preparer, "accounts-payable/invoices", invId);
        inv = ok(apAction(preparer, "accounts-payable/invoices", invId, "submit", inv.get("version").asLong(), null));
        inv = ok(apAction(approver, "accounts-payable/invoices", invId, "approve", inv.get("version").asLong(), null));
        ok(apAction(poster, "accounts-payable/invoices", invId, "post", inv.get("version").asLong(), null));

        register(asset(category, "INVOICE", invId, null, "36000.01", year + "-01-15")).andExpect(status().isUnprocessableEntity());
        JsonNode a1 = body(register(asset(category, "INVOICE", invId, null, "36000.00", year + "-01-15"))
                .andExpect(status().isCreated()).andReturn());
        assertThat(a1.get("status").asText()).isEqualTo("DRAFT");
        assertThat(a1.get("monthlyDepreciation").asText()).isEqualTo("1000.00");
        String a1Id = a1.get("id").asText();
        act(preparer, "fixed-assets", a1Id, "activate", a1).andExpect(status().isForbidden());
        a1 = ok(act(poster, "fixed-assets", a1Id, "activate", a1));
        assertThat(a1.get("assetNumber").asText()).matches("FA-" + year + "-\\d{6}");
        // Already in the ledger through the invoice: activation links to that entry, it does not post again.
        assertThat(a1.get("acquisitionEntryId").asText()).isEqualTo(
                jdbc.queryForObject("SELECT posted_entry_id::text FROM supplier_invoices WHERE id = ?::uuid", String.class, invId));
        // The same invoice cannot back a second asset.
        register(asset(category, "INVOICE", invId, null, "1.00", year + "-01-15")).andExpect(status().isUnprocessableEntity());

        // February: one month (depreciation starts the month after the asset is placed in service).
        JsonNode feb = postRun(year, 2);
        assertThat(lineFor(feb, a1Id).get("amount").asText()).isEqualTo("1000.00");
        JsonNode febEntry = entry(feb.get("postedEntryId").asText());
        assertThat(sumAccount(febEntry, "PLC-5301", "debit")).isEqualByComparingTo("1000.00");
        assertThat(sumAccount(febEntry, "PLC-1409", "credit")).isEqualByComparingTo("1000.00");
        mvc.perform(post("/api/v1/depreciation-runs").header("Authorization", preparer.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("periodId", period(year, 2).getId())))).andExpect(status().isConflict());
        // March is skipped; April catches up two months.
        JsonNode apr = postRun(year, 4);
        assertThat(lineFor(apr, a1Id).get("amount").asText()).isEqualTo("2000.00");

        // Sale in May for 30,000: NBV 33,000, loss 3,000.
        Map<String, Object> sale = new HashMap<>(Map.of("assetId", a1Id, "disposalDate", year + "-05-20", "method", "SALE",
                "proceeds", "30000.00", "proceedsAccountId", account("PLC-1102"), "reason", "Sold at public auction"));
        JsonNode d = body(mvc.perform(post("/api/v1/asset-disposals").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(sale))).andExpect(status().isCreated()).andReturn());
        assertThat(d.get("gainLoss").asText()).isEqualTo("-3000.00");
        act(preparer, "asset-disposals", d.get("id").asText(), "post", d).andExpect(status().isForbidden());
        d = ok(act(poster, "asset-disposals", d.get("id").asText(), "post", d));
        Map<String, BigDecimal> disposal = new HashMap<>();
        for (JsonNode l : entry(d.get("postedEntryId").asText()).get("lines")) {
            disposal.merge(l.get("accountCode").asText(), new BigDecimal(l.get("debit").asText()).subtract(new BigDecimal(l.get("credit").asText())),
                    BigDecimal::add);
        }
        assertThat(disposal.get("PLC-1409")).isEqualByComparingTo("3000.00");
        assertThat(disposal.get("PLC-1102")).isEqualByComparingTo("30000.00");
        assertThat(disposal.get("PLC-5302")).isEqualByComparingTo("3000.00");
        assertThat(disposal.get("PLC-1402")).isEqualByComparingTo("-36000.00");
        assertThat(body(mvc.perform(get("/api/v1/fixed-assets/" + a1Id).header("Authorization", preparer.bearer())).andReturn())
                .get("status").asText()).isEqualTo("DISPOSED");

        // Construction in progress: costs posted to the project, then capitalised.
        JsonNode project = body(mvc.perform(post("/api/v1/auc-projects").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("code", "P" + catCode, "name", "Terminal annex",
                        "aucAccountId", account("PLC-1410"))))).andExpect(status().isCreated()).andReturn());
        String projectId = project.get("id").asText();
        UUID projectSl = UUID.fromString(project.get("subsidiaryAccountId").asText());
        postThroughWorkflow(voucher("STANDARD", LocalDate.of(year, 2, 20), List.of(
                line(account("PLC-1410"), null, projectSl, "50000.00", "0"), line(account("PLC-1102"), "0", "50000.00"))));
        register(asset(category, "AUC", null, projectId, "50000.01", year + "-03-01")).andExpect(status().isUnprocessableEntity());
        JsonNode a2 = body(register(asset(category, "AUC", null, projectId, "50000.00", year + "-03-01"))
                .andExpect(status().isCreated()).andReturn());
        a2 = ok(act(poster, "fixed-assets", a2.get("id").asText(), "activate", a2));
        JsonNode cap = entry(a2.get("acquisitionEntryId").asText());
        assertThat(sumAccount(cap, "PLC-1402", "debit")).isEqualByComparingTo("50000.00");
        assertThat(sumAccount(cap, "PLC-1410", "credit")).isEqualByComparingTo("50000.00");
        JsonNode closed = ok(mvc.perform(post("/api/v1/auc-projects/" + projectId + "/close").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", project.get("version").asLong())))));
        assertThat(closed.get("status").asText()).isEqualTo("CLOSED");

        // A justified source is required for OTHER acquisitions.
        Map<String, Object> other = asset(category, "OTHER", null, null, "100.00", year + "-03-01");
        other.put("offsetAccountId", account("PLC-4203"));
        register(other).andExpect(status().isBadRequest());

        // A category whose asset and accumulated-depreciation accounts carry cost centres (test accounts, created here).
        String tc = "T" + System.nanoTime() % 1_000_000;
        UUID[] ccAccounts = tx.execute(s -> new UUID[] {
            accountService.create(new AccountRequest(tc + "-C", "Test cost-centre equipment", null, "ASSET", "DEBIT", "PLC-14", true, null, null,
                    "PLC-SFP-PPE", true, null, false, null)).id(),
            accountService.create(new AccountRequest(tc + "-A", "Test accumulated depreciation", null, "ASSET", "CREDIT", "PLC-14", true, null,
                    null, "PLC-SFP-PPE", true, null, false, null)).id()});
        UUID ccCategory = tx.execute(s -> setup.createCategory(new CategoryRequest("CC" + catCode, "Cost-centre equipment", ccAccounts[0],
                ccAccounts[1], account("PLC-5301"), 12, null), true, Actor.SYSTEM).id());
        Map<String, Object> a3Body = asset(ccCategory, "OTHER", null, null, "1200.00", year + "-03-01");
        a3Body.put("offsetAccountId", account("PLC-1102"));
        a3Body.put("justification", "Donated equipment received in March");
        a3Body.put("costCenterId", costCenter("PLC-CC-FIN"));
        a3Body.put("location", "Terminal 1");
        JsonNode a3 = body(register(a3Body).andExpect(status().isCreated()).andReturn());
        String a3Id = a3.get("id").asText();
        a3 = ok(act(poster, "fixed-assets", a3Id, "activate", a3));
        assertThat(entry(a3.get("acquisitionEntryId").asText()).toString()).contains("PLC-CC-FIN");

        // June: April to June caught up for the capitalised asset (50,000 / 36 = 1,388.89 a month).
        JsonNode jun = postRun(year, 6);
        assertThat(lineFor(jun, a2.get("id").asText()).get("amount").asText()).isEqualTo("4166.67");
        assertThat(jun.toString()).doesNotContain(a1Id);

        // Depreciation credits the accumulated account with the asset's cost centre when the account requires one.
        assertThat(lineFor(jun, a3Id).get("amount").asText()).isEqualTo("300.00");
        JsonNode junEntry = entry(jun.get("postedEntryId").asText());
        assertThat(junEntry.toString()).contains(tc + "-A");

        // Transfer to Operations in July: prepared by one person, posted by another; cost and accumulated depreciation follow.
        Map<String, Object> move = new HashMap<>(Map.of("transferDate", year + "-07-01", "toLocation", "Terminal 3",
                "reason", "Moved to the operations unit"));
        move.put("toCostCenterId", costCenter("PLC-CC-OPS"));
        Map<String, Object> same = new HashMap<>(Map.of("transferDate", year + "-07-01", "toLocation", "Terminal 1", "reason", "No change here"));
        same.put("toCostCenterId", costCenter("PLC-CC-FIN"));
        mvc.perform(post("/api/v1/fixed-assets/" + a3Id + "/transfers").header("Authorization", preparer.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(same))).andExpect(status().isUnprocessableEntity());
        JsonNode tr = body(mvc.perform(post("/api/v1/fixed-assets/" + a3Id + "/transfers").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(move))).andExpect(status().isCreated()).andReturn());
        // A pending transfer blocks a disposal and a second transfer.
        mvc.perform(post("/api/v1/asset-disposals").header("Authorization", preparer.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("assetId", a3Id, "disposalDate", year + "-07-15", "method", "SCRAP", "proceeds", "0", "reason", "Broken beyond repair"))))
                .andExpect(status().isUnprocessableEntity());
        act(preparer, "asset-transfers", tr.get("id").asText(), "post", tr).andExpect(status().isForbidden());
        tr = ok(act(poster, "asset-transfers", tr.get("id").asText(), "post", tr));
        assertThat(tr.get("status").asText()).isEqualTo("POSTED");
        JsonNode reclass = entry(jdbc.queryForObject("SELECT posted_entry_id::text FROM asset_transfers WHERE id = ?::uuid", String.class,
                tr.get("id").asText()));
        BigDecimal opsCost = BigDecimal.ZERO;
        BigDecimal finAcc = BigDecimal.ZERO;
        for (JsonNode l : reclass.get("lines")) {
            String acct = l.get("accountCode").asText();
            String cc = l.get("costCenterCode").asText();
            if (acct.equals(tc + "-C") && cc.equals("PLC-CC-OPS")) {
                opsCost = opsCost.add(new BigDecimal(l.get("debit").asText()));
            }
            if (acct.equals(tc + "-A") && cc.equals("PLC-CC-FIN")) {
                finAcc = finAcc.add(new BigDecimal(l.get("debit").asText()));
            }
        }
        assertThat(opsCost).isEqualByComparingTo("1200.00");
        assertThat(finAcc).isEqualByComparingTo("300.00");
        JsonNode moved = body(mvc.perform(get("/api/v1/fixed-assets/" + a3Id).header("Authorization", preparer.bearer())).andReturn());
        assertThat(moved.get("location").asText()).isEqualTo("Terminal 3");
        assertThat(body(mvc.perform(get("/api/v1/fixed-assets/" + a3Id + "/transfers").header("Authorization", preparer.bearer())).andReturn()))
                .hasSize(1);

        JsonNode sched = body(mvc.perform(get("/api/v1/fixed-assets/reports/ppe-schedule").param("from", year + "-01-01")
                .param("to", year + "-12-31").header("Authorization", preparer.bearer())).andExpect(status().isOk()).andReturn());
        JsonNode row = null;
        for (JsonNode r : sched.get("rows")) {
            if (catCode.equals(r.get("categoryCode").asText())) {
                row = r;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.get("additions").asText()).isEqualTo("86000.00");
        assertThat(row.get("disposalsCost").asText()).isEqualTo("36000.00");
        assertThat(row.get("closingCost").asText()).isEqualTo("50000.00");
        assertThat(row.get("depreciation").asText()).isEqualTo("7166.67");
        assertThat(row.get("disposalsAccumulated").asText()).isEqualTo("3000.00");
        assertThat(row.get("netBookValue").asText()).isEqualTo("45833.33");

        // Everything went through the register, so the register-to-ledger differences are unchanged.
        JsonNode reconAfter = recon();
        assertThat(diff(reconAfter, "PLC-1402")).isEqualByComparingTo(diff(reconBefore, "PLC-1402"));
        assertThat(diff(reconAfter, "PLC-1409")).isEqualByComparingTo(diff(reconBefore, "PLC-1409"));
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> asset(UUID category, String source, String invoiceId, String projectId, String cost, String inService) {
        Map<String, Object> m = new HashMap<>();
        m.put("categoryId", category);
        m.put("description", "Test asset " + source);
        m.put("acquisitionSource", source);
        m.put("supplierInvoiceId", invoiceId);
        m.put("aucProjectId", projectId);
        m.put("acquisitionDate", inService);
        m.put("inServiceDate", inService);
        m.put("cost", cost);
        return m;
    }

    private ResultActions register(Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/v1/fixed-assets").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(body)));
    }

    private ResultActions act(Session who, String path, String id, String action, JsonNode current) throws Exception {
        return mvc.perform(post("/api/v1/" + path + "/" + id + "/" + action).header("Authorization", who.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", current.get("version").asLong()))));
    }

    private JsonNode postRun(int year, int month) throws Exception {
        JsonNode run = body(mvc.perform(post("/api/v1/depreciation-runs").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("periodId", period(year, month).getId()))))
                .andExpect(status().isCreated()).andReturn());
        act(preparer, "depreciation-runs", run.get("id").asText(), "post", run).andExpect(status().isForbidden());
        return ok(act(poster, "depreciation-runs", run.get("id").asText(), "post", run));
    }

    private static JsonNode lineFor(JsonNode run, String assetId) {
        for (JsonNode l : run.get("lines")) {
            if (l.get("assetId").asText().equals(assetId)) {
                return l;
            }
        }
        throw new AssertionError("asset " + assetId + " not in run");
    }

    private JsonNode entry(String id) throws Exception {
        return body(mvc.perform(get("/api/v1/general-ledger/entries/" + id).header("Authorization", approver.bearer()))
                .andExpect(status().isOk()).andReturn());
    }

    private static BigDecimal sumAccount(JsonNode entry, String code, String side) {
        BigDecimal t = BigDecimal.ZERO;
        for (JsonNode l : entry.get("lines")) {
            if (l.get("accountCode").asText().equals(code)) {
                t = t.add(new BigDecimal(l.get(side).asText()));
            }
        }
        return t;
    }

    private JsonNode recon() throws Exception {
        return body(mvc.perform(get("/api/v1/fixed-assets/reports/reconciliation").header("Authorization", approver.bearer()))
                .andExpect(status().isOk()).andReturn());
    }

    private static BigDecimal diff(JsonNode recon, String code) {
        for (JsonNode r : recon.get("rows")) {
            if (r.get("accountCode").asText().equals(code)) {
                return new BigDecimal(r.get("difference").asText());
            }
        }
        return BigDecimal.ZERO;
    }
}
