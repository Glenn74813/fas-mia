package com.miaa.cas.tickets;

import com.miaa.cas.audit.Actor;
import com.miaa.cas.audit.AuditService;
import com.miaa.cas.notifications.OutboxService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Watches open tickets against their SLA. When the remaining resolution time falls below the warning
 * share, or a response or resolution deadline passes, it records the event in the ticket history,
 * raises the escalation level and e-mails the assignee and the configured escalation contacts through
 * the notification outbox. Each warning or breach is raised once. Paused tickets are skipped.
 */
@Component
public class TicketSlaMonitor {

    private static final Logger log = LoggerFactory.getLogger(TicketSlaMonitor.class);

    private final SlaPolicyService sla;
    private final TicketService tickets;
    private final OutboxService outbox;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final int warnPercent;
    private final List<String> escalationEmails;

    public TicketSlaMonitor(SlaPolicyService sla, TicketService tickets, OutboxService outbox, AuditService audit, JdbcTemplate jdbc, Clock clock,
            @Value("${cas.tickets.escalation.warn-percent:75}") int warnPercent,
            @Value("${cas.tickets.escalation.emails:}") String escalationEmails) {
        this.sla = sla;
        this.tickets = tickets;
        this.outbox = outbox;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
        this.warnPercent = warnPercent;
        this.escalationEmails = Arrays.stream(escalationEmails.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @Scheduled(fixedDelayString = "${cas.tickets.monitor-interval:PT1M}")
    public void poll() {
        try {
            check();
        } catch (RuntimeException e) {
            log.error("SLA monitor failed", e);
        }
        try {
            tickets.autoClose();
        } catch (RuntimeException e) {
            log.error("Ticket auto-close failed", e);
        }
    }

    /** One pass over open tickets; returns the number of escalations raised. */
    @Transactional
    public int check() {
        Instant now = Instant.now(clock);
        BusinessClock bc = sla.clock();
        int raised = 0;
        for (Map<String, Object> t : jdbc.queryForList("""
                SELECT t.*, p.business_hours, p.resolution_minutes, u.email AS assignee_email FROM tickets t
                JOIN sla_policies p ON p.id = t.sla_policy_id LEFT JOIN users u ON u.id = t.assignee_id
                WHERE t.status NOT IN ('RESOLVED', 'CLOSED', 'REJECTED', 'CANCELLED') AND t.on_hold_since IS NULL
                ORDER BY t.created_at FOR UPDATE OF t SKIP LOCKED""")) {
            UUID id = (UUID) t.get("id");
            boolean business = (Boolean) t.get("business_hours");
            Instant responseDue = ((Timestamp) t.get("response_due_at")).toInstant();
            Instant resolutionDue = ((Timestamp) t.get("resolution_due_at")).toInstant();
            if (t.get("first_response_at") == null && !(Boolean) t.get("response_breached") && now.isAfter(responseDue)) {
                escalate(t, "response_breached = TRUE", 1, "RESPONSE_BREACH", "No response by " + responseDue);
                raised++;
            }
            if (!(Boolean) t.get("resolution_breached") && now.isAfter(resolutionDue)) {
                escalate(t, "resolution_breached = TRUE", 2, "RESOLUTION_BREACH", "Not resolved by " + resolutionDue);
                raised++;
            } else if (!(Boolean) t.get("at_risk_notified") && !(Boolean) t.get("resolution_breached")) {
                long left = bc.minutesBetween(now, resolutionDue, business);
                long budget = ((Number) t.get("resolution_minutes")).longValue();
                if (left * 100 <= budget * (100L - warnPercent)) {
                    escalate(t, "at_risk_notified = TRUE", 1, "AT_RISK", left + " SLA minutes left of " + budget);
                    raised++;
                }
            }
        }
        return raised;
    }

    private void escalate(Map<String, Object> t, String flag, int level, String kind, String detail) {
        UUID id = (UUID) t.get("id");
        jdbc.update("UPDATE tickets SET " + flag + ", escalation_level = GREATEST(escalation_level, ?), updated_at = ? WHERE id = ?", level,
                Timestamp.from(Instant.now(clock)), id);
        tickets.event(id, "ESCALATED", null, kind, detail, true);
        List<String> recipients = new ArrayList<>(escalationEmails);
        if (t.get("assignee_email") != null) {
            recipients.add(0, (String) t.get("assignee_email"));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ticketNumber", t.get("ticket_number"));
        payload.put("title", t.get("title"));
        payload.put("priority", t.get("priority"));
        payload.put("kind", kind);
        payload.put("detail", detail);
        for (String r : recipients.stream().distinct().toList()) {
            outbox.enqueueEmail(r, "ticket-sla-escalation", payload);
        }
        audit.record(Actor.SYSTEM, "TICKET_SLA_" + kind, "TICKET", t.get("ticket_number"), Map.of("level", level, "notified", recipients.size()));
    }
}
