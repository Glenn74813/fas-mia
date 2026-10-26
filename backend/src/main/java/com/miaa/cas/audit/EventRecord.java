package com.miaa.cas.audit;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/** Read model of an audit or security event row. */
public record EventRecord(
        long sequence,
        UUID eventId,
        Instant occurredAt,
        UUID actorUserId,
        String actorUsername,
        String actorRoles,
        String action,
        String resourceType,
        String resourceId,
        String outcome,
        String severity,
        String correlationId,
        String clientIp,
        JsonNode details,
        String rowHash) {
}
