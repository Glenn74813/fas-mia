package com.miaa.cas.journal.domain;

import com.miaa.cas.common.money.Money;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Journal Entry Voucher: the editable, approvable document. Posting turns it into an immutable
 * journal entry; after that only its status pointers change.
 */
@Entity
@Table(name = "journal_vouchers")
public class JournalVoucher {

    @Id
    private UUID id;
    @Column(name = "voucher_number", unique = true, length = 30)
    private String voucherNumber;
    @Enumerated(EnumType.STRING)
    @Column(name = "voucher_type", nullable = false, length = 12)
    private VoucherType voucherType;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private VoucherStatus status;
    @Column(name = "accounting_date", nullable = false)
    private LocalDate accountingDate;
    @Column(name = "period_id", nullable = false)
    private UUID periodId;
    @Column(nullable = false, length = 1000)
    private String description;
    @Column(name = "supporting_reference", length = 200)
    private String supportingReference;
    @Column(name = "source_type", nullable = false, length = 30)
    private String sourceType;
    @Column(name = "source_reference", length = 100)
    private String sourceReference;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;
    @Column(name = "total_debit", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalDebit;
    @Column(name = "total_credit", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalCredit;
    @Column(name = "current_step_order")
    private Short currentStepOrder;
    @Column(name = "prepared_by_id", nullable = false, updatable = false)
    private UUID preparedById;
    @Column(name = "prepared_by", nullable = false, updatable = false, length = 64)
    private String preparedBy;
    @Column(name = "submitted_at")
    private Instant submittedAt;
    @Column(name = "posted_entry_id")
    private UUID postedEntryId;
    @Column(name = "reversal_of_entry_id", updatable = false)
    private UUID reversalOfEntryId;
    @Column(name = "recurring_journal_id", updatable = false)
    private UUID recurringJournalId;
    @OneToMany(mappedBy = "voucher", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo")
    private List<JournalVoucherLine> lines = new ArrayList<>();
    @Version
    @Column(name = "version", nullable = false)
    private Long rowVersion;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "updated_by", nullable = false, length = 64)
    private String updatedBy;

    protected JournalVoucher() {
    }

    public JournalVoucher(UUID id, VoucherType type, LocalDate accountingDate, UUID periodId, String description,
            String supportingReference, String sourceType, String sourceReference, UUID preparedById, String preparedBy,
            UUID reversalOfEntryId, UUID recurringJournalId, Instant now) {
        this.id = id;
        this.voucherType = type;
        this.status = VoucherStatus.DRAFT;
        this.accountingDate = accountingDate;
        this.periodId = periodId;
        this.description = description;
        this.supportingReference = supportingReference;
        this.sourceType = sourceType;
        this.sourceReference = sourceReference;
        this.currencyCode = "PHP";
        this.totalDebit = Money.ZERO;
        this.totalCredit = Money.ZERO;
        this.preparedById = preparedById;
        this.preparedBy = preparedBy;
        this.reversalOfEntryId = reversalOfEntryId;
        this.recurringJournalId = recurringJournalId;
        this.createdAt = now;
        this.updatedAt = now;
        this.updatedBy = preparedBy;
    }

    public void edit(VoucherType type, LocalDate date, UUID periodId, String description, String supportingReference) {
        this.voucherType = type;
        this.accountingDate = date;
        this.periodId = periodId;
        this.description = description;
        this.supportingReference = supportingReference;
    }

    /** Callers must flush between {@link #clearLines()} and adding new lines (unique line numbers). */
    public void clearLines() {
        lines.clear();
    }

    public void addLines(List<LineData> data) {
        int n = 1;
        BigDecimal d = BigDecimal.ZERO;
        BigDecimal c = BigDecimal.ZERO;
        for (LineData line : data) {
            lines.add(new JournalVoucherLine(this, n++, line));
            d = d.add(line.debit());
            c = c.add(line.credit());
        }
        totalDebit = Money.of(d);
        totalCredit = Money.of(c);
    }

    public void moveTo(VoucherStatus next, Short stepOrder, Instant now, String actor) {
        status = next;
        currentStepOrder = stepOrder;
        updatedAt = now;
        updatedBy = actor;
        if (next == VoucherStatus.SUBMITTED && submittedAt == null) {
            submittedAt = now;
        }
    }

    public void markSubmitted(Instant now) {
        submittedAt = now;
    }

    public void markPosted(String number, UUID entryId, Instant now, String actor) {
        voucherNumber = number;
        postedEntryId = entryId;
        moveTo(VoucherStatus.POSTED, null, now, actor);
    }

    public List<LineData> lineData() {
        return lines.stream().map(JournalVoucherLine::data).toList();
    }

    public UUID getId() { return id; }
    public String getVoucherNumber() { return voucherNumber; }
    public VoucherType getVoucherType() { return voucherType; }
    public VoucherStatus getStatus() { return status; }
    public LocalDate getAccountingDate() { return accountingDate; }
    public UUID getPeriodId() { return periodId; }
    public String getDescription() { return description; }
    public String getSupportingReference() { return supportingReference; }
    public String getSourceType() { return sourceType; }
    public String getSourceReference() { return sourceReference; }
    public String getCurrencyCode() { return currencyCode; }
    public BigDecimal getTotalDebit() { return totalDebit; }
    public BigDecimal getTotalCredit() { return totalCredit; }
    public Short getCurrentStepOrder() { return currentStepOrder; }
    public UUID getPreparedById() { return preparedById; }
    public String getPreparedBy() { return preparedBy; }
    public Instant getSubmittedAt() { return submittedAt; }
    public UUID getPostedEntryId() { return postedEntryId; }
    public UUID getReversalOfEntryId() { return reversalOfEntryId; }
    public UUID getRecurringJournalId() { return recurringJournalId; }
    public List<JournalVoucherLine> getLines() { return lines; }
    public long getVersion() { return rowVersion == null ? 0 : rowVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
}
