package com.miaa.cas.generalledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** A subsidiary-ledger party (supplier, customer, employee, other) used as a line dimension. */
@Entity
@Table(name = "subsidiary_ledger_accounts")
public class SubsidiaryAccount {

    @Id
    private UUID id;
    @Column(name = "sl_type", nullable = false, length = 12)
    private String slType;
    @Column(nullable = false, length = 40)
    private String code;
    @Column(nullable = false, length = 200)
    private String name;
    @Column(nullable = false, length = 10)
    private String status;
    @Version
    @Column(name = "version", nullable = false)
    private Long rowVersion;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "created_by", nullable = false, updatable = false, length = 64)
    private String createdBy;

    protected SubsidiaryAccount() {
    }

    public SubsidiaryAccount(UUID id, String slType, String code, String name, Instant now, String actor) {
        this.id = id;
        this.slType = slType;
        this.code = code;
        this.name = name;
        this.status = "ACTIVE";
        this.createdAt = now;
        this.createdBy = actor;
    }

    public void update(String name, String status) {
        this.name = name;
        this.status = status;
    }

    public boolean isActive() { return "ACTIVE".equals(status); }
    public UUID getId() { return id; }
    public String getSlType() { return slType; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getStatus() { return status; }
    public long getVersion() { return rowVersion == null ? 0 : rowVersion; }
}
