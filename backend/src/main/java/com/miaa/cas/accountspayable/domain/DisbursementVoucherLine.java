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
@Table(name = "disbursement_voucher_lines")
public class DisbursementVoucherLine {

    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dv_id")
    private DisbursementVoucher voucher;
    @Column(name = "invoice_id", nullable = false)
    private UUID invoiceId;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    protected DisbursementVoucherLine() {
    }

    DisbursementVoucherLine(DisbursementVoucher voucher, UUID invoiceId, BigDecimal amount) {
        this.id = UUID.randomUUID();
        this.voucher = voucher;
        this.invoiceId = invoiceId;
        this.amount = amount;
    }

    public UUID getInvoiceId() { return invoiceId; }
    public BigDecimal getAmount() { return amount; }
}
