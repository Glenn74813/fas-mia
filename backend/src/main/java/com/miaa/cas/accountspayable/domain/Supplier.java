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
    @Column(name = "zip_code", length = 4)
    private String zipCode;
    @Column(name = "foreign_address", length = 500)
    private String foreignAddress;
    @Column(name = "payee_type", length = 12)
    private String payeeType;
    @Column(name = "last_name", length = 30)
    private String lastName;
    @Column(name = "first_name", length = 30)
    private String firstName;
    @Column(name = "middle_name", length = 30)
    private String middleName;
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

    public void update(String name, String tin, String address, String zipCode, String foreignAddress, String payeeType, boolean vatRegistered,
            UUID defaultEwtCodeId, String status, Instant now, String actor) {
        this.name = name;
        this.tin = tin;
        this.address = address;
        this.zipCode = zipCode;
        this.foreignAddress = foreignAddress;
        this.payeeType = payeeType;
        this.vatRegistered = vatRegistered;
        this.defaultEwtCodeId = defaultEwtCodeId;
        this.status = status;
        this.updatedAt = now;
        this.updatedBy = actor;
    }

    /** The name parts the BIR alphalists use for an individual payee. */
    public void names(String lastName, String firstName, String middleName) {
        this.lastName = lastName;
        this.firstName = firstName;
        this.middleName = middleName;
    }

    public boolean isActive() { return "ACTIVE".equals(status); }
    public UUID getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getTin() { return tin; }
    public String getAddress() { return address; }
    public String getZipCode() { return zipCode; }
    public String getForeignAddress() { return foreignAddress; }
    public String getPayeeType() { return payeeType; }
    public String getLastName() { return lastName; }
    public String getFirstName() { return firstName; }
    public String getMiddleName() { return middleName; }
    public boolean isVatRegistered() { return vatRegistered; }
    public UUID getDefaultEwtCodeId() { return defaultEwtCodeId; }
    public UUID getSubsidiaryAccountId() { return subsidiaryAccountId; }
    public String getStatus() { return status; }
    public long getVersion() { return rowVersion == null ? 0 : rowVersion; }
}
