package com.miaa.cas.generalledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A chart-of-accounts entry. Only postable (leaf) accounts receive journal lines. */
@Entity
@Table(name = "chart_of_accounts")
public class Account {

    @Id
    private UUID id;
    @Column(nullable = false, unique = true, length = 30)
    private String code;
    @Column(nullable = false, length = 200)
    private String name;
    @Column(length = 1000)
    private String description;
    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 10)
    private AccountType accountType;
    @Enumerated(EnumType.STRING)
    @Column(name = "normal_balance", nullable = false, length = 6)
    private NormalBalance normalBalance;
    @Column(name = "parent_id")
    private UUID parentId;
    @Column(nullable = false)
    private boolean postable;
    @Column(nullable = false, length = 10)
    private String status;
    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;
    @Column(name = "effective_to")
    private LocalDate effectiveTo;
    @Column(name = "fs_line_code", length = 30)
    private String fsLineCode;
    @Column(name = "requires_cost_center", nullable = false)
    private boolean requiresCostCenter;
    @Column(name = "subsidiary_type", length = 12)
    private String subsidiaryType;
    @Column(name = "budget_controlled", nullable = false)
    private boolean budgetControlled;
    @Column(name = "tax_code", length = 20)
    private String taxCode;
    @Column(nullable = false)
    private boolean placeholder;
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

    protected Account() {
    }

    public Account(UUID id, String code, String name, AccountType type, NormalBalance normalBalance, UUID parentId,
            boolean postable, LocalDate effectiveFrom, boolean placeholder, Instant now, String actor) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.accountType = type;
        this.normalBalance = normalBalance;
        this.parentId = parentId;
        this.postable = postable;
        this.status = "ACTIVE";
        this.effectiveFrom = effectiveFrom;
        this.placeholder = placeholder;
        this.createdAt = now;
        this.createdBy = actor;
        this.updatedAt = now;
        this.updatedBy = actor;
    }

    public boolean isActiveOn(LocalDate date) {
        return "ACTIVE".equals(status) && !date.isBefore(effectiveFrom) && (effectiveTo == null || !date.isAfter(effectiveTo));
    }

    public void setAttributes(String name, String description, String fsLineCode, boolean requiresCostCenter,
            String subsidiaryType, boolean budgetControlled, String taxCode, LocalDate effectiveTo) {
        this.name = name;
        this.description = description;
        this.fsLineCode = fsLineCode;
        this.requiresCostCenter = requiresCostCenter;
        this.subsidiaryType = subsidiaryType;
        this.budgetControlled = budgetControlled;
        this.taxCode = taxCode;
        this.effectiveTo = effectiveTo;
    }

    public void setStructure(AccountType type, NormalBalance normalBalance, UUID parentId, boolean postable) {
        this.accountType = type;
        this.normalBalance = normalBalance;
        this.parentId = parentId;
        this.postable = postable;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void touch(Instant now, String actor) {
        this.updatedAt = now;
        this.updatedBy = actor;
    }

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public AccountType getAccountType() { return accountType; }
    public NormalBalance getNormalBalance() { return normalBalance; }
    public UUID getParentId() { return parentId; }
    public boolean isPostable() { return postable; }
    public String getStatus() { return status; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public LocalDate getEffectiveTo() { return effectiveTo; }
    public String getFsLineCode() { return fsLineCode; }
    public boolean isRequiresCostCenter() { return requiresCostCenter; }
    public String getSubsidiaryType() { return subsidiaryType; }
    public boolean isBudgetControlled() { return budgetControlled; }
    public String getTaxCode() { return taxCode; }
    public boolean isPlaceholder() { return placeholder; }
    public long getVersion() { return rowVersion == null ? 0 : rowVersion; }
}
