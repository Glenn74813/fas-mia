package com.miaa.cas.integrations.api;

import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.crypto.SecureTokens;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.security.CurrentActor;
import com.miaa.cas.security.client.ApiClientAuthenticator;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * API clients for third-party access (TOR §4.6): created with read scopes, their secret shown once and stored as an
 * Argon2id hash; rotated (the old secret stops working at once) or revoked (its tokens stop working at once).
 */
@Service
public class ApiClientService {

    public static final String MANAGE = "API_CLIENT_MANAGE";

    public record ClientView(UUID id, String clientId, String name, String description, List<String> scopes, String status,
            Instant expiresAt, Instant lastUsedAt, Instant secretRotatedAt, String createdBy, Instant createdAt, String revokedBy,
            Instant revokedAt, String revokeReason, long version) {
    }

    /** The client and its secret, which is shown only in this response. */
    public record ClientWithSecret(ClientView client, String clientSecret) {
    }

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final Clock clock;

    public ApiClientService(JdbcTemplate jdbc, PasswordEncoder encoder, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ClientView> list() {
        return jdbc.query("SELECT * FROM api_clients ORDER BY status, name", (rs, i) -> map(rs));
    }

    @Transactional
    public ClientWithSecret create(String name, String description, List<String> scopes, Instant expiresAt) {
        CurrentActor.requirePermission(MANAGE);
        String n = name == null ? "" : name.trim();
        if (n.length() < 3) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Name the client (at least 3 characters).");
        }
        List<String> s = scopes == null ? List.of() : scopes.stream().distinct().sorted().toList();
        if (s.isEmpty() || !ApiClientAuthenticator.SCOPES.containsAll(s)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Choose one or more scopes from: "
                    + ApiClientAuthenticator.SCOPES.stream().sorted().toList() + ".");
        }
        Instant now = Instant.now(clock);
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The expiry must be in the future.");
        }
        UUID id = UUID.randomUUID();
        String clientId = "cas_" + SecureTokens.newToken().replaceAll("[^A-Za-z0-9]", "").substring(0, 20).toLowerCase();
        String secret = SecureTokens.newToken();
        jdbc.update("""
                INSERT INTO api_clients (id, client_id, name, description, scopes, secret_hash, secret_rotated_at, status, expires_at,
                    created_by_id, created_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?)""",
                id, clientId, n, blank(description), s.toArray(String[]::new), encoder.encode(secret), Timestamp.from(now),
                expiresAt == null ? null : Timestamp.from(expiresAt), CurrentActor.userId(), CurrentActor.username(), Timestamp.from(now));
        audit.record("API_CLIENT_CREATED", "API_CLIENT", id, Map.of("clientId", clientId, "name", n, "scopes", String.join(" ", s)));
        return new ClientWithSecret(get(id), secret);
    }

    /** A new secret; the old one stops working immediately. */
    @Transactional
    public ClientWithSecret rotate(UUID id, long version) {
        CurrentActor.requirePermission(MANAGE);
        Map<String, Object> c = lock(id, version);
        if (!"ACTIVE".equals(c.get("status"))) {
            throw new ApiException(ErrorCode.INVALID_STATE, "A revoked client cannot be given a new secret.");
        }
        String secret = SecureTokens.newToken();
        jdbc.update("UPDATE api_clients SET secret_hash = ?, secret_rotated_at = ?, version = version + 1 WHERE id = ?",
                encoder.encode(secret), Timestamp.from(Instant.now(clock)), id);
        audit.record("API_CLIENT_SECRET_ROTATED", "API_CLIENT", id, Map.of("clientId", c.get("client_id")));
        return new ClientWithSecret(get(id), secret);
    }

    @Transactional
    public ClientView revoke(UUID id, String reason, long version) {
        CurrentActor.requirePermission(MANAGE);
        Map<String, Object> c = lock(id, version);
        if (!"ACTIVE".equals(c.get("status"))) {
            throw new ApiException(ErrorCode.INVALID_STATE, "The client is already revoked.");
        }
        String r = reason == null ? "" : reason.trim();
        if (r.length() < 5) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Give the reason for revoking the client.");
        }
        jdbc.update("UPDATE api_clients SET status = 'REVOKED', revoked_by = ?, revoked_at = ?, revoke_reason = ?, version = version + 1 "
                + "WHERE id = ?", CurrentActor.username(), Timestamp.from(Instant.now(clock)), r, id);
        audit.record("API_CLIENT_REVOKED", "API_CLIENT", id, Map.of("clientId", c.get("client_id"), "reason", r));
        return get(id);
    }

    private Map<String, Object> lock(UUID id, long version) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT client_id, status, version FROM api_clients WHERE id = ? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("API client");
        }
        if (((Number) rows.get(0).get("version")).longValue() != version) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION, "The client was changed by someone else. Reload and try again.");
        }
        return rows.get(0);
    }

    private ClientView get(UUID id) {
        return jdbc.queryForObject("SELECT * FROM api_clients WHERE id = ?", (rs, i) -> map(rs), id);
    }

    private static ClientView map(ResultSet rs) throws SQLException {
        return new ClientView(rs.getObject("id", UUID.class), rs.getString("client_id"), rs.getString("name"), rs.getString("description"),
                Arrays.stream((Object[]) rs.getArray("scopes").getArray()).map(Object::toString).toList(), rs.getString("status"),
                ts(rs, "expires_at"), ts(rs, "last_used_at"), ts(rs, "secret_rotated_at"), rs.getString("created_by"), ts(rs, "created_at"),
                rs.getString("revoked_by"), ts(rs, "revoked_at"), rs.getString("revoke_reason"), rs.getLong("version"));
    }

    static Instant ts(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
