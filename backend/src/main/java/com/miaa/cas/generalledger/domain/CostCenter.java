package com.miaa.cas.generalledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "cost_centers")
public class CostCenter {

    @Id
    private UUID id;
    @Column(nullable = false, unique = true, length = 30)
    private String code;
    @Column(nullable = false, length = 200)
    private String name;
    @Column(nullable = false, length = 10)
    private String status;
    @Column(nullable = false)
    private boolean placeholder;
    @Version
    @Column(name = "version", nullable = false)
    private Long rowVersion;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "created_by", nullable = false, updatable = false, length = 64)
    private String createdBy;

    protected CostCenter() {
    }

    public CostCenter(UUID id, String code, String name, boolean placeholder, Instant now, String actor) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.status = "ACTIVE";
        this.placeholder = placeholder;
        this.createdAt = now;
        this.createdBy = actor;
    }

    public void update(String name, String status) {
        this.name = name;
        this.status = status;
    }

    public boolean isActive() { return "ACTIVE".equals(status); }
    public UUID getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getStatus() { return status; }
    public boolean isPlaceholder() { return placeholder; }
    public long getVersion() { return rowVersion == null ? 0 : rowVersion; }
}
