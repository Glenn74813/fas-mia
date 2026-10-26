package com.miaa.cas.audit;

import com.miaa.cas.notifications.AlertService;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records authentication and authorisation events in their own transaction so they survive a
 * rollback of the request that triggered them (e.g. a failed login).
 */
@Service
public class SecurityEventService {

    public enum Severity { INFO, LOW, MEDIUM, HIGH }

    /** One alert per kind of high-severity event per quiet period; repeats are counted into the next alert. */
    static final Duration ALERT_QUIET_PERIOD = Duration.ofMinutes(15);

    private final EventStore store;
    private final AlertService alerts;

    public SecurityEventService(EventStore store, AlertService alerts) {
        this.store = store;
        this.alerts = alerts;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Actor actor, String action, String outcome, Severity severity, Map<String, Object> details) {
        store.insert(EventStore.Table.SECURITY, actor, action, "AUTHENTICATION",
                actor.userId() == null ? null : actor.userId().toString(), outcome, severity.name(), details);
        if (severity == Severity.HIGH) {
            alerts.raise(new AlertService.Alert("security:" + action, "High-severity security event: " + action.replace('_', ' ').toLowerCase(),
                    "Account " + (actor.username() == null ? "(unknown)" : actor.username()) + ", outcome " + outcome.toLowerCase()
                            + ". Review it in the security event log.", "/audit", "SECURITY_EVENT_VIEW", ALERT_QUIET_PERIOD, true));
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void accessDenied(String requestDescription) {
        store.insert(EventStore.Table.SECURITY, Actor.current(), "ACCESS_DENIED", "ENDPOINT", null, "DENIED",
                Severity.MEDIUM.name(), Map.of("request", requestDescription));
    }
}
