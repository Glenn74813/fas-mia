package com.miaa.cas.inventoryaccounting;

import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.csv.Csv;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.money.Money;
import com.miaa.cas.common.web.JdbcPaging;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.generalledger.AccountMappingService;
import com.miaa.cas.inventoryaccounting.InvDtos.DocumentRequest;
import com.miaa.cas.inventoryaccounting.InvDtos.DocumentSummary;
import com.miaa.cas.inventoryaccounting.InvDtos.DocumentView;
import com.miaa.cas.inventoryaccounting.InvDtos.LineRequest;
import com.miaa.cas.inventoryaccounting.InvDtos.LineView;
import com.miaa.cas.journal.NumberingService;
import com.miaa.cas.journal.PostingService;
import com.miaa.cas.journal.domain.LineData;
import com.miaa.cas.notifications.NotificationService;
import com.miaa.cas.security.CurrentActor;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inventory documents. The storekeeper prepares; accounting (never the preparer) posts, which moves
 * stock, re-prices the weighted-average cost pool and records the ledger entry:
 * <ul>
 * <li>Goods receipt: Dr inventory / Cr GR/IR clearing, at the receipt cost; the pool average is re-computed.</li>
 * <li>Issue: Dr consumption expense (cost centre) / Cr inventory, at the current average; the last unit
 * issued takes the remaining value so the pool never keeps a rounding residue.</li>
 * <li>Count: the difference between counted and recorded quantity at the average, to inventory adjustments.</li>
 * <li>Transfer: quantity moves between warehouses; no value change and no entry.</li>
 * </ul>
 */
@Service
public class InventoryDocumentService {

    private static final Map<String, String> PREFIX = Map.of("GOODS_RECEIPT", "GRN", "ISSUE", "ISS", "COUNT", "CNT", "TRANSFER", "TRF",
            "REVALUATION", "IPA");

    private final NumberingService numbering;
    private final PostingService posting;
    private final AccountMappingService mappings;
    private final AuditService audit;
    private final NotificationService notifications;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public InventoryDocumentService(NumberingService numbering, PostingService posting, AccountMappingService mappings,
            AuditService audit, NotificationService notifications, JdbcTemplate jdbc, Clock clock) {
        this.numbering = numbering;
        this.posting = posting;
        this.mappings = mappings;
        this.audit = audit;
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public PageResponse<DocumentSummary> list(String kind, String status, int page, int size) {
        String k = kind == null || kind.isBlank() ? null : kind.trim().toUpperCase();
        String s = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        return JdbcPaging.query(jdbc, """
                SELECT d.*, s.name AS supplier_name FROM inventory_documents d LEFT JOIN suppliers s ON s.id = d.supplier_id
                WHERE (?::text IS NULL OR d.kind = ?::text) AND (?::text IS NULL OR d.status = ?::text)
                ORDER BY d.document_date DESC, d.created_at DESC, d.id""", (rs, i) -> new DocumentSummary(rs.getObject("id", UUID.class),
                rs.getString("document_number"), rs.getString("kind"), rs.getDate("document_date").toLocalDate(),
                rs.getString("supplier_name"), rs.getString("reference"), rs.getString("description"), rs.getString("status"),
                rs.getBigDecimal("total_value"), rs.getString("prepared_by"), rs.getTimestamp("updated_at").toInstant(),
                rs.getLong("version")), page, size, k, k, s, s);
    }

    @Transactional(readOnly = true)
    public DocumentView get(UUID id) {
        return view(id);
    }

    // ------------------------------------------------------------------ commands

    @Transactional
    public DocumentView create(DocumentRequest req) {
        CurrentActor.requirePermission("INVENTORY_MANAGE");
        List<String> problems = new ArrayList<>();
        if ("GOODS_RECEIPT".equals(req.kind())) {
            if (req.supplierId() == null) {
                problems.add("A goods receipt names the supplier.");
            } else if (!"ACTIVE".equals(single("SELECT status FROM suppliers WHERE id = ?", req.supplierId()))) {
                problems.add("The supplier is not active.");
            }
        }
        if ("ISSUE".equals(req.kind()) && req.costCenterId() == null) {
            problems.add("An issue names the receiving cost centre.");
        }
        int n = 1;
        for (LineRequest l : req.lines()) {
            String at = "Line " + n++ + ": ";
            if (!"ACTIVE".equals(single("SELECT status FROM inventory_items WHERE id = ?", l.itemId()))) {
                problems.add(at + "item is not active.");
            }
            boolean revaluation = "REVALUATION".equals(req.kind());
            if (revaluation) {
                if (l.unitCost() == null) {
                    problems.add(at + "enter the new average unit cost.");
                }
                continue;
            }
            if (l.warehouseId() == null || !"ACTIVE".equals(single("SELECT status FROM warehouses WHERE id = ?", l.warehouseId()))) {
                problems.add(at + "warehouse is not active.");
            }
            if (!"COUNT".equals(req.kind()) && l.quantity().signum() <= 0) {
                problems.add(at + "quantity must be positive.");
            }
            if ("GOODS_RECEIPT".equals(req.kind()) && l.unitCost() == null) {
                problems.add(at + "enter the unit cost on the supplier's delivery.");
            }
            if ("TRANSFER".equals(req.kind()) && (l.toWarehouseId() == null || l.toWarehouseId().equals(l.warehouseId()))) {
                problems.add(at + "choose a different destination warehouse.");
            }
        }
        if ("REVALUATION".equals(req.kind()) && req.lines().stream().map(LineRequest::itemId).distinct().count() < req.lines().size()) {
            problems.add("Each item can appear only once in a price adjustment.");
        }
        if (!problems.isEmpty()) {
            throw new ApiException(ErrorCode.ACCOUNTING_RULE_VIOLATION, problems.get(0), Map.of("violations", problems));
        }
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now(clock));
        jdbc.update("""
                INSERT INTO inventory_documents (id, kind, document_date, supplier_id, reference, cost_center_id, description, status,
                    prepared_by_id, prepared_by, created_at, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?)""", id, req.kind(), Date.valueOf(req.documentDate()),
                "GOODS_RECEIPT".equals(req.kind()) ? req.supplierId() : null, blank(req.reference()),
                "ISSUE".equals(req.kind()) ? req.costCenterId() : null, req.description().trim(), CurrentActor.userId(),
                CurrentActor.username(), now, now, CurrentActor.username());
        n = 1;
        for (LineRequest l : req.lines()) {
            jdbc.update("INSERT INTO inventory_document_lines (document_id, line_no, item_id, warehouse_id, to_warehouse_id, quantity, "
                    + "unit_cost) VALUES (?, ?, ?, ?, ?, ?, ?)", id, n++, l.itemId(), "REVALUATION".equals(req.kind()) ? null : l.warehouseId(),
                    "TRANSFER".equals(req.kind()) ? l.toWarehouseId() : null,
                    "REVALUATION".equals(req.kind()) ? BigDecimal.ZERO.setScale(4) : l.quantity().setScale(4, RoundingMode.UNNECESSARY),
                    List.of("GOODS_RECEIPT", "COUNT", "REVALUATION").contains(req.kind()) ? l.unitCost() : null);
        }
        audit.record("INVENTORY_DOCUMENT_CREATED", req.kind(), id, Map.of("lines", req.lines().size()));
        return view(id);
    }

    /** Columns each document kind needs in an imported movement file. */
    private static final Map<String, List<String>> IMPORT_COLUMNS = Map.of(
            "GOODS_RECEIPT", List.of("item_code", "warehouse_code", "quantity", "unit_cost"),
            "ISSUE", List.of("item_code", "warehouse_code", "quantity"),
            "COUNT", List.of("item_code", "warehouse_code", "quantity"),
            "TRANSFER", List.of("item_code", "warehouse_code", "to_warehouse_code", "quantity"),
            "REVALUATION", List.of("item_code", "unit_cost"));
    static final int IMPORT_MAX_ROWS = 500;

    /**
     * Movement import (MS §11.7): a CSV of lines becomes a draft document of the given kind, validated as a whole (every
     * problem reported with its row number, nothing created unless all rows are valid). The draft is then reviewed and
     * posted by a second person like any other. Columns by kind are in {@link #IMPORT_COLUMNS}; COUNT may add unit_cost.
     */
    @Transactional
    public DocumentView importLines(String kind, LocalDate documentDate, UUID supplierId, String reference, UUID costCenterId,
            String description, String fileName, byte[] content) {
        CurrentActor.requirePermission("INVENTORY_MANAGE");
        List<String> required = IMPORT_COLUMNS.get(kind);
        if (required == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown document kind " + kind + ".");
        }
        List<String> rows = Csv.lines(content);
        if (rows.size() < 2) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The file needs a header row and at least one line.");
        }
        if (rows.size() - 1 > IMPORT_MAX_ROWS) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "At most " + IMPORT_MAX_ROWS + " lines per document; split the file.");
        }
        List<String> header = Csv.split(rows.get(0)).stream().map(h -> h.trim().toLowerCase(Locale.ROOT)).toList();
        List<String> missing = required.stream().filter(c -> !header.contains(c)).toList();
        if (!missing.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Missing column(s): " + String.join(", ", missing) + ". Expected: "
                    + String.join(", ", required) + ("COUNT".equals(kind) ? " (and optionally unit_cost)" : "") + ".");
        }
        Map<String, UUID> items = new HashMap<>();
        jdbc.query("SELECT code, id FROM inventory_items WHERE status = 'ACTIVE'", rs -> { items.put(rs.getString(1), rs.getObject(2, UUID.class)); });
        Map<String, UUID> warehouses = new HashMap<>();
        jdbc.query("SELECT code, id FROM warehouses WHERE status = 'ACTIVE'", rs -> { warehouses.put(rs.getString(1), rs.getObject(2, UUID.class)); });
        List<String> problems = new ArrayList<>();
        List<LineRequest> lines = new ArrayList<>();
        for (int r = 1; r < rows.size(); r++) {
            List<String> f = Csv.split(rows.get(r));
            String at = "Row " + (r + 1) + ": ";
            Function<String, String> col = name -> {
                int i = header.indexOf(name);
                return i < 0 || i >= f.size() ? "" : f.get(i).trim();
            };
            UUID item = items.get(col.apply("item_code").toUpperCase(Locale.ROOT));
            if (item == null) {
                item = items.get(col.apply("item_code"));
            }
            if (item == null) {
                problems.add(at + "no active item " + col.apply("item_code") + ".");
            }
            UUID wh = null;
            UUID to = null;
            if (!"REVALUATION".equals(kind)) {
                wh = warehouses.get(col.apply("warehouse_code"));
                if (wh == null) {
                    problems.add(at + "no active warehouse " + col.apply("warehouse_code") + ".");
                }
                if ("TRANSFER".equals(kind)) {
                    to = warehouses.get(col.apply("to_warehouse_code"));
                    if (to == null) {
                        problems.add(at + "no active warehouse " + col.apply("to_warehouse_code") + ".");
                    }
                }
            }
            BigDecimal qty = "REVALUATION".equals(kind) ? BigDecimal.ZERO : decimal(col.apply("quantity"), 4, at + "quantity", problems);
            BigDecimal unit = header.contains("unit_cost") && !col.apply("unit_cost").isEmpty()
                    ? decimal(col.apply("unit_cost"), 6, at + "unit_cost", problems) : null;
            if (item != null && qty != null) {
                lines.add(new LineRequest(item, wh, to, qty, unit));
            }
        }
        if (!problems.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The file was not imported: " + problems.size() + " problem(s).",
                    Map.of("violations", problems.stream().limit(100).toList()));
        }
        DocumentView created = create(new DocumentRequest(kind, documentDate, supplierId, reference, costCenterId, description, lines));
        audit.record("INVENTORY_DOCUMENT_IMPORTED", kind, created.id(), Map.of("file", String.valueOf(fileName), "lines", lines.size(),
                "sha256", sha256(content)));
        return created;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BigDecimal decimal(String text, int scale, String what, List<String> problems) {
        try {
            BigDecimal v = new BigDecimal(text.replace(",", ""));
            if (v.signum() < 0 || v.scale() > scale) {
                problems.add(what + " must be zero or more with at most " + scale + " decimal places.");
                return null;
            }
            return v;
        } catch (NumberFormatException e) {
            problems.add(what + " is not a number (" + text + ").");
            return null;
        }
    }

    @Transactional
    public DocumentView post(UUID id, long version) {
        CurrentActor.requirePermission("JOURNAL_POST");
        Map<String, Object> d = lockVersioned(id, version);
        if (!"DRAFT".equals(d.get("status"))) {
            throw state("Only draft documents can be posted.");
        }
        if (CurrentActor.userId().equals(d.get("prepared_by_id"))) {
            throw new ApiException(ErrorCode.SEGREGATION_OF_DUTIES, "You prepared this document and cannot post it.");
        }
        String kind = (String) d.get("kind");
        LocalDate date = ((Date) d.get("document_date")).toLocalDate();
        List<Map<String, Object>> lines = jdbc.queryForList("""
                SELECT l.*, i.code AS item_code, i.inventory_account_id, i.consumption_account_id, i.status AS item_status
                FROM inventory_document_lines l JOIN inventory_items i ON i.id = l.item_id WHERE l.document_id = ? ORDER BY l.line_no""", id);
        // Cost pools and warehouse balances are locked in a fixed order so concurrent postings cannot deadlock or interleave.
        Map<UUID, BigDecimal[]> pools = new HashMap<>();
        for (UUID item : new TreeSet<>(lines.stream().map(l -> (UUID) l.get("item_id")).toList())) {
            Map<String, Object> v = jdbc.queryForMap("SELECT quantity, value FROM inventory_valuations WHERE item_id = ? FOR UPDATE", item);
            pools.put(item, new BigDecimal[] {(BigDecimal) v.get("quantity"), (BigDecimal) v.get("value")});
        }
        List<String> problems = new ArrayList<>();
        Map<UUID, BigDecimal> ledger = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        UUID grir = "GOODS_RECEIPT".equals(kind) ? mappings.require("INVENTORY_GRIR") : null;
        UUID variance = "COUNT".equals(kind) ? mappings.require("INVENTORY_VARIANCE") : null;
        UUID revaluationAccount = "REVALUATION".equals(kind) ? mappings.require("INVENTORY_REVALUATION") : null;
        Timestamp now = Timestamp.from(Instant.now(clock));
        for (Map<String, Object> l : lines) {
            String at = "Line " + l.get("line_no") + " (" + l.get("item_code") + "): ";
            if (!"ACTIVE".equals(l.get("item_status"))) {
                problems.add(at + "item is no longer active.");
                continue;
            }
            UUID item = (UUID) l.get("item_id");
            UUID wh = (UUID) l.get("warehouse_id");
            UUID invAcct = (UUID) l.get("inventory_account_id");
            BigDecimal qty = (BigDecimal) l.get("quantity");
            BigDecimal[] pool = pools.get(item);
            BigDecimal onHand = wh == null ? BigDecimal.ZERO : lockBalance(item, wh);
            BigDecimal value;
            switch (kind) {
                case "REVALUATION" -> {
                    // Restates the whole pool at the new average unit cost; quantities do not change.
                    if (pool[0].signum() == 0) {
                        problems.add(at + "no stock on hand to revalue.");
                        continue;
                    }
                    BigDecimal restated = Money.of(pool[0].multiply((BigDecimal) l.get("unit_cost")));
                    value = restated.subtract(pool[1]);
                    pool[1] = restated;
                    movement(id, l, null, "REVALUATION", date, BigDecimal.ZERO.setScale(4), value, pool, now);
                    ledger.merge(invAcct, value, BigDecimal::add);
                    ledger.merge(revaluationAccount, value.negate(), BigDecimal::add);
                }
                case "GOODS_RECEIPT" -> {
                    value = Money.of(qty.multiply((BigDecimal) l.get("unit_cost")));
                    pool[0] = pool[0].add(qty);
                    pool[1] = pool[1].add(value);
                    setBalance(item, wh, onHand.add(qty));
                    movement(id, l, wh, "RECEIPT", date, qty, value, pool, now);
                    ledger.merge(invAcct, value, BigDecimal::add);
                    ledger.merge(grir, value.negate(), BigDecimal::add);
                }
                case "ISSUE" -> {
                    if (onHand.compareTo(qty) < 0) {
                        problems.add(at + "only " + onHand.stripTrailingZeros().toPlainString() + " on hand in the warehouse.");
                        continue;
                    }
                    value = issueValue(pool, qty);
                    pool[0] = pool[0].subtract(qty);
                    pool[1] = pool[1].subtract(value);
                    setBalance(item, wh, onHand.subtract(qty));
                    movement(id, l, wh, "ISSUE", date, qty.negate(), value.negate(), pool, now);
                    ledger.merge((UUID) l.get("consumption_account_id"), value, BigDecimal::add);
                    ledger.merge(invAcct, value.negate(), BigDecimal::add);
                }
                case "TRANSFER" -> {
                    if (onHand.compareTo(qty) < 0) {
                        problems.add(at + "only " + onHand.stripTrailingZeros().toPlainString() + " on hand in the source warehouse.");
                        continue;
                    }
                    UUID to = (UUID) l.get("to_warehouse_id");
                    setBalance(item, wh, onHand.subtract(qty));
                    movement(id, l, wh, "TRANSFER_OUT", date, qty.negate(), Money.ZERO, pool, now);
                    setBalance(item, to, lockBalance(item, to).add(qty));
                    movement(id, l, to, "TRANSFER_IN", date, qty, Money.ZERO, pool, now);
                    value = Money.ZERO;
                }
                default -> {
                    BigDecimal diff = qty.subtract(onHand);
                    jdbc.update("UPDATE inventory_document_lines SET system_quantity = ? WHERE document_id = ? AND line_no = ?", onHand, id,
                            l.get("line_no"));
                    if (diff.signum() < 0) {
                        value = issueValue(pool, diff.negate());
                        pool[0] = pool[0].add(diff);
                        pool[1] = pool[1].subtract(value);
                        setBalance(item, wh, qty);
                        movement(id, l, wh, "COUNT_LOSS", date, diff, value.negate(), pool, now);
                        ledger.merge(variance, value, BigDecimal::add);
                        ledger.merge(invAcct, value.negate(), BigDecimal::add);
                        value = value.negate();
                    } else if (diff.signum() > 0) {
                        BigDecimal unit = pool[0].signum() > 0 ? pool[1].divide(pool[0], 6, RoundingMode.HALF_EVEN)
                                : (BigDecimal) l.get("unit_cost");
                        if (unit == null) {
                            problems.add(at + "no cost on record; enter a unit cost for the surplus.");
                            continue;
                        }
                        value = Money.of(diff.multiply(unit));
                        pool[0] = pool[0].add(diff);
                        pool[1] = pool[1].add(value);
                        setBalance(item, wh, qty);
                        movement(id, l, wh, "COUNT_GAIN", date, diff, value, pool, now);
                        ledger.merge(invAcct, value, BigDecimal::add);
                        ledger.merge(variance, value.negate(), BigDecimal::add);
                    } else {
                        value = Money.ZERO;
                    }
                }
            }
            jdbc.update("UPDATE inventory_document_lines SET value = ? WHERE document_id = ? AND line_no = ?", value, id, l.get("line_no"));
            total = total.add(value.abs());
        }
        if (!problems.isEmpty()) {
            throw new ApiException(ErrorCode.ACCOUNTING_RULE_VIOLATION, problems.get(0), Map.of("violations", problems));
        }
        for (Map.Entry<UUID, BigDecimal[]> p : pools.entrySet()) {
            jdbc.update("UPDATE inventory_valuations SET quantity = ?, value = ? WHERE item_id = ?", p.getValue()[0], p.getValue()[1], p.getKey());
        }
        String number = numbering.next(PREFIX.get(kind), date.getYear());
        UUID entryId = null;
        List<LineData> entry = new ArrayList<>();
        UUID costCenter = (UUID) d.get("cost_center_id");
        ledger.forEach((account, net) -> {
            if (net.signum() != 0) {
                UUID cc = needsCostCenter(account) ? costCenter : null;
                entry.add(net.signum() > 0 ? new LineData(account, cc, null, number, Money.of(net), Money.ZERO)
                        : new LineData(account, cc, null, number, Money.ZERO, Money.of(net.negate())));
            }
        });
        if (!entry.isEmpty()) {
            entryId = posting.postSubledger(new PostingService.SubledgerEntry("SL_INV_" + kind, id.toString(), date,
                    label(kind) + " " + number + ": " + d.get("description"), entry, (UUID) d.get("prepared_by_id"),
                    (String) d.get("prepared_by"), false)).entryId();
        }
        jdbc.update("UPDATE inventory_documents SET status = 'POSTED', document_number = ?, total_value = ?, posted_by = ?, "
                + "posted_entry_id = ?, version = version + 1, updated_at = ?, updated_by = ? WHERE id = ?", number, Money.of(total),
                CurrentActor.username(), entryId, now, CurrentActor.username(), id);
        notifications.notify((UUID) d.get("prepared_by_id"), "DOCUMENT_POSTED", label(kind) + " " + number + " was posted by "
                + CurrentActor.username(), null, "/inventory/documents/" + id);
        audit.record("INVENTORY_DOCUMENT_POSTED", kind, id, Map.of("documentNumber", number, "value", Money.of(total).toPlainString()));
        return view(id);
    }

    @Transactional
    public DocumentView cancel(UUID id, long version) {
        Map<String, Object> d = lockVersioned(id, version);
        if (!"DRAFT".equals(d.get("status"))) {
            throw state("Only draft documents can be cancelled; posted movements are corrected by a new document.");
        }
        if (!CurrentActor.hasPermission("INVENTORY_MANAGE") && !CurrentActor.hasPermission("JOURNAL_POST")) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "You cannot cancel this document.");
        }
        jdbc.update("UPDATE inventory_documents SET status = 'CANCELLED', version = version + 1, updated_at = ?, updated_by = ? WHERE id = ?",
                Timestamp.from(Instant.now(clock)), CurrentActor.username(), id);
        audit.record("INVENTORY_DOCUMENT_CANCELLED", (String) d.get("kind"), id, Map.of());
        return view(id);
    }

    // ------------------------------------------------------------------ helpers

    /** Value of {@code qty} taken from the pool at average cost; the last units take whatever value remains. */
    static BigDecimal issueValue(BigDecimal[] pool, BigDecimal qty) {
        if (qty.compareTo(pool[0]) >= 0) {
            return Money.of(pool[1]);
        }
        return Money.of(pool[1].multiply(qty).divide(pool[0], 2, RoundingMode.HALF_EVEN));
    }

    private BigDecimal lockBalance(UUID item, UUID wh) {
        jdbc.update("INSERT INTO inventory_balances (item_id, warehouse_id, quantity) VALUES (?, ?, 0) ON CONFLICT DO NOTHING", item, wh);
        return jdbc.queryForObject("SELECT quantity FROM inventory_balances WHERE item_id = ? AND warehouse_id = ? FOR UPDATE",
                BigDecimal.class, item, wh);
    }

    private void setBalance(UUID item, UUID wh, BigDecimal qty) {
        jdbc.update("UPDATE inventory_balances SET quantity = ? WHERE item_id = ? AND warehouse_id = ?", qty, item, wh);
    }

    private void movement(UUID doc, Map<String, Object> line, UUID wh, String type, LocalDate date, BigDecimal qty, BigDecimal value,
            BigDecimal[] pool, Timestamp now) {
        jdbc.update("""
                INSERT INTO inventory_movements (id, document_id, line_no, item_id, warehouse_id, movement_type, movement_date, quantity,
                    value, pool_quantity, pool_value, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", UUID.randomUUID(), doc,
                line.get("line_no"), line.get("item_id"), wh, type, Date.valueOf(date), qty, value, pool[0], pool[1], now);
    }

    private boolean needsCostCenter(UUID account) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT requires_cost_center FROM chart_of_accounts WHERE id = ?", Boolean.class, account));
    }

    private String single(String sql, UUID id) {
        List<String> r = jdbc.queryForList(sql, String.class, id);
        return r.isEmpty() ? null : r.get(0);
    }

    private Map<String, Object> lockVersioned(UUID id, long version) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM inventory_documents WHERE id = ? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Inventory document");
        }
        if (((Number) rows.get(0).get("version")).longValue() != version) {
            throw ApiException.staleVersion();
        }
        return rows.get(0);
    }

    private DocumentView view(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT d.*, s.name AS supplier_name, cc.code AS cc_code, e.entry_number FROM inventory_documents d
                LEFT JOIN suppliers s ON s.id = d.supplier_id LEFT JOIN cost_centers cc ON cc.id = d.cost_center_id
                LEFT JOIN journal_entries e ON e.id = d.posted_entry_id WHERE d.id = ?""", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Inventory document");
        }
        Map<String, Object> d = rows.get(0);
        List<LineView> lines = jdbc.query("""
                SELECT l.*, i.code, i.name, i.unit_of_measure, w.code AS wh, t.code AS to_wh FROM inventory_document_lines l
                JOIN inventory_items i ON i.id = l.item_id LEFT JOIN warehouses w ON w.id = l.warehouse_id
                LEFT JOIN warehouses t ON t.id = l.to_warehouse_id WHERE l.document_id = ? ORDER BY l.line_no""",
                (rs, i) -> new LineView(rs.getInt("line_no"), rs.getObject("item_id", UUID.class), rs.getString("code"), rs.getString("name"),
                        rs.getString("unit_of_measure"), rs.getObject("warehouse_id", UUID.class), rs.getString("wh"), rs.getString("to_wh"),
                        rs.getBigDecimal("quantity"), rs.getBigDecimal("unit_cost"), rs.getBigDecimal("system_quantity"),
                        rs.getBigDecimal("value")), id);
        String status = (String) d.get("status");
        boolean preparer = CurrentActor.userId().equals(d.get("prepared_by_id"));
        List<String> actions = new ArrayList<>();
        if ("DRAFT".equals(status)) {
            if (!preparer && CurrentActor.hasPermission("JOURNAL_POST")) {
                actions.add("POST");
            }
            if (CurrentActor.hasPermission("INVENTORY_MANAGE") || CurrentActor.hasPermission("JOURNAL_POST")) {
                actions.add("CANCEL");
            }
        }
        return new DocumentView(id, (String) d.get("document_number"), (String) d.get("kind"), ((Date) d.get("document_date")).toLocalDate(),
                (UUID) d.get("supplier_id"), (String) d.get("supplier_name"), (String) d.get("reference"), (UUID) d.get("cost_center_id"),
                (String) d.get("cc_code"), (String) d.get("description"), status, (BigDecimal) d.get("total_value"),
                (String) d.get("prepared_by"), (String) d.get("posted_by"), (UUID) d.get("posted_entry_id"), (String) d.get("entry_number"),
                lines, actions, ((Number) d.get("version")).longValue());
    }

    static String label(String kind) {
        return switch (kind) {
            case "GOODS_RECEIPT" -> "Goods receipt";
            case "ISSUE" -> "Stock issue";
            case "COUNT" -> "Stock count";
            case "REVALUATION" -> "Price adjustment";
            default -> "Stock transfer";
        };
    }

    static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static ApiException state(String message) {
        return new ApiException(ErrorCode.INVALID_STATE, message);
    }

    static ApiException rule(String message) {
        return new ApiException(ErrorCode.ACCOUNTING_RULE_VIOLATION, message, Map.of("violations", List.of(message)));
    }
}
