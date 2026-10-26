package com.miaa.cas.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaa.cas.common.web.ClientInfo;
import com.miaa.cas.common.web.CorrelationIdFilter;
import com.miaa.cas.security.CasPrincipal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Low-level, insert-only access to the hash-chained event tables. Hashes are computed by the
 * database trigger (V3 migration); this class never issues UPDATE or DELETE.
 */
@Component
public class EventStore {

    public enum Table {
        AUDIT("audit_events"), SECURITY("security_events");

        final String sql;

        Table(String sql) {
            this.sql = sql;
        }
    }

    private static final String ZERO_HASH = "0".repeat(64);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public EventStore(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    public void insert(Table table, Actor actor, String action, String resourceType, String resourceId,
            String outcome, String severity, Map<String, Object> details) {
        ClientInfo client = ClientInfo.current();
        String json;
        try {
            json = mapper.writeValueAsString(details == null ? Map.of() : details);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Audit details are not serialisable", e);
        }
        Timestamp now = Timestamp.from(Instant.now(clock));
        String roles = rolesOf(actor);
        if (table == Table.AUDIT) {
            jdbc.update("""
                    INSERT INTO audit_events (event_id, occurred_at, actor_user_id, actor_username, action, resource_type,
                        resource_id, outcome, correlation_id, client_ip, details, actor_roles, prev_hash, row_hash)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, '', '')""",
                    UUID.randomUUID(), now, actor.userId(), actor.username(), action, resourceType, resourceId, outcome,
                    MDC.get(CorrelationIdFilter.MDC_KEY), client.ip(), json, roles);
        } else {
            jdbc.update("""
                    INSERT INTO security_events (event_id, occurred_at, actor_user_id, actor_username, action, resource_type,
                        resource_id, outcome, correlation_id, client_ip, details, severity, actor_roles, prev_hash, row_hash)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, '', '')""",
                    UUID.randomUUID(), now, actor.userId(), actor.username(), action, resourceType, resourceId, outcome,
                    MDC.get(CorrelationIdFilter.MDC_KEY), client.ip(), json, severity, roles);
        }
    }

    /**
     * The roles behind the action: those the request actually carried when the actor is the signed-in user, otherwise
     * (a scheduled run on a user's behalf) the user's current assignments. None for the system or an anonymous attempt.
     */
    private String rolesOf(Actor actor) {
        if (actor.userId() == null) {
            return null;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CasPrincipal p && p.userId().equals(actor.userId())) {
            return truncate(auth.getAuthorities().stream().map(a -> a.getAuthority()).filter(a -> a.startsWith("ROLE_"))
                    .map(a -> a.substring(5)).sorted().collect(Collectors.joining(",")));
        }
        return truncate(jdbc.queryForObject("SELECT coalesce(string_agg(r.code, ',' ORDER BY r.code), '') FROM user_roles ur "
                + "JOIN roles r ON r.id = ur.role_id WHERE ur.user_id = ?", String.class, actor.userId()));
    }

    private static String truncate(String roles) {
        return roles.length() <= 500 ? roles : roles.substring(0, 499) + "…";
    }

    public List<EventRecord> search(Table table, EventQuery q, int page, int size) {
        List<Object> args = new ArrayList<>();
        String where = q.toWhereClause(args);
        args.add(size);
        args.add((long) page * size);
        String severity = table == Table.SECURITY ? "severity" : "NULL AS severity";
        return jdbc.query("SELECT id, event_id, occurred_at, actor_user_id, actor_username, actor_roles, action, resource_type, "
                + "resource_id, outcome, " + severity + ", correlation_id, client_ip, details::text AS details, row_hash FROM "
                + table.sql + where + " ORDER BY id DESC LIMIT ? OFFSET ?", this::mapRow, args.toArray());
    }

    public long count(Table table, EventQuery q) {
        List<Object> args = new ArrayList<>();
        String where = q.toWhereClause(args);
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + table.sql + where, Long.class, args.toArray());
        return n == null ? 0 : n;
    }

    /** Recomputes every row hash in id order and checks each row links to its predecessor. */
    public ChainVerification verify(Table table) {
        String severity = table == Table.SECURITY ? "severity" : "NULL";
        String sql = "SELECT id, prev_hash, row_hash, cas_event_hash(prev_hash, event_id, occurred_at, actor_user_id, "
                + "actor_username, action, resource_type, resource_id, outcome, correlation_id, client_ip, details, "
                + severity + ", actor_roles) AS recomputed FROM " + table.sql + " WHERE id > ? ORDER BY id LIMIT 1000";
        String expectedPrev = ZERO_HASH;
        long lastId = 0;
        long checked = 0;
        while (true) {
            List<Object[]> rows = jdbc.query(sql, (rs, i) -> new Object[] {rs.getLong(1), rs.getString(2), rs.getString(3),
                    rs.getString(4)}, lastId);
            if (rows.isEmpty()) {
                return new ChainVerification(table.name(), true, checked, null, Instant.now(clock));
            }
            for (Object[] r : rows) {
                long id = (Long) r[0];
                if (!expectedPrev.equals(r[1]) || !r[2].equals(r[3])) {
                    return new ChainVerification(table.name(), false, checked, id, Instant.now(clock));
                }
                expectedPrev = (String) r[2];
                lastId = id;
                checked++;
            }
        }
    }

    private EventRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        try {
            return new EventRecord(rs.getLong("id"), rs.getObject("event_id", UUID.class),
                    rs.getTimestamp("occurred_at").toInstant(), rs.getObject("actor_user_id", UUID.class),
                    rs.getString("actor_username"), rs.getString("actor_roles"), rs.getString("action"), rs.getString("resource_type"),
                    rs.getString("resource_id"), rs.getString("outcome"), rs.getString("severity"),
                    rs.getString("correlation_id"), rs.getString("client_ip"), mapper.readTree(rs.getString("details")),
                    rs.getString("row_hash"));
        } catch (JsonProcessingException e) {
            throw new SQLException("Invalid details JSON", e);
        }
    }

    public record ChainVerification(String table, boolean intact, long rowsChecked, Long firstInvalidSequence,
            Instant verifiedAt) {
    }
}
