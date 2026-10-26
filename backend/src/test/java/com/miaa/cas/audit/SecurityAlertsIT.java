package com.miaa.cas.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.miaa.cas.audit.SecurityEventService.Severity;
import com.miaa.cas.support.AbstractIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** High-severity security events alert the security reviewers; every event records the actor's roles in its hash. */
class SecurityAlertsIT extends AbstractIntegrationTest {

    @Autowired SecurityEventService securityEvents;
    @Autowired EventStore events;

    @Test
    void aHighSeverityEventAlertsReviewersOncePerQuietPeriod() {
        TestUser reviewer = createUser("secrev", "INTERNAL_AUDITOR");
        TestUser bystander = createUser("secby", "ACCOUNTANT");
        String action = "TEST_HIGH_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String title = "High-severity security event: " + action.replace('_', ' ').toLowerCase();
        String count = "SELECT count(*) FROM notifications WHERE category = 'ALERT' AND title = ? AND user_id = ?";

        securityEvents.record(Actor.anonymous("someone"), action, "DENIED", Severity.MEDIUM, Map.of());
        assertThat(jdbc.queryForObject(count, Integer.class, title, reviewer.id())).isZero();

        securityEvents.record(Actor.anonymous("someone"), action, "DENIED", Severity.HIGH, Map.of("ip", "10.0.0.9"));
        securityEvents.record(Actor.anonymous("someone"), action, "DENIED", Severity.HIGH, Map.of("ip", "10.0.0.9"));
        assertThat(jdbc.queryForObject(count, Integer.class, title, reviewer.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject(count, Integer.class, title, bystander.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT suppressed FROM alert_throttle WHERE alert_key = ?", Integer.class, "security:" + action))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE template = 'operational-alert' AND recipient = ?",
                Integer.class, reviewer.username() + "@test.miaa.local")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void eventsRecordTheActorsRolesAndTheChainStillVerifies() {
        TestUser user = createUser("roled", "ACCOUNTANT", "CASHIER");
        securityEvents.record(new Actor(user.id(), user.username()), "TEST_ROLES", "SUCCESS", Severity.INFO, Map.of());
        assertThat(jdbc.queryForObject("SELECT actor_roles FROM security_events WHERE action = 'TEST_ROLES' AND actor_user_id = ?",
                String.class, user.id())).isEqualTo("ACCOUNTANT,CASHIER");
        assertThat(events.verify(EventStore.Table.SECURITY).intact()).isTrue();
        assertThat(events.verify(EventStore.Table.AUDIT).intact()).isTrue();
    }
}
