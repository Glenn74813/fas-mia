package com.miaa.cas.inventoryaccounting;

import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.money.Money;
import com.miaa.cas.common.web.JdbcPaging;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.generalledger.AccountMappingService;
import com.miaa.cas.inventoryaccounting.InvDtos.GrIrStatus;
import com.miaa.cas.inventoryaccounting.InvDtos.GrnOpen;
import com.miaa.cas.inventoryaccounting.InvDtos.InventoryReconciliation;
import com.miaa.cas.inventoryaccounting.InvDtos.InvoiceOpen;
import com.miaa.cas.inventoryaccounting.InvDtos.MatchRequest;
import com.miaa.cas.inventoryaccounting.InvDtos.MovementView;
import com.miaa.cas.inventoryaccounting.InvDtos.ReconRow;
import com.miaa.cas.security.CurrentActor;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Item movement history, GR/IR matching of goods receipts to supplier invoices charged to the clearing
 * account, and the reconciliation of the inventory and GR/IR registers to the general ledger.
 */
@Service
public class InventoryReportService {

    private final AccountMappingService mappings;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public InventoryReportService(AccountMappingService mappings, AuditService audit, JdbcTemplate jdbc, Clock clock) {
        this.mappings = mappings;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<MovementView> movements(UUID itemId, int page, int size) {
        return JdbcPaging.query(jdbc, """
                SELECT m.*, d.document_number, d.kind, w.code AS wh FROM inventory_movements m
                JOIN inventory_documents d ON d.id = m.document_id LEFT JOIN warehouses w ON w.id = m.warehouse_id
                WHERE m.item_id = ? ORDER BY m.created_at, m.line_no, m.quantity, m.id""", (rs, i) -> {
            BigDecimal pq = rs.getBigDecimal("pool_quantity");
            BigDecimal pv = rs.getBigDecimal("pool_value");
            return new MovementView(rs.getTimestamp("created_at").toInstant(), rs.getDate("movement_date").toLocalDate(),
                    rs.getString("document_number"), rs.getString("kind"), rs.getString("movement_type"), rs.getString("wh"),
                    rs.getBigDecimal("quantity"), rs.getBigDecimal("value"), pq, pv,
                    pq.signum() == 0 ? null : pv.divide(pq, 6, RoundingMode.HALF_EVEN));
        }, page, size, itemId);
    }

    @Transactional(readOnly = true)
    public GrIrStatus grIr() {
        UUID grir = mappings.require("INVENTORY_GRIR");
        List<GrnOpen> receipts = jdbc.query("""
                SELECT d.id, d.document_number, d.document_date, d.supplier_id, s.name, d.total_value,
                       coalesce((SELECT sum(amount) FROM gr_ir_matches m WHERE m.grn_id = d.id), 0) AS matched
                FROM inventory_documents d JOIN suppliers s ON s.id = d.supplier_id
                WHERE d.kind = 'GOODS_RECEIPT' AND d.status = 'POSTED' ORDER BY d.document_date, d.document_number""",
                (rs, i) -> new GrnOpen(rs.getObject(1, UUID.class), rs.getString(2), rs.getDate(3).toLocalDate(), rs.getObject(4, UUID.class),
                        rs.getString(5), rs.getBigDecimal(6), Money.of(rs.getBigDecimal("matched")),
                        Money.of(rs.getBigDecimal(6).subtract(rs.getBigDecimal("matched")))));
        List<InvoiceOpen> invoices = jdbc.query("""
                SELECT i.id, i.invoice_number, i.invoice_date, i.supplier_id, s.name,
                       (SELECT sum(l.net_amount) FROM supplier_invoice_lines l WHERE l.invoice_id = i.id AND l.account_id = ?) AS grir,
                       coalesce((SELECT sum(amount) FROM gr_ir_matches m WHERE m.invoice_id = i.id), 0) AS matched
                FROM supplier_invoices i JOIN suppliers s ON s.id = i.supplier_id
                WHERE i.status = 'POSTED' AND EXISTS (SELECT 1 FROM supplier_invoice_lines l WHERE l.invoice_id = i.id AND l.account_id = ?)
                ORDER BY i.invoice_date, i.invoice_number""", (rs, i) -> new InvoiceOpen(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getDate(3).toLocalDate(), rs.getObject(4, UUID.class), rs.getString(5), Money.of(rs.getBigDecimal("grir")),
                Money.of(rs.getBigDecimal("matched")), Money.of(rs.getBigDecimal("grir").subtract(rs.getBigDecimal("matched")))), grir, grir);
        BigDecimal openReceipts = Money.of(receipts.stream().map(GrnOpen::open).reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal unmatched = Money.of(invoices.stream().map(InvoiceOpen::available).reduce(BigDecimal.ZERO, BigDecimal::add));
        return new GrIrStatus(receipts.stream().filter(r -> r.open().signum() != 0).toList(),
                invoices.stream().filter(v -> v.available().signum() != 0).toList(), openReceipts, unmatched);
    }

    /** Matches part or all of a goods receipt to a supplier invoice of the same supplier charged to GR/IR. */
    @Transactional
    public GrIrStatus match(MatchRequest req) {
        CurrentActor.requirePermission("AP_INVOICE_PREPARE");
        UUID grir = mappings.require("INVENTORY_GRIR");
        List<Map<String, Object>> grn = jdbc.queryForList("SELECT * FROM inventory_documents WHERE id = ? AND kind = 'GOODS_RECEIPT' "
                + "AND status = 'POSTED' FOR UPDATE", req.grnId());
        List<Map<String, Object>> inv = jdbc.queryForList("SELECT * FROM supplier_invoices WHERE id = ? AND status = 'POSTED' FOR UPDATE",
                req.invoiceId());
        if (grn.isEmpty() || inv.isEmpty()) {
            throw ApiException.notFound("Posted goods receipt or supplier invoice");
        }
        if (!grn.get(0).get("supplier_id").equals(inv.get(0).get("supplier_id"))) {
            throw InventoryDocumentService.rule("The goods receipt and the invoice are from different suppliers.");
        }
        BigDecimal amount = Money.of(req.amount());
        BigDecimal grnOpen = ((BigDecimal) grn.get(0).get("total_value")).subtract(matched("grn_id", req.grnId()));
        BigDecimal invGrir = jdbc.queryForObject("SELECT coalesce(sum(net_amount), 0) FROM supplier_invoice_lines WHERE invoice_id = ? "
                + "AND account_id = ?", BigDecimal.class, req.invoiceId(), grir);
        BigDecimal invOpen = invGrir.subtract(matched("invoice_id", req.invoiceId()));
        if (amount.compareTo(grnOpen) > 0 || amount.compareTo(invOpen) > 0) {
            throw InventoryDocumentService.rule("The match exceeds what is open: receipt " + Money.of(grnOpen).toPlainString()
                    + ", invoice " + Money.of(invOpen).toPlainString() + ".");
        }
        jdbc.update("INSERT INTO gr_ir_matches (id, grn_id, invoice_id, amount, created_at, created_by) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), req.grnId(), req.invoiceId(), amount, Timestamp.from(Instant.now(clock)), CurrentActor.username());
        audit.record("GR_IR_MATCHED", "GOODS_RECEIPT", req.grnId(), Map.of("invoice", String.valueOf(inv.get(0).get("invoice_number")),
                "amount", amount.toPlainString()));
        return grIr();
    }

    @Transactional(readOnly = true)
    public InventoryReconciliation reconciliation() {
        List<ReconRow> rows = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT a.id, a.code, a.name,
                       (SELECT coalesce(sum(v.value), 0) FROM inventory_valuations v JOIN inventory_items i ON i.id = v.item_id
                        WHERE i.inventory_account_id = a.id) AS reg,
                       (SELECT coalesce(sum(debit - credit), 0) FROM journal_entry_lines l WHERE l.account_id = a.id) AS gl
                FROM chart_of_accounts a WHERE a.id IN (SELECT inventory_account_id FROM inventory_items) ORDER BY a.code""")) {
            rows.add(row(r, "INVENTORY", (BigDecimal) r.get("reg")));
        }
        UUID grir = mappings.require("INVENTORY_GRIR");
        GrIrStatus g = grIr();
        Map<String, Object> a = jdbc.queryForMap("SELECT code, name, (SELECT coalesce(sum(credit - debit), 0) FROM journal_entry_lines "
                + "WHERE account_id = c.id) AS gl FROM chart_of_accounts c WHERE c.id = ?", grir);
        rows.add(row(a, "GR_IR", g.openReceipts().subtract(g.unmatchedInvoices())));
        return new InventoryReconciliation(rows, rows.stream().allMatch(x -> x.difference().signum() == 0));
    }

    private BigDecimal matched(String column, UUID id) {
        return jdbc.queryForObject("SELECT coalesce(sum(amount), 0) FROM gr_ir_matches WHERE " + column + " = ?", BigDecimal.class, id);
    }

    private static ReconRow row(Map<String, Object> r, String side, BigDecimal register) {
        BigDecimal reg = Money.of(register);
        BigDecimal gl = Money.of((BigDecimal) r.get("gl"));
        return new ReconRow((String) r.get("code"), (String) r.get("name"), side, reg, gl, Money.of(gl.subtract(reg)));
    }
}
