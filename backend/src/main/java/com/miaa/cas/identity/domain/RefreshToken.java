package com.miaa.cas.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "replaced_by_id")
    private UUID replacedById;

    protected RefreshToken() {
    }

    public RefreshToken(UUID id, UUID sessionId, String tokenHash, Instant issuedAt, Instant expiresAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.tokenHash = tokenHash;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    public void consume(Instant now, UUID successorId) {
        usedAt = now;
        replacedById = successorId;
    }

    public UUID getId() { return id; }
    public UUID getSessionId() { return sessionId; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getUsedAt() { return usedAt; }
    public UUID getReplacedById() { return replacedById; }
}
