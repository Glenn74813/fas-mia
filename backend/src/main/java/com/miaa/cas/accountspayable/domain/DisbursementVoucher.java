package com.miaa.cas.accountspayable.domain;

import com.miaa.cas.common.money.Money;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Disbursement Voucher. Authorisation (certify, approve), payment instruction (check / ADA),
 * execution confirmation and accounting posting are distinct states (clarification C-09).
 */
@Entity
@Table(name = "disbursement_vouchers")
public class DisbursementVoucher {

    @Id
    private UUID id;
    @Column(name = "dv_number", unique = true, length = 30)
    private String dvNumber;
    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;
    @Column(nullable = false, length = 1000)
    private String particulars;
    @Column(nullable = false, length = 20)
    private String status;
    @Column(name = "current_step_order")
    private Short currentStepOrder;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;
    @Column(name = "payment_mode", length = 10)
    private String paymentMode;
    @Column(name = "bank_account_id")
    private UUID bankAccountId;
    @Column(name = "payment_reference", length = 60)
    private String paymentReference;
    @Column(name = "payment_date")
    private LocalDate paymentDate;
    @Column(name = "instructed_by", length = 64)
    private String instructedBy;
    @Column(name = "instructed_at")
    private Instant instructedAt;
    @Column(name = "confirmed_by", length = 64)
    private String confirmedBy;
    @Column(name = "confirmed_at")
    private Instant confirmedAt;
    @Column(name = "prepared_by_id", nullable = false, updatable = false)
    private UUID preparedById;
    @Column(name = "prepared_by", nullable = false, updatable = false, length = 64)
    private String preparedBy;
    @Column(name = "posted_entry_id")
    private UUID postedEntryId;
    @OneToMany(mappedBy = "voucher", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DisbursementVoucherLine> lines = new ArrayList<>();
    @Version
    @Column(name = "version", nullable = false)
    private Long rowVersion;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "updated_by", nullable = false, length = 64)
    private String updatedBy;

    protected DisbursementVoucher() {
    }

    public DisbursementVoucher(UUID id, UUID supplierId, String particulars, UUID preparedById, String preparedBy, Instant now) {
        this.id = id;
        this.supplierId = supplierId;
        this.particulars = particulars;
        this.status = "DRAFT";
        this.amount = Money.ZERO;
        this.preparedById = preparedById;
        this.preparedBy = preparedBy;
        this.createdAt = now;
        this.updatedAt = now;
        this.updatedBy = preparedBy;
    }

    public void setParticulars(String particulars) {
        this.particulars = particulars;
    }

    /** Callers must flush between clearing and adding allocations (unique invoice per DV). */
    public void clearLines() {
        lines.clear();
    }

    public void addLine(UUID invoiceId, BigDecimal value) {
        lines.add(new DisbursementVoucherLine(this, invoiceId, value));
        amount = Money.of(lines.stream().map(DisbursementVoucherLine::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    public void moveTo(String next, Short step, Instant now, String actor) {
        status = next;
        currentStepOrder = step;
        updatedAt = now;
        updatedBy = actor;
    }

    /** Records a change made outside the voucher's own fields (its check approved), so the version moves on. */
    public void touch(Instant now, String actor) {
        updatedAt = now;
        updatedBy = actor;
    }

    public void assignNumber(String number) {
        this.dvNumber = number;
    }

    public void instruct(String mode, UUID bankAccountId, String reference, LocalDate paymentDate, Instant now, String actor) {
        this.paymentMode = mode;
        this.bankAccountId = bankAccountId;
        this.paymentReference = reference;
        this.paymentDate = paymentDate;
        this.instructedBy = actor;
        this.instructedAt = now;
        moveTo("PAYMENT_INSTRUCTED", null, now, actor);
    }

    public void clearInstruction(Instant now, String actor) {
        this.paymentMode = null;
        this.bankAccountId = null;
        this.paymentReference = null;
        this.paymentDate = null;
        this.instructedBy = null;
        this.instructedAt = null;
        moveTo("APPROVED", null, now, actor);
    }

    public void confirm(LocalDate releasedOn, Instant now, String actor) {
        this.paymentDate = releasedOn;
        this.confirmedBy = actor;
        this.confirmedAt = now;
        moveTo("PAID", null, now, actor);
    }

    public void markPosted(UUID entryId, Instant now, String actor) {
        this.postedEntryId = entryId;
        moveTo("POSTED", null, now, actor);
    }

    public boolean isEditable() {
        return "DRAFT".equals(status) || "RETURNED".equals(status);
    }

    public UUID getId() { return id; }
    public String getDvNumber() { return dvNumber; }
    public UUID getSupplierId() { return supplierId; }
    public String getParticulars() { return particulars; }
    public String getStatus() { return status; }
    public Short getCurrentStepOrder() { return currentStepOrder; }
    public BigDecimal getAmount() { return amount; }
    public String getPaymentMode() { return paymentMode; }
    public UUID getBankAccountId() { return bankAccountId; }
    public String getPaymentReference() { return paymentReference; }
    public LocalDate getPaymentDate() { return paymentDate; }
    public String getInstructedBy() { return instructedBy; }
    public Instant getInstructedAt() { return instructedAt; }
    public String getConfirmedBy() { return confirmedBy; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public UUID getPreparedById() { return preparedById; }
    public String getPreparedBy() { return preparedBy; }
    public UUID getPostedEntryId() { return postedEntryId; }
    public List<DisbursementVoucherLine> getLines() { return lines; }
    public long getVersion() { return rowVersion == null ? 0 : rowVersion; }
    public Instant getUpdatedAt() { return updatedAt; }
}
