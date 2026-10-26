package com.miaa.cas.notifications;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Transactional outbox: notifications are written with the business change and dispatched later. */
@Service
public class OutboxService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public OutboxService(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID enqueueEmail(String recipient, String template, Map<String, Object> payload) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now(clock));
        try {
            jdbc.update("""
                    INSERT INTO notification_outbox (id, channel, recipient, template, payload, status, next_attempt_at, created_at)
                    VALUES (?, 'EMAIL', ?, ?, ?::jsonb, 'PENDING', ?, ?)""",
                    id, recipient, template, mapper.writeValueAsString(payload), now, now);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Notification payload not serialisable", e);
        }
        return id;
    }
}
