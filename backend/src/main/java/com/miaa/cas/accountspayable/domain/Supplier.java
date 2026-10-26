package com.miaa.cas.accountspayable.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "suppliers")
public class Supplier {

    @Id
    private UUID id;
    @Column(nullable = false, unique = true, length = 40)
    private String code;
    @Column(nullable = false, length = 200)
    private String name;
    @Column(length = 20)
    private String tin;
    @Column(length = 500)
    private String address;
    @Column(name = "vat_registered", nullable = false)
    private boolean vatRegistered;
    @Column(name = "default_ewt_code_id")
    private UUID defaultEwtCodeId;
    @Column(name = "subsidiary_account_id", nullable = false, unique = true, updatable = false)
    private UUID subsidiaryAccountId;
    @Column(nullable = false, length = 10)
    private String status;
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

    protected Supplier() {
    }

    public Supplier(UUID id, String code, UUID subsidiaryAccountId, Instant now, String actor) {
        this.id = id;
        this.code = code;
        this.subsidiaryAccountId = subsidiaryAccountId;
        this.status = "ACTIVE";
        this.createdAt = now;
        this.createdBy = actor;
        this.updatedAt = now;
        this.updatedBy = actor;
    }

    public void update(String name, String tin, String address, boolean vatRegistered, UUID defaultEwtCodeId, String status,
            Instant now, String actor) {
        this.name = name;
        this.tin = tin;
        this.address = address;
        this.vatRegistered = vatRegistered;
        this.defaultEwtCodeId = defaultEwtCodeId;
        this.status = status;
        this.updatedAt = now;
        this.updatedBy = actor;
    }

    public boolean isActive() { return "ACTIVE".equals(status); }
    public UUID getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getTin() { return tin; }
    public String getAddress() { return address; }
    public boolean isVatRegistered() { return vatRegistered; }
    public UUID getDefaultEwtCodeId() { return defaultEwtCodeId; }
    public UUID getSubsidiaryAccountId() { return subsidiaryAccountId; }
    public String getStatus() { return status; }
    public long getVersion() { return rowVersion == null ? 0 : rowVersion; }
}
