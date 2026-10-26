package com.miaa.cas.journal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "recurring_journal_lines")
public class RecurringJournalLine {

    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recurring_journal_id")
    private RecurringJournal recurringJournal;
    @Column(name = "line_no", nullable = false)
    private short lineNo;
    @Column(name = "account_id", nullable = false)
    private UUID accountId;
    @Column(name = "cost_center_id")
    private UUID costCenterId;
    @Column(name = "subsidiary_account_id")
    private UUID subsidiaryAccountId;
    @Column(length = 500)
    private String description;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal debit;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal credit;

    protected RecurringJournalLine() {
    }

    RecurringJournalLine(RecurringJournal parent, int lineNo, LineData d) {
        this.id = UUID.randomUUID();
        this.recurringJournal = parent;
        this.lineNo = (short) lineNo;
        this.accountId = d.accountId();
        this.costCenterId = d.costCenterId();
        this.subsidiaryAccountId = d.subsidiaryAccountId();
        this.description = d.description();
        this.debit = d.debit();
        this.credit = d.credit();
    }

    LineData data() {
        return new LineData(accountId, costCenterId, subsidiaryAccountId, description, debit, credit);
    }
}
