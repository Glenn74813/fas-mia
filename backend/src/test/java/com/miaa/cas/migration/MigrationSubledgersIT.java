package com.miaa.cas.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.audit.Actor;
import com.miaa.cas.fixedassets.AssetSetupService;
import com.miaa.cas.fixedassets.FaDtos.CategoryRequest;
import com.miaa.cas.support.AbstractReceivablesIT;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

/** Phase 5b: fixed-asset register, stock on hand, unapplied collections and supplier advances at cutover. */
class MigrationSubledgersIT extends AbstractReceivablesIT {

    @Autowired AssetSetupService setup;

    @Test
    void assetsStockUnappliedCollectionsAndAdvancesAgreeWithTheOpeningEntry() throws Exception {
        int year = newOpenYear();
        LocalDate cutover = period(year, 1).getStartDate();
        String tag = Long.toString(System.nanoTime() % 1_000_000_000L, 36).toUpperCase();
        String catCode = "MC" + tag;
        tx.executeWithoutResult(s -> {
            mappings.set("SUPPLIER_ADVANCES", account("PLC-1206"), Actor.SYSTEM);
            setup.createCategory(new CategoryRequest(catCode, "Migrated equipment", account("PLC-1402"), account("PLC-1409"),
                    account("PLC-5301"), 120, null, null), true, Actor.SYSTEM);
        });
        Session clerk = login(createUser("mstore", "INVENTORY_CLERK"));
        String w1 = "MW1" + tag;
        String w2 = "MW2" + tag;
        String item = "MI" + tag;
        inv("/warehouses", clerk, Map.of("code", w1, "name", "Main")).andExpect(status().isCreated());
        inv("/warehouses", clerk, Map.of("code", w2, "name", "Terminal")).andExpect(status().isCreated());
        String itemId = body(inv("/items", clerk, Map.of("code", item, "name", "Lamp", "unitOfMeasure", "pc",
                "inventoryAccountId", account("PLC-1302"), "consumptionAccountId", account("PLC-5202"))).andExpect(status().isCreated())
                .andReturn()).get("id").asText();
        JsonNode customer = createCustomer(null);
        JsonNode supplier = createSupplier(false);
        String cCode = customer.get("code").asText();
        String sCode = supplier.get("code").asText();

        // The opening entry carries asset cost and accumulated depreciation, stock, a customer's credit on account
        // and a supplier advance.
        String tb = "account_code,cost_center_code,subsidiary_code,description,debit,credit\n"
                + "PLC-1402,,,Equipment,120000.00,\n"
                + "PLC-1409,,,Accumulated depreciation,,30000.00\n"
                + "PLC-1302,,,Spare parts,1150.00,\n"
                + "PLC-2303,," + cCode + ",Unapplied collections,,4000.00\n"
                + "PLC-1206,," + sCode + ",Advance to contractor,7000.00,\n"
                + "PLC-3102,,,Accumulated surplus,,94150.00\n";
        String entryNumber = postedOpeningEntry(cutover, tb);

        // Fixed assets: a register whose accumulated depreciation disagrees cannot be applied; the corrected one can.
        String faHeader = "asset_number,category_code,description,serial_number,location,custodian,cost_center_code,acquisition_date,"
                + "in_service_date,cost,residual_value,useful_life_months,remaining_life_months,accumulated_depreciation\n";
        String badRows = faHeader
                + "X1" + tag + "," + catCode + ",Generator,,,,,2020-01-01," + cutover + ",1.00,,12,6,0.50\n"
                + "X2" + tag + "," + catCode + ",Pump,,,,,2020-01-01,2020-01-01,100.00,,12,0,10.00\n"
                + "X3" + tag + "," + catCode + ",Fan,,,,,2020-01-01,2020-01-01,100.00,,12,6,200.00\n";
        JsonNode bad = upload("FA_REGISTER", entryNumber, badRows);
        assertThat(bad.at("/summary/status").asText()).isEqualTo("INVALID");
        assertThat(bad.get("errorRows").toString()).contains("before the cutover date").contains("not fully depreciated")
                .contains("exceeds cost less residual value");
        String fa = faHeader
                + "P1" + tag + "," + catCode + ",Baggage conveyor,SN-1,Terminal 1,Engineering,,2018-03-01,2018-04-01,100000.00,,120,90,25000.00\n"
                + "P2" + tag + "," + catCode + ",Old radio,,,,,2015-01-01,2015-01-01,20000.00,15000.00,60,0,5000.00\n";
        JsonNode off = upload("FA_REGISTER", entryNumber, fa.replace("25000.00", "24000.00"));
        assertThat(off.at("/reconciliation/reconciled").asBoolean()).isFalse();
        assertThat(off.at("/reconciliation/differences/0/partyCode").asText()).isEqualTo("PLC-1409");
        assertThat(off.at("/reconciliation/differences/0/difference").asText()).isEqualTo("-1000.00");
        apply(approver, off).andExpect(status().isUnprocessableEntity());
        cancel(off);
        JsonNode fab = upload("FA_REGISTER", entryNumber, fa);
        assertThat(fab.at("/reconciliation/reconciled").asBoolean()).isTrue();
        apply(preparer, fab).andExpect(status().isForbidden());
        fab = ok(apply(approver, fab));
        assertThat(fab.at("/summary/status").asText()).isEqualTo("APPLIED");
        UUID p1 = jdbc.queryForObject("SELECT id FROM fixed_assets WHERE asset_number = ?", UUID.class, "P1" + tag);
        JsonNode asset = body(mvc.perform(get("/api/v1/fixed-assets/" + p1).header("Authorization", approver.bearer()))
                .andExpect(status().isOk()).andReturn());
        assertThat(asset.get("acquisitionSource").asText()).isEqualTo("MIGRATION");
        assertThat(asset.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(asset.get("accumulatedDepreciation").asText()).isEqualTo("25000.00");
        // 75,000 still to depreciate over the 90 months left, not 100,000 over 120 from 2018.
        assertThat(asset.get("monthlyDepreciation").asText()).isEqualTo("833.33");
        assertThat(asset.get("netBookValue").asText()).isEqualTo("75000.00");
        assertThat(jdbc.queryForObject("SELECT status FROM fixed_assets WHERE asset_number = ?", String.class, "P2" + tag))
                .isEqualTo("FULLY_DEPRECIATED");

        // Stock: valued at quantity x unit cost per inventory account; it sets the item's average-cost pool.
        String stock = "item_code,warehouse_code,quantity,unit_cost\n" + item + "," + w1 + ",10,100\n" + item + "," + w2 + ",5,30.000000\n";
        JsonNode dup = upload("INVENTORY_STOCK", entryNumber, stock + item + "," + w1 + ",1,1\n");
        assertThat(dup.get("errorRows").toString()).contains("appears more than once");
        cancel(dup);
        JsonNode st = upload("INVENTORY_STOCK", entryNumber, stock);
        assertThat(st.at("/summary/totalAmount").asText()).isEqualTo("1150.00");
        assertThat(st.at("/reconciliation/reconciled").asBoolean()).isTrue();
        ok(apply(approver, st));
        Map<String, Object> pool = jdbc.queryForMap("SELECT quantity, value FROM inventory_valuations WHERE item_id = ?::uuid", itemId);
        assertThat((BigDecimal) pool.get("quantity")).isEqualByComparingTo("15");
        assertThat((BigDecimal) pool.get("value")).isEqualByComparingTo("1150.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_movements WHERE item_id = ?::uuid AND movement_type = 'OPENING'",
                Integer.class, itemId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT kind FROM inventory_documents WHERE migration_batch_id = ?::uuid", String.class,
                st.at("/summary/id").asText())).isEqualTo("OPENING");
        // The item now has history: a second opening for it is refused row by row.
        JsonNode again = upload("INVENTORY_STOCK", lastEntryForOtherKinds(cutover), "item_code,warehouse_code,quantity,unit_cost\n"
                + item + "," + w1 + ",11.5,100\n");
        assertThat(again.get("errorRows").toString()).contains("already has stock or movements");

        // Unapplied collections: a credit on the customer's account, never offered for deposit.
        JsonNode un = upload("AR_UNAPPLIED", entryNumber, "customer_code,receipt_number,receipt_date,description,unapplied_amount\n"
                + cCode + ",OR-" + tag + "," + cutover.minusDays(3) + ",Advance rent,4000.00\n");
        assertThat(un.at("/reconciliation/reconciled").asBoolean()).isTrue();
        assertThat(un.at("/reconciliation/controlAccountCode").asText()).isEqualTo("PLC-2303");
        ok(apply(approver, un));
        Map<String, Object> rc = jdbc.queryForMap("SELECT * FROM receipts WHERE receipt_number = ?", "OR-" + tag);
        assertThat(rc.get("payment_method")).isEqualTo("MIGRATED");
        assertThat(rc.get("unapplied_amount").toString()).isEqualTo("4000.00");
        JsonNode undeposited = body(mvc.perform(get("/api/v1/treasury/undeposited-receipts").header("Authorization", cashier.bearer()))
                .andExpect(status().isOk()).andReturn());
        assertThat(undeposited.toString()).doesNotContain("OR-" + tag);
        JsonNode c = body(mvc.perform(get("/api/v1/customers/" + customer.get("id").asText()).header("Authorization", preparer.bearer()))
                .andExpect(status().isOk()).andReturn());
        assertThat(c.get("unappliedCollections").asText()).isEqualTo("4000.00");

        // Supplier advances: held in the advance register, which agrees with the ledger.
        JsonNode adv = upload("AP_ADVANCES", entryNumber, "supplier_code,advance_reference,advance_date,description,outstanding_amount\n"
                + sCode + ",MOB-" + tag + "," + cutover.minusDays(30) + ",Mobilisation advance,7000.00\n");
        assertThat(adv.at("/reconciliation/reconciled").asBoolean()).isTrue();
        ok(apply(approver, adv));
        JsonNode register = body(mvc.perform(get("/api/v1/accounts-payable/advances").header("Authorization", preparer.bearer()))
                .andExpect(status().isOk()).andReturn());
        assertThat(register.toString()).contains("MOB-" + tag);
        assertThat(register.at("/reconciliation/reconciled").asBoolean()).isTrue();

        // Every kind can be applied to an entry only once.
        mvc.perform(multipart("/api/v1/migration/batches").file(csv("x")).param("kind", "AP_ADVANCES").param("description", "Again")
                .param("openingEntryNumber", entryNumber).header("Authorization", preparer.bearer())).andExpect(status().isConflict());
    }

    /** A second opening entry in the same year, used only to show that item history blocks opening stock. */
    private String lastEntryForOtherKinds(LocalDate cutover) throws Exception {
        return postedOpeningEntry(cutover, "account_code,cost_center_code,subsidiary_code,description,debit,credit\n"
                + "PLC-1302,,,Spare parts,1150.00,\nPLC-3102,,,Accumulated surplus,,1150.00\n");
    }

    private String postedOpeningEntry(LocalDate cutover, String tb) throws Exception {
        var req = multipart("/api/v1/migration/batches").file(csv(tb)).param("kind", "GL_OPENING").param("description", "Opening TB")
                .param("cutoverDate", cutover.toString()).header("Authorization", preparer.bearer());
        JsonNode glb = body(mvc.perform(req).andExpect(status().isCreated()).andReturn());
        assertThat(glb.at("/summary/status").asText()).isEqualTo("VALIDATED");
        glb = ok(apply(preparer, glb));
        String voucherId = glb.at("/summary/journalVoucherId").asText();
        JsonNode v = body(mvc.perform(get("/api/v1/journal-vouchers/" + voucherId).header("Authorization", preparer.bearer()))
                .andExpect(status().isOk()).andReturn());
        v = ok(action(preparer, voucherId, "submit", v.get("version").asLong(), null));
        v = ok(action(reviewer, voucherId, "approve", v.get("version").asLong(), "Reviewed"));
        v = ok(action(approver, voucherId, "approve", v.get("version").asLong(), "Approved"));
        v = ok(postVoucher(poster, voucherId, v.get("version").asLong(), "key-" + UUID.randomUUID()));
        return v.get("voucherNumber").asText();
    }

    private JsonNode upload(String kind, String entryNumber, String content) throws Exception {
        return body(mvc.perform(multipart("/api/v1/migration/batches").file(csv(content)).param("kind", kind)
                .param("description", "Test " + kind).param("openingEntryNumber", entryNumber).header("Authorization", preparer.bearer()))
                .andExpect(status().isCreated()).andReturn());
    }

    private ResultActions apply(Session who, JsonNode batch) throws Exception {
        return mvc.perform(post("/api/v1/migration/batches/" + batch.at("/summary/id").asText() + "/apply")
                .header("Authorization", who.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("version", batch.at("/summary/version").asLong()))));
    }

    private void cancel(JsonNode batch) throws Exception {
        ok(mvc.perform(post("/api/v1/migration/batches/" + batch.at("/summary/id").asText() + "/cancel")
                .header("Authorization", preparer.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("version", batch.at("/summary/version").asLong(), "reason", "Superseded by a corrected file")))));
    }

    private ResultActions inv(String path, Session who, Object body) throws Exception {
        return mvc.perform(post("/api/v1/inventory" + path).header("Authorization", who.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(body)));
    }

    private static MockMultipartFile csv(String content) {
        return new MockMultipartFile("file", "batch-" + UUID.randomUUID() + ".csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }
}
