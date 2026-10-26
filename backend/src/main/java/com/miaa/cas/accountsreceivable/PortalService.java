package com.miaa.cas.accountsreceivable;

import com.miaa.cas.accountsreceivable.ArDtos.BillingLineView;
import com.miaa.cas.accountsreceivable.ArDtos.BillingSummary;
import com.miaa.cas.accountsreceivable.ArDtos.PortalAccount;
import com.miaa.cas.accountsreceivable.ArDtos.ReceiptSummary;
import com.miaa.cas.accountsreceivable.ArDtos.Statement;
import com.miaa.cas.accountsreceivable.domain.BillingDocument;
import com.miaa.cas.accountsreceivable.domain.BillingDocumentRepository;
import com.miaa.cas.accountsreceivable.domain.Customer;
import com.miaa.cas.accountsreceivable.domain.ReceiptRepository;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.money.Money;
import com.miaa.cas.common.web.PageRequests;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.security.CurrentActor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Concessionaire self-service. The customer is always resolved from the signed-in account's link
 * ({@code users.customer_id}), never from the request, and only posted records are shown. Records of
 * other customers answer 404 so their existence is not disclosed.
 */
@Service
public class PortalService {

    public record PortalBilling(UUID id, String kind, String documentNumber, LocalDate documentDate, LocalDate dueDate,
            String billingPeriod, String description, String relatedInvoiceNumber, BigDecimal netAmount, BigDecimal vatAmount,
            BigDecimal grossAmount, BigDecimal settledAmount, BigDecimal openBalance, List<BillingLineView> lines) {
    }

    private final CustomerService customers;
    private final BillingDocumentRepository billings;
    private final BillingService billing;
    private final ReceiptRepository receipts;
    private final ReceiptService receiptService;
    private final ArReportService reports;
    private final JdbcTemplate jdbc;

    public PortalService(CustomerService customers, BillingDocumentRepository billings, BillingService billing,
            ReceiptRepository receipts, ReceiptService receiptService, ArReportService reports, JdbcTemplate jdbc) {
        this.customers = customers;
        this.billings = billings;
        this.billing = billing;
        this.receipts = receipts;
        this.receiptService = receiptService;
        this.reports = reports;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public PortalAccount account() {
        Customer c = myCustomer();
        LocalDate today = billing.today();
        BigDecimal overdue = Money.of(jdbc.queryForObject("SELECT coalesce(sum(gross_amount - settled_amount), 0) FROM "
                + "billing_documents WHERE customer_id = ? AND status = 'POSTED' AND kind <> 'CREDIT_MEMO' AND due_date < ?",
                BigDecimal.class, c.getId(), java.sql.Date.valueOf(today)));
        return new PortalAccount(c.getCode(), c.getName(), c.getTin(), c.getAddress(), c.getPaymentTermsDays(),
                customers.openBalance(c.getId()), overdue, customers.unapplied(c.getId()), today);
    }

    @Transactional(readOnly = true)
    public PageResponse<BillingSummary> billings(String kind, int page, int size) {
        Customer c = myCustomer();
        return PageResponse.of(billings.findAll(billing.filter(kind, "POSTED", c.getId(), null),
                PageRequests.of(page, size, null, Map.of("documentDate", "documentDate"), "-documentDate")), billing::summary);
    }

    @Transactional(readOnly = true)
    public PortalBilling billing(UUID id) {
        Customer c = myCustomer();
        BillingDocument d = billings.findById(id).filter(x -> x.getCustomerId().equals(c.getId()))
                .filter(x -> "POSTED".equals(x.getStatus())).orElseThrow(() -> ApiException.notFound("Billing document"));
        String related = d.getRelatedInvoiceId() == null ? null
                : billings.findById(d.getRelatedInvoiceId()).map(BillingDocument::getDocumentNumber).orElse(null);
        return new PortalBilling(d.getId(), d.getKind(), d.getDocumentNumber(), d.getDocumentDate(), d.getDueDate(),
                d.getBillingPeriod(), d.getDescription(), related, d.getNetAmount(), d.getVatAmount(), d.getGrossAmount(),
                d.getSettledAmount(), d.openBalance(), billing.lineViews(d));
    }

    @Transactional(readOnly = true)
    public PageResponse<ReceiptSummary> receipts(int page, int size) {
        Customer c = myCustomer();
        return PageResponse.of(receipts.findAll(receiptService.filter("POSTED", c.getId(), null, null),
                PageRequests.of(page, size, null, Map.of("receiptDate", "receiptDate"), "-receiptDate")), receiptService::summary);
    }

    @Transactional(readOnly = true)
    public Statement statement(LocalDate from, LocalDate to) {
        return reports.statement(myCustomer().getId(), from, to);
    }

    private Customer myCustomer() {
        CurrentActor.requirePermission("PORTAL_SELF_VIEW");
        List<UUID> ids = jdbc.queryForList("SELECT customer_id FROM users WHERE id = ? AND user_type = 'CONCESSIONAIRE' "
                + "AND customer_id IS NOT NULL", UUID.class, CurrentActor.userId());
        if (ids.isEmpty()) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Your portal account is not yet linked to a customer account. "
                    + "Please contact MIAA Finance.");
        }
        return customers.load(ids.get(0));
    }
}
