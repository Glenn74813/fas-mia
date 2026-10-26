package com.miaa.cas.common.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaa.cas.common.crypto.SecureTokens;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.security.CurrentActor;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores the outcome of retry-sensitive operations by (user, operation, Idempotency-Key). A retry with
 * the same key and request returns the stored response; the same key with a different request is
 * rejected. Callers must serialise on the affected resource (e.g. a row lock) before calling
 * {@link #find}, so the record is written in the same transaction as the effect.
 */
@Service
public class IdempotencyService {

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]{8,100}");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public IdempotencyService(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    public static String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED,
                    "This operation requires an Idempotency-Key header (8-100 characters: letters, digits, '-', '_').");
        }
        if (!KEY.matcher(key).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Idempotency-Key must be 8-100 characters: letters, digits, '-', '_'.");
        }
        return key;
    }

    public static String hash(String operation, Object... parts) {
        StringBuilder sb = new StringBuilder(operation);
        for (Object p : parts) {
            sb.append('|').append(p);
        }
        return SecureTokens.sha256Hex(sb.toString());
    }

    /**
     * Runs {@code action} once per (caller, operation, key): the key is locked for the transaction, a retry of the same
     * request gets the stored response without running the action again, and a different request with the same key is
     * refused. The action joins this transaction, so its effect and the stored response commit or roll back together.
     */
    @Transactional
    public Object once(String operation, String idempotencyKey, String requestHash, int status, Supplier<Object> action) {
        String key = requireKey(idempotencyKey);
        UUID user = CurrentActor.userId();
        lock(user, operation, key);
        Optional<JsonNode> replay = find(user, operation, key, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        Object response = action.get();
        record(user, operation, key, requestHash, status, response);
        return response;
    }

    /** Serialises concurrent requests that carry the same key until the transaction ends, so only one of them acts. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(UUID userId, String operation, String key) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", userId + "|" + operation + "|" + key);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<JsonNode> find(UUID userId, String operation, String key, String requestHash) {
        List<Object[]> rows = jdbc.query("SELECT request_hash, response_body::text FROM idempotency_keys "
                + "WHERE user_id = ? AND operation = ? AND idempotency_key = ?",
                (rs, i) -> new Object[] {rs.getString(1), rs.getString(2)}, userId, operation, key);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        String stored = (String) rows.get(0)[0];
        if (stored == null || !java.security.MessageDigest.isEqual(requestHash.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                stored.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "This Idempotency-Key was already used for a different request.");
        }
        try {
            return Optional.of(mapper.readTree((String) rows.get(0)[1]));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored idempotent response is not valid JSON", e);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID userId, String operation, String key, String requestHash, int status, Object response) {
        try {
            jdbc.update("INSERT INTO idempotency_keys (user_id, operation, idempotency_key, request_hash, response_status, "
                    + "response_body, created_at) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)", userId, operation, key, requestHash,
                    status, mapper.writeValueAsString(response), Timestamp.from(Instant.now(clock)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Response not serialisable", e);
        }
    }
}
