package com.miaa.cas.generalledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** History of period status changes; a REOPEN starts PENDING and needs a second user's decision. */
@Entity
@Table(name = "period_status_changes")
public class PeriodStatusChange {

    @Id
    private UUID id;
    @Column(name = "period_id", nullable = false)
    private UUID periodId;
    @Column(name = "change_type", nullable = false, length = 12)
    private String changeType;
    @Column(name = "from_status", nullable = false, length = 12)
    private String fromStatus;
    @Column(name = "to_status", nullable = false, length = 12)
    private String toStatus;
    @Column(name = "request_status", nullable = false, length = 10)
    private String requestStatus;
    @Column(length = 1000)
    private String reason;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "waived_checks", nullable = false)
    private String waivedChecks;
    @Column(name = "requested_by_id")
    private UUID requestedById;
    @Column(name = "requested_by", nullable = false, length = 64)
    private String requestedBy;
    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;
    @Column(name = "decided_by_id")
    private UUID decidedById;
    @Column(name = "decided_by", length = 64)
    private String decidedBy;
    @Column(name = "decided_at")
    private Instant decidedAt;
    @Column(name = "decision_note", length = 1000)
    private String decisionNote;
    @Version
    @Column(name = "version", nullable = false)
    private Long rowVersion;

    protected PeriodStatusChange() {
    }

    public PeriodStatusChange(UUID id, UUID periodId, String changeType, PeriodStatus from, PeriodStatus to,
            String requestStatus, String reason, String waivedChecksJson, UUID actorId, String actor, Instant now) {
        this.id = id;
        this.periodId = periodId;
        this.changeType = changeType;
        this.fromStatus = from.name();
        this.toStatus = to.name();
        this.requestStatus = requestStatus;
        this.reason = reason;
        this.waivedChecks = waivedChecksJson;
        this.requestedById = actorId;
        this.requestedBy = actor;
        this.requestedAt = now;
    }

    public void decide(String outcome, UUID actorId, String actor, String note, Instant now) {
        requestStatus = outcome;
        decidedById = actorId;
        decidedBy = actor;
        decisionNote = note;
        decidedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getPeriodId() { return periodId; }
    public String getChangeType() { return changeType; }
    public String getFromStatus() { return fromStatus; }
    public String getToStatus() { return toStatus; }
    public String getRequestStatus() { return requestStatus; }
    public String getReason() { return reason; }
    public String getWaivedChecks() { return waivedChecks; }
    public UUID getRequestedById() { return requestedById; }
    public String getRequestedBy() { return requestedBy; }
    public Instant getRequestedAt() { return requestedAt; }
    public String getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getDecisionNote() { return decisionNote; }
    public long getVersion() { return rowVersion == null ? 0 : rowVersion; }
}
