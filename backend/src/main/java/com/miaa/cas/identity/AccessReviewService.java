package com.miaa.cas.identity;

import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.security.CurrentActor;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Periodic access reviews (MS §9.3, FR-RBAC-08). A review cycle snapshots every active internal user's roles. A
 * reviewer (never the user concerned) records RETAIN, MODIFY or REVOKE. A change becomes an action for the user
 * administrators, who apply it through the normal role screens (where financial grants still need approval) and
 * record it. The cycle closes when every review is done and every action applied. How often cycles run is C-08/C-19.
 */
@Service
public class AccessReviewService {

    public record CycleSummary(UUID id, String name, LocalDate dueDate, String status, String startedBy, Instant startedAt,
            String closedBy, Instant closedAt, long total, long reviewed, long actionsOpen, long version) {
    }

    public record ReviewItem(UUID id, UUID userId, String username, String displayName, List<String> rolesAtStart,
            List<String> rolesNow, boolean financial, String outcome, String reviewer, Instant reviewedAt, String notes,
            String actionedBy, Instant actionedAt, String actionNote, List<String> availableActions, long version) {
    }

    public record CycleDetail(CycleSummary summary, List<ReviewItem> items, List<String> availableActions) {
    }

    private final JdbcTemplate jdbc;
    private final RoleAssignments assignments;
    private final AuditService audit;
    private final Clock clock;

    public AccessReviewService(JdbcTemplate jdbc, RoleAssignments assignments, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.assignments = assignments;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<CycleSummary> cycles() {
        requireViewer();
        return jdbc.queryForList("SELECT id FROM access_review_cycles ORDER BY started_at DESC LIMIT 100", UUID.class).stream()
                .map(this::summary).toList();
    }

    @Transactional
    public CycleSummary start(String name, LocalDate dueDate) {
        CurrentActor.requirePermission("ACCESS_REVIEW_MANAGE");
        LocalDate today = LocalDate.now(clock);
        if (dueDate == null || dueDate.isBefore(today)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The due date must be today or later.");
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM access_review_cycles WHERE status = 'OPEN')", Boolean.class))) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Close the open review cycle before starting another.");
        }
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        jdbc.update("INSERT INTO access_review_cycles (id, name, due_date, status, started_by, started_at) VALUES (?, ?, ?, 'OPEN', ?, ?)",
                id, name.trim(), Date.valueOf(dueDate), CurrentActor.username(), Timestamp.from(now));
        int users = jdbc.update("""
                INSERT INTO access_reviews (id, user_id, review_due, cycle_id, roles_snapshot, financial, created_at)
                SELECT gen_random_uuid(), u.id, ?, ?, string_agg(r.code, ',' ORDER BY r.code),
                       bool_or(EXISTS (SELECT 1 FROM role_permissions rp JOIN permissions p ON p.code = rp.permission_code
                                       WHERE rp.role_id = r.id AND p.financial)), ?
                FROM users u JOIN user_roles ur ON ur.user_id = u.id JOIN roles r ON r.id = ur.role_id
                WHERE u.status = 'ACTIVE' AND u.user_type = 'INTERNAL' AND (u.access_expires_at IS NULL OR u.access_expires_at > ?)
                GROUP BY u.id""", Date.valueOf(dueDate), id, Timestamp.from(now), Timestamp.from(now));
        audit.record("ACCESS_REVIEW_STARTED", "ACCESS_REVIEW_CYCLE", id, Map.of("name", name.trim(), "due", dueDate.toString(), "users", users));
        return summary(id);
    }

    @Transactional(readOnly = true)
    public CycleDetail cycle(UUID id) {
        requireViewer();
        CycleSummary s = summary(id);
        List<ReviewItem> items = jdbc.queryForList("""
                SELECT a.id FROM access_reviews a JOIN users u ON u.id = a.user_id WHERE a.cycle_id = ?
                ORDER BY a.financial DESC, u.username""", UUID.class, id).stream().map(r -> item(r, s.status())).toList();
        List<String> actions = new ArrayList<>();
        if ("OPEN".equals(s.status()) && CurrentActor.hasPermission("ACCESS_REVIEW_MANAGE")) {
            actions.add("CLOSE");
        }
        return new CycleDetail(s, items, actions);
    }

    @Transactional
    public ReviewItem review(UUID reviewId, long version, String outcome, String notes) {
        CurrentActor.requirePermission("ACCESS_REVIEW_PERFORM");
        Map<String, Object> a = lock(reviewId, version);
        requireOpen(a);
        if (CurrentActor.userId().equals(a.get("user_id"))) {
            throw new ApiException(ErrorCode.SEGREGATION_OF_DUTIES, "You cannot review your own access.");
        }
        if (a.get("outcome") != null) {
            throw new ApiException(ErrorCode.INVALID_STATE, "This access has already been reviewed.");
        }
        if (!List.of("RETAIN", "MODIFY", "REVOKE").contains(outcome)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The outcome must be RETAIN, MODIFY or REVOKE.");
        }
        if (!"RETAIN".equals(outcome) && (notes == null || notes.trim().length() < 5)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Say what should change (at least 5 characters).");
        }
        jdbc.update("UPDATE access_reviews SET outcome = ?, notes = ?, reviewer_id = ?, reviewer = ?, reviewed_at = ?, version = version + 1 "
                + "WHERE id = ?", outcome, notes == null || notes.isBlank() ? null : notes.trim(), CurrentActor.userId(), CurrentActor.username(),
                Timestamp.from(Instant.now(clock)), reviewId);
        audit.record("ACCESS_REVIEWED", "USER", a.get("user_id"), Map.of("cycle", String.valueOf(a.get("cycle_id")), "outcome", outcome,
                "notes", String.valueOf(notes)));
        return item(reviewId, "OPEN");
    }

    /** A user administrator records that a MODIFY or REVOKE outcome has been applied. */
    @Transactional
    public ReviewItem recordAction(UUID reviewId, long version, String note) {
        CurrentActor.requirePermission("USER_ADMINISTER");
        Map<String, Object> a = lock(reviewId, version);
        requireOpen(a);
        String outcome = (String) a.get("outcome");
        if (!"MODIFY".equals(outcome) && !"REVOKE".equals(outcome)) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Only a MODIFY or REVOKE outcome needs an action.");
        }
        if (a.get("actioned_at") != null) {
            throw new ApiException(ErrorCode.INVALID_STATE, "The action has already been recorded.");
        }
        UUID userId = (UUID) a.get("user_id");
        if ("REVOKE".equals(outcome)) {
            String status = jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, userId);
            if ("ACTIVE".equals(status) && !assignments.roleCodes(userId).isEmpty()) {
                throw new ApiException(ErrorCode.INVALID_STATE, "Remove the user's roles or deactivate the account first.");
            }
        } else if (note == null || note.trim().length() < 5) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Say what was changed (at least 5 characters).");
        } else if (assignments.roleCodes(userId).equals(new TreeSet<>(List.of(String.valueOf(a.get("roles_snapshot")).split(","))))) {
            throw new ApiException(ErrorCode.INVALID_STATE, "The user's roles are unchanged since the review started; change them first.");
        }
        jdbc.update("UPDATE access_reviews SET actioned_by = ?, actioned_at = ?, action_note = ?, version = version + 1 WHERE id = ?",
                CurrentActor.username(), Timestamp.from(Instant.now(clock)), note == null || note.isBlank() ? null : note.trim(), reviewId);
        audit.record("ACCESS_REVIEW_ACTIONED", "USER", userId, Map.of("cycle", String.valueOf(a.get("cycle_id")), "outcome", outcome,
                "rolesNow", assignments.roleCodes(userId)));
        return item(reviewId, "OPEN");
    }

    @Transactional
    public CycleSummary close(UUID id, long version) {
        CurrentActor.requirePermission("ACCESS_REVIEW_MANAGE");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM access_review_cycles WHERE id = ? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Access review cycle");
        }
        if (((Number) rows.get(0).get("version")).longValue() != version) {
            throw ApiException.staleVersion();
        }
        if (!"OPEN".equals(rows.get(0).get("status"))) {
            throw new ApiException(ErrorCode.INVALID_STATE, "The cycle is already closed.");
        }
        CycleSummary s = summary(id);
        if (s.reviewed() < s.total() || s.actionsOpen() > 0) {
            throw new ApiException(ErrorCode.INVALID_STATE, (s.total() - s.reviewed()) + " review(s) and " + s.actionsOpen()
                    + " action(s) are still open.");
        }
        jdbc.update("UPDATE access_review_cycles SET status = 'CLOSED', closed_by = ?, closed_at = ?, version = version + 1 WHERE id = ?",
                CurrentActor.username(), Timestamp.from(Instant.now(clock)), id);
        audit.record("ACCESS_REVIEW_CLOSED", "ACCESS_REVIEW_CYCLE", id, Map.of("users", s.total()));
        return summary(id);
    }

    private CycleSummary summary(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT c.*, (SELECT count(*) FROM access_reviews a WHERE a.cycle_id = c.id) AS total,
                       (SELECT count(*) FROM access_reviews a WHERE a.cycle_id = c.id AND a.outcome IS NOT NULL) AS reviewed,
                       (SELECT count(*) FROM access_reviews a WHERE a.cycle_id = c.id AND a.outcome IN ('MODIFY', 'REVOKE')
                          AND a.actioned_at IS NULL) AS actions_open
                FROM access_review_cycles c WHERE c.id = ?""", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Access review cycle");
        }
        Map<String, Object> c = rows.get(0);
        Timestamp closed = (Timestamp) c.get("closed_at");
        return new CycleSummary(id, (String) c.get("name"), ((Date) c.get("due_date")).toLocalDate(), (String) c.get("status"),
                (String) c.get("started_by"), ((Timestamp) c.get("started_at")).toInstant(), (String) c.get("closed_by"),
                closed == null ? null : closed.toInstant(), ((Number) c.get("total")).longValue(), ((Number) c.get("reviewed")).longValue(),
                ((Number) c.get("actions_open")).longValue(), ((Number) c.get("version")).longValue());
    }

    private ReviewItem item(UUID id, String cycleStatus) {
        Map<String, Object> a = jdbc.queryForMap("""
                SELECT a.*, u.username, u.display_name FROM access_reviews a JOIN users u ON u.id = a.user_id WHERE a.id = ?""", id);
        UUID userId = (UUID) a.get("user_id");
        String outcome = (String) a.get("outcome");
        List<String> actions = new ArrayList<>();
        if ("OPEN".equals(cycleStatus)) {
            if (outcome == null && CurrentActor.hasPermission("ACCESS_REVIEW_PERFORM") && !CurrentActor.userId().equals(userId)) {
                actions.add("REVIEW");
            }
            if (("MODIFY".equals(outcome) || "REVOKE".equals(outcome)) && a.get("actioned_at") == null
                    && CurrentActor.hasPermission("USER_ADMINISTER")) {
                actions.add("RECORD_ACTION");
            }
        }
        String snapshot = (String) a.get("roles_snapshot");
        Timestamp reviewed = (Timestamp) a.get("reviewed_at");
        Timestamp actioned = (Timestamp) a.get("actioned_at");
        return new ReviewItem(id, userId, (String) a.get("username"), (String) a.get("display_name"),
                snapshot == null || snapshot.isBlank() ? List.of() : List.of(snapshot.split(",")), List.copyOf(assignments.roleCodes(userId)),
                (Boolean) a.get("financial"), outcome, (String) a.get("reviewer"), reviewed == null ? null : reviewed.toInstant(),
                (String) a.get("notes"), (String) a.get("actioned_by"), actioned == null ? null : actioned.toInstant(),
                (String) a.get("action_note"), actions, ((Number) a.get("version")).longValue());
    }

    private Map<String, Object> lock(UUID id, long version) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM access_reviews WHERE id = ? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Access review");
        }
        if (((Number) rows.get(0).get("version")).longValue() != version) {
            throw ApiException.staleVersion();
        }
        return rows.get(0);
    }

    private void requireOpen(Map<String, Object> a) {
        String status = jdbc.queryForObject("SELECT status FROM access_review_cycles WHERE id = ?", String.class, a.get("cycle_id"));
        if (!"OPEN".equals(status)) {
            throw new ApiException(ErrorCode.INVALID_STATE, "The review cycle is closed.");
        }
    }

    private static void requireViewer() {
        if (!CurrentActor.hasPermission("ACCESS_REVIEW_MANAGE") && !CurrentActor.hasPermission("ACCESS_REVIEW_PERFORM")
                && !CurrentActor.hasPermission("USER_ADMINISTER")) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "You cannot view access reviews.");
        }
    }
}
