package com.miaa.cas.accountsreceivable.domain;

import com.miaa.cas.common.money.Money;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/** A billing invoice, credit memo or debit memo to a customer. */
@Entity
@Table(name = "billing_documents")
public class BillingDocument {

    @Id
    private UUID id;
    @Column(nullable = false, length = 12, updatable = false)
    private String kind;
    @Column(name = "document_number", unique = true, length = 30)
    private String documentNumber;
    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;
    @Column(name = "contract_id", updatable = false)
    private UUID contractId;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "billing_period", length = 7, updatable = false)
    private String billingPeriod;
    @Column(name = "related_invoice_id")
    private UUID relatedInvoiceId;
    @Column(name = "document_date", nullable = false)
    private LocalDate documentDate;
    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;
    @Column(name = "period_id", nullable = false)
    private UUID periodId;
    @Column(nullable = false, length = 1000)
    private String description;
    @Column(nullable = false, length = 12)
    private String status;
    @Column(name = "current_step_order")
    private Short currentStepOrder;
    @Column(name = "vat_code_id")
    private UUID vatCodeId;
    @Column(name = "net_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal netAmount;
    @Column(name = "vat_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal vatAmount;
    @Column(name = "gross_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal grossAmount;
    @Column(name = "settled_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal settledAmount;
    @Column(nullable = false, length = 10, updatable = false)
    private String source;
    @Column(name = "prepared_by_id", nullable = false, updatable = false)
    private UUID preparedById;
    @Column(name = "prepared_by", nullable = false, updatable = false, length = 64)
    private String preparedBy;
    @Column(name = "posted_entry_id")
    private UUID postedEntryId;
    @OneToMany(mappedBy = "document", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo")
    private List<BillingDocumentLine> lines = new ArrayList<>();
    @Version
    @Column(name = "version", nullable = false)
    private Long rowVersion;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "updated_by", nullable = false, length = 64)
    private String updatedBy;

    protected BillingDocument() {
    }

    public BillingDocument(UUID id, String kind, UUID customerId, UUID contractId, String billingPeriod, String source,
            UUID preparedById, String preparedBy, Instant now) {
        this.id = id;
        this.kind = kind;
        this.customerId = customerId;
        this.contractId = contractId;
        this.billingPeriod = billingPeriod;
        this.source = source;
        this.status = "DRAFT";
        this.netAmount = Money.ZERO;
        this.vatAmount = Money.ZERO;
        this.grossAmount = Money.ZERO;
        this.settledAmount = Money.ZERO;
        this.preparedById = preparedById;
        this.preparedBy = preparedBy;
        this.createdAt = now;
        this.updatedAt = now;
        this.updatedBy = preparedBy;
    }

    public void setHeader(LocalDate documentDate, LocalDate dueDate, UUID periodId, String description, UUID vatCodeId,
            UUID relatedInvoiceId) {
        this.documentDate = documentDate;
        this.dueDate = dueDate;
        this.periodId = periodId;
        this.description = description;
        this.vatCodeId = vatCodeId;
        this.relatedInvoiceId = relatedInvoiceId;
    }

    /** Callers must flush between clearing and adding lines (unique line numbers). */
    public void clearLines() {
        lines.clear();
    }

    public void addLine(int lineNo, UUID revenueAccountId, UUID costCenterId, String description, BigDecimal net) {
        lines.add(new BillingDocumentLine(this, lineNo, revenueAccountId, costCenterId, description, net));
    }

    public void setTotals(BigDecimal net, BigDecimal vat) {
        this.netAmount = Money.of(net);
        this.vatAmount = Money.of(vat);
        this.grossAmount = Money.of(net.add(vat));
    }

    public void moveTo(String next, Short step, Instant now, String actor) {
        status = next;
        currentStepOrder = step;
        updatedAt = now;
        updatedBy = actor;
    }

    public void markPosted(String number, UUID entryId, Instant now, String actor) {
        documentNumber = number;
        postedEntryId = entryId;
        if ("CREDIT_MEMO".equals(kind)) {
            settledAmount = grossAmount;
        }
        moveTo("POSTED", null, now, actor);
    }

    public void settle(BigDecimal amount) {
        settledAmount = Money.of(settledAmount.add(amount));
    }

    public BigDecimal openBalance() {
        return "CREDIT_MEMO".equals(kind) ? Money.ZERO : grossAmount.subtract(settledAmount);
    }

    public boolean isEditable() {
        return "DRAFT".equals(status) || "RETURNED".equals(status);
    }

    public boolean isReceivable() {
        return !"CREDIT_MEMO".equals(kind);
    }

    public UUID getId() { return id; }
    public String getKind() { return kind; }
    public String getDocumentNumber() { return documentNumber; }
    public UUID getCustomerId() { return customerId; }
    public UUID getContractId() { return contractId; }
    public String getBillingPeriod() { return billingPeriod; }
    public UUID getRelatedInvoiceId() { return relatedInvoiceId; }
    public LocalDate getDocumentDate() { return documentDate; }
    public LocalDate getDueDate() { return dueDate; }
    public UUID getPeriodId() { return periodId; }
    public String getDescription() { return description; }
    public String getStatus() { return status; }
    public Short getCurrentStepOrder() { return currentStepOrder; }
    public UUID getVatCodeId() { return vatCodeId; }
    public BigDecimal getNetAmount() { return netAmount; }
    public BigDecimal getVatAmount() { return vatAmount; }
    public BigDecimal getGrossAmount() { return grossAmount; }
    public BigDecimal getSettledAmount() { return settledAmount; }
    public String getSource() { return source; }
    public UUID getPreparedById() { return preparedById; }
    public String getPreparedBy() { return preparedBy; }
    public UUID getPostedEntryId() { return postedEntryId; }
    public List<BillingDocumentLine> getLines() { return lines; }
    public long getVersion() { return rowVersion == null ? 0 : rowVersion; }
    public Instant getUpdatedAt() { return updatedAt; }
}
