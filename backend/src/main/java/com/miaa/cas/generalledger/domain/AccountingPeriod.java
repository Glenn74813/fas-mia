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

/** Monthly period (1-12) or the year-end adjustment period (13). */
@Entity
@Table(name = "accounting_periods")
public class AccountingPeriod {

    @Id
    private UUID id;
    @Column(name = "fiscal_year_id", nullable = false)
    private UUID fiscalYearId;
    @Column(name = "period_number", nullable = false)
    private short periodNumber;
    @Column(nullable = false, length = 40)
    private String name;
    @Column(name = "period_type", nullable = false, length = 12)
    private String periodType;
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;
    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private PeriodStatus status;
    @Column(name = "sequence_key", nullable = false, unique = true)
    private int sequenceKey;
    @Version
    @Column(name = "version", nullable = false)
    private Long rowVersion;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "updated_by", nullable = false, length = 64)
    private String updatedBy;

    protected AccountingPeriod() {
    }

    public AccountingPeriod(UUID id, UUID fiscalYearId, int fiscalYear, int number, String name, LocalDate start,
            LocalDate end, Instant now, String actor) {
        this.id = id;
        this.fiscalYearId = fiscalYearId;
        this.periodNumber = (short) number;
        this.name = name;
        this.periodType = number == 13 ? "ADJUSTMENT" : "REGULAR";
        this.startDate = start;
        this.endDate = end;
        this.status = PeriodStatus.FUTURE;
        this.sequenceKey = fiscalYear * 100 + number;
        this.updatedAt = now;
        this.updatedBy = actor;
    }

    public boolean isAdjustment() {
        return periodNumber == 13;
    }

    public void changeStatus(PeriodStatus next, Instant now, String actor) {
        status = next;
        updatedAt = now;
        updatedBy = actor;
    }

    public UUID getId() { return id; }
    public UUID getFiscalYearId() { return fiscalYearId; }
    public int getPeriodNumber() { return periodNumber; }
    public String getName() { return name; }
    public String getPeriodType() { return periodType; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public PeriodStatus getStatus() { return status; }
    public int getSequenceKey() { return sequenceKey; }
    public long getVersion() { return rowVersion == null ? 0 : rowVersion; }
}
