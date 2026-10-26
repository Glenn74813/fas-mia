package com.miaa.cas.audit;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Validated filter set for event searches; always rendered as bind parameters, never concatenated. */
public record EventQuery(Instant from, Instant to, UUID actorUserId, String action, String resourceType,
        String resourceId) {

    String toWhereClause(List<Object> args) {
        List<String> clauses = new ArrayList<>();
        if (from != null) {
            clauses.add("occurred_at >= ?");
            args.add(Timestamp.from(from));
        }
        if (to != null) {
            clauses.add("occurred_at < ?");
            args.add(Timestamp.from(to));
        }
        if (actorUserId != null) {
            clauses.add("actor_user_id = ?");
            args.add(actorUserId);
        }
        if (action != null && !action.isBlank()) {
            clauses.add("action = ?");
            args.add(action);
        }
        if (resourceType != null && !resourceType.isBlank()) {
            clauses.add("resource_type = ?");
            args.add(resourceType);
        }
        if (resourceId != null && !resourceId.isBlank()) {
            clauses.add("resource_id = ?");
            args.add(resourceId);
        }
        return clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
    }
}
