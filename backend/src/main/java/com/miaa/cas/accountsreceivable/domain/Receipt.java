package com.miaa.cas.accountsreceivable.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A collection from a customer. Cash plus tax withheld settles invoices; any excess is unapplied. */
@Entity
@Table(name = "receipts")
public class Receipt {

    @Id
    private UUID id;
    @Column(name = "receipt_number", nullable = false, unique = true, length = 30, updatable = false)
    private String receiptNumber;
    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;
    @Column(name = "receipt_date", nullable = false)
    private LocalDate receiptDate;
    @Column(name = "payment_method", nullable = false, length = 16)
    private String paymentMethod;
    @Column(name = "payment_reference", length = 60)
    private String paymentReference;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;
    @Column(name = "tax_withheld", nullable = false, precision = 19, scale = 2)
    private BigDecimal taxWithheld;
    @Column(name = "unapplied_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal unappliedAmount;
    @Column(nullable = false, length = 10)
    private String status;
    @Column(length = 500)
    private String remarks;
    @Column(name = "recorded_by_id", nullable = false, updatable = false)
    private UUID recordedById;
    @Column(name = "recorded_by", nullable = false, updatable = false, length = 64)
    private String recordedBy;
    @Column(name = "posted_entry_id")
    private UUID postedEntryId;
    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;
    @Version
    @Column(name = "version", nullable = false)
    private Long rowVersion;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "updated_by", nullable = false, length = 64)
    private String updatedBy;

    protected Receipt() {
    }

    public Receipt(UUID id, String receiptNumber, UUID customerId, LocalDate receiptDate, String paymentMethod,
            String paymentReference, BigDecimal amount, BigDecimal taxWithheld, BigDecimal unapplied, String remarks,
            UUID recordedById, String recordedBy, Instant now) {
        this.id = id;
        this.receiptNumber = receiptNumber;
        this.customerId = customerId;
        this.receiptDate = receiptDate;
        this.paymentMethod = paymentMethod;
        this.paymentReference = paymentReference;
        this.amount = amount;
        this.taxWithheld = taxWithheld;
        this.unappliedAmount = unapplied;
        this.remarks = remarks;
        this.status = "RECORDED";
        this.recordedById = recordedById;
        this.recordedBy = recordedBy;
        this.createdAt = now;
        this.updatedAt = now;
        this.updatedBy = recordedBy;
    }

    public void markPosted(UUID entryId, Instant now, String actor) {
        this.postedEntryId = entryId;
        this.status = "POSTED";
        this.updatedAt = now;
        this.updatedBy = actor;
    }

    public void cancel(String reason, Instant now, String actor) {
        this.status = "CANCELLED";
        this.cancelReason = reason;
        this.updatedAt = now;
        this.updatedBy = actor;
    }

    public void reduceUnapplied(BigDecimal applied, Instant now, String actor) {
        this.unappliedAmount = this.unappliedAmount.subtract(applied);
        this.updatedAt = now;
        this.updatedBy = actor;
    }

    public UUID getId() { return id; }
    public String getReceiptNumber() { return receiptNumber; }
    public UUID getCustomerId() { return customerId; }
    public LocalDate getReceiptDate() { return receiptDate; }
    public String getPaymentMethod() { return paymentMethod; }
    public String getPaymentReference() { return paymentReference; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getTaxWithheld() { return taxWithheld; }
    public BigDecimal getUnappliedAmount() { return unappliedAmount; }
    public String getStatus() { return status; }
    public String getRemarks() { return remarks; }
    public UUID getRecordedById() { return recordedById; }
    public String getRecordedBy() { return recordedBy; }
    public UUID getPostedEntryId() { return postedEntryId; }
    public String getCancelReason() { return cancelReason; }
    public long getVersion() { return rowVersion == null ? 0 : rowVersion; }
    public Instant getUpdatedAt() { return updatedAt; }
}
