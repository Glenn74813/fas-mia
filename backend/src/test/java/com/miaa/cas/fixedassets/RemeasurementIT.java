package com.miaa.cas.fixedassets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.audit.Actor;
import com.miaa.cas.fixedassets.FaDtos.CategoryRequest;
import com.miaa.cas.generalledger.web.LedgerDtos.AccountRequest;
import com.miaa.cas.support.AbstractPayablesIT;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * One asset through impairment (PAS 36), revaluation (PAS 16, elimination method), impairment of a revalued asset and
 * disposal, with depreciation restarting from each new carrying amount and the register reconciling to the ledger.
 */
class RemeasurementIT extends AbstractPayablesIT {

    @Autowired AssetSetupService setup;

    @Test
    void impairmentAndRevaluationRemeasureTheAssetAndItsDepreciation() throws Exception {
        int year = newOpenYear();
        String tc = "R" + System.nanoTime() % 1_000_000;
        String impCode = tc + "-IMP";
        String lossCode = tc + "-LOSS";
        String gainCode = tc + "-GAIN";
        String surplusCode = tc + "-SURP";
        UUID category = tx.execute(s -> {
            UUID loss = accountService.create(new AccountRequest(lossCode, "Test impairment loss", null, "EXPENSE", "DEBIT", "PLC-53", true, null,
                    null, "PLC-SFPERF-EXP", false, null, false, null)).id();
            UUID gain = accountService.create(new AccountRequest(gainCode, "Test reversal of impairment", null, "REVENUE", "CREDIT", "PLC-42", true,
                    null, null, "PLC-SFPERF-REV", false, null, false, null)).id();
            UUID surplus = accountService.create(new AccountRequest(surplusCode, "Test revaluation surplus", null, "EQUITY", "CREDIT", "PLC-3", true,
                    null, null, "PLC-SFP-EQ", false, null, false, null)).id();
            accountService.create(new AccountRequest(impCode, "Test accumulated impairment", null, "ASSET", "CREDIT", "PLC-14", true, null, null,
                    "PLC-SFP-PPE", false, null, false, null));
            mappings.set("FA_IMPAIRMENT_LOSS", loss, Actor.SYSTEM);
            mappings.set("FA_IMPAIRMENT_REVERSAL", gain, Actor.SYSTEM);
            mappings.set("FA_REVALUATION_SURPLUS", surplus, Actor.SYSTEM);
            mappings.set("FA_DISPOSAL_GAIN", account("PLC-4204"), Actor.SYSTEM);
            mappings.set("FA_DISPOSAL_LOSS", account("PLC-5302"), Actor.SYSTEM);
            return setup.createCategory(new CategoryRequest(tc, "Remeasured equipment", account("PLC-1402"), account("PLC-1409"),
                    account("PLC-5301"), 36, null, null), true, Actor.SYSTEM).id();
        });

        Map<String, Object> body = new HashMap<>();
        body.put("categoryId", category);
        body.put("description", "Baggage scanner");
        body.put("acquisitionSource", "OTHER");
        body.put("offsetAccountId", account("PLC-4203"));
        body.put("justification", "Donated by a partner agency");
        body.put("acquisitionDate", year + "-01-15");
        body.put("inServiceDate", year + "-01-15");
        body.put("cost", "3600.00");
        JsonNode asset = body(mvc.perform(post("/api/v1/fixed-assets").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(body))).andExpect(status().isCreated()).andReturn());
        String assetId = asset.get("id").asText();
        ok(act(poster, "fixed-assets", assetId, "activate", asset));
        postRun(year, 4); // February to April: 3 x 100

        // Impairment needs the category's accumulated impairment account.
        remeasure(assetId, "IMPAIRMENT", year + "-05-10", "2000.00", null).andExpect(status().isUnprocessableEntity());
        UUID imp = account(impCode);
        mvc.perform(put("/api/v1/asset-categories/" + category + "/impairment-account").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("accountId", account("PLC-1409")))))
                .andExpect(status().isUnprocessableEntity());
        JsonNode cat = ok(mvc.perform(put("/api/v1/asset-categories/" + category + "/impairment-account").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("accountId", imp)))));
        assertThat(cat.get("accumulatedImpairmentAccountCode").asText()).isEqualTo(impCode);

        // May: recoverable amount 2,000 against a carrying amount of 3,300.
        JsonNode impairment = body(remeasure(assetId, "IMPAIRMENT", year + "-05-10", "2000.00", null).andExpect(status().isCreated()).andReturn());
        assertThat(impairment.get("carryingBefore").asText()).isEqualTo("3300.00");
        assertThat(impairment.get("impairmentChange").asText()).isEqualTo("1300.00");
        assertThat(impairment.get("lossInProfit").asText()).isEqualTo("1300.00");
        assertThat(impairment.get("basisRemainingMonths").asInt()).isEqualTo(33);
        act(preparer, "asset-remeasurements", impairment.get("id").asText(), "post", impairment).andExpect(status().isForbidden());
        impairment = ok(act(poster, "asset-remeasurements", impairment.get("id").asText(), "post", impairment));
        JsonNode e1 = entry(impairment.get("postedEntryId").asText());
        assertThat(sum(e1, lossCode, "debit")).isEqualByComparingTo("1300.00");
        assertThat(sum(e1, impCode, "credit")).isEqualByComparingTo("1300.00");
        asset = view(assetId);
        assertThat(asset.get("netBookValue").asText()).isEqualTo("2000.00");
        assertThat(asset.get("accumulatedImpairment").asText()).isEqualTo("1300.00");
        assertThat(asset.get("monthlyDepreciation").asText()).isEqualTo("60.61");
        assertThat(asset.get("remeasurements").size()).isEqualTo(1);

        // Depreciation restarts from the impaired amount; May can no longer be remeasured once depreciated.
        assertThat(lineFor(postRun(year, 5), assetId).get("amount").asText()).isEqualTo("60.61");
        JsonNode late = body(remeasure(assetId, "IMPAIRMENT", year + "-05-20", "1000.00", null).andExpect(status().isCreated()).andReturn());
        act(poster, "asset-remeasurements", late.get("id").asText(), "post", late)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value(Matchers.containsString("already includes")));
        ok(act(preparer, "asset-remeasurements", late.get("id").asText(), "cancel", late));

        // June: revalued to 3,300. Accumulated depreciation (360.61) and impairment (1,300) are eliminated; the increase of
        // 1,360.61 first reverses the 1,300 charged to profit or loss, the rest goes to revaluation surplus.
        JsonNode reval = body(remeasure(assetId, "REVALUATION", year + "-06-15", "3300.00", null).andExpect(status().isCreated()).andReturn());
        reval = ok(act(poster, "asset-remeasurements", reval.get("id").asText(), "post", reval));
        assertThat(reval.get("carryingBefore").asText()).isEqualTo("1939.39");
        assertThat(reval.get("reversalInProfit").asText()).isEqualTo("1300.00");
        assertThat(reval.get("surplusChange").asText()).isEqualTo("60.61");
        JsonNode e2 = entry(reval.get("postedEntryId").asText());
        assertThat(sum(e2, "PLC-1409", "debit")).isEqualByComparingTo("360.61");
        assertThat(sum(e2, impCode, "debit")).isEqualByComparingTo("1300.00");
        assertThat(sum(e2, "PLC-1402", "credit")).isEqualByComparingTo("300.00");
        assertThat(sum(e2, gainCode, "credit")).isEqualByComparingTo("1300.00");
        assertThat(sum(e2, surplusCode, "credit")).isEqualByComparingTo("60.61");
        asset = view(assetId);
        assertThat(asset.get("grossCarryingAmount").asText()).isEqualTo("3300.00");
        assertThat(asset.get("accumulatedDepreciation").asText()).isEqualTo("0.00");
        assertThat(asset.get("revaluationSurplus").asText()).isEqualTo("60.61");
        assertThat(asset.get("monthlyDepreciation").asText()).isEqualTo("103.12"); // 3,300 over the 32 months left
        assertThat(lineFor(postRun(year, 6), assetId).get("amount").asText()).isEqualTo("103.12");

        // July: an impairment of the revalued asset uses its surplus first.
        JsonNode imp2 = body(remeasure(assetId, "IMPAIRMENT", year + "-07-05", "3100.00", null).andExpect(status().isCreated()).andReturn());
        imp2 = ok(act(poster, "asset-remeasurements", imp2.get("id").asText(), "post", imp2));
        assertThat(imp2.get("surplusChange").asText()).isEqualTo("-60.61");
        assertThat(imp2.get("lossInProfit").asText()).isEqualTo("36.27");
        assertThat(lineFor(postRun(year, 7), assetId).get("amount").asText()).isEqualTo("100.00"); // 3,100 over 31 months

        // August: sold for 2,900 against a carrying amount of 3,000.
        Map<String, Object> sale = new HashMap<>(Map.of("assetId", assetId, "disposalDate", year + "-08-10", "method", "SALE",
                "proceeds", "2900.00", "proceedsAccountId", account("PLC-1102"), "reason", "Sold after the terminal refit"));
        JsonNode d = body(mvc.perform(post("/api/v1/asset-disposals").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(sale))).andExpect(status().isCreated()).andReturn());
        assertThat(d.get("netBookValue").asText()).isEqualTo("3000.00");
        d = ok(act(poster, "asset-disposals", d.get("id").asText(), "post", d));
        JsonNode e3 = entry(d.get("postedEntryId").asText());
        assertThat(sum(e3, "PLC-1409", "debit")).isEqualByComparingTo("203.12");
        assertThat(sum(e3, impCode, "debit")).isEqualByComparingTo("96.88");
        assertThat(sum(e3, "PLC-5302", "debit")).isEqualByComparingTo("100.00");
        assertThat(sum(e3, "PLC-1402", "credit")).isEqualByComparingTo("3300.00");

        // The year's schedule rolls this category forward to nothing, and the register still matches the ledger.
        JsonNode schedule = body(mvc.perform(get("/api/v1/fixed-assets/reports/ppe-schedule").param("from", year + "-01-01")
                .param("to", year + "-12-31").header("Authorization", approver.bearer())).andExpect(status().isOk()).andReturn());
        JsonNode row = null;
        for (JsonNode r : schedule.get("rows")) {
            if (tc.equals(r.get("categoryCode").asText())) {
                row = r;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.get("additions").asText()).isEqualTo("3600.00");
        assertThat(row.get("revaluations").asText()).isEqualTo("-300.00");
        assertThat(row.get("disposalsCost").asText()).isEqualTo("3300.00");
        assertThat(row.get("closingCost").asText()).isEqualTo("0.00");
        assertThat(row.get("depreciation").asText()).isEqualTo("563.73");
        assertThat(row.get("eliminatedAccumulated").asText()).isEqualTo("360.61");
        assertThat(row.get("closingAccumulated").asText()).isEqualTo("0.00");
        assertThat(row.get("impairment").asText()).isEqualTo("96.88");
        assertThat(row.get("closingImpairment").asText()).isEqualTo("0.00");
        JsonNode recon = body(mvc.perform(get("/api/v1/fixed-assets/reports/reconciliation").header("Authorization", approver.bearer()))
                .andExpect(status().isOk()).andReturn());
        for (JsonNode r : recon.get("rows")) {
            if (impCode.equals(r.get("accountCode").asText())) {
                assertThat(new BigDecimal(r.get("difference").asText())).isEqualByComparingTo("0");
            }
        }
    }

    private ResultActions remeasure(String assetId, String kind, String date, String amount, Integer remainingLife) throws Exception {
        Map<String, Object> m = new HashMap<>();
        m.put("assetId", assetId);
        m.put("kind", kind);
        m.put("remeasuredOn", date);
        m.put("newCarryingAmount", amount);
        m.put("remainingLifeMonths", remainingLife);
        m.put("basisReference", "Appraisal report " + date);
        m.put("reason", kind.equals("IMPAIRMENT") ? "Damaged in the typhoon" : "Independent appraisal");
        return mvc.perform(post("/api/v1/asset-remeasurements").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(m)));
    }

    private JsonNode view(String assetId) throws Exception {
        return body(mvc.perform(get("/api/v1/fixed-assets/" + assetId).header("Authorization", preparer.bearer()))
                .andExpect(status().isOk()).andReturn());
    }

    private ResultActions act(Session who, String path, String id, String action, JsonNode current) throws Exception {
        return mvc.perform(post("/api/v1/" + path + "/" + id + "/" + action).header("Authorization", who.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("version", current.get("version").asLong()))));
    }

    private JsonNode postRun(int year, int month) throws Exception {
        JsonNode run = body(mvc.perform(post("/api/v1/depreciation-runs").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("periodId", period(year, month).getId()))))
                .andExpect(status().isCreated()).andReturn());
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

    private static BigDecimal sum(JsonNode entry, String code, String side) {
        BigDecimal t = BigDecimal.ZERO;
        for (JsonNode l : entry.get("lines")) {
            if (l.get("accountCode").asText().equals(code)) {
                t = t.add(new BigDecimal(l.get(side).asText()));
            }
        }
        return t;
    }
}
