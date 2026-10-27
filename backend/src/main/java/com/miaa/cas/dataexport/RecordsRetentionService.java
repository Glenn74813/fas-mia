package com.miaa.cas.dataexport;

import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.security.CurrentActor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records retention (TOR §4.5, Annex A.9): the retention schedule per fiscal year, its archives, and legal holds. Every
 * fiscal year is kept at least {@code cas.records.retention-years} (10) after it ends. Disposal is not offered: posted
 * records and the audit trail stay immutable until MIAA decides how lawful disposal works (C-05, C-13), and a year
 * under a legal hold could not be disposed of in any case.
 */
@Service
public class RecordsRetentionService {

    public static final String MANAGE = "RECORDS_RETENTION_MANAGE";
    static final String DISPOSAL = "Disposal is not available: posted records and the audit trail are kept until MIAA decides how lawful "
            + "disposal works (C-05, C-13).";

    public record Hold(UUID id, UUID fiscalYearId, Integer fiscalYear, String reason, String reference, String placedBy, Instant placedAt,
            String releasedBy, Instant releasedAt, String releaseNote, long version) {
    }

    public record Archive(UUID exportId, String status, String fileName, String sha256, Instant finishedAt, String requestedBy,
            String approvedBy) {
    }

    public record YearRetention(UUID fiscalYearId, int fiscalYear, LocalDate start, LocalDate end, String status, Instant closedAt,
            LocalDate retainUntil, boolean retentionElapsed, boolean onHold, List<Archive> archives) {
    }

    public record Schedule(int retentionYears, String disposal, List<YearRetention> years, List<Hold> holds) {
    }

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Clock clock;
    private final int retentionYears;

    public RecordsRetentionService(JdbcTemplate jdbc, AuditService audit, Clock clock,
            @Value("${cas.records.retention-years:10}") int retentionYears) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
        this.retentionYears = retentionYears;
    }

    @Transactional(readOnly = true)
    public Schedule schedule() {
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Manila")));
        List<Hold> holds = jdbc.query(HOLDS + " ORDER BY h.released_at IS NULL DESC, h.placed_at DESC", (rs, i) -> hold(rs));
        boolean globalHold = holds.stream().anyMatch(h -> h.releasedAt() == null && h.fiscalYearId() == null);
        Map<UUID, List<Archive>> archives = new HashMap<>();
        jdbc.query("""
                SELECT fiscal_year_id, id, status, file_name, sha256, finished_at, requested_by, decided_by FROM data_exports
                WHERE kind = 'ARCHIVE' AND status IN ('DONE', 'EXPIRED') ORDER BY finished_at DESC""", (ResultSet rs) -> {
            archives.computeIfAbsent(rs.getObject(1, UUID.class), k -> new java.util.ArrayList<>()).add(new Archive(rs.getObject(2, UUID.class),
                    rs.getString(3), rs.getString(4), rs.getString(5), ts(rs, "finished_at"), rs.getString(7), rs.getString(8)));
        });
        List<YearRetention> years = jdbc.query("SELECT id, fiscal_year, start_date, end_date, status, closed_at FROM fiscal_years "
                + "ORDER BY fiscal_year DESC", (rs, i) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    LocalDate end = rs.getDate("end_date").toLocalDate();
                    LocalDate until = end.plusYears(retentionYears);
                    boolean held = globalHold || holds.stream().anyMatch(h -> h.releasedAt() == null && id.equals(h.fiscalYearId()));
                    return new YearRetention(id, rs.getInt("fiscal_year"), rs.getDate("start_date").toLocalDate(), end, rs.getString("status"),
                            ts(rs, "closed_at"), until, today.isAfter(until), held, archives.getOrDefault(id, List.of()));
                });
        return new Schedule(retentionYears, DISPOSAL, years, holds);
    }

    /** A legal hold on one fiscal year, or with no year on every record. */
    @Transactional
    public Hold place(UUID fiscalYearId, String reason, String reference) {
        CurrentActor.requirePermission(MANAGE);
        String r = reason == null ? "" : reason.trim();
        if (r.length() < 10) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "State the reason for the hold in at least 10 characters.");
        }
        if (fiscalYearId != null && jdbc.queryForObject("SELECT count(*) FROM fiscal_years WHERE id = ?", Long.class, fiscalYearId) == 0) {
            throw ApiException.notFound("Fiscal year");
        }
        UUID id = UUID.randomUUID();
        String ref = reference == null || reference.isBlank() ? null : reference.trim();
        jdbc.update("INSERT INTO legal_holds (id, fiscal_year_id, reason, reference, placed_by_id, placed_by, placed_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, fiscalYearId, r, ref, CurrentActor.userId(), CurrentActor.username(), Timestamp.from(Instant.now(clock)));
        Map<String, Object> details = new HashMap<>(Map.of("reason", r, "scope", fiscalYearId == null ? "ALL" : fiscalYearId.toString()));
        if (ref != null) {
            details.put("reference", ref);
        }
        audit.record("LEGAL_HOLD_PLACED", "LEGAL_HOLD", id, details);
        return get(id);
    }

    @Transactional
    public Hold release(UUID id, String note, long version) {
        CurrentActor.requirePermission(MANAGE);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT released_at, version FROM legal_holds WHERE id = ? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Legal hold");
        }
        if (((Number) rows.get(0).get("version")).longValue() != version) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION, "The hold was changed by someone else. Reload and try again.");
        }
        if (rows.get(0).get("released_at") != null) {
            throw new ApiException(ErrorCode.INVALID_STATE, "The hold has already been released.");
        }
        String n = note == null ? "" : note.trim();
        if (n.length() < 10) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Explain why the hold is released in at least 10 characters.");
        }
        jdbc.update("UPDATE legal_holds SET released_by_id = ?, released_by = ?, released_at = ?, release_note = ?, version = version + 1 "
                + "WHERE id = ?", CurrentActor.userId(), CurrentActor.username(), Timestamp.from(Instant.now(clock)), n, id);
        audit.record("LEGAL_HOLD_RELEASED", "LEGAL_HOLD", id, Map.of("note", n));
        return get(id);
    }

    private static final String HOLDS = "SELECT h.*, fy.fiscal_year FROM legal_holds h LEFT JOIN fiscal_years fy ON fy.id = h.fiscal_year_id";

    private Hold get(UUID id) {
        return jdbc.queryForObject(HOLDS + " WHERE h.id = ?", (rs, i) -> hold(rs), id);
    }

    private static Hold hold(ResultSet rs) throws SQLException {
        int y = rs.getInt("fiscal_year");
        Integer year = rs.wasNull() ? null : y;
        return new Hold(rs.getObject("id", UUID.class), rs.getObject("fiscal_year_id", UUID.class), year, rs.getString("reason"),
                rs.getString("reference"), rs.getString("placed_by"), ts(rs, "placed_at"), rs.getString("released_by"), ts(rs, "released_at"),
                rs.getString("release_note"), rs.getLong("version"));
    }

    private static Instant ts(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }
}
