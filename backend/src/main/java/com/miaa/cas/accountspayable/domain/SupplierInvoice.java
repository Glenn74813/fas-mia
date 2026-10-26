package com.miaa.cas.accountspayable.domain;

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

/** A supplier invoice recognised as a payable once approved and posted. Amounts are PHP, scale 2. */
@Entity
@Table(name = "supplier_invoices")
public class SupplierInvoice {

    @Id
    private UUID id;
    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;
    @Column(name = "invoice_number", nullable = false, length = 60)
    private String invoiceNumber;
    @Column(name = "invoice_date", nullable = false)
    private LocalDate invoiceDate;
    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;
    @Column(name = "accounting_date", nullable = false)
    private LocalDate accountingDate;
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
    @Column(name = "ewt_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal ewtAmount;
    @Column(name = "payable_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal payableAmount;
    @Column(name = "paid_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal paidAmount;
    @Column(name = "prepared_by_id", nullable = false, updatable = false)
    private UUID preparedById;
    @Column(name = "prepared_by", nullable = false, updatable = false, length = 64)
    private String preparedBy;
    @Column(name = "posted_entry_id")
    private UUID postedEntryId;
    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo")
    private List<SupplierInvoiceLine> lines = new ArrayList<>();
    @Version
    @Column(name = "version", nullable = false)
    private Long rowVersion;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "updated_by", nullable = false, length = 64)
    private String updatedBy;

    protected SupplierInvoice() {
    }

    public SupplierInvoice(UUID id, UUID supplierId, UUID preparedById, String preparedBy, Instant now) {
        this.id = id;
        this.supplierId = supplierId;
        this.status = "DRAFT";
        this.preparedById = preparedById;
        this.preparedBy = preparedBy;
        this.netAmount = Money.ZERO;
        this.vatAmount = Money.ZERO;
        this.ewtAmount = Money.ZERO;
        this.payableAmount = Money.ZERO;
        this.paidAmount = Money.ZERO;
        this.createdAt = now;
        this.updatedAt = now;
        this.updatedBy = preparedBy;
    }

    public void setHeader(String invoiceNumber, LocalDate invoiceDate, LocalDate dueDate, LocalDate accountingDate,
            UUID periodId, String description, UUID vatCodeId) {
        this.invoiceNumber = invoiceNumber;
        this.invoiceDate = invoiceDate;
        this.dueDate = dueDate;
        this.accountingDate = accountingDate;
        this.periodId = periodId;
        this.description = description;
        this.vatCodeId = vatCodeId;
    }

    /** Callers must flush between clearing and adding lines (unique line numbers). */
    public void clearLines() {
        lines.clear();
    }

    public void addLine(int lineNo, UUID accountId, UUID costCenterId, String description, BigDecimal net, UUID ewtCodeId,
            BigDecimal ewt) {
        lines.add(new SupplierInvoiceLine(this, lineNo, accountId, costCenterId, description, net, ewtCodeId, ewt));
    }

    public void setTotals(BigDecimal net, BigDecimal vat, BigDecimal ewt) {
        this.netAmount = Money.of(net);
        this.vatAmount = Money.of(vat);
        this.ewtAmount = Money.of(ewt);
        this.payableAmount = Money.of(net.add(vat).subtract(ewt));
    }

    public void moveTo(String next, Short step, Instant now, String actor) {
        status = next;
        currentStepOrder = step;
        updatedAt = now;
        updatedBy = actor;
    }

    public void markPosted(UUID entryId, Instant now, String actor) {
        postedEntryId = entryId;
        moveTo("POSTED", null, now, actor);
    }

    public void addPayment(BigDecimal amount) {
        paidAmount = Money.of(paidAmount.add(amount));
    }

    public BigDecimal openBalance() {
        return payableAmount.subtract(paidAmount);
    }

    public boolean isEditable() {
        return "DRAFT".equals(status) || "RETURNED".equals(status);
    }

    public UUID getId() { return id; }
    public UUID getSupplierId() { return supplierId; }
    public String getInvoiceNumber() { return invoiceNumber; }
    public LocalDate getInvoiceDate() { return invoiceDate; }
    public LocalDate getDueDate() { return dueDate; }
    public LocalDate getAccountingDate() { return accountingDate; }
    public UUID getPeriodId() { return periodId; }
    public String getDescription() { return description; }
    public String getStatus() { return status; }
    public Short getCurrentStepOrder() { return currentStepOrder; }
    public UUID getVatCodeId() { return vatCodeId; }
    public BigDecimal getNetAmount() { return netAmount; }
    public BigDecimal getVatAmount() { return vatAmount; }
    public BigDecimal getEwtAmount() { return ewtAmount; }
    public BigDecimal getPayableAmount() { return payableAmount; }
    public BigDecimal getPaidAmount() { return paidAmount; }
    public UUID getPreparedById() { return preparedById; }
    public String getPreparedBy() { return preparedBy; }
    public UUID getPostedEntryId() { return postedEntryId; }
    public List<SupplierInvoiceLine> getLines() { return lines; }
    public long getVersion() { return rowVersion == null ? 0 : rowVersion; }
    public Instant getUpdatedAt() { return updatedAt; }
}
