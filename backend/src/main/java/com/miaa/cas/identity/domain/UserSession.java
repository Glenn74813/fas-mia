package com.miaa.cas.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One sign-in session; the access token's {@code sid} claim and the refresh-token family point here. */
@Entity
@Table(name = "user_sessions")
public class UserSession {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_refreshed_at", nullable = false)
    private Instant lastRefreshedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoke_reason", length = 40)
    private String revokeReason;

    @Column(name = "client_ip", length = 45)
    private String clientIp;

    @Column(name = "user_agent", length = 300)
    private String userAgent;

    protected UserSession() {
    }

    public UserSession(UUID id, UUID userId, Instant now, Instant expiresAt, String clientIp, String userAgent) {
        this.id = id;
        this.userId = userId;
        this.createdAt = now;
        this.lastRefreshedAt = now;
        this.expiresAt = expiresAt;
        this.clientIp = clientIp;
        this.userAgent = userAgent;
    }

    public boolean isActive(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public void revoke(Instant now, String reason) {
        if (revokedAt == null) {
            revokedAt = now;
            revokeReason = reason;
        }
    }

    public void refreshed(Instant now) {
        lastRefreshedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastRefreshedAt() { return lastRefreshedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public String getRevokeReason() { return revokeReason; }
    public String getClientIp() { return clientIp; }
    public String getUserAgent() { return userAgent; }
}
