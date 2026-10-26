package com.miaa.cas.integrations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaa.cas.audit.Actor;
import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.web.JdbcPaging;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.notifications.AlertService;
import com.miaa.cas.security.CurrentActor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The transmission log: an outbox for external systems. Each (channel, idempotency key) is one row,
 * so re-queuing never duplicates. A worker claims due rows with {@code FOR UPDATE SKIP LOCKED}, calls the
 * channel's adapter outside any transaction, records every attempt, and retries retryable failures with
 * exponential backoff until the attempt limit, after which the row is DEAD and needs a person to act.
 */
@Service
public class TransmissionService {

    private static final Logger log = LoggerFactory.getLogger(TransmissionService.class);

    public record AttemptView(int attemptNo, Instant startedAt, Instant finishedAt, String outcome, String endpoint, Integer httpStatus,
            String responseExcerpt) {
    }

    public record TransmissionView(UUID id, String channel, String documentType, UUID documentId, String idempotencyKey,
            Object payload, String payloadSha256, String status, int attempts, Instant nextAttemptAt, String lastError,
            String externalReference, boolean simulated, String requestedBy, Instant createdAt, Instant updatedAt,
            List<AttemptView> attemptLog) {
    }

    public record ChannelView(String channel, String mode, boolean simulated, String availability, Map<String, Long> counts) {
    }

    private final Map<String, IntegrationAdapter> adapters;
    private final AuditService audit;
    private final AlertService alerts;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;
    private final int maxAttempts;
    private final Duration retryBase;
    private final Duration retryMax;
    private final Duration stuckAfter;

    public TransmissionService(List<IntegrationAdapter> adapters, AuditService audit, AlertService alerts, JdbcTemplate jdbc, TransactionTemplate tx,
            ObjectMapper json, Clock clock, @Value("${cas.integrations.max-attempts:8}") int maxAttempts,
            @Value("${cas.integrations.retry-base:PT1M}") Duration retryBase, @Value("${cas.integrations.retry-max:PT1H}") Duration retryMax,
            @Value("${cas.integrations.stuck-after:PT10M}") Duration stuckAfter) {
        this.adapters = adapters.stream().collect(Collectors.toMap(IntegrationAdapter::channel, Function.identity()));
        this.audit = audit;
        this.alerts = alerts;
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
        this.maxAttempts = maxAttempts;
        this.retryBase = retryBase;
        this.retryMax = retryMax;
        this.stuckAfter = stuckAfter;
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public List<ChannelView> channels() {
        return adapters.values().stream().sorted((a, b) -> a.channel().compareTo(b.channel())).map(a -> {
            String availability;
            try {
                a.checkEnabled();
                availability = "ENABLED";
            } catch (ApiException e) {
                availability = e.getMessage();
            }
            Map<String, Long> counts = new java.util.TreeMap<>();
            jdbc.query("SELECT status, count(*) FROM integration_transmissions WHERE channel = ? GROUP BY status",
                    rs -> { counts.put(rs.getString(1), rs.getLong(2)); }, a.channel());
            return new ChannelView(a.channel(), a.mode(), a.simulated(), availability, counts);
        }).toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<TransmissionView> list(String channel, String status, int page, int size) {
        String c = blank(channel);
        String s = blank(status);
        return JdbcPaging.ids(jdbc, "SELECT id FROM integration_transmissions WHERE (?::text IS NULL OR channel = ?::text) "
                + "AND (?::text IS NULL OR status = ?::text) ORDER BY created_at DESC, id", page, size, c, c, s, s).map(id -> view(id, false));
    }

    @Transactional(readOnly = true)
    public TransmissionView get(UUID id) {
        return view(id, true);
    }

    // ------------------------------------------------------------------ commands

    /** Queues a payload for a channel; the same key returns the existing transmission instead of a second one. */
    @Transactional
    public TransmissionView enqueue(String channel, String documentType, UUID documentId, String idempotencyKey, Map<String, Object> payload) {
        IntegrationAdapter adapter = adapter(channel);
        adapter.checkEnabled();
        List<UUID> existing = jdbc.queryForList("SELECT id FROM integration_transmissions WHERE channel = ? AND idempotency_key = ?",
                UUID.class, channel, idempotencyKey);
        if (!existing.isEmpty()) {
            return view(existing.get(0), true);
        }
        String body = toJson(payload);
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now(clock));
        int n = jdbc.update("""
                INSERT INTO integration_transmissions (id, channel, document_type, document_id, idempotency_key, payload, payload_sha256,
                    status, next_attempt_at, simulated, requested_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, 'PENDING', ?, ?, ?, ?, ?) ON CONFLICT (channel, idempotency_key) DO NOTHING""", id, channel,
                documentType, documentId, idempotencyKey, body, sha256(body), now, adapter.simulated(), CurrentActor.username(), now, now);
        if (n == 0) {
            // Lost a race with a concurrent enqueue of the same key.
            return view(jdbc.queryForObject("SELECT id FROM integration_transmissions WHERE channel = ? AND idempotency_key = ?",
                    UUID.class, channel, idempotencyKey), true);
        }
        audit.record("TRANSMISSION_QUEUED", "INTEGRATION_TRANSMISSION", id, Map.of("channel", channel, "document", documentType + " "
                + documentId, "simulated", adapter.simulated()));
        return view(id, true);
    }

    @Transactional
    public TransmissionView retry(UUID id) {
        CurrentActor.requirePermission("INTEGRATION_MANAGE");
        Map<String, Object> t = lock(id);
        if (!List.of("DEAD", "REJECTED").contains(t.get("status"))) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Only dead or rejected transmissions are retried by hand; pending ones retry automatically.");
        }
        adapter((String) t.get("channel")).checkEnabled();
        jdbc.update("UPDATE integration_transmissions SET status = 'PENDING', next_attempt_at = ?, updated_at = ? WHERE id = ?",
                Timestamp.from(Instant.now(clock)), Timestamp.from(Instant.now(clock)), id);
        audit.record("TRANSMISSION_RETRY_REQUESTED", "INTEGRATION_TRANSMISSION", id, Map.of("previousStatus", t.get("status")));
        return view(id, true);
    }

    @Transactional
    public TransmissionView cancel(UUID id, String reason) {
        CurrentActor.requirePermission("INTEGRATION_MANAGE");
        Map<String, Object> t = lock(id);
        if (!List.of("PENDING", "DEAD", "REJECTED").contains(t.get("status"))) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Only pending, dead or rejected transmissions can be cancelled.");
        }
        jdbc.update("UPDATE integration_transmissions SET status = 'CANCELLED', last_error = ?, updated_at = ? WHERE id = ?",
                "Cancelled: " + reason.trim(), Timestamp.from(Instant.now(clock)), id);
        audit.record("TRANSMISSION_CANCELLED", "INTEGRATION_TRANSMISSION", id, Map.of("reason", reason.trim()));
        return view(id, true);
    }

    // ------------------------------------------------------------------ worker

    @Scheduled(fixedDelayString = "${cas.integrations.poll-interval:PT15S}")
    public void poll() {
        try {
            recoverStuck();
            processDue();
        } catch (RuntimeException e) {
            log.error("Integration worker failed", e);
        }
    }

    /** Delivers every due transmission once; returns how many were attempted. */
    public int processDue() {
        int n = 0;
        while (attemptOne()) {
            n++;
        }
        return n;
    }

    /** A worker that died mid-send leaves SENDING rows; they are re-queued (adapters receive the same idempotency key). */
    void recoverStuck() {
        tx.executeWithoutResult(s -> jdbc.update("UPDATE integration_transmissions SET status = 'PENDING', next_attempt_at = ?, updated_at = ? "
                + "WHERE status = 'SENDING' AND updated_at < ?", Timestamp.from(Instant.now(clock)), Timestamp.from(Instant.now(clock)),
                Timestamp.from(Instant.now(clock).minus(stuckAfter))));
    }

    private boolean attemptOne() {
        Instant now = Instant.now(clock);
        Map<String, Object> t = tx.execute(s -> {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    UPDATE integration_transmissions SET status = 'SENDING', attempts = attempts + 1, updated_at = ?
                    WHERE id = (SELECT id FROM integration_transmissions WHERE status = 'PENDING' AND next_attempt_at <= ?
                                ORDER BY next_attempt_at FOR UPDATE SKIP LOCKED LIMIT 1)
                    RETURNING id, channel, document_type, document_id, idempotency_key, payload::text AS payload, attempts""",
                    Timestamp.from(now), Timestamp.from(now));
            return rows.isEmpty() ? null : rows.get(0);
        });
        if (t == null) {
            return false;
        }
        UUID id = (UUID) t.get("id");
        int attempt = ((Number) t.get("attempts")).intValue();
        IntegrationAdapter adapter = adapters.get((String) t.get("channel"));
        IntegrationAdapter.Result r;
        Instant started = Instant.now(clock);
        try {
            if (adapter == null) {
                throw new IllegalStateException("No adapter for channel " + t.get("channel"));
            }
            adapter.checkEnabled();
            r = adapter.send(new IntegrationAdapter.Transmission(id, (String) t.get("channel"), (String) t.get("document_type"),
                    (UUID) t.get("document_id"), (String) t.get("idempotency_key"), (String) t.get("payload"), attempt));
        } catch (ApiException e) {
            // The channel was disabled after queuing: keep the row, retry later.
            r = new IntegrationAdapter.Result(IntegrationAdapter.Outcome.RETRYABLE, null, null, e.getMessage(), null);
        } catch (RuntimeException e) {
            r = new IntegrationAdapter.Result(IntegrationAdapter.Outcome.RETRYABLE, null, null, e.getClass().getSimpleName() + ": "
                    + e.getMessage(), null);
        }
        IntegrationAdapter.Result result = r;
        Instant finished = Instant.now(clock);
        tx.executeWithoutResult(s -> record(id, attempt, started, finished, result));
        return true;
    }

    private void record(UUID id, int attempt, Instant started, Instant finished, IntegrationAdapter.Result r) {
        jdbc.update("INSERT INTO integration_attempts (transmission_id, attempt_no, started_at, finished_at, outcome, endpoint, http_status, "
                + "response_excerpt) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", id, attempt, Timestamp.from(started), Timestamp.from(finished),
                r.outcome().name(), r.endpoint(), r.httpStatus(), cut(r.responseExcerpt(), 2000));
        Timestamp now = Timestamp.from(finished);
        switch (r.outcome()) {
            case SENT, ACKNOWLEDGED -> {
                jdbc.update("UPDATE integration_transmissions SET status = ?, external_reference = ?, last_error = NULL, updated_at = ? "
                        + "WHERE id = ?", r.outcome().name(), cut(r.externalReference(), 200), now, id);
                audit.record(Actor.SYSTEM, "TRANSMISSION_DELIVERED", "INTEGRATION_TRANSMISSION", id, Map.of("attempt", attempt,
                        "outcome", r.outcome().name(), "reference", String.valueOf(r.externalReference())));
            }
            case REJECTED -> {
                jdbc.update("UPDATE integration_transmissions SET status = 'REJECTED', last_error = ?, updated_at = ? WHERE id = ?",
                        cut(r.responseExcerpt(), 1000), now, id);
                audit.record(Actor.SYSTEM, "TRANSMISSION_REJECTED", "INTEGRATION_TRANSMISSION", id, Map.of("attempt", attempt));
                alertStuck(id, "was rejected by the receiving system");
            }
            default -> {
                boolean dead = attempt >= maxAttempts;
                Duration wait = backoff(attempt);
                jdbc.update("UPDATE integration_transmissions SET status = ?, next_attempt_at = ?, last_error = ?, updated_at = ? WHERE id = ?",
                        dead ? "DEAD" : "PENDING", Timestamp.from(finished.plus(wait)), cut(r.responseExcerpt(), 1000), now, id);
                if (dead) {
                    audit.record(Actor.SYSTEM, "TRANSMISSION_DEAD", "INTEGRATION_TRANSMISSION", id, Map.of("attempts", attempt));
                    alertStuck(id, "failed " + attempt + " times and will not be retried automatically");
                }
            }
        }
    }

    /** A transmission that needs a person: alert the integration managers (one alert per channel per hour). */
    private void alertStuck(UUID id, String what) {
        Map<String, Object> t = jdbc.queryForMap("SELECT channel, document_type FROM integration_transmissions WHERE id = ?", id);
        alerts.raise(new AlertService.Alert("integration:" + t.get("channel"), "Integration " + t.get("channel") + " needs attention",
                "A " + String.valueOf(t.get("document_type")).replace('_', ' ').toLowerCase() + " transmission " + what
                        + ". Retry or cancel it on the Integrations page.", "/integrations", "INTEGRATION_MANAGE", Duration.ofHours(1), true));
    }

    /** Exponential backoff: base, 2x base, 4x base, ... capped. */
    Duration backoff(int attempt) {
        long factor = 1L << Math.min(attempt - 1, 20);
        Duration d = retryBase.multipliedBy(factor);
        return d.compareTo(retryMax) > 0 ? retryMax : d;
    }

    // ------------------------------------------------------------------ helpers

    private IntegrationAdapter adapter(String channel) {
        IntegrationAdapter a = adapters.get(channel);
        if (a == null) {
            throw ApiException.notFound("Integration channel " + channel);
        }
        return a;
    }

    private Map<String, Object> lock(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM integration_transmissions WHERE id = ? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Transmission");
        }
        return rows.get(0);
    }

    private TransmissionView view(UUID id, boolean withAttempts) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT t.*, t.payload::text AS payload_text FROM integration_transmissions t "
                + "WHERE t.id = ?", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Transmission");
        }
        Map<String, Object> t = rows.get(0);
        List<AttemptView> attempts = !withAttempts ? List.of() : jdbc.query("SELECT * FROM integration_attempts WHERE transmission_id = ? "
                + "ORDER BY attempt_no", (rs, i) -> new AttemptView(rs.getInt("attempt_no"), rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("finished_at").toInstant(), rs.getString("outcome"), rs.getString("endpoint"),
                (Integer) rs.getObject("http_status"), rs.getString("response_excerpt")), id);
        Object payload;
        try {
            payload = withAttempts ? json.readTree((String) t.get("payload_text")) : null;
        } catch (JsonProcessingException e) {
            payload = null;
        }
        return new TransmissionView(id, (String) t.get("channel"), (String) t.get("document_type"), (UUID) t.get("document_id"),
                (String) t.get("idempotency_key"), payload, (String) t.get("payload_sha256"), (String) t.get("status"),
                ((Number) t.get("attempts")).intValue(), ((Timestamp) t.get("next_attempt_at")).toInstant(), (String) t.get("last_error"),
                (String) t.get("external_reference"), (Boolean) t.get("simulated"), (String) t.get("requested_by"),
                ((Timestamp) t.get("created_at")).toInstant(), ((Timestamp) t.get("updated_at")).toInstant(), attempts);
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim().toUpperCase();
    }

    Optional<IntegrationAdapter> find(String channel) {
        return Optional.ofNullable(adapters.get(channel));
    }
}
