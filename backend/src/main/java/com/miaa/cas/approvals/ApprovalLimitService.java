package com.miaa.cas.approvals;

import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.money.Money;
import com.miaa.cas.security.CurrentActor;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-role approval limits by document type (MS §9.3, FR-RBAC-07). A user may approve a document only up to the
 * highest limit among their roles that have one for its type; a user none of whose roles has a limit is not limited.
 * No limits are seeded: the amounts are AVTECH's decision (C-24). Limits bind approval steps, not posting.
 */
@Service
public class ApprovalLimitService {

    /** Document types with approval steps. */
    public static final List<String> DOCUMENT_TYPES = List.of("JOURNAL_VOUCHER", "AP_INVOICE", "DISBURSEMENT_VOUCHER", "AR_INVOICE",
            "AR_MEMO", "BUDGET", "BANK_RECONCILIATION");

    public record Limit(String roleCode, String documentType, BigDecimal maxAmount, String updatedBy, Instant updatedAt) {
    }

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Clock clock;

    public ApprovalLimitService(JdbcTemplate jdbc, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Limit> list() {
        return jdbc.query("SELECT role_code, document_type, max_amount, updated_by, updated_at FROM approval_limits ORDER BY role_code, document_type",
                (rs, i) -> new Limit(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getString(4), rs.getTimestamp(5).toInstant()));
    }

    @Transactional
    public Limit set(String roleCode, String documentType, BigDecimal maxAmount) {
        CurrentActor.requirePermission("APPROVAL_LIMIT_MANAGE");
        if (!DOCUMENT_TYPES.contains(documentType)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown document type. Allowed: " + String.join(", ", DOCUMENT_TYPES));
        }
        if (maxAmount == null || maxAmount.signum() < 0 || maxAmount.scale() > 2) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The limit must be zero or more, with at most two decimal places.");
        }
        Boolean role = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM roles WHERE code = ?)", Boolean.class, roleCode);
        if (!Boolean.TRUE.equals(role)) {
            throw ApiException.notFound("Role");
        }
        Optional<BigDecimal> before = current(roleCode, documentType);
        Instant now = Instant.now(clock);
        jdbc.update("""
                INSERT INTO approval_limits (role_code, document_type, max_amount, updated_by, updated_at) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (role_code, document_type) DO UPDATE SET max_amount = EXCLUDED.max_amount, updated_by = EXCLUDED.updated_by,
                    updated_at = EXCLUDED.updated_at""", roleCode, documentType, Money.of(maxAmount), CurrentActor.username(), Timestamp.from(now));
        audit.record("APPROVAL_LIMIT_SET", "APPROVAL_LIMIT", roleCode + "/" + documentType, Map.of(
                "before", before.map(BigDecimal::toPlainString).orElse("none"), "after", Money.of(maxAmount).toPlainString()));
        return new Limit(roleCode, documentType, Money.of(maxAmount), CurrentActor.username(), now);
    }

    @Transactional
    public void remove(String roleCode, String documentType) {
        CurrentActor.requirePermission("APPROVAL_LIMIT_MANAGE");
        Optional<BigDecimal> before = current(roleCode, documentType);
        if (before.isEmpty()) {
            throw ApiException.notFound("Approval limit");
        }
        jdbc.update("DELETE FROM approval_limits WHERE role_code = ? AND document_type = ?", roleCode, documentType);
        audit.record("APPROVAL_LIMIT_REMOVED", "APPROVAL_LIMIT", roleCode + "/" + documentType, Map.of("before", before.get().toPlainString()));
    }

    /** The current user's limit for the type, or empty when none of their roles has one. */
    public Optional<BigDecimal> limitOf(String documentType) {
        List<String> roles = currentRoles();
        if (roles.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(jdbc.queryForObject("SELECT max(max_amount) FROM approval_limits WHERE document_type = ? AND role_code = ANY (?)",
                BigDecimal.class, documentType, roles.toArray(String[]::new)));
    }

    public boolean allows(String documentType, BigDecimal amount) {
        return limitOf(documentType).map(l -> amount.abs().compareTo(l) <= 0).orElse(true);
    }

    /** Refuses an approval above the current user's limit for the type. */
    public void require(String documentType, BigDecimal amount) {
        Optional<BigDecimal> limit = limitOf(documentType);
        if (limit.isPresent() && amount.abs().compareTo(limit.get()) > 0) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "The amount " + Money.of(amount.abs()).toPlainString()
                    + " is above your approval limit of " + limit.get().toPlainString() + " for this document type; "
                    + "an approver with a higher limit must approve it.", Map.of("limit", limit.get().toPlainString()));
        }
    }

    /** A cache-friendly snapshot of the current user's limits by type, for checking many documents at once. */
    public Map<String, BigDecimal> limitsByType() {
        List<String> roles = currentRoles();
        Map<String, BigDecimal> out = new HashMap<>();
        if (!roles.isEmpty()) {
            jdbc.query("SELECT document_type, max(max_amount) FROM approval_limits WHERE role_code = ANY (?) GROUP BY document_type",
                    rs -> { out.put(rs.getString(1), rs.getBigDecimal(2)); }, (Object) roles.toArray(String[]::new));
        }
        return out;
    }

    private Optional<BigDecimal> current(String roleCode, String documentType) {
        return jdbc.queryForList("SELECT max_amount FROM approval_limits WHERE role_code = ? AND document_type = ?", BigDecimal.class,
                roleCode, documentType).stream().findFirst();
    }

    private static List<String> currentRoles() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return List.of();
        }
        return auth.getAuthorities().stream().map(a -> a.getAuthority()).filter(a -> a.startsWith("ROLE_")).map(a -> a.substring(5)).toList();
    }
}
