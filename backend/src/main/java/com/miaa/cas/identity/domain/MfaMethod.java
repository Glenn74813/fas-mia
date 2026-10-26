package com.miaa.cas.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "mfa_methods")
public class MfaMethod {

    public static final String TOTP = "TOTP";
    public static final String PENDING = "PENDING";
    public static final String ACTIVE = "ACTIVE";
    public static final String REVOKED = "REVOKED";

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "method_type", nullable = false, length = 20)
    private String methodType;

    @Column(name = "secret_ciphertext")
    private byte[] secretCiphertext;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "enrollment_challenge_id", length = 64)
    private String enrollmentChallengeId;

    @Column(name = "last_used_step")
    private Long lastUsedStep;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected MfaMethod() {
    }

    public MfaMethod(UUID id, UUID userId, String methodType, byte[] secretCiphertext, String challengeId, Instant now) {
        this.id = id;
        this.enrollmentChallengeId = challengeId;
        this.userId = userId;
        this.methodType = methodType;
        this.secretCiphertext = secretCiphertext;
        this.status = PENDING;
        this.createdAt = now;
    }

    public void replacePendingSecret(byte[] ciphertext, String challengeId, Instant now) {
        this.secretCiphertext = ciphertext;
        this.enrollmentChallengeId = challengeId;
        this.createdAt = now;
    }

    public void activate(long usedStep, Instant now) {
        status = ACTIVE;
        lastUsedStep = usedStep;
        activatedAt = now;
    }

    public void markUsed(long step) {
        lastUsedStep = step;
    }

    public void revoke(Instant now) {
        status = REVOKED;
        revokedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getMethodType() { return methodType; }
    public byte[] getSecretCiphertext() { return secretCiphertext; }
    public String getStatus() { return status; }
    public Long getLastUsedStep() { return lastUsedStep; }
    public String getEnrollmentChallengeId() { return enrollmentChallengeId; }
    public Instant getActivatedAt() { return activatedAt; }
}
