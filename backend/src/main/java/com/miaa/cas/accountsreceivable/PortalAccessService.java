package com.miaa.cas.accountsreceivable;

import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.web.JdbcPaging;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.documents.DocumentOwnerPolicy;
import com.miaa.cas.documents.DocumentService;
import com.miaa.cas.documents.DocumentService.DocumentInfo;
import com.miaa.cas.notifications.NotificationService;
import com.miaa.cas.notifications.OutboxService;
import com.miaa.cas.security.CurrentActor;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
 * Concessionaire portal onboarding (C-18). The system administrator creates the CONCESSIONAIRE account;
 * a customer manager requests the link to the customer, stating how the person's identity and authority
 * were confirmed (with the authorisation letter attached where available); a second person with portal
 * approval authority approves it, and only then does the account see the customer's records. Removing
 * access takes effect at once and needs no approval.
 */
@Service
public class PortalAccessService implements DocumentOwnerPolicy {

    public static final String OWNER = "PORTAL_LINK_REQUEST";

    public record LinkRequest(@NotBlank @Size(max = 64) String username,
            @NotBlank @Size(min = 10, max = 1000) String evidence) {
    }

    public record Decision(@NotNull Boolean approve, @NotBlank @Size(min = 5, max = 500) String note) {
    }

    public record RequestView(UUID id, UUID customerId, String customerCode, String customerName, UUID userId, String username,
            String displayName, String email, String status, String evidence, String requestedBy, Instant requestedAt, String decidedBy,
            Instant decidedAt, String decisionNote, List<DocumentInfo> documents, List<String> availableActions, long version) {
    }

    private final CustomerService customers;
    private final DocumentService documents;
    private final OutboxService outbox;
    private final AuditService audit;
    private final NotificationService notifications;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public PortalAccessService(CustomerService customers, DocumentService documents, OutboxService outbox, AuditService audit, NotificationService notifications,
            JdbcTemplate jdbc, Clock clock) {
        this.customers = customers;
        this.documents = documents;
        this.outbox = outbox;
        this.audit = audit;
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<RequestView> list(UUID customerId, String status, int page, int size) {
        String s = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        return JdbcPaging.ids(jdbc, "SELECT id FROM portal_link_requests WHERE (?::uuid IS NULL OR customer_id = ?::uuid) "
                + "AND (?::text IS NULL OR status = ?::text) ORDER BY requested_at DESC, id", page, size, customerId, customerId, s, s)
                .map(this::view);
    }

    @Transactional(readOnly = true)
    public RequestView get(UUID id) {
        return view(id);
    }

    @Transactional
    public RequestView request(UUID customerId, LinkRequest req) {
        CurrentActor.requirePermission("CUSTOMER_MANAGE");
        var c = customers.active(customerId);
        Map<String, Object> u = lockConcessionaire(req.username().trim().toLowerCase());
        UUID userId = (UUID) u.get("id");
        checkLinkable(u, customerId);
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM portal_link_requests WHERE user_id = ? AND status = 'PENDING')",
                Boolean.class, userId))) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "A link request for this account is already awaiting approval.");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO portal_link_requests (id, customer_id, user_id, status, evidence, requested_by_id, requested_by, requested_at)
                VALUES (?, ?, ?, 'PENDING', ?, ?, ?, ?)""", id, customerId, userId, req.evidence().trim(), CurrentActor.userId(),
                CurrentActor.username(), Timestamp.from(Instant.now(clock)));
        audit.record("PORTAL_LINK_REQUESTED", "CUSTOMER", c.getCode(), Map.of("username", u.get("username")));
        return view(id);
    }

    @Transactional
    public RequestView decide(UUID id, boolean approve, String note) {
        CurrentActor.requirePermission("PORTAL_ACCESS_APPROVE");
        Map<String, Object> r = lockPending(id);
        if (CurrentActor.userId().equals(r.get("requested_by_id"))) {
            throw new ApiException(ErrorCode.SEGREGATION_OF_DUTIES, "You requested this link; another person must decide it.");
        }
        UUID customerId = (UUID) r.get("customer_id");
        Map<String, Object> u = jdbc.queryForMap("SELECT id, username, email, user_type, status, customer_id FROM users WHERE id = ? FOR UPDATE",
                r.get("user_id"));
        if (approve) {
            checkLinkable(u, customerId);
            jdbc.update("UPDATE users SET customer_id = ? WHERE id = ?", customerId, u.get("id"));
            Map<String, Object> c = jdbc.queryForMap("SELECT name FROM customers WHERE id = ?", customerId);
            outbox.enqueueEmail((String) u.get("email"), "portal-access-granted", Map.of("customerName", c.get("name"),
                    "username", u.get("username")));
        }
        jdbc.update("UPDATE portal_link_requests SET status = ?, decided_by_id = ?, decided_by = ?, decided_at = ?, decision_note = ?, "
                + "version = version + 1 WHERE id = ?", approve ? "APPROVED" : "REJECTED", CurrentActor.userId(), CurrentActor.username(),
                Timestamp.from(Instant.now(clock)), note.trim(), id);
        String code = jdbc.queryForObject("SELECT code FROM customers WHERE id = ?", String.class, customerId);
        notifications.notify((UUID) r.get("requested_by_id"), approve ? "REQUEST_APPROVED" : "REQUEST_REJECTED", "Portal access for "
                + u.get("username") + " was " + (approve ? "approved" : "rejected") + " by " + CurrentActor.username(), note.trim(),
                "/receivables/portal-access");
        if (approve) {
            notifications.notify((UUID) u.get("id"), "REQUEST_APPROVED", "Your portal account is now linked to your concessionaire account",
                    null, "/portal");
        }
        audit.record(approve ? "PORTAL_USER_LINKED" : "PORTAL_LINK_REJECTED", "CUSTOMER", code, Map.of("username", u.get("username"),
                "requestedBy", r.get("requested_by"), "note", note.trim()));
        return view(id);
    }

    @Transactional
    public RequestView cancel(UUID id) {
        Map<String, Object> r = lockPending(id);
        if (!CurrentActor.userId().equals(r.get("requested_by_id"))) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Only the requester can withdraw the request.");
        }
        jdbc.update("UPDATE portal_link_requests SET status = 'CANCELLED', version = version + 1 WHERE id = ?", id);
        audit.record("PORTAL_LINK_CANCELLED", "PORTAL_LINK_REQUEST", id, Map.of());
        return view(id);
    }

    // ------------------------------------------------------------------ document policy

    @Override
    public String ownerType() {
        return OWNER;
    }

    @Override
    public void assertCanModify(UUID ownerId) {
        Map<String, Object> r = row(ownerId);
        CurrentActor.requirePermission("DOCUMENT_UPLOAD");
        if (!CurrentActor.userId().equals(r.get("requested_by_id")) || !"PENDING".equals(r.get("status"))) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Only the requester can attach evidence while the request is pending.");
        }
    }

    @Override
    public void assertCanView(UUID ownerId) {
        row(ownerId);
        if (!CurrentActor.hasPermission("CUSTOMER_MANAGE") && !CurrentActor.hasPermission("PORTAL_ACCESS_APPROVE")) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "You cannot view this evidence.");
        }
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> lockConcessionaire(String username) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, username, user_type, status, customer_id FROM users WHERE username = ? "
                + "FOR UPDATE", username);
        if (rows.isEmpty()) {
            throw ApiException.notFound("User");
        }
        return rows.get(0);
    }

    private static void checkLinkable(Map<String, Object> u, UUID customerId) {
        if (!"CONCESSIONAIRE".equals(u.get("user_type"))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Only concessionaire accounts can be linked to a customer.");
        }
        if (!"ACTIVE".equals(u.get("status"))) {
            throw new ApiException(ErrorCode.INVALID_STATE, "The portal account is not active.");
        }
        Object current = u.get("customer_id");
        if (current != null) {
            throw new ApiException(ErrorCode.INVALID_STATE, current.equals(customerId) ? "The account is already linked to this customer."
                    : "This account is already linked to another customer; unlink it first.");
        }
    }

    private Map<String, Object> row(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM portal_link_requests WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Portal link request");
        }
        return rows.get(0);
    }

    private Map<String, Object> lockPending(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM portal_link_requests WHERE id = ? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Portal link request");
        }
        if (!"PENDING".equals(rows.get(0).get("status"))) {
            throw new ApiException(ErrorCode.INVALID_STATE, "The request has already been decided.");
        }
        return rows.get(0);
    }

    private RequestView view(UUID id) {
        Map<String, Object> r = jdbc.queryForMap("""
                SELECT p.*, c.code AS c_code, c.name AS c_name, u.username, u.display_name, u.email FROM portal_link_requests p
                JOIN customers c ON c.id = p.customer_id JOIN users u ON u.id = p.user_id WHERE p.id = ?""", id);
        boolean pending = "PENDING".equals(r.get("status"));
        boolean requester = CurrentActor.userId().equals(r.get("requested_by_id"));
        List<String> actions = new ArrayList<>();
        if (pending && !requester && CurrentActor.hasPermission("PORTAL_ACCESS_APPROVE")) {
            actions.addAll(List.of("APPROVE", "REJECT"));
        }
        if (pending && requester) {
            actions.add("CANCEL");
            if (CurrentActor.hasPermission("DOCUMENT_UPLOAD")) {
                actions.add("UPLOAD");
            }
        }
        Timestamp decided = (Timestamp) r.get("decided_at");
        return new RequestView(id, (UUID) r.get("customer_id"), (String) r.get("c_code"), (String) r.get("c_name"), (UUID) r.get("user_id"),
                (String) r.get("username"), (String) r.get("display_name"), (String) r.get("email"), (String) r.get("status"),
                (String) r.get("evidence"), (String) r.get("requested_by"), ((Timestamp) r.get("requested_at")).toInstant(),
                (String) r.get("decided_by"), decided == null ? null : decided.toInstant(), (String) r.get("decision_note"),
                documents.list(OWNER, id), actions, ((Number) r.get("version")).longValue());
    }
}
