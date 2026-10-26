package com.miaa.cas.accountsreceivable.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "customers")
public class Customer {

    @Id
    private UUID id;
    @Column(nullable = false, unique = true, length = 40)
    private String code;
    @Column(nullable = false, length = 200)
    private String name;
    @Column(name = "customer_type", nullable = false, length = 16)
    private String customerType;
    @Column(length = 20)
    private String tin;
    @Column(length = 500)
    private String address;
    @Column(length = 254)
    private String email;
    @Column(name = "credit_limit", precision = 19, scale = 2)
    private BigDecimal creditLimit;
    @Column(name = "payment_terms_days", nullable = false)
    private short paymentTermsDays;
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

    protected Customer() {
    }

    public Customer(UUID id, String code, UUID subsidiaryAccountId, Instant now, String actor) {
        this.id = id;
        this.code = code;
        this.subsidiaryAccountId = subsidiaryAccountId;
        this.status = "ACTIVE";
        this.createdAt = now;
        this.createdBy = actor;
        this.updatedAt = now;
        this.updatedBy = actor;
    }

    public void update(String name, String type, String tin, String address, String email, BigDecimal creditLimit,
            int paymentTermsDays, String status, Instant now, String actor) {
        this.name = name;
        this.customerType = type;
        this.tin = tin;
        this.address = address;
        this.email = email;
        this.creditLimit = creditLimit;
        this.paymentTermsDays = (short) paymentTermsDays;
        this.status = status;
        this.updatedAt = now;
        this.updatedBy = actor;
    }

    public boolean isActive() { return "ACTIVE".equals(status); }
    public UUID getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getCustomerType() { return customerType; }
    public String getTin() { return tin; }
    public String getAddress() { return address; }
    public String getEmail() { return email; }
    public BigDecimal getCreditLimit() { return creditLimit; }
    public int getPaymentTermsDays() { return paymentTermsDays; }
    public UUID getSubsidiaryAccountId() { return subsidiaryAccountId; }
    public String getStatus() { return status; }
    public long getVersion() { return rowVersion == null ? 0 : rowVersion; }
}
