package com.miaa.cas.tickets;

import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.web.JdbcPaging;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.documents.DocumentOwnerPolicy;
import com.miaa.cas.documents.DocumentService;
import com.miaa.cas.documents.DocumentService.DocumentInfo;
import com.miaa.cas.journal.NumberingService;
import com.miaa.cas.security.CurrentActor;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Support tickets. Incidents and service requests go NEW → ACKNOWLEDGED → IN_PROGRESS → RESOLVED →
 * CLOSED; change requests and enhancements must be approved (by someone other than the requester and the
 * assignee) before work starts. The first acknowledgement or public reply by support counts as the
 * response. Time on hold or awaiting approval is not counted against the resolution SLA. Breaches are
 * recorded permanently, even if the ticket is later resolved.
 *
 * <p>Only the requester closes a resolved ticket, which confirms the resolution (optionally rating it); a resolution
 * not confirmed or reopened within the reopen window is closed automatically and marked unconfirmed. A change
 * request or enhancement carries a rollback plan from submission, is validated on staging by the requester or an
 * approver (never the assignee) and is resolved with release notes.
 */
@Service
public class TicketService implements DocumentOwnerPolicy {

    public static final String OWNER = "TICKET";

    public static final Set<String> CLOSED = Set.of("RESOLVED", "CLOSED", "REJECTED", "CANCELLED");
    private static final Set<String> CHANGE_TYPES = Set.of("CHANGE_REQUEST", "ENHANCEMENT");

    public record TicketRequest(@NotNull @Pattern(regexp = "INCIDENT|SERVICE_REQUEST|CHANGE_REQUEST|ENHANCEMENT") String ticketType,
            @NotNull @Pattern(regexp = "P1|P2|P3|P4") String priority, @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 4000) String description, @Size(max = 40) String module,
            @NotNull @Pattern(regexp = "S1|S2|S3|S4") String severity, @NotNull UUID categoryId) {
    }

    public record ActionRequest(@NotNull Long version, @NotNull @Pattern(regexp = "ACKNOWLEDGE|ASSIGN|COMMENT|START|SUBMIT_FOR_APPROVAL|APPROVE|"
            + "REJECT|HOLD|RESUME|RESOLVE|REOPEN|CLOSE|CANCEL|PRIORITY|CLASSIFY|VALIDATE_STAGING") String action, @Size(max = 4000) String comment,
            Boolean internal, @Size(max = 64) String assignee, @Pattern(regexp = "P1|P2|P3|P4") String priority,
            @Pattern(regexp = "S1|S2|S3|S4") String severity, UUID categoryId, @Size(max = 4000) String rollbackPlan,
            @Size(max = 4000) String releaseNotes, @Size(max = 60) String releaseReference, @Min(1) @Max(5) Integer satisfaction) {
    }

    public record ChangeControl(String rollbackPlan, Instant stagingValidatedAt, String stagingValidatedBy, String stagingValidationNote,
            String releaseNotes, String releaseReference) {
    }

    public record Category(UUID id, String code, String name, int sortOrder, String status, boolean placeholder) {
    }

    public record EventView(int seq, Instant occurredAt, String actor, String eventType, String fromValue, String toValue, String comment,
            boolean internal) {
    }

    public record SlaState(String policy, boolean businessHours, Instant responseDueAt, Instant resolutionDueAt, Instant firstResponseAt,
            Long responseMinutesLeft, Long resolutionMinutesLeft, boolean paused, boolean responseBreached, boolean resolutionBreached,
            int escalationLevel) {
    }

    public record TicketView(UUID id, String ticketNumber, String ticketType, String priority, String severity, String category, UUID categoryId,
            String title, String description, String module, String status, String reporter, String assignee, String contractPhase, SlaState sla,
            Instant createdAt, Instant resolvedAt, Instant closedAt, Boolean closureConfirmed, Integer satisfaction, ChangeControl change,
            List<DocumentInfo> documents, List<EventView> events, List<String> availableActions, long version) {
    }

    public record TicketSummary(UUID id, String ticketNumber, String ticketType, String priority, String severity, String category, String title,
            String status, String reporter, String assignee, Instant resolutionDueAt, boolean breached, int escalationLevel, Instant createdAt) {
    }

    private final SlaPolicyService sla;
    private final NumberingService numbering;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final DocumentService documents;
    private final Clock clock;
    private final int reopenDays;

    public TicketService(SlaPolicyService sla, NumberingService numbering, AuditService audit, JdbcTemplate jdbc, DocumentService documents,
            Clock clock, @Value("${cas.tickets.reopen-days:7}") int reopenDays) {
        this.sla = sla;
        this.documents = documents;
        this.numbering = numbering;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
        this.reopenDays = reopenDays;
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public PageResponse<TicketSummary> list(boolean all, String status, String type, int page, int size) {
        return list(all, status, type, null, null, page, size);
    }

    @Transactional(readOnly = true)
    public PageResponse<TicketSummary> list(boolean all, String status, String type, String severity, UUID categoryId, int page, int size) {
        boolean everyone = all && CurrentActor.hasPermission("TICKET_VIEW_ALL");
        String s = blank(status);
        String t = blank(type);
        String v = blank(severity);
        return JdbcPaging.query(jdbc, """
                SELECT t.*, c.name AS category_name FROM tickets t LEFT JOIN ticket_categories c ON c.id = t.category_id
                WHERE (? OR t.reporter_id = ?) AND (?::text IS NULL OR t.status = ?::text) AND (?::text IS NULL OR t.ticket_type = ?::text)
                  AND (?::text IS NULL OR t.severity = ?::text) AND (?::uuid IS NULL OR t.category_id = ?::uuid)
                ORDER BY CASE WHEN t.status IN ('RESOLVED', 'CLOSED', 'REJECTED', 'CANCELLED') THEN 1 ELSE 0 END, t.priority, t.created_at DESC, t.id""",
                (rs, i) -> new TicketSummary(rs.getObject("id", UUID.class), rs.getString("ticket_number"), rs.getString("ticket_type"),
                        rs.getString("priority"), rs.getString("severity"), rs.getString("category_name"), rs.getString("title"), rs.getString("status"),
                        rs.getString("reporter"), rs.getString("assignee"), ts(rs.getTimestamp("resolution_due_at")),
                        rs.getBoolean("response_breached") || rs.getBoolean("resolution_breached"), rs.getInt("escalation_level"),
                        rs.getTimestamp("created_at").toInstant()), page, size, everyone, CurrentActor.userId(), s, s, t, t, v, v, categoryId, categoryId);
    }

    @Transactional(readOnly = true)
    public List<Category> categories() {
        return jdbc.query("SELECT * FROM ticket_categories ORDER BY sort_order, code", (rs, i) -> new Category(rs.getObject("id", UUID.class),
                rs.getString("code"), rs.getString("name"), rs.getInt("sort_order"), rs.getString("status"), rs.getBoolean("placeholder")));
    }

    @Transactional
    public Category saveCategory(UUID id, String code, String name, int sortOrder, String status) {
        CurrentActor.requirePermission("TICKET_ADMIN");
        Timestamp now = Timestamp.from(Instant.now(clock));
        if (id == null) {
            String c = code == null ? "" : code.trim().toUpperCase();
            if (!c.matches("[A-Z0-9-]{2,30}")) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "The code is 2 to 30 letters, digits or hyphens.");
            }
            if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM ticket_categories WHERE code = ?)", Boolean.class, c))) {
                throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "A category with code " + c + " already exists.");
            }
            id = UUID.randomUUID();
            jdbc.update("INSERT INTO ticket_categories (id, code, name, sort_order, status, updated_by, updated_at) VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)",
                    id, c, name.trim(), sortOrder, CurrentActor.username(), now);
            audit.record("TICKET_CATEGORY_CREATED", "TICKET_CATEGORY", c, Map.of("name", name.trim()));
        } else {
            if (!"ACTIVE".equals(status) && !"INACTIVE".equals(status)) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "The status is ACTIVE or INACTIVE.");
            }
            int n = jdbc.update("UPDATE ticket_categories SET name = ?, sort_order = ?, status = ?, placeholder = FALSE, updated_by = ?, updated_at = ? "
                    + "WHERE id = ?", name.trim(), sortOrder, status, CurrentActor.username(), now, id);
            if (n == 0) {
                throw ApiException.notFound("Ticket category");
            }
            audit.record("TICKET_CATEGORY_UPDATED", "TICKET_CATEGORY", id, Map.of("name", name.trim(), "status", status));
        }
        UUID found = id;
        return categories().stream().filter(c -> c.id().equals(found)).findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    public TicketView get(UUID id) {
        return view(visible(id));
    }

    // ------------------------------------------------------------------ commands

    @Transactional
    public TicketView create(TicketRequest req) {
        CurrentActor.requirePermission("TICKET_CREATE");
        requireActiveCategory(req.categoryId());
        Instant now = Instant.now(clock);
        String phase = sla.contractPhase();
        Map<String, Object> policy = sla.activePolicy(phase, req.ticketType(), req.priority());
        BusinessClock bc = sla.clock();
        UUID id = UUID.randomUUID();
        String number = numbering.next("TKT", now.atZone(ZoneId.of("Asia/Manila")).getYear());
        jdbc.update("""
                INSERT INTO tickets (id, ticket_number, ticket_type, priority, severity, category_id, title, description, module, status, reporter_id,
                    reporter, contract_phase, sla_policy_id, response_due_at, resolution_due_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'NEW', ?, ?, ?, ?, ?, ?, ?, ?)""", id, number, req.ticketType(), req.priority(), req.severity(),
                req.categoryId(), req.title().trim(),
                req.description().trim(), blank(req.module()), CurrentActor.userId(), CurrentActor.username(), phase,
                policy == null ? null : policy.get("id"), policy == null ? null : due(bc, now, policy, "response_minutes"),
                policy == null ? null : due(bc, now, policy, "resolution_minutes"), Timestamp.from(now), Timestamp.from(now));
        event(id, "CREATED", null, "NEW", null, false);
        audit.record("TICKET_CREATED", "TICKET", number, Map.of("type", req.ticketType(), "priority", req.priority(), "severity", req.severity(),
                "sla", policy != null));
        return view(id);
    }

    @Transactional
    public TicketView act(UUID id, ActionRequest req) {
        Map<String, Object> t = visible(id);
        jdbc.queryForObject("SELECT id FROM tickets WHERE id = ? FOR UPDATE", UUID.class, id);
        t = row(id);
        if (((Number) t.get("version")).longValue() != req.version()) {
            throw ApiException.staleVersion();
        }
        String status = (String) t.get("status");
        String type = (String) t.get("ticket_type");
        boolean reporter = CurrentActor.userId().equals(t.get("reporter_id"));
        boolean worker = CurrentActor.hasPermission("TICKET_WORK");
        String comment = req.comment() == null ? null : req.comment().trim();
        Instant now = Instant.now(clock);
        switch (req.action()) {
            case "ACKNOWLEDGE" -> {
                need(worker, "Only support can acknowledge tickets.");
                expect(status, "NEW");
                move(id, status, "ACKNOWLEDGED", comment);
                respond(t, now);
            }
            case "ASSIGN" -> {
                need(worker, "Only support can assign tickets.");
                expectOpen(status);
                List<Map<String, Object>> u = jdbc.queryForList("SELECT id, username FROM users WHERE username = lower(?) AND status = 'ACTIVE'",
                        req.assignee() == null ? "" : req.assignee().trim());
                if (u.isEmpty()) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "No active user with that username.");
                }
                jdbc.update("UPDATE tickets SET assignee_id = ?, assignee = ? WHERE id = ?", u.get(0).get("id"), u.get(0).get("username"), id);
                event(id, "ASSIGNED", (String) t.get("assignee"), (String) u.get(0).get("username"), comment, true);
            }
            case "COMMENT" -> {
                need(reporter || worker || CurrentActor.hasPermission("TICKET_VIEW_ALL"), "You cannot comment on this ticket.");
                if (comment == null || comment.isEmpty()) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "Write a comment.");
                }
                boolean internal = Boolean.TRUE.equals(req.internal()) && worker;
                event(id, "COMMENT", null, null, comment, internal);
                if (worker && !reporter && !internal) {
                    respond(t, now);
                }
            }
            case "SUBMIT_FOR_APPROVAL" -> {
                need(worker, "Only support can submit a change for approval.");
                need(CHANGE_TYPES.contains(type), "Only change requests and enhancements need approval.");
                expect(status, "ACKNOWLEDGED");
                String plan = req.rollbackPlan() == null ? "" : req.rollbackPlan().trim();
                if (plan.length() < 10) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "Give the rollback plan: how the change is undone if the release fails.");
                }
                String impact = requireComment(comment, "Describe the proposed change and its impact.");
                pause(id, now, status);
                jdbc.update("UPDATE tickets SET rollback_plan = ? WHERE id = ?", plan, id);
                move(id, status, "PENDING_APPROVAL", impact);
                event(id, "ROLLBACK_PLAN", null, null, plan, false);
            }
            case "APPROVE", "REJECT" -> {
                CurrentActor.requirePermission("TICKET_APPROVE");
                expect(status, "PENDING_APPROVAL");
                if (reporter || CurrentActor.userId().equals(t.get("assignee_id"))) {
                    throw new ApiException(ErrorCode.SEGREGATION_OF_DUTIES, "The requester and the assignee cannot approve the change.");
                }
                unpause(t, now);
                String next = "APPROVE".equals(req.action()) ? "APPROVED" : "REJECTED";
                move(id, status, next, requireComment(comment, "Give the reason for the decision."));
                if ("REJECTED".equals(next)) {
                    jdbc.update("UPDATE tickets SET closed_at = ? WHERE id = ?", Timestamp.from(now), id);
                }
            }
            case "START" -> {
                need(worker, "Only support can start work.");
                if (CHANGE_TYPES.contains(type)) {
                    if (!"APPROVED".equals(status)) {
                        throw state("A change request or enhancement must be approved before work starts.");
                    }
                } else {
                    expect(status, "ACKNOWLEDGED");
                }
                move(id, status, "IN_PROGRESS", comment);
            }
            case "HOLD" -> {
                need(worker, "Only support can put a ticket on hold.");
                if (!Set.of("ACKNOWLEDGED", "IN_PROGRESS", "APPROVED").contains(status)) {
                    throw state("Only acknowledged, approved or in-progress tickets can be put on hold.");
                }
                pause(id, now, status);
                move(id, status, "ON_HOLD", requireComment(comment, "Say what the ticket is waiting for."));
            }
            case "RESUME" -> {
                need(worker || reporter, "You cannot resume this ticket.");
                expect(status, "ON_HOLD");
                String back = (String) t.get("status_before_hold");
                unpause(t, now);
                move(id, status, back, comment);
            }
            case "VALIDATE_STAGING" -> {
                need(CHANGE_TYPES.contains(type), "Only change requests and enhancements are validated on staging.");
                need(reporter || CurrentActor.hasPermission("TICKET_APPROVE"), "Only the requester or a change approver validates on staging.");
                if (CurrentActor.userId().equals(t.get("assignee_id"))) {
                    throw new ApiException(ErrorCode.SEGREGATION_OF_DUTIES, "The assignee cannot validate their own change.");
                }
                expect(status, "IN_PROGRESS");
                String note = requireComment(comment, "Record what was tested on staging and the result.");
                jdbc.update("UPDATE tickets SET staging_validated_at = ?, staging_validated_by_id = ?, staging_validated_by = ?, "
                        + "staging_validation_note = ? WHERE id = ?", Timestamp.from(now), CurrentActor.userId(), CurrentActor.username(), note, id);
                event(id, "STAGING_VALIDATED", null, null, note, false);
            }
            case "RESOLVE" -> {
                need(worker, "Only support can resolve tickets.");
                boolean ok = "IN_PROGRESS".equals(status) || (!CHANGE_TYPES.contains(type) && "ACKNOWLEDGED".equals(status));
                if (!ok) {
                    throw state("Only tickets being worked on can be resolved.");
                }
                String resolution = requireComment(comment, "Describe the resolution.");
                if (CHANGE_TYPES.contains(type)) {
                    if (t.get("staging_validated_at") == null) {
                        throw state("A change must be validated on staging by the requester or an approver before release.");
                    }
                    String notes = req.releaseNotes() == null ? "" : req.releaseNotes().trim();
                    if (notes.length() < 10) {
                        throw new ApiException(ErrorCode.VALIDATION_FAILED, "Give the release notes: what changed for users.");
                    }
                    jdbc.update("UPDATE tickets SET release_notes = ?, release_reference = ? WHERE id = ?", notes,
                            req.releaseReference() == null || req.releaseReference().isBlank() ? null : req.releaseReference().trim(), id);
                    event(id, "RELEASED", null, req.releaseReference() == null ? null : req.releaseReference().trim(), notes, false);
                }
                move(id, status, "RESOLVED", resolution);
                jdbc.update("UPDATE tickets SET resolved_at = ? WHERE id = ?", Timestamp.from(now), id);
                respond(t, now);
            }
            case "REOPEN" -> {
                need(reporter, "Only the requester can reopen a resolved ticket.");
                expect(status, "RESOLVED");
                Instant resolved = ((Timestamp) t.get("resolved_at")).toInstant();
                if (resolved.plus(reopenDays, ChronoUnit.DAYS).isBefore(now)) {
                    throw state("Resolved tickets can be reopened within " + reopenDays + " days; raise a new ticket instead.");
                }
                move(id, status, "IN_PROGRESS", requireComment(comment, "Say why the resolution did not work."));
                // A reopened change is fixed again and validated again before the next release.
                jdbc.update("UPDATE tickets SET resolved_at = NULL, staging_validated_at = NULL, staging_validated_by_id = NULL, "
                        + "staging_validated_by = NULL WHERE id = ?", id);
            }
            case "CLOSE" -> {
                need(reporter, "Only the requester confirms the resolution and closes the ticket; unconfirmed tickets close automatically after "
                        + reopenDays + " days.");
                expect(status, "RESOLVED");
                move(id, status, "CLOSED", comment);
                jdbc.update("UPDATE tickets SET closed_at = ?, closure_confirmed = TRUE, satisfaction = ? WHERE id = ?", Timestamp.from(now),
                        req.satisfaction(), id);
                event(id, "CONFIRMED", null, req.satisfaction() == null ? null : req.satisfaction() + "/5", null, false);
            }
            case "CLASSIFY" -> {
                need(worker, "Only support can reclassify tickets.");
                expectOpen(status);
                String sev = req.severity() == null ? (String) t.get("severity") : req.severity();
                UUID cat = req.categoryId() == null ? (UUID) t.get("category_id") : req.categoryId();
                if (java.util.Objects.equals(sev, t.get("severity")) && java.util.Objects.equals(cat, t.get("category_id"))) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "Choose a different severity or category.");
                }
                if (cat != null && !cat.equals(t.get("category_id"))) {
                    requireActiveCategory(cat);
                }
                String why = requireComment(comment, "Give the reason for the change.");
                jdbc.update("UPDATE tickets SET severity = ?, category_id = ? WHERE id = ?", sev, cat, id);
                if (!java.util.Objects.equals(sev, t.get("severity"))) {
                    event(id, "SEVERITY", (String) t.get("severity"), sev, why, false);
                }
                if (!java.util.Objects.equals(cat, t.get("category_id"))) {
                    event(id, "CATEGORY", categoryName((UUID) t.get("category_id")), categoryName(cat), why, false);
                }
            }
            case "CANCEL" -> {
                need(reporter, "Only the requester can cancel a ticket.");
                if (!Set.of("NEW", "ACKNOWLEDGED").contains(status)) {
                    throw state("Only tickets not yet being worked on can be cancelled.");
                }
                move(id, status, "CANCELLED", requireComment(comment, "Give a reason."));
                jdbc.update("UPDATE tickets SET closed_at = ? WHERE id = ?", Timestamp.from(now), id);
            }
            default -> {
                need(worker, "Only support can change the priority.");
                expectOpen(status);
                if (req.priority() == null || req.priority().equals(t.get("priority"))) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "Choose a different priority.");
                }
                reprioritise(t, req.priority(), requireComment(comment, "Give the reason for the change."));
            }
        }
        jdbc.update("UPDATE tickets SET version = version + 1, updated_at = ? WHERE id = ?", Timestamp.from(now), id);
        audit.record("TICKET_" + req.action(), "TICKET", t.get("ticket_number"), Map.of("from", status));
        return view(id);
    }

    // ------------------------------------------------------------------ attachments and auto-close

    /** Attaches a screenshot or document; recorded in the ticket's history. */
    @Transactional
    public TicketView attach(UUID id, String fileName, byte[] content) {
        DocumentInfo doc = documents.upload(OWNER, id, fileName, content);
        event(id, "ATTACHMENT", null, null, doc.fileName(), false);
        return view(id);
    }

    @Override
    public String ownerType() {
        return OWNER;
    }

    /** The requester and support may attach files until the ticket is closed, rejected or cancelled. */
    @Override
    public void assertCanModify(UUID ownerId) {
        Map<String, Object> t = visible(ownerId);
        if (!CurrentActor.userId().equals(t.get("reporter_id")) && !CurrentActor.hasPermission("TICKET_WORK")) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Only the requester and support can change a ticket's attachments.");
        }
        if (Set.of("CLOSED", "REJECTED", "CANCELLED").contains(t.get("status"))) {
            throw state("The ticket is " + ((String) t.get("status")).toLowerCase() + "; attachments can no longer change.");
        }
    }

    @Override
    public void assertCanView(UUID ownerId) {
        visible(ownerId);
    }

    /** Closes resolutions the requester neither confirmed nor reopened within the reopen window; returns how many. */
    @Transactional
    public int autoClose() {
        Instant now = Instant.now(clock);
        List<UUID> due = jdbc.queryForList("SELECT id FROM tickets WHERE status = 'RESOLVED' AND resolved_at < ? ORDER BY resolved_at FOR UPDATE SKIP LOCKED",
                UUID.class, Timestamp.from(now.minus(reopenDays, ChronoUnit.DAYS)));
        for (UUID id : due) {
            jdbc.update("UPDATE tickets SET status = 'CLOSED', closed_at = ?, closure_confirmed = FALSE, version = version + 1, updated_at = ? WHERE id = ?",
                    Timestamp.from(now), Timestamp.from(now), id);
            event(id, "STATUS", "RESOLVED", "CLOSED", "Closed automatically: the requester did not confirm or reopen within " + reopenDays + " days.",
                    false);
            audit.record(com.miaa.cas.audit.Actor.SYSTEM, "TICKET_AUTO_CLOSED", "TICKET", jdbc.queryForObject("SELECT ticket_number FROM tickets WHERE id = ?", String.class, id),
                    Map.of("reopenDays", reopenDays));
        }
        return due.size();
    }

    private void requireActiveCategory(UUID categoryId) {
        if (categoryId == null || !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM ticket_categories WHERE id = ? AND status = 'ACTIVE')",
                Boolean.class, categoryId))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Choose an active ticket category.");
        }
    }

    private String categoryName(UUID id) {
        if (id == null) {
            return null;
        }
        return jdbc.queryForObject("SELECT code FROM ticket_categories WHERE id = ?", String.class, id);
    }

    // ------------------------------------------------------------------ SLA helpers

    private void respond(Map<String, Object> t, Instant now) {
        jdbc.update("UPDATE tickets SET first_response_at = ? WHERE id = ? AND first_response_at IS NULL", Timestamp.from(now), t.get("id"));
    }

    private void pause(UUID id, Instant now, String status) {
        jdbc.update("UPDATE tickets SET on_hold_since = ?, status_before_hold = ? WHERE id = ?", Timestamp.from(now), status, id);
    }

    /** Extends the open SLA deadlines by the SLA time spent paused. */
    private void unpause(Map<String, Object> t, Instant now) {
        Timestamp since = (Timestamp) t.get("on_hold_since");
        if (since == null) {
            return;
        }
        Map<String, Object> policy = t.get("sla_policy_id") == null ? null
                : jdbc.queryForMap("SELECT * FROM sla_policies WHERE id = ?", t.get("sla_policy_id"));
        if (policy != null) {
            BusinessClock bc = sla.clock();
            boolean business = (Boolean) policy.get("business_hours");
            long paused = bc.minutesBetween(since.toInstant(), now, business);
            Timestamp resolution = (Timestamp) t.get("resolution_due_at");
            if (resolution != null && !(Boolean) t.get("resolution_breached")) {
                jdbc.update("UPDATE tickets SET resolution_due_at = ? WHERE id = ?", Timestamp.from(bc.plus(resolution.toInstant(), paused, business)),
                        t.get("id"));
            }
        }
        jdbc.update("UPDATE tickets SET on_hold_since = NULL, status_before_hold = NULL WHERE id = ?", t.get("id"));
    }

    private void reprioritise(Map<String, Object> t, String priority, String comment) {
        Map<String, Object> policy = sla.activePolicy((String) t.get("contract_phase"), (String) t.get("ticket_type"), priority);
        Instant created = ((Timestamp) t.get("created_at")).toInstant();
        BusinessClock bc = sla.clock();
        jdbc.update("UPDATE tickets SET priority = ?, sla_policy_id = ?, response_due_at = ?, resolution_due_at = ?, at_risk_notified = FALSE "
                + "WHERE id = ?", priority, policy == null ? null : policy.get("id"), policy == null ? null : due(bc, created, policy, "response_minutes"),
                policy == null ? null : due(bc, created, policy, "resolution_minutes"), t.get("id"));
        event((UUID) t.get("id"), "PRIORITY", (String) t.get("priority"), priority, comment, false);
    }

    private static Timestamp due(BusinessClock bc, Instant from, Map<String, Object> policy, String column) {
        return Timestamp.from(bc.plus(from, ((Number) policy.get(column)).longValue(), (Boolean) policy.get("business_hours")));
    }

    // ------------------------------------------------------------------ helpers

    void event(UUID ticket, String type, String from, String to, String comment, boolean internal) {
        jdbc.update("""
                INSERT INTO ticket_events (ticket_id, seq, occurred_at, actor, event_type, from_value, to_value, comment, internal)
                VALUES (?, (SELECT coalesce(max(seq), 0) + 1 FROM ticket_events WHERE ticket_id = ?), ?, ?, ?, ?, ?, ?, ?)""", ticket, ticket,
                Timestamp.from(Instant.now(clock)), actorName(), type, from, to, comment, internal);
    }

    private static String actorName() {
        try {
            return CurrentActor.username();
        } catch (ApiException e) {
            return "system";
        }
    }

    private void move(UUID id, String from, String to, String comment) {
        jdbc.update("UPDATE tickets SET status = ? WHERE id = ?", to, id);
        event(id, "STATUS", from, to, comment, false);
    }

    private Map<String, Object> row(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM tickets WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Ticket");
        }
        return rows.get(0);
    }

    /** The ticket, if the caller may see it; others' tickets answer 404 to users without the all-tickets view. */
    private Map<String, Object> visible(UUID id) {
        Map<String, Object> t = row(id);
        if (!CurrentActor.userId().equals(t.get("reporter_id")) && !CurrentActor.hasPermission("TICKET_VIEW_ALL")
                && !CurrentActor.hasPermission("TICKET_WORK")) {
            throw ApiException.notFound("Ticket");
        }
        return t;
    }

    private TicketView view(Map<String, Object> t) {
        return view((UUID) t.get("id"));
    }

    private TicketView view(UUID id) {
        Map<String, Object> t = row(id);
        boolean staff = CurrentActor.hasPermission("TICKET_WORK") || CurrentActor.hasPermission("TICKET_VIEW_ALL");
        List<EventView> events = jdbc.query("SELECT * FROM ticket_events WHERE ticket_id = ? AND (? OR NOT internal) ORDER BY seq",
                (rs, i) -> new EventView(rs.getInt("seq"), rs.getTimestamp("occurred_at").toInstant(), rs.getString("actor"),
                        rs.getString("event_type"), rs.getString("from_value"), rs.getString("to_value"), rs.getString("comment"),
                        rs.getBoolean("internal")), id, staff);
        Map<String, Object> policy = t.get("sla_policy_id") == null ? null
                : jdbc.queryForMap("SELECT * FROM sla_policies WHERE id = ?", t.get("sla_policy_id"));
        String status = (String) t.get("status");
        boolean paused = t.get("on_hold_since") != null;
        SlaState s;
        if (policy == null) {
            s = new SlaState(null, false, null, null, ts((Timestamp) t.get("first_response_at")), null, null, paused,
                    (Boolean) t.get("response_breached"), (Boolean) t.get("resolution_breached"), ((Number) t.get("escalation_level")).intValue());
        } else {
            boolean business = (Boolean) policy.get("business_hours");
            BusinessClock bc = sla.clock();
            Instant now = Instant.now(clock);
            Instant rDue = ts((Timestamp) t.get("response_due_at"));
            Instant xDue = ts((Timestamp) t.get("resolution_due_at"));
            Long rLeft = t.get("first_response_at") != null ? null : signed(bc, now, rDue, business);
            Long xLeft = CLOSED.contains(status) || paused ? null : signed(bc, now, xDue, business);
            s = new SlaState(policy.get("contract_phase") + " " + policy.get("ticket_type") + " " + policy.get("priority") + ": respond "
                    + policy.get("response_minutes") + " min, resolve " + policy.get("resolution_minutes") + " min" + (business ? " (business hours)" : " (24x7)")
                    + ((Boolean) policy.get("placeholder") ? " [PLACEHOLDER]" : ""), business, rDue, xDue, ts((Timestamp) t.get("first_response_at")),
                    rLeft, xLeft, paused, (Boolean) t.get("response_breached"), (Boolean) t.get("resolution_breached"),
                    ((Number) t.get("escalation_level")).intValue());
        }
        UUID categoryId = (UUID) t.get("category_id");
        String category = categoryId == null ? null : jdbc.queryForObject("SELECT name FROM ticket_categories WHERE id = ?", String.class, categoryId);
        ChangeControl change = CHANGE_TYPES.contains(t.get("ticket_type")) ? new ChangeControl((String) t.get("rollback_plan"),
                ts((Timestamp) t.get("staging_validated_at")), (String) t.get("staging_validated_by"), (String) t.get("staging_validation_note"),
                (String) t.get("release_notes"), (String) t.get("release_reference")) : null;
        return new TicketView(id, (String) t.get("ticket_number"), (String) t.get("ticket_type"), (String) t.get("priority"), (String) t.get("severity"),
                category, categoryId, (String) t.get("title"), (String) t.get("description"), (String) t.get("module"), status, (String) t.get("reporter"),
                (String) t.get("assignee"), (String) t.get("contract_phase"), s, ts((Timestamp) t.get("created_at")), ts((Timestamp) t.get("resolved_at")),
                ts((Timestamp) t.get("closed_at")), (Boolean) t.get("closure_confirmed"),
                t.get("satisfaction") == null ? null : ((Number) t.get("satisfaction")).intValue(), change, documents.list(OWNER, id), events, actions(t),
                ((Number) t.get("version")).longValue());
    }

    private List<String> actions(Map<String, Object> t) {
        String status = (String) t.get("status");
        String type = (String) t.get("ticket_type");
        boolean reporter = CurrentActor.userId().equals(t.get("reporter_id"));
        boolean worker = CurrentActor.hasPermission("TICKET_WORK");
        boolean change = CHANGE_TYPES.contains(type);
        List<String> a = new ArrayList<>();
        if (!CLOSED.contains(status)) {
            a.add("COMMENT");
        }
        if (worker) {
            if ("NEW".equals(status)) {
                a.add("ACKNOWLEDGE");
            }
            if (!CLOSED.contains(status)) {
                a.add("ASSIGN");
                a.add("PRIORITY");
                a.add("CLASSIFY");
            }
            if ("ACKNOWLEDGED".equals(status) && change) {
                a.add("SUBMIT_FOR_APPROVAL");
            }
            if (("ACKNOWLEDGED".equals(status) && !change) || "APPROVED".equals(status)) {
                a.add("START");
            }
            if (Set.of("ACKNOWLEDGED", "IN_PROGRESS", "APPROVED").contains(status)) {
                a.add("HOLD");
            }
            if (("IN_PROGRESS".equals(status) && (!change || t.get("staging_validated_at") != null)) || ("ACKNOWLEDGED".equals(status) && !change)) {
                a.add("RESOLVE");
            }
        }
        if ("PENDING_APPROVAL".equals(status) && CurrentActor.hasPermission("TICKET_APPROVE") && !reporter
                && !CurrentActor.userId().equals(t.get("assignee_id"))) {
            a.addAll(List.of("APPROVE", "REJECT"));
        }
        if ("IN_PROGRESS".equals(status) && change && t.get("staging_validated_at") == null
                && (reporter || CurrentActor.hasPermission("TICKET_APPROVE")) && !CurrentActor.userId().equals(t.get("assignee_id"))) {
            a.add("VALIDATE_STAGING");
        }
        if ("ON_HOLD".equals(status) && (worker || reporter)) {
            a.add("RESUME");
        }
        if ("RESOLVED".equals(status)) {
            if (reporter) {
                a.add("REOPEN");
                a.add("CLOSE");
            }
        }
        if (reporter && Set.of("NEW", "ACKNOWLEDGED").contains(status)) {
            a.add("CANCEL");
        }
        return a;
    }

    private static Long signed(BusinessClock bc, Instant now, Instant due, boolean business) {
        if (due == null) {
            return null;
        }
        return due.isAfter(now) ? bc.minutesBetween(now, due, business) : -bc.minutesBetween(due, now, business);
    }

    private static void need(boolean ok, String message) {
        if (!ok) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, message);
        }
    }

    private static void expect(String status, String expected) {
        if (!expected.equals(status)) {
            throw state("This action needs the ticket to be " + expected.replace('_', ' ').toLowerCase() + " (it is " + status.replace('_', ' ')
                    .toLowerCase() + ").");
        }
    }

    private static void expectOpen(String status) {
        if (CLOSED.contains(status)) {
            throw state("The ticket is " + status.toLowerCase() + ".");
        }
    }

    private static String requireComment(String comment, String message) {
        if (comment == null || comment.length() < 5) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, message);
        }
        return comment;
    }

    private static ApiException state(String message) {
        return new ApiException(ErrorCode.INVALID_STATE, message);
    }

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim().toUpperCase();
    }
}
