package com.miaa.cas.audit.anomaly;

import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.web.JdbcPaging;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.security.CurrentActor;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lists anomaly findings and records their review; nobody involved in a finding's document may review it. */
@Service
public class AnomalyReviewService {

    public record Finding(UUID id, String ruleCode, String severity, String documentType, UUID documentId, String reference,
            BigDecimal amount, LocalDate occurredOn, String title, String detail, List<String> involvedUsers, Instant detectedAt,
            String status, String reviewNote, String reviewedBy, Instant reviewedAt, long version) {
    }

    public record Summary(long openHigh, long openMedium, long openLow) {
    }

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Clock clock;

    public AnomalyReviewService(JdbcTemplate jdbc, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<Finding> list(String status, String severity, String rule, int page, int size) {
        return JdbcPaging.query(jdbc, """
                SELECT * FROM anomaly_findings
                WHERE (?::text IS NULL OR status = ?::text) AND (?::text IS NULL OR severity = ?::text) AND (?::text IS NULL OR rule_code = ?::text)
                ORDER BY CASE severity WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 ELSE 2 END, detected_at DESC, id""",
                AnomalyReviewService::map, page, size, blank(status), blank(status), blank(severity), blank(severity), blank(rule), blank(rule));
    }

    @Transactional(readOnly = true)
    public Summary summary() {
        Map<String, Object> r = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE severity = 'HIGH') AS h, count(*) FILTER (WHERE severity = 'MEDIUM') AS m,
                       count(*) FILTER (WHERE severity = 'LOW') AS l FROM anomaly_findings WHERE status = 'OPEN'""");
        return new Summary(((Number) r.get("h")).longValue(), ((Number) r.get("m")).longValue(), ((Number) r.get("l")).longValue());
    }

    /** EXPLAINED (a legitimate reason was found) or ESCALATED (needs investigation), with a note of at least 10 characters. */
    @Transactional
    public Finding review(UUID id, String status, String note, long version) {
        CurrentActor.requirePermission("ANOMALY_REVIEW");
        if (!List.of("EXPLAINED", "ESCALATED").contains(status)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "status must be EXPLAINED or ESCALATED.");
        }
        if (note == null || note.trim().length() < 10) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Explain the review in a note of at least 10 characters.");
        }
        List<Finding> rows = jdbc.query("SELECT * FROM anomaly_findings WHERE id = ? FOR UPDATE", AnomalyReviewService::map, id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Anomaly finding");
        }
        Finding f = rows.get(0);
        if (f.version() != version) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION, "The finding was changed by someone else. Reload and try again.");
        }
        if (!"OPEN".equals(f.status())) {
            throw new ApiException(ErrorCode.INVALID_STATE, "The finding has already been reviewed.");
        }
        if (f.involvedUsers().contains(CurrentActor.username())) {
            throw new ApiException(ErrorCode.SEGREGATION_OF_DUTIES, "You are involved in this document; another reviewer must review it.");
        }
        Instant now = Instant.now(clock);
        jdbc.update("UPDATE anomaly_findings SET status = ?, review_note = ?, reviewed_by_id = ?, reviewed_by = ?, reviewed_at = ?, "
                + "version = version + 1 WHERE id = ?", status, note.trim(), CurrentActor.userId(), CurrentActor.username(), Timestamp.from(now), id);
        audit.record("ANOMALY_REVIEWED", "ANOMALY_FINDING", id, Map.of("rule", f.ruleCode(), "status", status, "note", note.trim()));
        return jdbc.queryForObject("SELECT * FROM anomaly_findings WHERE id = ?", AnomalyReviewService::map, id);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim().toUpperCase(java.util.Locale.ROOT);
    }

    static Finding map(ResultSet rs, int i) throws SQLException {
        Array users = rs.getArray("involved_users");
        Timestamp reviewed = rs.getTimestamp("reviewed_at");
        java.sql.Date occurred = rs.getDate("occurred_on");
        return new Finding(rs.getObject("id", UUID.class), rs.getString("rule_code"), rs.getString("severity"), rs.getString("document_type"),
                rs.getObject("document_id", UUID.class), rs.getString("reference"), rs.getBigDecimal("amount"),
                occurred == null ? null : occurred.toLocalDate(), rs.getString("title"), rs.getString("detail"),
                users == null ? List.of() : Arrays.stream((Object[]) users.getArray()).map(String::valueOf).toList(),
                rs.getTimestamp("detected_at").toInstant(), rs.getString("status"), rs.getString("review_note"), rs.getString("reviewed_by"),
                reviewed == null ? null : reviewed.toInstant(), rs.getLong("version"));
    }
}
