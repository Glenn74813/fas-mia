package com.miaa.cas.integrations.webhooks;

import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.crypto.AesGcmEncryptor;
import com.miaa.cas.common.crypto.SecureTokens;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.web.JdbcPaging;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.integrations.api.ApiClientService;
import com.miaa.cas.security.CurrentActor;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Webhook subscriptions (TOR §4.6): a target URL, the events it wants and a signing secret shown once. Deliveries are
 * made by {@link WebhookDispatcher}; this service manages subscriptions and shows the delivery log.
 */
@Service
public class WebhookService {

    /** Events a subscriber may ask for: audit-trail actions for posting, approval and payment. */
    public static final Set<String> EVENTS = java.util.Collections.unmodifiableSortedSet(new TreeSet<>(Set.of("JOURNAL_VOUCHER_POSTED", "AP_INVOICE_APPROVED", "AP_INVOICE_POSTED",
            "DV_APPROVED", "DV_PAYMENT_INSTRUCTED", "DV_PAYMENT_CONFIRMED", "DV_POSTED", "AR_BILLING_POSTED", "RECEIPT_POSTED",
            "DEPOSIT_POSTED", "BUDGET_APPROVED", "FISCAL_YEAR_CLOSED")));

    public record SubscriptionView(UUID id, String name, String url, List<String> events, String status, int consecutiveFailures,
            String createdBy, Instant createdAt, String updatedBy, Instant updatedAt, long version) {
    }

    public record SubscriptionWithSecret(SubscriptionView subscription, String signingSecret) {
    }

    public record DeliveryView(UUID id, UUID subscriptionId, UUID eventId, String eventType, String status, int attempts,
            Instant nextAttemptAt, Integer lastStatus, String lastError, Instant createdAt, Instant deliveredAt) {
    }

    private final JdbcTemplate jdbc;
    private final AesGcmEncryptor encryptor;
    private final WebhookTargets targets;
    private final AuditService audit;
    private final Clock clock;

    public WebhookService(JdbcTemplate jdbc, AesGcmEncryptor encryptor, WebhookTargets targets, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.encryptor = encryptor;
        this.targets = targets;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<SubscriptionView> list() {
        return jdbc.query("SELECT * FROM webhook_subscriptions ORDER BY status, name", (rs, i) -> map(rs));
    }

    @Transactional
    public SubscriptionWithSecret create(String name, String url, List<String> events) {
        CurrentActor.requirePermission(ApiClientService.MANAGE);
        String n = name == null ? "" : name.trim();
        if (n.length() < 3) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Name the subscription (at least 3 characters).");
        }
        targets.check(url);
        List<String> e = validEvents(events);
        UUID id = UUID.randomUUID();
        String secret = "whsec_" + SecureTokens.newToken();
        Timestamp now = Timestamp.from(Instant.now(clock));
        jdbc.update("""
                INSERT INTO webhook_subscriptions (id, name, url, events, secret_enc, status, created_by_id, created_by, created_at, updated_by,
                    updated_at) VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?)""", id, n, url.trim(), e.toArray(String[]::new),
                encryptor.encrypt(secret.getBytes(StandardCharsets.UTF_8), aad(id)), CurrentActor.userId(), CurrentActor.username(), now,
                CurrentActor.username(), now);
        audit.record("WEBHOOK_SUBSCRIBED", "WEBHOOK", id, Map.of("name", n, "url", url.trim(), "events", String.join(" ", e)));
        return new SubscriptionWithSecret(get(id), secret);
    }

    /** ACTIVE (also resuming a subscription disabled after failures) or PAUSED. */
    @Transactional
    public SubscriptionView setStatus(UUID id, String status, long version) {
        CurrentActor.requirePermission(ApiClientService.MANAGE);
        if (!"ACTIVE".equals(status) && !"PAUSED".equals(status)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "status must be ACTIVE or PAUSED.");
        }
        Map<String, Object> s = lock(id, version);
        if ("ACTIVE".equals(status)) {
            targets.check((String) s.get("url"));
        }
        jdbc.update("UPDATE webhook_subscriptions SET status = ?, consecutive_failures = CASE WHEN ? = 'ACTIVE' THEN 0 ELSE consecutive_failures END, "
                + "updated_by = ?, updated_at = ?, version = version + 1 WHERE id = ?", status, status, CurrentActor.username(),
                Timestamp.from(Instant.now(clock)), id);
        audit.record("WEBHOOK_" + ("ACTIVE".equals(status) ? "RESUMED" : "PAUSED"), "WEBHOOK", id, Map.of());
        return get(id);
    }

    @Transactional
    public SubscriptionWithSecret rotateSecret(UUID id, long version) {
        CurrentActor.requirePermission(ApiClientService.MANAGE);
        lock(id, version);
        String secret = "whsec_" + SecureTokens.newToken();
        jdbc.update("UPDATE webhook_subscriptions SET secret_enc = ?, updated_by = ?, updated_at = ?, version = version + 1 WHERE id = ?",
                encryptor.encrypt(secret.getBytes(StandardCharsets.UTF_8), aad(id)), CurrentActor.username(), Timestamp.from(Instant.now(clock)), id);
        audit.record("WEBHOOK_SECRET_ROTATED", "WEBHOOK", id, Map.of());
        return new SubscriptionWithSecret(get(id), secret);
    }

    @Transactional(readOnly = true)
    public PageResponse<DeliveryView> deliveries(UUID subscriptionId, int page, int size) {
        return JdbcPaging.query(jdbc, "SELECT * FROM webhook_deliveries WHERE subscription_id = ? ORDER BY created_at DESC, id",
                (rs, i) -> delivery(rs), page, size, subscriptionId);
    }

    /** Sends a dead delivery again (one more round of attempts). */
    @Transactional
    public DeliveryView redeliver(UUID deliveryId) {
        CurrentActor.requirePermission(ApiClientService.MANAGE);
        int n = jdbc.update("UPDATE webhook_deliveries SET status = 'PENDING', attempts = 0, next_attempt_at = ? WHERE id = ? AND status = 'DEAD'",
                Timestamp.from(Instant.now(clock)), deliveryId);
        if (n == 0) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Only a failed delivery can be sent again.");
        }
        audit.record("WEBHOOK_REDELIVERY_REQUESTED", "WEBHOOK_DELIVERY", deliveryId, Map.of());
        return jdbc.queryForObject("SELECT * FROM webhook_deliveries WHERE id = ?", (rs, i) -> delivery(rs), deliveryId);
    }

    String secret(UUID id, byte[] sealed) {
        return new String(encryptor.decrypt(sealed, aad(id)), StandardCharsets.UTF_8);
    }

    private static List<String> validEvents(List<String> events) {
        List<String> e = events == null ? List.of() : events.stream().distinct().sorted().toList();
        if (e.isEmpty() || !EVENTS.containsAll(e)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Choose one or more events from: " + EVENTS + ".");
        }
        return e;
    }

    private Map<String, Object> lock(UUID id, long version) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT url, version FROM webhook_subscriptions WHERE id = ? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Webhook subscription");
        }
        if (((Number) rows.get(0).get("version")).longValue() != version) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION, "The subscription was changed by someone else. Reload and try again.");
        }
        return rows.get(0);
    }

    SubscriptionView get(UUID id) {
        return jdbc.queryForObject("SELECT * FROM webhook_subscriptions WHERE id = ?", (rs, i) -> map(rs), id);
    }

    private static String aad(UUID id) {
        return "webhook-secret:" + id;
    }

    private static SubscriptionView map(ResultSet rs) throws SQLException {
        return new SubscriptionView(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("url"),
                Arrays.stream((Object[]) rs.getArray("events").getArray()).map(Object::toString).toList(), rs.getString("status"),
                rs.getInt("consecutive_failures"), rs.getString("created_by"), ts(rs, "created_at"), rs.getString("updated_by"),
                ts(rs, "updated_at"), rs.getLong("version"));
    }

    static DeliveryView delivery(ResultSet rs) throws SQLException {
        int last = rs.getInt("last_status");
        Integer lastStatus = rs.wasNull() ? null : last;
        return new DeliveryView(rs.getObject("id", UUID.class), rs.getObject("subscription_id", UUID.class), rs.getObject("event_id", UUID.class),
                rs.getString("event_type"), rs.getString("status"), rs.getInt("attempts"), ts(rs, "next_attempt_at"), lastStatus,
                rs.getString("last_error"), ts(rs, "created_at"), ts(rs, "delivered_at"));
    }

    private static Instant ts(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }
}
