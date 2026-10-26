package com.miaa.cas.accountsreceivable.domain;

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
@Table(name = "billing_document_lines")
public class BillingDocumentLine {

    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id")
    private BillingDocument document;
    @Column(name = "line_no", nullable = false)
    private short lineNo;
    @Column(name = "revenue_account_id", nullable = false)
    private UUID revenueAccountId;
    @Column(name = "cost_center_id")
    private UUID costCenterId;
    @Column(nullable = false, length = 500)
    private String description;
    @Column(name = "net_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal netAmount;

    protected BillingDocumentLine() {
    }

    BillingDocumentLine(BillingDocument document, int lineNo, UUID revenueAccountId, UUID costCenterId, String description,
            BigDecimal net) {
        this.id = UUID.randomUUID();
        this.document = document;
        this.lineNo = (short) lineNo;
        this.revenueAccountId = revenueAccountId;
        this.costCenterId = costCenterId;
        this.description = description;
        this.netAmount = net;
    }

    public int getLineNo() { return lineNo; }
    public UUID getRevenueAccountId() { return revenueAccountId; }
    public UUID getCostCenterId() { return costCenterId; }
    public String getDescription() { return description; }
    public BigDecimal getNetAmount() { return netAmount; }
}
