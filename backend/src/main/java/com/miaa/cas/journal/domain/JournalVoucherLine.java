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
@Table(name = "journal_voucher_lines")
public class JournalVoucherLine {

    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "voucher_id")
    private JournalVoucher voucher;
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

    protected JournalVoucherLine() {
    }

    public JournalVoucherLine(JournalVoucher voucher, int lineNo, LineData data) {
        this.id = UUID.randomUUID();
        this.voucher = voucher;
        this.lineNo = (short) lineNo;
        this.accountId = data.accountId();
        this.costCenterId = data.costCenterId();
        this.subsidiaryAccountId = data.subsidiaryAccountId();
        this.description = data.description();
        this.debit = data.debit();
        this.credit = data.credit();
    }

    public LineData data() {
        return new LineData(accountId, costCenterId, subsidiaryAccountId, description, debit, credit);
    }

    public UUID getId() { return id; }
    public int getLineNo() { return lineNo; }
    public UUID getAccountId() { return accountId; }
    public UUID getCostCenterId() { return costCenterId; }
    public UUID getSubsidiaryAccountId() { return subsidiaryAccountId; }
    public String getDescription() { return description; }
    public BigDecimal getDebit() { return debit; }
    public BigDecimal getCredit() { return credit; }
}
