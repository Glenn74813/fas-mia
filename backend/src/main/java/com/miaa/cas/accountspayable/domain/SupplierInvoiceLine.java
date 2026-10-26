package com.miaa.cas.accountspayable.domain;

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
@Table(name = "supplier_invoice_lines")
public class SupplierInvoiceLine {

    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id")
    private SupplierInvoice invoice;
    @Column(name = "line_no", nullable = false)
    private short lineNo;
    @Column(name = "account_id", nullable = false)
    private UUID accountId;
    @Column(name = "cost_center_id")
    private UUID costCenterId;
    @Column(length = 500)
    private String description;
    @Column(name = "net_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal netAmount;
    @Column(name = "ewt_code_id")
    private UUID ewtCodeId;
    @Column(name = "ewt_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal ewtAmount;

    protected SupplierInvoiceLine() {
    }

    SupplierInvoiceLine(SupplierInvoice invoice, int lineNo, UUID accountId, UUID costCenterId, String description,
            BigDecimal net, UUID ewtCodeId, BigDecimal ewt) {
        this.id = UUID.randomUUID();
        this.invoice = invoice;
        this.lineNo = (short) lineNo;
        this.accountId = accountId;
        this.costCenterId = costCenterId;
        this.description = description;
        this.netAmount = net;
        this.ewtCodeId = ewtCodeId;
        this.ewtAmount = ewt;
    }

    public int getLineNo() { return lineNo; }
    public UUID getAccountId() { return accountId; }
    public UUID getCostCenterId() { return costCenterId; }
    public String getDescription() { return description; }
    public BigDecimal getNetAmount() { return netAmount; }
    public UUID getEwtCodeId() { return ewtCodeId; }
    public BigDecimal getEwtAmount() { return ewtAmount; }
}
