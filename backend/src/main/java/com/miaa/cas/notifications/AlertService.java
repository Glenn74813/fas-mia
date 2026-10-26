package com.miaa.cas.notifications;

import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operational alerts (MS §10): high-severity security events and integrations that need a person. An alert reaches
 * every active user holding the given permission, in the app and (unless the e-mail channel is itself the problem)
 * by e-mail, and is counted in the {@code cas.alerts} metric so external monitoring can page on it. Repeats of one
 * alert key within its quiet period are counted, not sent, and reported with the next alert.
 */
@Service
public class AlertService {

    public record Alert(String key, String title, String body, String link, String permission, Duration quietPeriod, boolean email) {
    }

    public static final String EMAIL_TEMPLATE = "operational-alert";
    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private final JdbcTemplate jdbc;
    private final NotificationService notifications;
    private final OutboxService outbox;
    private final MeterRegistry meters;
    private final Clock clock;

    public AlertService(JdbcTemplate jdbc, NotificationService notifications, OutboxService outbox, MeterRegistry meters, Clock clock) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.outbox = outbox;
        this.meters = meters;
        this.clock = clock;
    }

    /** @return true when the alert was sent, false when it fell inside the quiet period of an earlier one */
    @Transactional
    public boolean raise(Alert a) {
        meters.counter("cas.alerts", "kind", a.key().split(":")[0]).increment();
        Instant now = Instant.now(clock);
        jdbc.update("INSERT INTO alert_throttle (alert_key, last_sent_at) VALUES (?, '-infinity') ON CONFLICT (alert_key) DO NOTHING", a.key());
        Map<String, Object> t = jdbc.queryForMap("SELECT last_sent_at, suppressed FROM alert_throttle WHERE alert_key = ? FOR UPDATE", a.key());
        Timestamp last = (Timestamp) t.get("last_sent_at");
        if (last != null && last.toInstant().isAfter(now.minus(a.quietPeriod()))) {
            jdbc.update("UPDATE alert_throttle SET suppressed = suppressed + 1 WHERE alert_key = ?", a.key());
            return false;
        }
        int suppressed = ((Number) t.get("suppressed")).intValue();
        jdbc.update("UPDATE alert_throttle SET last_sent_at = ?, suppressed = 0 WHERE alert_key = ?", Timestamp.from(now), a.key());
        String body = suppressed == 0 ? a.body() : a.body() + " (" + suppressed + " more like this since the previous alert.)";
        log.warn("ALERT {}: {}", a.key(), a.title());
        List<Map<String, Object>> recipients = jdbc.queryForList("""
                SELECT DISTINCT u.id, u.email FROM users u JOIN user_roles ur ON ur.user_id = u.id
                JOIN role_permissions rp ON rp.role_id = ur.role_id
                WHERE rp.permission_code = ? AND u.status = 'ACTIVE' AND (u.access_expires_at IS NULL OR u.access_expires_at > ?)""",
                a.permission(), Timestamp.from(now));
        for (Map<String, Object> r : recipients) {
            notifications.notify((UUID) r.get("id"), "ALERT", a.title(), body, a.link());
            if (a.email() && r.get("email") != null) {
                outbox.enqueueEmail((String) r.get("email"), EMAIL_TEMPLATE, Map.of("title", a.title(), "body", body, "link", a.link()));
            }
        }
        return true;
    }
}
