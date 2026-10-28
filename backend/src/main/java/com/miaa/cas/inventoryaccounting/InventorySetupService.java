package com.miaa.cas.inventoryaccounting;

import com.miaa.cas.audit.Actor;
import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.generalledger.AccountPurpose;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.money.Money;
import com.miaa.cas.common.web.JdbcPaging;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.inventoryaccounting.InvDtos.ItemRequest;
import com.miaa.cas.inventoryaccounting.InvDtos.ItemView;
import com.miaa.cas.inventoryaccounting.InvDtos.WarehouseQuantity;
import com.miaa.cas.inventoryaccounting.InvDtos.WarehouseRequest;
import com.miaa.cas.inventoryaccounting.InvDtos.WarehouseView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Warehouses and the item master; each item has a weighted-average cost pool created with it. */
@Service
public class InventorySetupService {

    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public InventorySetupService(AuditService audit, JdbcTemplate jdbc, Clock clock) {
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<WarehouseView> warehouses() {
        return jdbc.query("SELECT * FROM warehouses ORDER BY code", (rs, i) -> new WarehouseView(rs.getObject("id", UUID.class),
                rs.getString("code"), rs.getString("name"), rs.getString("status")));
    }

    @Transactional
    public WarehouseView createWarehouse(WarehouseRequest req, Actor actor) {
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM warehouses WHERE code = ?)", Boolean.class, req.code()))) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "Warehouse code already exists.");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO warehouses (id, code, name, status, created_at, created_by) VALUES (?, ?, ?, 'ACTIVE', ?, ?)", id,
                req.code(), req.name().trim(), Timestamp.from(Instant.now(clock)), actor.username());
        audit.record(actor, "WAREHOUSE_CREATED", "WAREHOUSE", req.code(), Map.of("name", req.name().trim()));
        return new WarehouseView(id, req.code(), req.name().trim(), "ACTIVE");
    }

    @Transactional(readOnly = true)
    /** Every item, for reports that value the whole stock; screens use the paged {@link #items(String, int, int)}. */
    public List<ItemView> allItems() {
        return jdbc.queryForList("SELECT id FROM inventory_items ORDER BY code", UUID.class).stream().map(this::item).toList();
    }

    public PageResponse<ItemView> items(String q, int page, int size) {
        String like = JdbcPaging.like(q);
        return JdbcPaging.ids(jdbc, "SELECT id FROM inventory_items WHERE (?::text IS NULL OR lower(code || ' ' || name) LIKE ?::text) "
                + "ORDER BY code, id", page, size, like, like).map(this::item);
    }

    @Transactional(readOnly = true)
    public ItemView item(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT i.*, a.code AS inv_code, c.code AS con_code, v.quantity AS pool_qty, v.value AS pool_value
                FROM inventory_items i JOIN chart_of_accounts a ON a.id = i.inventory_account_id
                JOIN chart_of_accounts c ON c.id = i.consumption_account_id JOIN inventory_valuations v ON v.item_id = i.id
                WHERE i.id = ?""", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Item");
        }
        Map<String, Object> r = rows.get(0);
        BigDecimal qty = (BigDecimal) r.get("pool_qty");
        BigDecimal value = (BigDecimal) r.get("pool_value");
        List<WarehouseQuantity> wh = jdbc.query("""
                SELECT w.id, w.code, b.quantity FROM inventory_balances b JOIN warehouses w ON w.id = b.warehouse_id
                WHERE b.item_id = ? AND b.quantity <> 0 ORDER BY w.code""", (rs, i) -> new WarehouseQuantity(rs.getObject(1, UUID.class),
                rs.getString(2), rs.getBigDecimal(3)), id);
        return new ItemView(id, (String) r.get("code"), (String) r.get("name"), (String) r.get("unit_of_measure"),
                (UUID) r.get("inventory_account_id"), (String) r.get("inv_code"), (UUID) r.get("consumption_account_id"),
                (String) r.get("con_code"), (String) r.get("status"), qty, value,
                qty.signum() == 0 ? null : value.divide(qty, 6, RoundingMode.HALF_EVEN), wh);
    }

    @Transactional
    public ItemView createItem(ItemRequest req, Actor actor) {
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM inventory_items WHERE code = ?)", Boolean.class, req.code()))) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "Item code already exists.");
        }
        AccountPurpose.require(jdbc, req.inventoryAccountId(), AccountPurpose.INVENTORY_STOCK, "Inventory account");
        AccountPurpose.require(jdbc, req.consumptionAccountId(), AccountPurpose.EXPENSE, "Consumption account");
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO inventory_items (id, code, name, unit_of_measure, inventory_account_id, consumption_account_id, status, "
                + "created_at, created_by) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)", id, req.code(), req.name().trim(),
                req.unitOfMeasure().trim(), req.inventoryAccountId(), req.consumptionAccountId(), Timestamp.from(Instant.now(clock)),
                actor.username());
        jdbc.update("INSERT INTO inventory_valuations (item_id, quantity, value) VALUES (?, 0, ?)", id, Money.ZERO);
        audit.record(actor, "INVENTORY_ITEM_CREATED", "INVENTORY_ITEM", req.code(), Map.of("name", req.name().trim()));
        return item(id);
    }
}
