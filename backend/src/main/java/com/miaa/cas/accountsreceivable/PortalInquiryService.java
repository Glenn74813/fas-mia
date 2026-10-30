package com.miaa.cas.accountsreceivable;

import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.web.JdbcPaging;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.documents.DocumentOwnerPolicy;
import com.miaa.cas.documents.DocumentService;
import com.miaa.cas.documents.DocumentService.DocumentInfo;
import com.miaa.cas.journal.NumberingService;
import com.miaa.cas.notifications.NotificationService;
import com.miaa.cas.notifications.OutboxService;
import com.miaa.cas.security.CurrentActor;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Concessionaire inquiries and payment references (MS §11.3). A concessionaire writes about their own account only;
 * a payment reference tells the cashier about a payment made (with proof attached if they wish) but records and posts
 * nothing: the receipt is still recorded and posted through Collections. Staff answer; the concessionaire is e-mailed.
 */
@Service
public class PortalInquiryService implements DocumentOwnerPolicy {

    public static final String OWNER = "PORTAL_INQUIRY";

    public record InquiryRequest(@NotNull @Pattern(regexp = "PAYMENT_REFERENCE|QUESTION") String kind,
            @NotBlank @Size(max = 200) String subject, @NotBlank @Size(max = 4000) String message, UUID billingId, LocalDate paymentDate,
            @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal paymentAmount,
            @Pattern(regexp = "CASH|CHECK|BANK_TRANSFER") String paymentMethod, @Size(max = 60) String paymentReference) {
    }

    public record InquiryView(UUID id, String reference, UUID customerId, String customerCode, String customerName, String kind,
            String subject, String message, UUID billingId, String billingNumber, LocalDate paymentDate, BigDecimal paymentAmount,
            String paymentMethod, String paymentReference, String status, String response, String respondedBy, Instant respondedAt,
            String submittedBy, Instant createdAt, List<DocumentInfo> documents, List<String> availableActions, long version) {
    }

    private final DocumentService documents;
    private final NumberingService numbering;
    private final OutboxService outbox;
    private final AuditService audit;
    private final NotificationService notifications;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public PortalInquiryService(DocumentService documents, NumberingService numbering, OutboxService outbox, AuditService audit, NotificationService notifications,
            JdbcTemplate jdbc, Clock clock) {
        this.documents = documents;
        this.numbering = numbering;
        this.outbox = outbox;
        this.audit = audit;
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ concessionaire side

    @Transactional(readOnly = true)
    public PageResponse<InquiryView> mine(int page, int size) {
        UUID customer = myCustomer();
        return JdbcPaging.ids(jdbc, "SELECT id FROM portal_inquiries WHERE customer_id = ? ORDER BY created_at DESC, id", page, size, customer)
                .map(this::view);
    }

    @Transactional
    public InquiryView submit(InquiryRequest req) {
        UUID customer = myCustomer();
        boolean payment = "PAYMENT_REFERENCE".equals(req.kind());
        if (payment && (req.paymentDate() == null || req.paymentAmount() == null || req.paymentMethod() == null
                || req.paymentReference() == null || req.paymentReference().isBlank())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Give the payment date, amount, method and reference.");
        }
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Manila")));
        if (payment && req.paymentDate().isAfter(today)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The payment date cannot be in the future.");
        }
        if (req.billingId() != null && Boolean.FALSE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM billing_documents WHERE id = ? "
                + "AND customer_id = ? AND status = 'POSTED')", Boolean.class, req.billingId(), customer))) {
            throw ApiException.notFound("Billing document");
        }
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        jdbc.update("""
                INSERT INTO portal_inquiries (id, reference, customer_id, submitted_by_id, submitted_by, kind, subject, message, billing_id,
                    payment_date, payment_amount, payment_method, payment_reference, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?)""", id, numbering.next("INQ", today.getYear()), customer,
                CurrentActor.userId(), CurrentActor.username(), req.kind(), req.subject().trim(), req.message().trim(), req.billingId(),
                payment ? Date.valueOf(req.paymentDate()) : null, payment ? req.paymentAmount() : null, payment ? req.paymentMethod() : null,
                payment ? req.paymentReference().trim() : null, Timestamp.from(now));
        audit.record("PORTAL_INQUIRY_SUBMITTED", OWNER, id, Map.of("kind", req.kind()));
        return view(id);
    }

    // ------------------------------------------------------------------ staff side

    @Transactional(readOnly = true)
    public PageResponse<InquiryView> list(String status, UUID customerId, int page, int size) {
        CurrentActor.requirePermission("AR_VIEW");
        String s = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        return JdbcPaging.ids(jdbc, "SELECT id FROM portal_inquiries WHERE (?::text IS NULL OR status = ?::text) "
                + "AND (?::uuid IS NULL OR customer_id = ?::uuid) ORDER BY created_at DESC, id", page, size, s, s, customerId, customerId)
                .map(this::view);
    }

    @Transactional(readOnly = true)
    public InquiryView get(UUID id) {
        assertCanView(id);
        return view(id);
    }

    @Transactional
    public InquiryView respond(UUID id, long version, String response) {
        CurrentActor.requirePermission("PORTAL_INQUIRY_RESPOND");
        Map<String, Object> r = lock(id, version);
        if ("CLOSED".equals(r.get("status"))) {
            throw new ApiException(ErrorCode.INVALID_STATE, "The inquiry is closed.");
        }
        if (response == null || response.trim().length() < 5 || response.trim().length() > 4000) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Write a response (5 to 4000 characters).");
        }
        jdbc.update("UPDATE portal_inquiries SET status = 'ANSWERED', response = ?, responded_by = ?, responded_at = ?, version = version + 1 "
                + "WHERE id = ?", response.trim(), CurrentActor.username(), Timestamp.from(Instant.now(clock)), id);
        String email = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, r.get("submitted_by_id"));
        outbox.enqueueEmail(email, "portal-inquiry-answered", Map.of("reference", r.get("reference"), "subject", r.get("subject")));
        notifications.notify((UUID) r.get("submitted_by_id"), "INQUIRY_ANSWERED", "AVTECH Finance answered inquiry " + r.get("reference"),
                (String) r.get("subject"), "/portal");
        audit.record("PORTAL_INQUIRY_ANSWERED", OWNER, id, Map.of("reference", r.get("reference")));
        return view(id);
    }

    @Transactional
    public InquiryView close(UUID id, long version) {
        CurrentActor.requirePermission("PORTAL_INQUIRY_RESPOND");
        Map<String, Object> r = lock(id, version);
        if (!"ANSWERED".equals(r.get("status"))) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Only an answered inquiry can be closed.");
        }
        jdbc.update("UPDATE portal_inquiries SET status = 'CLOSED', version = version + 1 WHERE id = ?", id);
        audit.record("PORTAL_INQUIRY_CLOSED", OWNER, id, Map.of("reference", r.get("reference")));
        return view(id);
    }

    // ------------------------------------------------------------------ document policy

    @Override
    public String ownerType() {
        return OWNER;
    }

    /** Only the concessionaire who submitted it attaches proof, while it is open. */
    @Override
    public void assertCanModify(UUID ownerId) {
        Map<String, Object> r = row(ownerId);
        if (!CurrentActor.userId().equals(r.get("submitted_by_id")) || !"OPEN".equals(r.get("status"))) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Only the sender can attach files while the inquiry is open.");
        }
    }

    /** Staff with AR access, or the concessionaire whose customer it concerns; others are told it does not exist. */
    @Override
    public void assertCanView(UUID ownerId) {
        Map<String, Object> r = row(ownerId);
        if (CurrentActor.hasPermission("AR_VIEW")) {
            return;
        }
        if (CurrentActor.hasPermission("PORTAL_SELF_VIEW") && r.get("customer_id").equals(myCustomer())) {
            return;
        }
        throw ApiException.notFound("Inquiry");
    }

    // ------------------------------------------------------------------ helpers

    private UUID myCustomer() {
        CurrentActor.requirePermission("PORTAL_SELF_VIEW");
        List<UUID> ids = jdbc.queryForList("SELECT customer_id FROM users WHERE id = ? AND user_type = 'CONCESSIONAIRE' AND customer_id IS NOT NULL",
                UUID.class, CurrentActor.userId());
        if (ids.isEmpty()) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Your portal account is not yet linked to a customer account. "
                    + "Please contact AVTECH Finance.");
        }
        return ids.get(0);
    }

    private Map<String, Object> row(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM portal_inquiries WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Inquiry");
        }
        return rows.get(0);
    }

    private Map<String, Object> lock(UUID id, long version) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM portal_inquiries WHERE id = ? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Inquiry");
        }
        if (((Number) rows.get(0).get("version")).longValue() != version) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION, "The inquiry was changed by someone else; reload it.");
        }
        return rows.get(0);
    }

    private InquiryView view(UUID id) {
        Map<String, Object> r = jdbc.queryForMap("""
                SELECT i.*, c.code AS c_code, c.name AS c_name, b.document_number FROM portal_inquiries i JOIN customers c ON c.id = i.customer_id
                LEFT JOIN billing_documents b ON b.id = i.billing_id WHERE i.id = ?""", id);
        String status = (String) r.get("status");
        List<String> actions = new ArrayList<>();
        boolean staff = CurrentActor.hasPermission("PORTAL_INQUIRY_RESPOND");
        if (staff && !"CLOSED".equals(status)) {
            actions.add("RESPOND");
        }
        if (staff && "ANSWERED".equals(status)) {
            actions.add("CLOSE");
        }
        if (CurrentActor.userId().equals(r.get("submitted_by_id")) && "OPEN".equals(status)) {
            actions.add("UPLOAD");
        }
        Timestamp responded = (Timestamp) r.get("responded_at");
        Date paid = (Date) r.get("payment_date");
        return new InquiryView(id, (String) r.get("reference"), (UUID) r.get("customer_id"), (String) r.get("c_code"), (String) r.get("c_name"),
                (String) r.get("kind"), (String) r.get("subject"), (String) r.get("message"), (UUID) r.get("billing_id"),
                (String) r.get("document_number"), paid == null ? null : paid.toLocalDate(), (BigDecimal) r.get("payment_amount"),
                (String) r.get("payment_method"), (String) r.get("payment_reference"), status, (String) r.get("response"),
                (String) r.get("responded_by"), responded == null ? null : responded.toInstant(), (String) r.get("submitted_by"),
                ((Timestamp) r.get("created_at")).toInstant(), documents.list(OWNER, id), actions, ((Number) r.get("version")).longValue());
    }
}
