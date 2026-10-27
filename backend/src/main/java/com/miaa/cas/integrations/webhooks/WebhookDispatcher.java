package com.miaa.cas.integrations.webhooks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaa.cas.audit.Actor;
import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.crypto.TlsPolicy;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.integrations.api.ApiClientService;
import com.miaa.cas.notifications.AlertService;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns audit-trail events into webhook deliveries and sends them. Events are read in audit order from a cursor, so
 * only committed business changes are announced, once per subscription. Each delivery is a POST of a small JSON
 * envelope (event id, type, time, resource), never the business data; the receiver fetches what it needs through
 * the API. Requests are signed: {@code X-CAS-Signature: t=<unix seconds>,v1=<hex HMAC-SHA-256 of "t.body">}.
 * Failures are retried after 1 min, 5 min, 30 min, 2 h, 6 h and 12 h, then the delivery is dead; ten dead deliveries
 * in a row disable the subscription and alert its managers.
 */
@Service
public class WebhookDispatcher {

    static final List<Duration> BACKOFF = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2),
            Duration.ofHours(6), Duration.ofHours(12));
    static final int DISABLE_AFTER = 10;
    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final WebhookService webhooks;
    private final WebhookTargets targets;
    private final AuditService audit;
    private final AlertService alerts;
    private final ObjectMapper json;
    private final Clock clock;
    private final HttpClient http;

    public WebhookDispatcher(JdbcTemplate jdbc, TransactionTemplate tx, WebhookService webhooks, WebhookTargets targets, AuditService audit,
            AlertService alerts, ObjectMapper json, Clock clock, TlsPolicy tls,
            @Value("${cas.webhooks.connect-timeout:PT10S}") Duration connectTimeout) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.webhooks = webhooks;
        this.targets = targets;
        this.audit = audit;
        this.alerts = alerts;
        this.json = json;
        this.clock = clock;
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER)
                .sslParameters(tls.clientParameters()).build();
    }

    @Scheduled(fixedDelayString = "${cas.webhooks.poll-interval:PT15S}")
    public void poll() {
        try {
            enqueue();
            deliverDue();
        } catch (RuntimeException e) {
            log.error("Webhook dispatcher failed", e);
        }
    }

    /** Reads new audit events and creates a delivery for every active subscription that wants each one. */
    public int enqueue() {
        Integer added = tx.execute(s -> {
            long cursor = jdbc.queryForObject("SELECT last_audit_id FROM webhook_cursor WHERE id = 1 FOR UPDATE", Long.class);
            List<Map<String, Object>> events = jdbc.queryForList("SELECT id, event_id, occurred_at, action, resource_type, resource_id "
                    + "FROM audit_events WHERE id > ? ORDER BY id LIMIT 500", cursor);
            if (events.isEmpty()) {
                return 0;
            }
            int n = 0;
            Timestamp now = Timestamp.from(Instant.now(clock));
            for (Map<String, Object> e : events) {
                String action = (String) e.get("action");
                if (!WebhookService.EVENTS.contains(action)) {
                    continue;
                }
                String payload = payload((UUID) e.get("event_id"), action, ((Timestamp) e.get("occurred_at")).toInstant(),
                        (String) e.get("resource_type"), (String) e.get("resource_id"));
                n += jdbc.update("""
                        INSERT INTO webhook_deliveries (id, subscription_id, event_id, event_type, payload, status, next_attempt_at, created_at)
                        SELECT gen_random_uuid(), w.id, ?, ?, ?, 'PENDING', ?, ? FROM webhook_subscriptions w
                        WHERE w.status = 'ACTIVE' AND ? = ANY (w.events)
                        ON CONFLICT (subscription_id, event_id) DO NOTHING""", e.get("event_id"), action, payload, now, now, action);
            }
            jdbc.update("UPDATE webhook_cursor SET last_audit_id = ? WHERE id = 1", events.get(events.size() - 1).get("id"));
            return n;
        });
        return added == null ? 0 : added;
    }

    /** Sends every delivery that is due; returns how many were attempted. */
    public int deliverDue() {
        int n = 0;
        while (true) {
            List<Map<String, Object>> batch = tx.execute(s -> {
                List<Map<String, Object>> rows = jdbc.queryForList("""
                        SELECT d.id, d.subscription_id, d.event_id, d.event_type, d.payload, d.attempts, w.url, w.secret_enc
                        FROM webhook_deliveries d JOIN webhook_subscriptions w ON w.id = d.subscription_id
                        WHERE d.status = 'PENDING' AND d.next_attempt_at <= ? AND w.status = 'ACTIVE'
                        ORDER BY d.next_attempt_at FOR UPDATE OF d SKIP LOCKED LIMIT 20""", Timestamp.from(Instant.now(clock)));
                // A lease: another instance will not pick these up while they are being sent.
                for (Map<String, Object> r : rows) {
                    jdbc.update("UPDATE webhook_deliveries SET next_attempt_at = ? WHERE id = ?",
                            Timestamp.from(Instant.now(clock).plus(Duration.ofMinutes(2))), r.get("id"));
                }
                return rows;
            });
            if (batch == null || batch.isEmpty()) {
                return n;
            }
            for (Map<String, Object> d : batch) {
                attempt(d);
                n++;
            }
        }
    }

    /** Sends a test event to a subscription now and returns the delivery. */
    public WebhookService.DeliveryView ping(UUID subscriptionId) {
        com.miaa.cas.security.CurrentActor.requirePermission(ApiClientService.MANAGE);
        WebhookService.SubscriptionView s = webhooks.get(subscriptionId);
        UUID eventId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now(clock));
        tx.executeWithoutResult(t -> {
            jdbc.update("INSERT INTO webhook_deliveries (id, subscription_id, event_id, event_type, payload, status, next_attempt_at, created_at) "
                    + "VALUES (?, ?, ?, 'PING', ?, 'PENDING', ?, ?)", id, subscriptionId, eventId,
                    payload(eventId, "PING", now.toInstant(), "WEBHOOK", subscriptionId.toString()), now, now);
            audit.record("WEBHOOK_PINGED", "WEBHOOK", subscriptionId, Map.of("url", s.url()));
        });
        attempt(jdbc.queryForMap("""
                SELECT d.id, d.subscription_id, d.event_id, d.event_type, d.payload, d.attempts, w.url, w.secret_enc
                FROM webhook_deliveries d JOIN webhook_subscriptions w ON w.id = d.subscription_id WHERE d.id = ?""", id));
        return jdbc.queryForObject("SELECT * FROM webhook_deliveries WHERE id = ?", (rs, i) -> WebhookService.delivery(rs), id);
    }

    private void attempt(Map<String, Object> d) {
        UUID id = (UUID) d.get("id");
        UUID subscription = (UUID) d.get("subscription_id");
        String body = (String) d.get("payload");
        int attempt = ((Number) d.get("attempts")).intValue() + 1;
        Integer status = null;
        String error = null;
        try {
            targets.check((String) d.get("url"));
            String secret = webhooks.secret(subscription, (byte[]) d.get("secret_enc"));
            long t = Instant.now(clock).getEpochSecond();
            HttpRequest req = HttpRequest.newBuilder(URI.create((String) d.get("url"))).timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "MIAA-CAS-Webhooks/1")
                    .header("X-CAS-Event", (String) d.get("event_type"))
                    .header("X-CAS-Delivery", id.toString())
                    .header("X-CAS-Attempt", Integer.toString(attempt))
                    .header("X-CAS-Signature", "t=" + t + ",v1=" + sign(secret, t + "." + body))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            HttpResponse<Void> res = http.send(req, HttpResponse.BodyHandlers.discarding());
            status = res.statusCode();
            if (status < 200 || status >= 300) {
                error = "HTTP " + status;
            }
        } catch (ApiException e) {
            error = e.getMessage();
        } catch (IOException e) {
            error = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = "Interrupted";
        }
        record(id, subscription, attempt, status, error);
    }

    private void record(UUID id, UUID subscription, int attempt, Integer status, String error) {
        Instant now = Instant.now(clock);
        String err = error == null ? null : error.length() > 500 ? error.substring(0, 500) : error;
        tx.executeWithoutResult(s -> {
            if (err == null) {
                jdbc.update("UPDATE webhook_deliveries SET status = 'DELIVERED', attempts = ?, last_status = ?, last_error = NULL, delivered_at = ?, "
                        + "next_attempt_at = NULL WHERE id = ?", attempt, status, Timestamp.from(now), id);
                jdbc.update("UPDATE webhook_subscriptions SET consecutive_failures = 0 WHERE id = ?", subscription);
                return;
            }
            boolean dead = attempt > BACKOFF.size();
            jdbc.update("UPDATE webhook_deliveries SET status = ?, attempts = ?, last_status = ?, last_error = ?, next_attempt_at = ? WHERE id = ?",
                    dead ? "DEAD" : "PENDING", attempt, status, err, dead ? null : Timestamp.from(now.plus(BACKOFF.get(attempt - 1))), id);
            if (dead) {
                Integer failures = jdbc.queryForObject("UPDATE webhook_subscriptions SET consecutive_failures = consecutive_failures + 1 "
                        + "WHERE id = ? RETURNING consecutive_failures", Integer.class, subscription);
                if (failures != null && failures >= DISABLE_AFTER && jdbc.update("UPDATE webhook_subscriptions SET status = 'DISABLED', "
                        + "updated_by = 'system', updated_at = ?, version = version + 1 WHERE id = ? AND status = 'ACTIVE'",
                        Timestamp.from(now), subscription) == 1) {
                    audit.record(Actor.SYSTEM, "WEBHOOK_DISABLED", "WEBHOOK", subscription, Map.of("consecutiveFailures", failures));
                    alerts.raise(new AlertService.Alert("webhook:disabled:" + subscription, "A webhook subscription was disabled",
                            failures + " deliveries in a row failed. Check the receiver, then resume it under Integrations → API access.",
                            "/integrations/api-access", ApiClientService.MANAGE, Duration.ofHours(1), true));
                }
            }
        });
    }

    String payload(UUID eventId, String type, Instant occurredAt, String resourceType, String resourceId) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", eventId.toString());
        p.put("type", type);
        p.put("occurredAt", occurredAt.toString());
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("type", resourceType);
        resource.put("id", resourceId);
        p.put("resource", resource);
        try {
            return json.writeValueAsString(p);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sign(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
