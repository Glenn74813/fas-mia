package com.miaa.cas.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.OptimisticLock;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_type", nullable = false, length = 20)
    private UserType userType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "password_changed_at", nullable = false)
    private Instant passwordChangedAt;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @OptimisticLock(excluded = true) // sign-in bookkeeping must not conflict with admin edits
    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    @OptimisticLock(excluded = true) // sign-in bookkeeping must not conflict with admin edits
    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "access_expires_at")
    private Instant accessExpiresAt;

    @OptimisticLock(excluded = true) // sign-in bookkeeping must not conflict with admin edits
    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    /** Wrapper type so Spring Data treats a new instance (null version) as new and persists it. */
    @Version
    @Column(name = "version", nullable = false)
    private Long rowVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false, updatable = false, length = 64)
    private String createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", nullable = false, length = 64)
    private String updatedBy;

    protected User() {
    }

    public User(UUID id, String username, String email, String displayName, UserType userType, String passwordHash,
            boolean mustChangePassword, Instant now, String actor) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.displayName = displayName;
        this.userType = userType;
        this.status = UserStatus.ACTIVE;
        this.passwordHash = passwordHash;
        this.passwordChangedAt = now;
        this.mustChangePassword = mustChangePassword;
        this.createdAt = now;
        this.createdBy = actor;
        this.updatedAt = now;
        this.updatedBy = actor;
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public boolean canSignIn(Instant now) {
        return status == UserStatus.ACTIVE && (accessExpiresAt == null || accessExpiresAt.isAfter(now));
    }

    /** Registers a failed authentication attempt; returns true when this attempt triggered a lockout. */
    public boolean registerFailure(int maxFailures, Instant lockUntil) {
        failedLoginCount++;
        if (failedLoginCount >= maxFailures) {
            failedLoginCount = 0;
            lockedUntil = lockUntil;
            return true;
        }
        return false;
    }

    public void registerSuccessfulLogin(Instant now) {
        failedLoginCount = 0;
        lockedUntil = null;
        lastLoginAt = now;
    }

    public void clearFailures() {
        failedLoginCount = 0;
    }

    public void unlock(Instant now, String actor) {
        failedLoginCount = 0;
        lockedUntil = null;
        touch(now, actor);
    }

    public void changePassword(String newHash, boolean temporary, Instant now, String actor) {
        passwordHash = newHash;
        passwordChangedAt = now;
        mustChangePassword = temporary;
        touch(now, actor);
    }

    public void updateProfile(String email, String displayName, Instant accessExpiresAt, Instant now, String actor) {
        this.email = email;
        this.displayName = displayName;
        this.accessExpiresAt = accessExpiresAt;
        touch(now, actor);
    }

    public void setStatus(UserStatus status, Instant now, String actor) {
        this.status = status;
        touch(now, actor);
    }

    public void setAccessExpiresAt(Instant accessExpiresAt) {
        this.accessExpiresAt = accessExpiresAt;
    }

    /** Marks the aggregate changed so its version increments (used when the role set changes). */
    public void touch(Instant now, String actor) {
        updatedAt = now;
        updatedBy = actor;
    }

    public UUID getId() { return id; }
    public String getUsername() { return username; }
    public String getEmail() { return email; }
    public String getDisplayName() { return displayName; }
    public UserType getUserType() { return userType; }
    public UserStatus getStatus() { return status; }
    public String getPasswordHash() { return passwordHash; }
    public Instant getPasswordChangedAt() { return passwordChangedAt; }
    public boolean isMustChangePassword() { return mustChangePassword; }
    public int getFailedLoginCount() { return failedLoginCount; }
    public Instant getLockedUntil() { return lockedUntil; }
    public Instant getAccessExpiresAt() { return accessExpiresAt; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public long getVersion() { return rowVersion == null ? 0 : rowVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
    public Instant getUpdatedAt() { return updatedAt; }
}
