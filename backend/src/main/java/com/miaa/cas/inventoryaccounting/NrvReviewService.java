package com.miaa.cas.inventoryaccounting;

import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.money.Money;
import com.miaa.cas.inventoryaccounting.InvDtos.DocumentRequest;
import com.miaa.cas.inventoryaccounting.InvDtos.LineRequest;
import com.miaa.cas.security.CurrentActor;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lower of cost and net realisable value review (TOR Annex B.4). A review snapshots, as of a date, every item with
 * stock: quantity and weighted-average cost from the last movement on or before the date, the last movement, the
 * quantity issued in the twelve months before, and the months of stock that represents (coverage). An item is
 * slow-moving when nothing was issued in those twelve months or its coverage exceeds the threshold
 * ({@code cas.inventory.slow-moving-coverage-months}, 24, a proposal). Market values per unit are entered or imported;
 * where one is below cost, the write-down is the difference times the quantity. Proposing creates a draft
 * REVALUATION document restating those items at market value, which is reviewed and posted as usual.
 */
@Service
public class NrvReviewService {

    public record Line(UUID itemId, String itemCode, String itemName, String unitOfMeasure, BigDecimal quantity, BigDecimal unitCost,
            BigDecimal carryingValue, LocalDate lastMovementDate, BigDecimal issued12m, BigDecimal coverageMonths, boolean slowMoving,
            BigDecimal marketUnitValue, String priceSource, BigDecimal writeDown) {
    }

    public record Review(UUID id, LocalDate asOf, String status, BigDecimal coverageThreshold, String note, UUID proposedDocumentId,
            String preparedBy, Instant createdAt, BigDecimal totalCarryingValue, BigDecimal totalWriteDown, int slowMovingItems,
            int pricedItems, List<Line> lines, long version) {
    }

    public record Price(String itemCode, BigDecimal marketUnitValue, String source) {
    }

    private final JdbcTemplate jdbc;
    private final InventoryDocumentService documents;
    private final AuditService audit;
    private final Clock clock;
    private final BigDecimal threshold;

    public NrvReviewService(JdbcTemplate jdbc, InventoryDocumentService documents, AuditService audit, Clock clock,
            @Value("${cas.inventory.slow-moving-coverage-months:24}") BigDecimal threshold) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.audit = audit;
        this.clock = clock;
        this.threshold = threshold;
    }

    @Transactional(readOnly = true)
    public List<Review> list() {
        return jdbc.queryForList("SELECT id FROM inventory_nrv_reviews ORDER BY as_of DESC, created_at DESC", UUID.class).stream()
                .map(id -> get(id, false)).toList();
    }

    @Transactional
    public Review create(LocalDate asOf) {
        CurrentActor.requirePermission("INVENTORY_MANAGE");
        if (asOf == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Choose the review date.");
        }
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now(clock));
        jdbc.update("INSERT INTO inventory_nrv_reviews (id, as_of, status, coverage_threshold, prepared_by_id, prepared_by, created_at, updated_at) "
                + "VALUES (?, ?, 'OPEN', ?, ?, ?, ?, ?)", id, Date.valueOf(asOf), threshold, CurrentActor.userId(), CurrentActor.username(), now, now);
        // Each item's pool as of the date (its last movement on or before it), and its issues in the twelve months before.
        int n = jdbc.update("""
                INSERT INTO inventory_nrv_lines (review_id, item_id, quantity, unit_cost, carrying_value, last_movement_date, issued_12m,
                    coverage_months, slow_moving)
                SELECT ?, p.item_id, p.pool_quantity, round(p.pool_value / p.pool_quantity, 6), p.pool_value, p.movement_date, x.issued,
                       CASE WHEN x.issued > 0 THEN round(p.pool_quantity / (x.issued / 12), 1) END,
                       x.issued = 0 OR p.pool_quantity / (x.issued / 12) > ?
                FROM (SELECT DISTINCT ON (m.item_id) m.item_id, m.pool_quantity, m.pool_value, m.movement_date
                      FROM inventory_movements m WHERE m.movement_date <= ?
                      ORDER BY m.item_id, m.movement_date DESC, m.created_at DESC, m.line_no DESC) p
                CROSS JOIN LATERAL (SELECT coalesce(-sum(i.quantity), 0) AS issued FROM inventory_movements i
                      WHERE i.item_id = p.item_id AND i.movement_type = 'ISSUE' AND i.movement_date > ? AND i.movement_date <= ?) x
                WHERE p.pool_quantity > 0""", id, threshold, Date.valueOf(asOf), Date.valueOf(asOf.minusYears(1)), Date.valueOf(asOf));
        audit.record("NRV_REVIEW_CREATED", "NRV_REVIEW", id, Map.of("asOf", asOf.toString(), "items", n));
        return get(id, true);
    }

    /** Market values per unit for items in the review (entered on the screen or imported); a blank value clears one. */
    @Transactional
    public Review setPrices(UUID id, long version, List<Price> prices) {
        CurrentActor.requirePermission("INVENTORY_MANAGE");
        lockOpen(id, version);
        List<String> problems = new ArrayList<>();
        for (Price p : prices) {
            List<UUID> item = jdbc.queryForList("SELECT l.item_id FROM inventory_nrv_lines l JOIN inventory_items i ON i.id = l.item_id "
                    + "WHERE l.review_id = ? AND i.code = ?", UUID.class, id, p.itemCode());
            if (item.isEmpty()) {
                problems.add(p.itemCode() + ": not in this review (no stock on the review date).");
            } else if (p.marketUnitValue() != null && p.marketUnitValue().signum() < 0) {
                problems.add(p.itemCode() + ": the market value cannot be negative.");
            } else {
                jdbc.update("UPDATE inventory_nrv_lines SET market_unit_value = ?, price_source = ?, write_down = CASE WHEN ?::numeric IS NOT NULL "
                        + "AND ?::numeric < unit_cost THEN round(quantity * (unit_cost - ?::numeric), 2) ELSE 0 END WHERE review_id = ? AND item_id = ?",
                        p.marketUnitValue(), p.source() == null || p.source().isBlank() ? null : p.source().trim(), p.marketUnitValue(),
                        p.marketUnitValue(), p.marketUnitValue(), id, item.get(0));
            }
        }
        if (!problems.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, String.join(" ", problems));
        }
        touch(id);
        audit.record("NRV_REVIEW_PRICES_SET", "NRV_REVIEW", id, Map.of("prices", prices.size()));
        return get(id, true);
    }

    /** Creates the draft revaluation for every item whose market value is below cost. */
    @Transactional
    public Review propose(UUID id, long version, String note) {
        CurrentActor.requirePermission("INVENTORY_MANAGE");
        Map<String, Object> r = lockOpen(id, version);
        List<LineRequest> lines = jdbc.query("SELECT item_id, market_unit_value FROM inventory_nrv_lines WHERE review_id = ? AND write_down > 0 "
                + "ORDER BY item_id", (rs, i) -> new LineRequest(rs.getObject(1, UUID.class), null, null, BigDecimal.ZERO,
                        rs.getBigDecimal(2).setScale(6, RoundingMode.HALF_UP)), id);
        if (lines.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_STATE, "No item's market value is below its cost; there is nothing to write down.");
        }
        LocalDate asOf = ((Date) r.get("as_of")).toLocalDate();
        BigDecimal total = jdbc.queryForObject("SELECT sum(write_down) FROM inventory_nrv_lines WHERE review_id = ?", BigDecimal.class, id);
        UUID doc = documents.create(new DocumentRequest("REVALUATION", asOf, null, "NRV-" + asOf, null,
                "Write-down to lower of cost and net realisable value as of " + asOf + " (" + Money.of(total).toPlainString() + ")"
                        + (note == null || note.isBlank() ? "" : ": " + note.trim()), lines)).id();
        jdbc.update("UPDATE inventory_nrv_reviews SET status = 'PROPOSED', proposed_document_id = ?, note = ?, updated_at = ?, version = version + 1 "
                + "WHERE id = ?", doc, note == null || note.isBlank() ? null : note.trim(), Timestamp.from(Instant.now(clock)), id);
        audit.record("NRV_WRITE_DOWN_PROPOSED", "NRV_REVIEW", id, Map.of("documentId", doc.toString(), "writeDown", total, "items", lines.size()));
        return get(id, true);
    }

    @Transactional
    public Review cancel(UUID id, long version) {
        CurrentActor.requirePermission("INVENTORY_MANAGE");
        lockOpen(id, version);
        jdbc.update("UPDATE inventory_nrv_reviews SET status = 'CANCELLED', updated_at = ?, version = version + 1 WHERE id = ?",
                Timestamp.from(Instant.now(clock)), id);
        audit.record("NRV_REVIEW_CANCELLED", "NRV_REVIEW", id, Map.of());
        return get(id, true);
    }

    @Transactional(readOnly = true)
    public Review get(UUID id, boolean withLines) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT r.*, coalesce(sum(l.carrying_value), 0) AS carrying, coalesce(sum(l.write_down), 0) AS write_down,
                       count(*) FILTER (WHERE l.slow_moving) AS slow, count(l.market_unit_value) AS priced
                FROM inventory_nrv_reviews r LEFT JOIN inventory_nrv_lines l ON l.review_id = r.id WHERE r.id = ? GROUP BY r.id""", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("NRV review");
        }
        Map<String, Object> r = rows.get(0);
        List<Line> lines = withLines ? jdbc.query("""
                SELECT l.*, i.code, i.name, i.unit_of_measure FROM inventory_nrv_lines l JOIN inventory_items i ON i.id = l.item_id
                WHERE l.review_id = ? ORDER BY l.slow_moving DESC, i.code""", (rs, i) -> line(rs), id) : List.of();
        return new Review(id, ((Date) r.get("as_of")).toLocalDate(), (String) r.get("status"), (BigDecimal) r.get("coverage_threshold"),
                (String) r.get("note"), (UUID) r.get("proposed_document_id"), (String) r.get("prepared_by"),
                ((Timestamp) r.get("created_at")).toInstant(), Money.of((BigDecimal) r.get("carrying")), Money.of((BigDecimal) r.get("write_down")),
                ((Number) r.get("slow")).intValue(), ((Number) r.get("priced")).intValue(), lines, ((Number) r.get("version")).longValue());
    }

    private static Line line(ResultSet rs) throws SQLException {
        Date last = rs.getDate("last_movement_date");
        return new Line(rs.getObject("item_id", UUID.class), rs.getString("code"), rs.getString("name"), rs.getString("unit_of_measure"),
                rs.getBigDecimal("quantity").stripTrailingZeros(), rs.getBigDecimal("unit_cost"), rs.getBigDecimal("carrying_value"),
                last == null ? null : last.toLocalDate(), rs.getBigDecimal("issued_12m").stripTrailingZeros(), rs.getBigDecimal("coverage_months"),
                rs.getBoolean("slow_moving"), rs.getBigDecimal("market_unit_value"), rs.getString("price_source"), rs.getBigDecimal("write_down"));
    }

    private Map<String, Object> lockOpen(UUID id, long version) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT status, as_of, version FROM inventory_nrv_reviews WHERE id = ? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("NRV review");
        }
        if (((Number) rows.get(0).get("version")).longValue() != version) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION, "The review was changed by someone else. Reload and try again.");
        }
        if (!"OPEN".equals(rows.get(0).get("status"))) {
            throw new ApiException(ErrorCode.INVALID_STATE, "The review is " + rows.get(0).get("status").toString().toLowerCase() + ".");
        }
        return rows.get(0);
    }

    private void touch(UUID id) {
        jdbc.update("UPDATE inventory_nrv_reviews SET updated_at = ?, version = version + 1 WHERE id = ?", Timestamp.from(Instant.now(clock)), id);
    }
}
