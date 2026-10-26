package com.miaa.cas.identity.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.OptimisticLock;
import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

@Entity
@Table(name = "roles")
public class Role {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(name = "system_role", nullable = false)
    private boolean systemRole;

    // Excluded from automatic versioning (Hibernate would bump the version on first insert);
    // replacePermissions() touches the role, which increments the version explicitly.
    @OptimisticLock(excluded = true)
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "role_permissions", joinColumns = @JoinColumn(name = "role_id"))
    @Column(name = "permission_code", length = 64)
    private Set<String> permissionCodes = new TreeSet<>();

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

    protected Role() {
    }

    public Role(UUID id, String code, String name, String description, Set<String> permissionCodes, Instant now,
            String actor) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.description = description;
        this.permissionCodes = new TreeSet<>(permissionCodes);
        this.createdAt = now;
        this.createdBy = actor;
        this.updatedAt = now;
        this.updatedBy = actor;
    }

    public void update(String name, String description, Instant now, String actor) {
        this.name = name;
        this.description = description;
        this.updatedAt = now;
        this.updatedBy = actor;
    }

    public void replacePermissions(Set<String> codes, Instant now, String actor) {
        permissionCodes.clear();
        permissionCodes.addAll(codes);
        this.updatedAt = now;
        this.updatedBy = actor;
    }

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public boolean isSystemRole() { return systemRole; }
    public Set<String> getPermissionCodes() { return Set.copyOf(permissionCodes); }
    public long getVersion() { return rowVersion == null ? 0 : rowVersion; }
    public Instant getUpdatedAt() { return updatedAt; }
}
