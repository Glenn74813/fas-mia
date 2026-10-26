package com.miaa.cas.migration;

import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.security.CurrentActor;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Stock on hand at cutover, per item and warehouse, at a unit cost. Applied as one posted OPENING inventory
 * document: it sets each item's weighted-average cost pool and its warehouse quantities and records one
 * immutable movement per row. Only items with no stock history in CAS can receive opening stock. The batch
 * reconciles per inventory account with the opening entry.
 */
@Component
class StockRules implements KindRules {

    /** Inventory document lines are numbered with a SMALLINT. */
    static final int MAX_LINES = 30_000;

    record Stock(UUID itemId, UUID warehouseId, BigDecimal quantity, BigDecimal unitCost, BigDecimal value) {
    }

    private record ItemInfo(UUID id, boolean active, UUID account, String accountCode, String accountName, boolean hasHistory) {
    }

    private final JdbcTemplate jdbc;

    StockRules(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public MigrationService.Kind kind() {
        return MigrationService.Kind.INVENTORY_STOCK;
    }

    @Override
    public String basis() {
        return "inventory accounts";
    }

    @Override
    public Checked check(List<MigrationCsv.Row> rows, LocalDate cutover) {
        Map<String, ItemInfo> items = new HashMap<>();
        jdbc.query("""
                SELECT i.id, i.code, i.status, a.id AS a_id, a.code AS a_code, a.name AS a_name,
                       (v.quantity > 0 OR EXISTS (SELECT 1 FROM inventory_movements m WHERE m.item_id = i.id)) AS history
                FROM inventory_items i JOIN chart_of_accounts a ON a.id = i.inventory_account_id
                JOIN inventory_valuations v ON v.item_id = i.id""", rs -> {
            items.put(rs.getString("code"), new ItemInfo(rs.getObject("id", UUID.class), "ACTIVE".equals(rs.getString("status")),
                    rs.getObject("a_id", UUID.class), rs.getString("a_code"), rs.getString("a_name"), rs.getBoolean("history")));
        });
        Map<String, UUID> warehouses = new HashMap<>();
        jdbc.query("SELECT id, code FROM warehouses WHERE status = 'ACTIVE'", rs -> {
            warehouses.put(rs.getString("code"), rs.getObject("id", UUID.class));
        });
        List<String> batchErrors = new ArrayList<>();
        if (rows.size() > MAX_LINES) {
            batchErrors.add("A stock batch holds at most " + MAX_LINES + " rows; split the file.");
        }
        List<List<String>> rowErrors = new ArrayList<>();
        List<Stock> stock = new ArrayList<>();
        Map<String, Side> batch = KindRules.sides();
        Set<String> seen = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (MigrationCsv.Row r : rows) {
            List<String> e = new ArrayList<>();
            Map<String, String> v = r.values();
            ItemInfo item = items.get(v.get("item_code"));
            if (v.get("item_code").isEmpty()) {
                e.add("item_code is required.");
            } else if (item == null) {
                e.add("Item " + v.get("item_code") + " does not exist.");
            } else if (!item.active()) {
                e.add("Item " + v.get("item_code") + " is inactive.");
            } else if (item.hasHistory()) {
                e.add("Item " + v.get("item_code") + " already has stock or movements in CAS; opening stock is only for items without history.");
            }
            UUID wh = warehouses.get(v.get("warehouse_code"));
            if (v.get("warehouse_code").isEmpty()) {
                e.add("warehouse_code is required.");
            } else if (wh == null) {
                e.add("No active warehouse " + v.get("warehouse_code") + ".");
            }
            if (!seen.add(v.get("item_code") + "|" + v.get("warehouse_code"))) {
                e.add("Item " + v.get("item_code") + " in warehouse " + v.get("warehouse_code") + " appears more than once in the file.");
            }
            BigDecimal qty = KindRules.decimal(v.get("quantity"), "quantity", e, false, 15, 4);
            BigDecimal unitCost = KindRules.decimal(v.get("unit_cost"), "unit_cost", e, false, 13, 6);
            if (qty != null && qty.signum() == 0) {
                e.add("quantity must be greater than zero.");
            }
            if (e.isEmpty()) {
                BigDecimal value = qty.multiply(unitCost).setScale(2, RoundingMode.HALF_EVEN);
                total = total.add(value);
                stock.add(new Stock(item.id(), wh, qty, unitCost, value));
                KindRules.add(batch, item.account().toString(), item.accountCode(), item.accountName(), value);
            }
            rowErrors.add(e);
        }
        return new Checked(rowErrors, batchErrors, total, batch, stock, List.of());
    }

    @Override
    public Map<String, Side> ledger(Entry entry) {
        Map<String, Side> sides = KindRules.sides();
        jdbc.query("""
                SELECT a.id, a.code, a.name, coalesce(sum(l.debit - l.credit), 0) AS amt
                FROM chart_of_accounts a LEFT JOIN journal_entry_lines l ON l.account_id = a.id AND l.entry_id = ?
                WHERE a.id IN (SELECT inventory_account_id FROM inventory_items) GROUP BY a.id, a.code, a.name""", rs -> {
            KindRules.add(sides, rs.getObject("id").toString(), rs.getString("code"), rs.getString("name"), rs.getBigDecimal("amt"));
        }, entry.id());
        return sides;
    }

    @Override
    public void apply(Context ctx, Checked checked) {
        @SuppressWarnings("unchecked")
        List<Stock> stock = (List<Stock>) checked.items();
        // Cost pools are locked in item order, as stock postings do, and checked again under the lock.
        Map<UUID, BigDecimal[]> pools = new TreeMap<>();
        stock.forEach(s -> pools.put(s.itemId(), new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO}));
        for (UUID item : pools.keySet()) {
            Map<String, Object> v = jdbc.queryForMap("SELECT quantity FROM inventory_valuations WHERE item_id = ? FOR UPDATE", item);
            if (((BigDecimal) v.get("quantity")).signum() != 0 || Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM inventory_movements WHERE item_id = ?)", Boolean.class, item))) {
                throw new ApiException(ErrorCode.INVALID_STATE, "An item in the batch received stock in CAS after the upload; "
                        + "cancel the batch and upload it again.");
            }
        }
        UUID doc = UUID.randomUUID();
        Entry entry = ctx.entry();
        BigDecimal total = stock.stream().map(Stock::value).reduce(BigDecimal.ZERO, BigDecimal::add);
        jdbc.update("""
                INSERT INTO inventory_documents (id, document_number, kind, document_date, description, status, total_value, prepared_by_id,
                    prepared_by, posted_by, posted_entry_id, created_at, updated_at, updated_by, migration_batch_id)
                VALUES (?, ?, 'OPENING', ?, ?, 'POSTED', ?, ?, ?, ?, ?, ?, ?, ?, ?)""", doc, "OPEN-" + entry.number(),
                Date.valueOf(entry.date()), ctx.description(), total, ctx.preparerId(), ctx.preparer(), CurrentActor.username(), entry.id(),
                ctx.now(), ctx.now(), CurrentActor.username(), ctx.batchId());
        List<Object[]> lines = new ArrayList<>();
        List<Object[]> movements = new ArrayList<>();
        List<Object[]> balances = new ArrayList<>();
        int n = 0;
        for (Stock s : stock) {
            n++;
            BigDecimal[] pool = pools.get(s.itemId());
            pool[0] = pool[0].add(s.quantity());
            pool[1] = pool[1].add(s.value());
            lines.add(new Object[] {doc, n, s.itemId(), s.warehouseId(), s.quantity(), s.unitCost(), s.value()});
            movements.add(new Object[] {UUID.randomUUID(), doc, n, s.itemId(), s.warehouseId(), Date.valueOf(entry.date()), s.quantity(),
                    s.value(), pool[0], pool[1], ctx.now()});
            balances.add(new Object[] {s.itemId(), s.warehouseId(), s.quantity()});
        }
        jdbc.batchUpdate("""
                INSERT INTO inventory_document_lines (document_id, line_no, item_id, warehouse_id, quantity, unit_cost, system_quantity, value)
                VALUES (?, ?, ?, ?, ?, ?, 0, ?)""", lines);
        jdbc.batchUpdate("""
                INSERT INTO inventory_movements (id, document_id, line_no, item_id, warehouse_id, movement_type, movement_date, quantity, value,
                    pool_quantity, pool_value, created_at) VALUES (?, ?, ?, ?, ?, 'OPENING', ?, ?, ?, ?, ?, ?)""", movements);
        jdbc.batchUpdate("""
                INSERT INTO inventory_balances (item_id, warehouse_id, quantity) VALUES (?, ?, ?)
                ON CONFLICT (item_id, warehouse_id) DO UPDATE SET quantity = EXCLUDED.quantity""", balances);
        List<Object[]> valuations = new ArrayList<>();
        pools.forEach((item, p) -> valuations.add(new Object[] {p[0], p[1], item}));
        jdbc.batchUpdate("UPDATE inventory_valuations SET quantity = ?, value = ? WHERE item_id = ?", valuations);
    }
}
