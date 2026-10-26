package com.miaa.cas.accountsreceivable;

import com.miaa.cas.accountsreceivable.ArDtos.BillingLineRequest;
import com.miaa.cas.accountsreceivable.ArDtos.BillingLineView;
import com.miaa.cas.accountsreceivable.ArDtos.BillingRequest;
import com.miaa.cas.accountsreceivable.ArDtos.BillingRunResult;
import com.miaa.cas.accountsreceivable.ArDtos.BillingSummary;
import com.miaa.cas.accountsreceivable.ArDtos.BillingView;
import com.miaa.cas.accountsreceivable.ArDtos.ContractView;
import com.miaa.cas.accountsreceivable.domain.BillingDocument;
import com.miaa.cas.accountsreceivable.domain.BillingDocumentLine;
import com.miaa.cas.accountsreceivable.domain.BillingDocumentRepository;
import com.miaa.cas.accountsreceivable.domain.Customer;
import com.miaa.cas.approvals.DocumentWorkflow;
import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.money.Money;
import com.miaa.cas.common.web.PageRequests;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.documents.DocumentOwnerPolicy;
import com.miaa.cas.documents.DocumentService;
import com.miaa.cas.generalledger.AccountMappingService;
import com.miaa.cas.generalledger.FiscalCalendarService;
import com.miaa.cas.generalledger.domain.AccountingPeriod;
import com.miaa.cas.generalledger.domain.PeriodStatus;
import com.miaa.cas.journal.NumberingService;
import com.miaa.cas.journal.PostingService;
import com.miaa.cas.journal.VoucherValidator;
import com.miaa.cas.journal.domain.LineData;
import com.miaa.cas.journal.domain.VoucherType;
import com.miaa.cas.security.CurrentActor;
import com.miaa.cas.tax.TaxCodeService;
import com.miaa.cas.tax.TaxCodeService.TaxCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Billing invoices, credit memos and debit memos: preparation (manual or from contracts), approval
 * (never by the preparer), and posting through the ledger.
 * <ul>
 * <li>Invoice / debit memo: Dr accounts receivable (customer SL) gross / Cr revenue lines / Cr output VAT.</li>
 * <li>Credit memo: the mirror image, and it settles part of the related invoice.</li>
 * </ul>
 */
@Service
public class BillingService implements DocumentOwnerPolicy {

    public static final String OWNER = "AR_BILLING";
    public static final String SOURCE = "SL_AR_BILLING";
    private static final Map<String, String> SORTS = Map.of("dueDate", "dueDate", "documentDate", "documentDate",
            "updatedAt", "updatedAt", "amount", "grossAmount");

    private final BillingDocumentRepository billings;
    private final CustomerService customers;
    private final TaxCodeService taxCodes;
    private final FiscalCalendarService calendar;
    private final VoucherValidator validator;
    private final DocumentWorkflow workflow;
    private final PostingService posting;
    private final NumberingService numbering;
    private final AccountMappingService mappings;
    private final DocumentService documents;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public BillingService(BillingDocumentRepository billings, CustomerService customers, TaxCodeService taxCodes,
            FiscalCalendarService calendar, VoucherValidator validator, DocumentWorkflow workflow, PostingService posting,
            NumberingService numbering, AccountMappingService mappings, DocumentService documents, AuditService audit,
            JdbcTemplate jdbc, Clock clock) {
        this.billings = billings;
        this.customers = customers;
        this.taxCodes = taxCodes;
        this.calendar = calendar;
        this.validator = validator;
        this.workflow = workflow;
        this.posting = posting;
        this.numbering = numbering;
        this.mappings = mappings;
        this.documents = documents;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public PageResponse<BillingSummary> list(String kind, String status, UUID customerId, String q, int page, int size,
            String sort) {
        return PageResponse.of(billings.findAll(filter(kind, status, customerId, q),
                PageRequests.of(page, size, sort, SORTS, "-updatedAt")), this::summary);
    }

    Specification<BillingDocument> filter(String kind, String status, UUID customerId, String q) {
        Specification<BillingDocument> spec = (root, query, cb) -> cb.conjunction();
        if (kind != null && !kind.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("kind"), kind.trim().toUpperCase()));
        }
        if (status != null && !status.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status.trim().toUpperCase()));
        }
        if (customerId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("customerId"), customerId));
        }
        if (q != null && !q.isBlank()) {
            String p = "%" + q.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            spec = spec.and((root, query, cb) -> cb.or(cb.like(cb.lower(root.get("documentNumber")), p, '\\'),
                    cb.like(cb.lower(root.get("description")), p, '\\')));
        }
        return spec;
    }

    @Transactional(readOnly = true)
    public BillingView get(UUID id) {
        return view(load(id));
    }

    // ------------------------------------------------------------------ commands

    @Transactional
    public BillingView create(BillingRequest req) {
        CurrentActor.requirePermission("AR_BILLING_PREPARE");
        Customer c = customers.active(req.customerId());
        BillingDocument d = new BillingDocument(UUID.randomUUID(), req.kind(), c.getId(), null, null, "MANUAL",
                CurrentActor.userId(), CurrentActor.username(), Instant.now(clock));
        apply(d, c, req);
        billings.saveAndFlush(d);
        audit.record("AR_BILLING_CREATED", d.getKind(), d.getId(), Map.of("customer", c.getCode(),
                "gross", d.getGrossAmount().toPlainString()));
        return view(d);
    }

    @Transactional
    public BillingView update(UUID id, long version, BillingRequest req) {
        BillingDocument d = lockVersioned(id, version);
        requirePreparer(d);
        if (!d.isEditable()) {
            throw state("Only draft or returned documents can be edited.");
        }
        if (!d.getCustomerId().equals(req.customerId()) || !d.getKind().equals(req.kind())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The customer and kind of a document cannot change; cancel it instead.");
        }
        if (d.getContractId() != null) {
            throw state("Contract billings are regenerated from the contract; return or cancel it instead of editing.");
        }
        Customer c = customers.active(req.customerId());
        d.clearLines();
        billings.flush();
        apply(d, c, req);
        d.moveTo("DRAFT", null, Instant.now(clock), CurrentActor.username());
        billings.flush();
        audit.record("AR_BILLING_UPDATED", d.getKind(), id, Map.of("gross", d.getGrossAmount().toPlainString()));
        return view(d);
    }

    /**
     * Creates DRAFT invoices for every active contract covering the month that has not been billed for
     * it yet. Re-running the same month creates nothing new. Amounts are the full contract monthly
     * amount; proration of partial months awaits MIAA's billing policy (C-28).
     */
    @Transactional
    public BillingRunResult runContractBilling(String billingPeriod, LocalDate documentDate) {
        CurrentActor.requirePermission("AR_BILLING_PREPARE");
        YearMonth ym = YearMonth.parse(billingPeriod);
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext('ar-contract-billing'))", Object.class);
        List<BillingSummary> created = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (ContractView k : customers.billableContracts(ym.atDay(1), ym.atEndOfMonth())) {
            Integer existing = jdbc.queryForObject("SELECT count(*) FROM billing_documents WHERE contract_id = ? AND "
                    + "billing_period = ? AND status NOT IN ('REJECTED', 'CANCELLED')", Integer.class, k.id(), billingPeriod);
            if (existing != null && existing > 0) {
                skipped.add(k.contractNumber() + ": already billed for " + billingPeriod);
                continue;
            }
            Customer c = customers.load(k.customerId());
            if (!c.isActive()) {
                skipped.add(k.contractNumber() + ": customer " + c.getCode() + " is inactive");
                continue;
            }
            BillingDocument d = new BillingDocument(UUID.randomUUID(), "INVOICE", c.getId(), k.id(), billingPeriod, "CONTRACT",
                    CurrentActor.userId(), CurrentActor.username(), Instant.now(clock));
            apply(d, c, new BillingRequest("INVOICE", c.getId(), null, documentDate, null,
                    k.description() + " - " + billingPeriod + " (contract " + k.contractNumber() + ")", k.vatCodeId(),
                    List.of(new BillingLineRequest(k.revenueAccountId(), k.costCenterId(),
                            "Contract " + k.contractNumber() + " " + billingPeriod, k.monthlyAmount()))));
            billings.saveAndFlush(d);
            created.add(summary(d));
        }
        audit.record("AR_CONTRACT_BILLING_RUN", "BILLING_PERIOD", billingPeriod, Map.of("created", created.size(),
                "skipped", skipped.size()));
        return new BillingRunResult(billingPeriod, created, skipped);
    }

    @Transactional
    public BillingView submit(UUID id, long version, String comment) {
        BillingDocument d = lockVersioned(id, version);
        requirePreparer(d);
        if (!d.isEditable()) {
            throw state("Only draft or returned documents can be submitted.");
        }
        validateForLedger(d);
        if (!"INVOICE".equals(d.getKind()) && documents.activeCount(OWNER, id) == 0) {
            throw rule("Attach the justification for the memo before submitting.");
        }
        if ("CREDIT_MEMO".equals(d.getKind())) {
            checkCreditMemoFits(d, billings.findById(d.getRelatedInvoiceId()).orElseThrow());
        }
        DocumentWorkflow.Step first = workflow.submit(workflowType(d), id, d.getGrossAmount(), comment);
        d.moveTo(first == null ? "APPROVED" : "SUBMITTED", first == null ? null : (short) first.order(), Instant.now(clock),
                CurrentActor.username());
        billings.flush();
        audit.record("AR_BILLING_SUBMITTED", d.getKind(), id, Map.of("gross", d.getGrossAmount().toPlainString()));
        return view(d);
    }

    @Transactional
    public BillingView approve(UUID id, long version, String comment) {
        BillingDocument d = lockVersioned(id, version);
        if (!"SUBMITTED".equals(d.getStatus())) {
            throw state("The document is not awaiting approval.");
        }
        DocumentWorkflow.Step next = workflow.approve(workflowType(d), id, d.getGrossAmount(), toInt(d.getCurrentStepOrder()),
                d.getPreparedById(), comment);
        d.moveTo(next == null ? "APPROVED" : "SUBMITTED", next == null ? null : (short) next.order(), Instant.now(clock),
                CurrentActor.username());
        billings.flush();
        audit.record("AR_BILLING_APPROVED", d.getKind(), id, Map.of("final", next == null));
        return view(d);
    }

    @Transactional
    public BillingView returnOrReject(UUID id, long version, String comment, boolean reject) {
        BillingDocument d = lockVersioned(id, version);
        if ("SUBMITTED".equals(d.getStatus())) {
            workflow.returnOrReject(workflowType(d), id, d.getGrossAmount(), toInt(d.getCurrentStepOrder()),
                    d.getPreparedById(), comment, reject);
        } else if ("APPROVED".equals(d.getStatus())) {
            CurrentActor.requirePermission("JOURNAL_POST");
            if (comment == null || comment.trim().length() < 5) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "A comment of at least 5 characters is required.");
            }
            workflow.record(workflowType(d), id, reject ? "REJECT" : "RETURN", comment.trim());
        } else {
            throw state("Only documents awaiting approval or posting can be " + (reject ? "rejected." : "returned."));
        }
        d.moveTo(reject ? "REJECTED" : "RETURNED", null, Instant.now(clock), CurrentActor.username());
        billings.flush();
        audit.record(reject ? "AR_BILLING_REJECTED" : "AR_BILLING_RETURNED", d.getKind(), id, Map.of("comment", comment.trim()));
        return view(d);
    }

    @Transactional
    public BillingView cancel(UUID id, long version, String comment) {
        BillingDocument d = lockVersioned(id, version);
        requirePreparer(d);
        if (!d.isEditable()) {
            throw state("Only draft or returned documents can be cancelled.");
        }
        workflow.record(workflowType(d), id, "CANCEL", comment);
        d.moveTo("CANCELLED", null, Instant.now(clock), CurrentActor.username());
        billings.flush();
        audit.record("AR_BILLING_CANCELLED", d.getKind(), id, Map.of());
        return view(d);
    }

    /** Recognises the receivable (or its reduction) in the ledger and assigns the document number. */
    @Transactional
    public BillingView post(UUID id, long version) {
        BillingDocument d = lockVersioned(id, version);
        if (!"APPROVED".equals(d.getStatus())) {
            throw state("POSTED".equals(d.getStatus()) ? "The document has already been posted." : "Only approved documents can be posted.");
        }
        Customer c = customers.load(d.getCustomerId());
        BillingDocument related = null;
        if ("CREDIT_MEMO".equals(d.getKind())) {
            related = billings.findByIdForUpdate(d.getRelatedInvoiceId()).orElseThrow();
            checkCreditMemoFits(d, related);
        }
        String number = numbering.next(prefix(d.getKind()), d.getDocumentDate().getYear());
        PostingService.PostedEntry entry = posting.postSubledger(new PostingService.SubledgerEntry(SOURCE, id.toString(),
                d.getDocumentDate(), label(d.getKind()) + " " + number + " " + c.getCode() + ": " + d.getDescription(),
                ledgerLines(d, c), d.getPreparedById(), d.getPreparedBy(), false));
        if (related != null) {
            related.settle(d.getGrossAmount());
        }
        workflow.record(workflowType(d), id, "POST", entry.entryNumber());
        d.markPosted(number, entry.entryId(), Instant.now(clock), CurrentActor.username());
        billings.flush();
        audit.record("AR_BILLING_POSTED", d.getKind(), id, Map.of("documentNumber", number, "entryNumber", entry.entryNumber(),
                "gross", d.getGrossAmount().toPlainString()));
        return view(d);
    }

    // ------------------------------------------------------------------ document policy

    @Override
    public String ownerType() {
        return OWNER;
    }

    @Override
    public void assertCanModify(UUID ownerId) {
        BillingDocument d = load(ownerId);
        CurrentActor.requirePermission("DOCUMENT_UPLOAD");
        if (!CurrentActor.userId().equals(d.getPreparedById())) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Only the preparer can change this document's attachments.");
        }
        if (!d.isEditable()) {
            throw state("Attachments can only change while the document is a draft or returned.");
        }
    }

    @Override
    public void assertCanView(UUID ownerId) {
        load(ownerId);
        if (!CurrentActor.hasPermission("AR_VIEW")) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "You do not have permission to view receivables.");
        }
    }

    // ------------------------------------------------------------------ helpers

    private void apply(BillingDocument d, Customer c, BillingRequest req) {
        BillingDocument related = null;
        UUID vatCodeId = req.vatCodeId();
        if ("CREDIT_MEMO".equals(req.kind())) {
            if (req.relatedInvoiceId() == null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "A credit memo must reference the invoice it reduces.");
            }
            related = billings.findById(req.relatedInvoiceId()).orElseThrow(() -> ApiException.notFound("Related invoice"));
            if (!related.getCustomerId().equals(c.getId()) || !related.isReceivable() || !"POSTED".equals(related.getStatus())) {
                throw rule("A credit memo can only reduce a posted invoice or debit memo of the same customer.");
            }
            // VAT on a credit memo follows the invoice it reduces.
            vatCodeId = related.getVatCodeId();
        } else if (req.relatedInvoiceId() != null) {
            related = billings.findById(req.relatedInvoiceId()).orElseThrow(() -> ApiException.notFound("Related invoice"));
            if (!related.getCustomerId().equals(c.getId())) {
                throw rule("The related invoice belongs to another customer.");
            }
        }
        LocalDate due = req.dueDate();
        if ("CREDIT_MEMO".equals(req.kind())) {
            due = req.documentDate();
        } else if (due == null) {
            due = req.documentDate().plusDays(c.getPaymentTermsDays());
        }
        if (due.isBefore(req.documentDate())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The due date cannot precede the document date.");
        }
        AccountingPeriod period = calendar.resolve(req.documentDate(), false);
        if (period.getStatus() == PeriodStatus.CLOSED) {
            throw rule("Period " + period.getName() + " is closed.");
        }
        TaxCode vat = vatCodeId == null ? null : taxCodes.requireActive(vatCodeId, "VAT_OUTPUT");
        d.setHeader(req.documentDate(), due, period.getId(), req.description().trim(), vatCodeId,
                related == null ? null : related.getId());
        BigDecimal net = BigDecimal.ZERO;
        int n = 1;
        for (BillingLineRequest l : req.lines()) {
            customers.requireRevenueAccount(l.revenueAccountId());
            BigDecimal lineNet = Money.of(l.netAmount());
            d.addLine(n++, l.revenueAccountId(), l.costCenterId(), l.description().trim(), lineNet);
            net = net.add(lineNet);
        }
        d.setTotals(net, vat == null ? BigDecimal.ZERO : TaxCodeService.compute(net, vat.rate()));
    }

    /** A credit memo may not exceed what is still open and not already committed to recorded collections. */
    private void checkCreditMemoFits(BillingDocument memo, BillingDocument invoice) {
        BigDecimal available = invoice.openBalance().subtract(pendingCollections(invoice.getId()));
        if (memo.getGrossAmount().compareTo(available) > 0) {
            throw rule("The credit memo (" + memo.getGrossAmount().toPlainString() + ") exceeds the invoice's available open "
                    + "balance (" + Money.of(available).toPlainString() + ").");
        }
    }

    /** Amounts of recorded (not yet posted) collections allocated to the document. */
    BigDecimal pendingCollections(UUID documentId) {
        return Money.of(jdbc.queryForObject("SELECT coalesce(sum(a.amount), 0) FROM receipt_allocations a JOIN receipts r "
                + "ON r.id = a.receipt_id WHERE a.document_id = ? AND r.status = 'RECORDED' AND a.posted_entry_id IS NULL",
                BigDecimal.class, documentId));
    }

    private List<LineData> ledgerLines(BillingDocument d, Customer c) {
        List<LineData> lines = new ArrayList<>();
        Set<UUID> customerSlAccounts = customerSubsidiaryAccounts(d);
        lines.add(new LineData(mappings.require("AR_TRADE_RECEIVABLE"), null, c.getSubsidiaryAccountId(),
                "Receivable from " + c.getName(), d.getGrossAmount(), Money.ZERO));
        for (BillingDocumentLine l : d.getLines()) {
            UUID sl = customerSlAccounts.contains(l.getRevenueAccountId()) ? c.getSubsidiaryAccountId() : null;
            lines.add(new LineData(l.getRevenueAccountId(), l.getCostCenterId(), sl, l.getDescription(), Money.ZERO,
                    l.getNetAmount()));
        }
        if (d.getVatAmount().signum() > 0) {
            lines.add(new LineData(taxCodes.get(d.getVatCodeId()).accountId(), null, null, "Output VAT", Money.ZERO,
                    d.getVatAmount()));
        }
        if ("CREDIT_MEMO".equals(d.getKind())) {
            return lines.stream().map(LineData::mirrored).toList();
        }
        return lines;
    }

    /** Revenue accounts on the document that carry a customer sub-ledger dimension. */
    private Set<UUID> customerSubsidiaryAccounts(BillingDocument d) {
        Set<UUID> ids = new HashSet<>();
        for (BillingDocumentLine l : d.getLines()) {
            String type = jdbc.queryForObject("SELECT subsidiary_type FROM chart_of_accounts WHERE id = ?", String.class,
                    l.getRevenueAccountId());
            if ("CUSTOMER".equals(type)) {
                ids.add(l.getRevenueAccountId());
            }
        }
        return ids;
    }

    private void validateForLedger(BillingDocument d) {
        Customer c = customers.load(d.getCustomerId());
        validator.validate(VoucherValidator.Level.FULL, d.getId(), VoucherType.SUBLEDGER, d.getDocumentDate(),
                calendar.period(d.getPeriodId()), ledgerLines(d, c));
    }

    /** Credit-limit and overdue warnings shown to approvers (informational; the credit policy is clarification C-27). */
    private List<String> warnings(BillingDocument d, Customer c) {
        List<String> w = new ArrayList<>();
        if (!d.isReceivable() || "POSTED".equals(d.getStatus())) {
            return w;
        }
        BigDecimal open = customers.openBalance(c.getId());
        if (c.getCreditLimit() != null && open.add(d.getGrossAmount()).compareTo(c.getCreditLimit()) > 0) {
            w.add("Posting this brings " + c.getCode() + " to " + Money.of(open.add(d.getGrossAmount())).toPlainString()
                    + ", above the credit limit of " + c.getCreditLimit().toPlainString() + ".");
        }
        BigDecimal overdue = Money.of(jdbc.queryForObject("SELECT coalesce(sum(gross_amount - settled_amount), 0) FROM "
                + "billing_documents WHERE customer_id = ? AND status = 'POSTED' AND kind <> 'CREDIT_MEMO' AND due_date < ?",
                BigDecimal.class, c.getId(), java.sql.Date.valueOf(today())));
        if (overdue.signum() > 0) {
            w.add(c.getCode() + " has overdue balances of " + overdue.toPlainString() + ".");
        }
        return w;
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(java.time.ZoneId.of("Asia/Manila")));
    }

    static String workflowType(BillingDocument d) {
        return "INVOICE".equals(d.getKind()) ? "AR_INVOICE" : "AR_MEMO";
    }

    private static String prefix(String kind) {
        return switch (kind) {
            case "INVOICE" -> "BI";
            case "CREDIT_MEMO" -> "CM";
            default -> "DM";
        };
    }

    static String label(String kind) {
        return switch (kind) {
            case "INVOICE" -> "Billing invoice";
            case "CREDIT_MEMO" -> "Credit memo";
            default -> "Debit memo";
        };
    }

    BillingDocument load(UUID id) {
        return billings.findById(id).orElseThrow(() -> ApiException.notFound("Billing document"));
    }

    private BillingDocument lockVersioned(UUID id, long version) {
        BillingDocument d = billings.findByIdForUpdate(id).orElseThrow(() -> ApiException.notFound("Billing document"));
        if (d.getVersion() != version) {
            throw ApiException.staleVersion();
        }
        return d;
    }

    private static void requirePreparer(BillingDocument d) {
        CurrentActor.requirePermission("AR_BILLING_PREPARE");
        if (!CurrentActor.userId().equals(d.getPreparedById())) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Only the preparer can change this document.");
        }
    }

    BillingSummary summary(BillingDocument d) {
        Customer c = customers.load(d.getCustomerId());
        return new BillingSummary(d.getId(), d.getKind(), d.getDocumentNumber(), c.getCode(), c.getName(), d.getDocumentDate(),
                d.getDueDate(), d.getBillingPeriod(), d.getStatus(), d.getGrossAmount(), d.getSettledAmount(),
                "POSTED".equals(d.getStatus()) ? d.openBalance() : Money.ZERO, d.getPreparedBy(), d.getUpdatedAt(),
                d.getVersion());
    }

    List<BillingLineView> lineViews(BillingDocument d) {
        return d.getLines().stream().map(l -> jdbc.queryForObject("""
                SELECT a.code, a.name, cc.code AS cc FROM chart_of_accounts a LEFT JOIN cost_centers cc ON cc.id = ?
                WHERE a.id = ?""", (rs, i) -> new BillingLineView(l.getLineNo(), l.getRevenueAccountId(), rs.getString("code"),
                rs.getString("name"), l.getCostCenterId(), rs.getString("cc"), l.getDescription(), l.getNetAmount()),
                l.getCostCenterId(), l.getRevenueAccountId())).toList();
    }

    BillingView view(BillingDocument d) {
        Customer c = customers.load(d.getCustomerId());
        String entryNumber = d.getPostedEntryId() == null ? null : jdbc.queryForObject(
                "SELECT entry_number FROM journal_entries WHERE id = ?", String.class, d.getPostedEntryId());
        String contractNumber = d.getContractId() == null ? null : customers.contract(d.getContractId()).contractNumber();
        String relatedNumber = d.getRelatedInvoiceId() == null ? null : load(d.getRelatedInvoiceId()).getDocumentNumber();
        List<String> actions = new ArrayList<>();
        boolean preparer = CurrentActor.userId().equals(d.getPreparedById());
        String wf = workflowType(d);
        if (d.isEditable() && preparer && CurrentActor.hasPermission("AR_BILLING_PREPARE")) {
            if (d.getContractId() == null) {
                actions.add("EDIT");
            }
            actions.addAll(List.of("SUBMIT", "CANCEL"));
            if (CurrentActor.hasPermission("DOCUMENT_UPLOAD")) {
                actions.add("UPLOAD");
            }
        }
        if ("SUBMITTED".equals(d.getStatus()) && workflow.canAct(wf, d.getId(), d.getGrossAmount(),
                toInt(d.getCurrentStepOrder()), d.getPreparedById())) {
            actions.addAll(List.of("APPROVE", "RETURN", "REJECT"));
        }
        if ("APPROVED".equals(d.getStatus()) && !preparer && CurrentActor.hasPermission("JOURNAL_POST")) {
            actions.addAll(List.of("POST", "RETURN"));
        }
        String vatCode = d.getVatCodeId() == null ? null : taxCodes.get(d.getVatCodeId()).code();
        return new BillingView(d.getId(), d.getKind(), d.getDocumentNumber(), c.getId(), c.getCode(), c.getName(), c.getTin(),
                d.getContractId(), contractNumber, d.getBillingPeriod(), d.getRelatedInvoiceId(), relatedNumber,
                d.getDocumentDate(), d.getDueDate(), calendar.period(d.getPeriodId()).getName(), d.getDescription(),
                d.getStatus(), workflow.currentStepCode(wf, d.getGrossAmount(), toInt(d.getCurrentStepOrder())),
                d.getVatCodeId(), vatCode, d.getNetAmount(), d.getVatAmount(), d.getGrossAmount(), d.getSettledAmount(),
                "POSTED".equals(d.getStatus()) ? d.openBalance() : Money.ZERO, d.getSource(), d.getPreparedBy(),
                d.getPostedEntryId(), entryNumber, lineViews(d), workflow.history(wf, d.getId()), documents.list(OWNER, d.getId()),
                warnings(d, c), actions, d.getVersion());
    }

    static Integer toInt(Short s) {
        return s == null ? null : s.intValue();
    }

    static ApiException state(String message) {
        return new ApiException(ErrorCode.INVALID_STATE, message);
    }

    static ApiException rule(String message) {
        return new ApiException(ErrorCode.ACCOUNTING_RULE_VIOLATION, message, Map.of("violations", List.of(message)));
    }
}
