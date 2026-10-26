package com.miaa.cas.generalledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "fiscal_years")
public class FiscalYear {

    @Id
    private UUID id;
    @Column(name = "fiscal_year", nullable = false, unique = true)
    private int fiscalYear;
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;
    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;
    @Column(nullable = false, length = 10)
    private String status;
    @Column(name = "closed_at")
    private Instant closedAt;
    @Column(name = "closed_by", length = 64)
    private String closedBy;
    @Version
    @Column(name = "version", nullable = false)
    private Long rowVersion;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "created_by", nullable = false, updatable = false, length = 64)
    private String createdBy;

    protected FiscalYear() {
    }

    public FiscalYear(UUID id, int fiscalYear, LocalDate startDate, LocalDate endDate, Instant now, String actor) {
        this.id = id;
        this.fiscalYear = fiscalYear;
        this.startDate = startDate;
        this.endDate = endDate;
        this.status = "OPEN";
        this.createdAt = now;
        this.createdBy = actor;
    }

    public boolean isOpen() { return "OPEN".equals(status); }

    public void close(Instant now, String actor) {
        status = "CLOSED";
        closedAt = now;
        closedBy = actor;
    }

    public UUID getId() { return id; }
    public int getFiscalYear() { return fiscalYear; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public String getStatus() { return status; }
    public Instant getClosedAt() { return closedAt; }
    public String getClosedBy() { return closedBy; }
    public long getVersion() { return rowVersion == null ? 0 : rowVersion; }
}
