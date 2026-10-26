package com.miaa.cas.notifications;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaa.cas.config.CasProperties;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delivers pending outbox rows. Safe with several instances ({@code FOR UPDATE SKIP LOCKED}).
 * After delivery the payload is cleared because it may contain single-use links.
 */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Optional<EmailSender> emailSender;
    private final CasProperties.Outbox cfg;
    private final AlertService alerts;

    public OutboxDispatcher(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock, Optional<EmailSender> emailSender,
            CasProperties properties, AlertService alerts) {
        this.alerts = alerts;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
        this.emailSender = emailSender;
        this.cfg = properties.outbox();
    }

    @Scheduled(fixedDelayString = "${cas.outbox.poll-interval:PT30S}")
    @Transactional
    public void dispatch() {
        if (!cfg.dispatchEnabled()) {
            return;
        }
        if (emailSender.isEmpty()) {
            Long pending = jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE status = 'PENDING'", Long.class);
            if (pending != null && pending > 0) {
                log.warn("{} notification(s) pending but no approved e-mail provider is configured", pending);
            }
            return;
        }
        Instant now = Instant.now(clock);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, recipient, template, payload::text AS payload, attempts FROM notification_outbox
                WHERE status = 'PENDING' AND channel = 'EMAIL' AND next_attempt_at <= ?
                ORDER BY next_attempt_at LIMIT 20 FOR UPDATE SKIP LOCKED""", Timestamp.from(now));
        for (Map<String, Object> row : rows) {
            UUID id = (UUID) row.get("id");
            int attempts = ((Number) row.get("attempts")).intValue() + 1;
            try {
                Map<String, Object> model = mapper.readValue((String) row.get("payload"), new TypeReference<>() { });
                emailSender.get().send((String) row.get("recipient"), (String) row.get("template"), model);
                jdbc.update("UPDATE notification_outbox SET status = 'SENT', attempts = ?, sent_at = ?, payload = '{}'::jsonb "
                        + "WHERE id = ?", attempts, Timestamp.from(now), id);
            } catch (Exception e) {
                String status = attempts >= cfg.maxAttempts() ? "FAILED" : "PENDING";
                Instant next = now.plus(Duration.ofMinutes((long) Math.pow(2, Math.min(attempts, 8))));
                log.warn("Notification {} delivery attempt {} failed: {}", id, attempts, e.getClass().getSimpleName());
                jdbc.update("UPDATE notification_outbox SET status = ?, attempts = ?, next_attempt_at = ?, last_error = ? "
                        + "WHERE id = ?", status, attempts, Timestamp.from(next), e.getClass().getSimpleName(), id);
                if ("FAILED".equals(status)) {
                    // In the app only: the e-mail channel is what failed.
                    alerts.raise(new AlertService.Alert("integration:EMAIL", "E-mail delivery is failing",
                            "A " + row.get("template") + " e-mail could not be delivered after " + attempts + " attempts ("
                                    + e.getClass().getSimpleName() + "). Check the mail server settings.", "/integrations",
                            "INTEGRATION_MANAGE", Duration.ofHours(1), false));
                }
            }
        }
    }
}
