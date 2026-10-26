package com.miaa.cas.budgeting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** The Corporate Operating Budget of one fiscal year; changes after approval go through adjustments. */
@Entity
@Table(name = "budgets")
public class Budget {

    @Id
    private UUID id;
    @Column(name = "fiscal_year_id", nullable = false, unique = true, updatable = false)
    private UUID fiscalYearId;
    @Column(nullable = false, length = 200)
    private String name;
    @Column(nullable = false, length = 12)
    private String status;
    @Column(name = "current_step_order")
    private Short currentStepOrder;
    @Column(name = "prepared_by_id", nullable = false, updatable = false)
    private UUID preparedById;
    @Column(name = "prepared_by", nullable = false, updatable = false, length = 64)
    private String preparedBy;
    @Column(name = "approved_at")
    private Instant approvedAt;
    @Column(name = "approved_by", length = 64)
    private String approvedBy;
    @Version
    @Column(name = "version", nullable = false)
    private Long rowVersion;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "updated_by", nullable = false, length = 64)
    private String updatedBy;

    protected Budget() {
    }

    public Budget(UUID id, UUID fiscalYearId, String name, UUID preparedById, String preparedBy, Instant now) {
        this.id = id;
        this.fiscalYearId = fiscalYearId;
        this.name = name;
        this.status = "DRAFT";
        this.preparedById = preparedById;
        this.preparedBy = preparedBy;
        this.createdAt = now;
        this.updatedAt = now;
        this.updatedBy = preparedBy;
    }

    public void moveTo(String next, Short step, Instant now, String actor) {
        status = next;
        currentStepOrder = step;
        updatedAt = now;
        updatedBy = actor;
        if ("APPROVED".equals(next)) {
            approvedAt = now;
            approvedBy = actor;
        }
    }

    public void touch(Instant now, String actor) {
        updatedAt = now;
        updatedBy = actor;
    }

    public boolean isEditable() {
        return "DRAFT".equals(status) || "RETURNED".equals(status);
    }

    public UUID getId() { return id; }
    public UUID getFiscalYearId() { return fiscalYearId; }
    public String getName() { return name; }
    public String getStatus() { return status; }
    public Short getCurrentStepOrder() { return currentStepOrder; }
    public UUID getPreparedById() { return preparedById; }
    public String getPreparedBy() { return preparedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public String getApprovedBy() { return approvedBy; }
    public long getVersion() { return rowVersion == null ? 0 : rowVersion; }
}
