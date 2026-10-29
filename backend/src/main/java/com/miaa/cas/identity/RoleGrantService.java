package com.miaa.cas.identity;

import com.miaa.cas.audit.Actor;
import com.miaa.cas.audit.AuditService;
import com.miaa.cas.audit.SecurityEventService;
import com.miaa.cas.audit.SecurityEventService.Severity;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.web.JdbcPaging;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.identity.domain.Role;
import com.miaa.cas.identity.domain.RoleRepository;
import com.miaa.cas.notifications.NotificationService;
import com.miaa.cas.security.CurrentActor;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maker-checker for financial access (MS §9.3, C-19 as proposed): a role change that adds a role carrying financial
 * permissions is held as a request until a second person holding ACCESS_GRANT_APPROVE approves it. Neither the
 * requester nor the user concerned may decide it. When nobody can approve yet (first setup), the grant applies at once
 * and is recorded as a HIGH-severity security event, which alerts the security reviewers.
 */
@Service
public class RoleGrantService {

    public record GrantView(UUID id, UUID userId, String username, String displayName, List<String> rolesBefore,
            List<String> rolesRequested, List<String> financialAdded, String status, String requestedBy, Instant requestedAt,
            String decidedBy, Instant decidedAt, String decisionNote, List<String> availableActions, long version) {
    }

    private final JdbcTemplate jdbc;
    private final RoleRepository roles;
    private final RoleAssignments assignments;
    private final PermissionCatalog catalog;
    private final AuditService audit;
    private final SecurityEventService securityEvents;
    private final NotificationService notifications;
    private final Clock clock;
    private final boolean required;

    public RoleGrantService(JdbcTemplate jdbc, RoleRepository roles, RoleAssignments assignments, PermissionCatalog catalog,
            AuditService audit, SecurityEventService securityEvents, NotificationService notifications, Clock clock,
            @Value("${cas.access.financial-grant-approval:true}") boolean required, Environment env) {
        if (!required && Arrays.asList(env.getActiveProfiles()).contains("prod")) {
            throw new IllegalStateException("cas.access.financial-grant-approval cannot be switched off in production (C-19)");
        }
        this.jdbc = jdbc;
        this.roles = roles;
        this.assignments = assignments;
        this.catalog = catalog;
        this.audit = audit;
        this.securityEvents = securityEvents;
        this.notifications = notifications;
        this.clock = clock;
        this.required = required;
    }

    /**
     * The financial roles among those a change adds that must wait for approval; empty when nothing needs approval
     * (no financial role added, approval switched off, or nobody able to approve yet, which is recorded).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Set<String> heldBack(UUID userId, Set<String> before, Set<String> requested) {
        Set<String> added = new TreeSet<>(requested);
        added.removeAll(before);
        Set<String> financial = financialRoles(added);
        if (!required || financial.isEmpty()) {
            return Set.of();
        }
        if (!approverExists(userId)) {
            securityEvents.record(Actor.current(), "FINANCIAL_ACCESS_GRANTED_WITHOUT_APPROVER", "SUCCESS", Severity.HIGH,
                    Map.of("user", String.valueOf(userId), "roles", financial));
            return Set.of();
        }
        if (userId != null && pendingFor(userId)) {
            throw new ApiException(ErrorCode.INVALID_STATE, "A role grant for this user is already waiting for approval. "
                    + "Have it decided or withdraw it first.");
        }
        return financial;
    }

    /** Files the request for the held-back roles; the user keeps {@code before} until it is approved. */
    @Transactional(propagation = Propagation.MANDATORY)
    public GrantView file(UUID userId, Set<String> before, Set<String> requested, Set<String> financial) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO role_grant_requests (id, user_id, roles_before, roles_requested, financial_added, status, requested_by_id,
                    requested_by, requested_at) VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?, ?)""", id, userId, join(before), join(requested),
                join(financial), CurrentActor.userId(), CurrentActor.username(), Timestamp.from(Instant.now(clock)));
        audit.record("ROLE_GRANT_REQUESTED", "USER", userId, Map.of("requestId", id.toString(), "before", before, "requested", requested,
                "financialAdded", financial));
        for (UUID approver : jdbc.queryForList("""
                SELECT DISTINCT u.id FROM users u JOIN user_roles ur ON ur.user_id = u.id JOIN role_permissions rp ON rp.role_id = ur.role_id
                WHERE rp.permission_code = 'ACCESS_GRANT_APPROVE' AND u.status = 'ACTIVE' AND u.id <> ?""", UUID.class, userId)) {
            notifications.notify(approver, "ACCESS_GRANT", "A role grant for " + username(userId) + " needs approval",
                    CurrentActor.username() + " asked to add " + String.join(", ", financial) + ".", "/admin/access-grants");
        }
        return view(id);
    }

    @Transactional(readOnly = true)
    public PageResponse<GrantView> list(String status, int page, int size) {
        requireViewer();
        String s = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        return JdbcPaging.ids(jdbc, "SELECT id FROM role_grant_requests WHERE (?::text IS NULL OR status = ?::text) ORDER BY requested_at DESC, id",
                page, size, s, s).map(this::view);
    }

    @Transactional(readOnly = true)
    public GrantView get(UUID id) {
        requireViewer();
        return view(id);
    }

    @Transactional
    public GrantView approve(UUID id, long version, String note) {
        CurrentActor.requirePermission("ACCESS_GRANT_APPROVE");
        Map<String, Object> r = lockPending(id, version);
        requireSecondPerson(r);
        UUID userId = (UUID) r.get("user_id");
        Set<String> before = split((String) r.get("roles_before"));
        if (!assignments.roleCodes(userId).equals(before)) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION, "The user's roles changed after this request was made; "
                    + "reject it and ask for a new one.");
        }
        Set<String> requested = split((String) r.get("roles_requested"));
        List<Role> roleList = roles.findByCodeIn(requested);
        if (roleList.size() != requested.size()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A requested role no longer exists; reject the request.");
        }
        Set<String> permissions = new TreeSet<>();
        roleList.forEach(role -> permissions.addAll(role.getPermissionCodes()));
        List<SodPolicy.Conflict> blocking = SodPolicy.blocking(SodPolicy.evaluate(permissions, catalog.sodRules()));
        if (!blocking.isEmpty()) {
            throw new ApiException(ErrorCode.SOD_CONFLICT, "The role combination violates segregation-of-duties rules.",
                    Map.of("conflicts", blocking));
        }
        assignments.replaceRoles(userId, roleList.stream().map(Role::getId).toList(), CurrentActor.username());
        decide(id, "APPROVED", note);
        audit.record("USER_ROLES_CHANGED", "USER", userId, Map.of("before", before, "after", requested, "approvedRequest", id.toString()));
        notifications.notify((UUID) r.get("requested_by_id"), "REQUEST_APPROVED", "Role grant for " + username(userId)
                + " was approved by " + CurrentActor.username(), note, "/admin/access-grants");
        return view(id);
    }

    @Transactional
    public GrantView reject(UUID id, long version, String note) {
        CurrentActor.requirePermission("ACCESS_GRANT_APPROVE");
        Map<String, Object> r = lockPending(id, version);
        requireSecondPerson(r);
        if (note == null || note.trim().length() < 5) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Give the reason for rejecting (at least 5 characters).");
        }
        decide(id, "REJECTED", note);
        notifications.notify((UUID) r.get("requested_by_id"), "REQUEST_REJECTED", "Role grant for " + username((UUID) r.get("user_id"))
                + " was rejected by " + CurrentActor.username(), note.trim(), "/admin/access-grants");
        return view(id);
    }

    /** The requester, or another user administrator, withdraws a pending request. */
    @Transactional
    public GrantView cancel(UUID id, long version) {
        Map<String, Object> r = lockPending(id, version);
        if (!CurrentActor.userId().equals(r.get("requested_by_id")) && !CurrentActor.hasPermission("USER_ADMINISTER")) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Only the requester or a user administrator can withdraw this request.");
        }
        jdbc.update("UPDATE role_grant_requests SET status = 'CANCELLED', version = version + 1 WHERE id = ?", id);
        audit.record("ROLE_GRANT_CANCELLED", "USER", r.get("user_id"), Map.of("requestId", id.toString()));
        return view(id);
    }

    @Transactional(readOnly = true)
    public GrantView pending(UUID userId) {
        return jdbc.queryForList("SELECT id FROM role_grant_requests WHERE user_id = ? AND status = 'PENDING'", UUID.class, userId).stream()
                .findFirst().map(this::view).orElse(null);
    }

    Set<String> financialRoles(Collection<String> roleCodes) {
        if (roleCodes.isEmpty()) {
            return Set.of();
        }
        return new TreeSet<>(jdbc.queryForList("""
                SELECT DISTINCT r.code FROM roles r JOIN role_permissions rp ON rp.role_id = r.id JOIN permissions p ON p.code = rp.permission_code
                WHERE p.financial AND r.code = ANY (?)""", String.class, (Object) roleCodes.toArray(String[]::new)));
    }

    /** Someone other than the requester and the user concerned, active and holding ACCESS_GRANT_APPROVE. */
    private boolean approverExists(UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM users u JOIN user_roles ur ON ur.user_id = u.id JOIN role_permissions rp ON rp.role_id = ur.role_id
                WHERE rp.permission_code = 'ACCESS_GRANT_APPROVE' AND u.status = 'ACTIVE' AND (u.access_expires_at IS NULL OR u.access_expires_at > now())
                  AND u.id <> ? AND (?::uuid IS NULL OR u.id <> ?::uuid))""", Boolean.class, CurrentActor.userId(), userId, userId));
    }

    private boolean pendingFor(UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM role_grant_requests WHERE user_id = ? AND status = 'PENDING')",
                Boolean.class, userId));
    }

    private void decide(UUID id, String status, String note) {
        jdbc.update("UPDATE role_grant_requests SET status = ?, decided_by_id = ?, decided_by = ?, decided_at = ?, decision_note = ?, "
                + "version = version + 1 WHERE id = ?", status, CurrentActor.userId(), CurrentActor.username(), Timestamp.from(Instant.now(clock)),
                note == null || note.isBlank() ? null : note.trim(), id);
        audit.record("APPROVED".equals(status) ? "ROLE_GRANT_APPROVED" : "ROLE_GRANT_REJECTED", "ROLE_GRANT_REQUEST", id,
                Map.of("note", String.valueOf(note)));
    }

    private void requireSecondPerson(Map<String, Object> r) {
        UUID me = CurrentActor.userId();
        if (me.equals(r.get("requested_by_id"))) {
            throw new ApiException(ErrorCode.SEGREGATION_OF_DUTIES, "You requested this grant; another person must decide it.");
        }
        if (me.equals(r.get("user_id"))) {
            throw new ApiException(ErrorCode.SEGREGATION_OF_DUTIES, "You cannot decide a grant of your own access.");
        }
    }

    private Map<String, Object> lockPending(UUID id, long version) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM role_grant_requests WHERE id = ? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Role grant request");
        }
        Map<String, Object> r = rows.get(0);
        if (((Number) r.get("version")).longValue() != version) {
            throw ApiException.staleVersion();
        }
        if (!"PENDING".equals(r.get("status"))) {
            throw new ApiException(ErrorCode.INVALID_STATE, "The request has already been " + r.get("status").toString().toLowerCase() + ".");
        }
        return r;
    }

    private static void requireViewer() {
        if (!CurrentActor.hasPermission("ACCESS_GRANT_APPROVE") && !CurrentActor.hasPermission("USER_ADMINISTER")
                && !CurrentActor.hasPermission("AUDIT_LOG_VIEW")) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "You cannot view role grant requests.");
        }
    }

    private String username(UUID userId) {
        return jdbc.queryForObject("SELECT username FROM users WHERE id = ?", String.class, userId);
    }

    private GrantView view(UUID id) {
        Map<String, Object> r = jdbc.queryForMap("""
                SELECT g.*, u.username, u.display_name FROM role_grant_requests g JOIN users u ON u.id = g.user_id WHERE g.id = ?""", id);
        String status = (String) r.get("status");
        List<String> actions = new ArrayList<>();
        if ("PENDING".equals(status)) {
            UUID me = CurrentActor.userId();
            if (CurrentActor.hasPermission("ACCESS_GRANT_APPROVE") && !me.equals(r.get("requested_by_id")) && !me.equals(r.get("user_id"))) {
                actions.addAll(List.of("APPROVE", "REJECT"));
            }
            if (me.equals(r.get("requested_by_id")) || CurrentActor.hasPermission("USER_ADMINISTER")) {
                actions.add("CANCEL");
            }
        }
        Timestamp decided = (Timestamp) r.get("decided_at");
        return new GrantView(id, (UUID) r.get("user_id"), (String) r.get("username"), (String) r.get("display_name"),
                List.copyOf(split((String) r.get("roles_before"))), List.copyOf(split((String) r.get("roles_requested"))),
                List.copyOf(split((String) r.get("financial_added"))), status, (String) r.get("requested_by"),
                ((Timestamp) r.get("requested_at")).toInstant(), (String) r.get("decided_by"), decided == null ? null : decided.toInstant(),
                (String) r.get("decision_note"), actions, ((Number) r.get("version")).longValue());
    }

    private static String join(Set<String> codes) {
        return String.join(",", new TreeSet<>(codes));
    }

    private static Set<String> split(String codes) {
        return codes == null || codes.isBlank() ? new TreeSet<>() : new TreeSet<>(Arrays.asList(codes.split(",")));
    }
}
