package com.miaa.cas.inventoryaccounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.audit.Actor;
import com.miaa.cas.support.AbstractPayablesIT;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

class InventoryIT extends AbstractPayablesIT {

    @Test
    void weightedAverageCostFlowsThroughReceiptsIssuesCountsAndTransfersAndReconciles() throws Exception {
        int year = newOpenYear();
        tx.executeWithoutResult(s -> {
            mappings.set("INVENTORY_GRIR", account("PLC-2104"), Actor.SYSTEM);
            mappings.set("INVENTORY_VARIANCE", account("PLC-5303"), Actor.SYSTEM);
        });
        Session clerk = login(createUser("store", "INVENTORY_CLERK"));
        JsonNode reconBefore = recon();
        String tag = String.valueOf(System.nanoTime() % 100000);
        String wh1 = created(inv("/warehouses", clerk, Map.of("code", "W1-" + tag, "name", "Main"))).get("id").asText();
        String wh2 = created(inv("/warehouses", clerk, Map.of("code", "W2-" + tag, "name", "Terminal 3"))).get("id").asText();
        String item = created(inv("/items", clerk, Map.of("code", "BULB-" + tag, "name", "Runway edge lamp", "unitOfMeasure", "pc",
                "inventoryAccountId", account("PLC-1302"), "consumptionAccountId", account("PLC-5202")))).get("id").asText();
        String supplier = createSupplier(false).get("id").asText();

        // Two receipts at different prices: 10 @ 100 and 10 @ 130 -> 20 units worth 2,300 (average 115).
        JsonNode grn1 = postDoc(clerk, doc("GOODS_RECEIPT", year + "-03-02", supplier, null, line(item, wh1, null, "10", "100")));
        assertThat(grn1.get("documentNumber").asText()).matches("GRN-" + year + "-\\d{6}");
        Map<String, BigDecimal> e = entry(grn1);
        assertThat(e.get("PLC-1302")).isEqualByComparingTo("1000.00");
        assertThat(e.get("PLC-2104")).isEqualByComparingTo("-1000.00");
        JsonNode grn2 = postDoc(clerk, doc("GOODS_RECEIPT", year + "-03-05", supplier, null, line(item, wh1, null, "10", "130")));
        JsonNode it = itemView(item);
        assertThat(new BigDecimal(it.get("quantity").asText())).isEqualByComparingTo("20");
        assertThat(it.get("value").asText()).isEqualTo("2300.00");
        assertThat(new BigDecimal(it.get("averageCost").asText())).isEqualByComparingTo("115");

        // Issue 5 at average: 575 to repairs and maintenance for the requesting cost centre.
        JsonNode iss = postDoc(clerk, doc("ISSUE", year + "-03-10", null, costCenter("PLC-CC-OPS").toString(), line(item, wh1, null, "5", null)));
        e = entry(iss);
        assertThat(e.get("PLC-5202")).isEqualByComparingTo("575.00");
        assertThat(e.get("PLC-1302")).isEqualByComparingTo("-575.00");
        // Cannot issue more than is on hand.
        JsonNode tooMuch = created(inv("/documents", clerk, doc("ISSUE", year + "-03-11", null, costCenter("PLC-CC-OPS").toString(),
                line(item, wh1, null, "16", null))));
        act(poster, tooMuch, "post").andExpect(status().isUnprocessableEntity());

        // The clerk cannot post their own documents (and holds no posting authority).
        JsonNode count = created(inv("/documents", clerk, doc("COUNT", year + "-03-31", null, null, line(item, wh1, null, "14", null))));
        act(clerk, count, "post").andExpect(status().isForbidden());
        count = ok(act(poster, count, "post"));
        // Counted 14 against 15 on record: one unit short at 115.
        e = entry(count);
        assertThat(e.get("PLC-5303")).isEqualByComparingTo("115.00");
        assertThat(new BigDecimal(count.get("lines").get(0).get("systemQuantity").asText())).isEqualByComparingTo("15");

        JsonNode trf = postDoc(clerk, doc("TRANSFER", year + "-04-01", null, null, line(item, wh1, wh2, "4", null)));
        assertThat(trf.get("postedEntryId").isNull()).isTrue();
        it = itemView(item);
        assertThat(it.get("value").asText()).isEqualTo("1610.00");
        assertThat(it.toString()).contains("\"warehouseCode\":\"W2-" + tag + "\"");

        // Issuing everything leaves no rounding residue in the pool.
        postDoc(clerk, doc("ISSUE", year + "-04-02", null, costCenter("PLC-CC-OPS").toString(), line(item, wh1, null, "7", null),
                line(item, wh2, null, "4", null)));
        JsonNode last = postDoc(clerk, doc("ISSUE", year + "-04-03", null, costCenter("PLC-CC-OPS").toString(), line(item, wh1, null, "3", null)));
        assertThat(entry(last).get("PLC-5202")).isEqualByComparingTo("345.00");
        it = itemView(item);
        assertThat(new BigDecimal(it.get("quantity").asText())).isZero();
        assertThat(it.get("value").asText()).isEqualTo("0.00");
        JsonNode moves = body(mvc.perform(get("/api/v1/inventory/items/" + item + "/movements").header("Authorization", approver.bearer()))
                .andExpect(status().isOk()).andReturn());
        assertThat(moves.get("items")).hasSize(9);
        assertThat(moves.get("totalItems").asLong()).isEqualTo(9);

        // GR/IR: the supplier's invoice for the first delivery is charged to the clearing account and matched.
        Map<String, Object> grirLine = new HashMap<>();
        grirLine.put("accountId", account("PLC-2104"));
        grirLine.put("netAmount", "1000.00");
        JsonNode inv = body(mvc.perform(post("/api/v1/accounts-payable/invoices").header("Authorization", preparer.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(invoice(supplier, "INV-" + tag, LocalDate.of(year, 3, 6),
                        List.of(grirLine), false)))).andExpect(status().isCreated()).andReturn());
        String invId = inv.get("id").asText();
        attach(preparer, "accounts-payable/invoices", invId);
        inv = ok(apAction(preparer, "accounts-payable/invoices", invId, "submit", inv.get("version").asLong(), null));
        inv = ok(apAction(approver, "accounts-payable/invoices", invId, "approve", inv.get("version").asLong(), null));
        ok(apAction(poster, "accounts-payable/invoices", invId, "post", inv.get("version").asLong(), null));
        match(clerk, grn1, invId, "1000.00").andExpect(status().isForbidden());
        match(preparer, grn2, invId, "1000.01").andExpect(status().isUnprocessableEntity());
        JsonNode grir = ok(match(preparer, grn1, invId, "1000.00"));
        assertThat(grir.toString()).doesNotContain(grn1.get("id").asText()).contains(grn2.get("id").asText());

        // Every value went through documents, so register-to-ledger differences are unchanged.
        JsonNode reconAfter = recon();
        assertThat(diff(reconAfter, "PLC-1302")).isEqualByComparingTo(diff(reconBefore, "PLC-1302"));
        assertThat(diff(reconAfter, "PLC-2104")).isEqualByComparingTo(diff(reconBefore, "PLC-2104"));
    }

    // ------------------------------------------------------------------ helpers

    @Test
    void movementsImportFromCsvAndPriceAdjustmentsRestateTheAverageCost() throws Exception {
        int year = newOpenYear();
        tx.executeWithoutResult(s -> {
            mappings.set("INVENTORY_GRIR", account("PLC-2104"), Actor.SYSTEM);
            mappings.set("INVENTORY_REVALUATION", account("PLC-5303"), Actor.SYSTEM);
        });
        Session clerk = login(createUser("store2", "INVENTORY_CLERK"));
        String tag = String.valueOf(System.nanoTime() % 100000);
        String wh = created(inv("/warehouses", clerk, Map.of("code", "WI-" + tag, "name", "Import test"))).get("id").asText();
        String item = created(inv("/items", clerk, Map.of("code", "FUSE-" + tag, "name", "Cartridge fuse", "unitOfMeasure", "pc",
                "inventoryAccountId", account("PLC-1302"), "consumptionAccountId", account("PLC-5202")))).get("id").asText();
        String supplier = createSupplier(false).get("id").asText();

        // A goods receipt imported from a file: every bad row is reported with its row number and nothing is created.
        String bad = "item_code,warehouse_code,quantity,unit_cost\nFUSE-" + tag + ",WI-" + tag + ",10,100\nNOPE,WI-" + tag + ",x,1\n";
        JsonNode refused = body(importFile(clerk, "GOODS_RECEIPT", year + "-04-01", supplier, bad).andExpect(status().isBadRequest()).andReturn());
        assertThat(refused.toString()).contains("Row 3: no active item NOPE").contains("Row 3: quantity is not a number");
        String good = "item_code,warehouse_code,quantity,unit_cost\nFUSE-" + tag + ",WI-" + tag + ",10,100\nFUSE-" + tag + ",WI-" + tag + ",10,130\n";
        JsonNode draft = body(importFile(clerk, "GOODS_RECEIPT", year + "-04-01", supplier, good).andExpect(status().isCreated()).andReturn());
        assertThat(draft.get("status").asText()).isEqualTo("DRAFT");
        assertThat(draft.get("lines")).hasSize(2);
        ok(act(poster, draft, "post"));
        assertThat(itemView(item).get("value").asText()).isEqualTo("2300.00");

        // Price adjustment: restate 20 units from an average of 115 to 105; the 200 difference goes to the revaluation account.
        Map<String, Object> reval = doc("REVALUATION", year + "-04-30", null, null, line(item, null, null, "0", "105"));
        JsonNode adj = postDoc(clerk, reval);
        assertThat(adj.get("documentNumber").asText()).matches("IPA-" + year + "-\\d{6}");
        Map<String, BigDecimal> e = entry(adj);
        assertThat(e.get("PLC-1302")).isEqualByComparingTo("-200.00");
        assertThat(e.get("PLC-5303")).isEqualByComparingTo("200.00");
        JsonNode it = itemView(item);
        assertThat(it.get("value").asText()).isEqualTo("2100.00");
        assertThat(new BigDecimal(it.get("quantity").asText())).isEqualByComparingTo("20");
        // One line per item; an item without stock cannot be revalued.
        inv("/documents", clerk, doc("REVALUATION", year + "-04-30", null, null, line(item, null, null, "0", "1"), line(item, null, null, "0", "2")))
                .andExpect(status().isUnprocessableEntity());
        String empty = created(inv("/items", clerk, Map.of("code", "NONE-" + tag, "name", "Never stocked", "unitOfMeasure", "pc",
                "inventoryAccountId", account("PLC-1302"), "consumptionAccountId", account("PLC-5202")))).get("id").asText();
        act(poster, created(inv("/documents", clerk, doc("REVALUATION", year + "-04-30", null, null, line(empty, null, null, "0", "5")))), "post")
                .andExpect(status().isUnprocessableEntity());
        // The price adjustment shows in the item's movements without a warehouse.
        JsonNode moves = body(mvc.perform(get("/api/v1/inventory/items/" + item + "/movements").header("Authorization", approver.bearer())).andReturn());
        assertThat(moves.get("items").toString()).contains("REVALUATION");
    }

    private ResultActions importFile(Session who, String kind, String date, String supplier, String csv) throws Exception {
        return mvc.perform(multipart("/api/v1/inventory/documents/import").file(new MockMultipartFile("file", "lines.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8))).param("kind", kind).param("documentDate", date).param("description", "Imported delivery")
                .param("supplierId", supplier).header("Authorization", who.bearer()));
    }

    private static Map<String, Object> line(String item, String wh, String to, String qty, String unitCost) {
        Map<String, Object> m = new HashMap<>();
        m.put("itemId", item);
        m.put("warehouseId", wh);
        m.put("toWarehouseId", to);
        m.put("quantity", qty);
        m.put("unitCost", unitCost);
        return m;
    }

    @SafeVarargs
    private static Map<String, Object> doc(String kind, String date, String supplier, String costCenter, Map<String, Object>... lines) {
        Map<String, Object> m = new HashMap<>();
        m.put("kind", kind);
        m.put("documentDate", date);
        m.put("supplierId", supplier);
        m.put("costCenterId", costCenter);
        m.put("description", kind + " " + date);
        m.put("lines", new ArrayList<>(List.of(lines)));
        return m;
    }

    private ResultActions inv(String path, Session who, Object body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/inventory" + path)
                .header("Authorization", who.bearer()).contentType(MediaType.APPLICATION_JSON).content(toJson(body)));
    }

    private JsonNode created(ResultActions r) throws Exception {
        return body(r.andExpect(status().isCreated()).andReturn());
    }

    private ResultActions act(Session who, JsonNode doc, String action) throws Exception {
        return inv("/documents/" + doc.get("id").asText() + "/" + action, who, Map.of("version", doc.get("version").asLong()));
    }

    private JsonNode postDoc(Session clerk, Map<String, Object> body) throws Exception {
        return ok(act(poster, created(inv("/documents", clerk, body)), "post"));
    }

    private JsonNode itemView(String id) throws Exception {
        return body(mvc.perform(get("/api/v1/inventory/items/" + id).header("Authorization", approver.bearer()))
                .andExpect(status().isOk()).andReturn());
    }

    /** Net debit (positive) or credit (negative) per account code of a document's ledger entry. */
    private Map<String, BigDecimal> entry(JsonNode doc) throws Exception {
        JsonNode en = body(mvc.perform(get("/api/v1/general-ledger/entries/" + doc.get("postedEntryId").asText())
                .header("Authorization", approver.bearer())).andExpect(status().isOk()).andReturn());
        Map<String, BigDecimal> m = new HashMap<>();
        en.get("lines").forEach(l -> m.merge(l.get("accountCode").asText(),
                new BigDecimal(l.get("debit").asText()).subtract(new BigDecimal(l.get("credit").asText())), BigDecimal::add));
        return m;
    }

    private ResultActions match(Session who, JsonNode grn, String invoiceId, String amount) throws Exception {
        return inv("/gr-ir/matches", who, Map.of("grnId", grn.get("id").asText(), "invoiceId", invoiceId, "amount", amount));
    }

    private JsonNode recon() throws Exception {
        return body(mvc.perform(get("/api/v1/inventory/reconciliation").header("Authorization", approver.bearer()))
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
