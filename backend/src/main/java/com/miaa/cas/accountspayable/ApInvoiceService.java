package com.miaa.cas.accountspayable;

import com.miaa.cas.accountspayable.ApDtos.InvoiceLineRequest;
import com.miaa.cas.accountspayable.ApDtos.InvoiceLineView;
import com.miaa.cas.accountspayable.ApDtos.InvoiceRequest;
import com.miaa.cas.accountspayable.ApDtos.InvoiceSummary;
import com.miaa.cas.accountspayable.ApDtos.InvoiceView;
import com.miaa.cas.accountspayable.domain.Supplier;
import com.miaa.cas.accountspayable.domain.SupplierInvoice;
import com.miaa.cas.accountspayable.domain.SupplierInvoiceLine;
import com.miaa.cas.accountspayable.domain.SupplierInvoiceRepository;
import com.miaa.cas.approvals.DocumentWorkflow;
import com.miaa.cas.audit.AuditService;
import com.miaa.cas.budgeting.BudgetControl;
import com.miaa.cas.budgeting.BudgetDtos.Demand;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.money.Money;
import com.miaa.cas.common.web.PageRequests;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.documents.DocumentOwnerPolicy;
import com.miaa.cas.documents.DocumentService;
import com.miaa.cas.generalledger.AccountMappingService;
import com.miaa.cas.generalledger.AccountPurpose;
import com.miaa.cas.generalledger.FiscalCalendarService;
import com.miaa.cas.generalledger.domain.AccountingPeriod;
import com.miaa.cas.generalledger.domain.PeriodStatus;
import com.miaa.cas.journal.PostingService;
import com.miaa.cas.journal.VoucherValidator;
import com.miaa.cas.journal.domain.LineData;
import com.miaa.cas.journal.domain.VoucherType;
import com.miaa.cas.security.CurrentActor;
import com.miaa.cas.tax.TaxCodeService;
import com.miaa.cas.tax.TaxCodeService.TaxCode;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Supplier invoices: registration with tax computation and duplicate detection, approval (never by
 * the preparer), budget reservation from submission until posting, and posting through the ledger as
 * Dr expense/asset lines (+ input VAT) / Cr withholding tax payable / Cr accounts payable (supplier SL).
 */
@Service
public class ApInvoiceService implements DocumentOwnerPolicy {

    public static final String DOCUMENT = "AP_INVOICE";
    public static final String SOURCE = "SL_AP_INVOICE";
    private static final Map<String, String> SORTS = Map.of("dueDate", "dueDate", "invoiceDate", "invoiceDate",
            "updatedAt", "updatedAt", "amount", "payableAmount");

    private final SupplierInvoiceRepository invoices;
    private final SupplierService suppliers;
    private final TaxCodeService taxCodes;
    private final FiscalCalendarService calendar;
    private final VoucherValidator validator;
    private final DocumentWorkflow workflow;
    private final BudgetControl budget;
    private final PostingService posting;
    private final AccountMappingService mappings;
    private final DocumentService documents;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ApInvoiceService(SupplierInvoiceRepository invoices, SupplierService suppliers, TaxCodeService taxCodes,
            FiscalCalendarService calendar, VoucherValidator validator, DocumentWorkflow workflow, BudgetControl budget,
            PostingService posting, AccountMappingService mappings, DocumentService documents,
            AuditService audit, JdbcTemplate jdbc, Clock clock) {
        this.invoices = invoices;
        this.suppliers = suppliers;
        this.taxCodes = taxCodes;
        this.calendar = calendar;
        this.validator = validator;
        this.workflow = workflow;
        this.budget = budget;
        this.posting = posting;
        this.mappings = mappings;
        this.documents = documents;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public PageResponse<InvoiceSummary> list(String status, UUID supplierId, String q, int page, int size, String sort) {
        var pageable = PageRequests.of(page, size, sort, SORTS, "-updatedAt");
        Specification<SupplierInvoice> spec = (root, query, cb) -> cb.conjunction();
        if (status != null && !status.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status.trim().toUpperCase()));
        }
        if (supplierId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("supplierId"), supplierId));
        }
        if (q != null && !q.isBlank()) {
            String p = "%" + q.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            spec = spec.and((root, query, cb) -> cb.or(cb.like(cb.lower(root.get("invoiceNumber")), p, '\\'),
                    cb.like(cb.lower(root.get("description")), p, '\\')));
        }
        return PageResponse.of(invoices.findAll(spec, pageable), this::summary);
    }

    @Transactional(readOnly = true)
    public InvoiceView get(UUID id) {
        return view(load(id));
    }

    // ------------------------------------------------------------------ commands

    @Transactional
    public InvoiceView create(InvoiceRequest req) {
        CurrentActor.requirePermission("AP_INVOICE_PREPARE");
        Supplier s = activeSupplier(req.supplierId());
        SupplierInvoice inv = new SupplierInvoice(UUID.randomUUID(), s.getId(), CurrentActor.userId(), CurrentActor.username(),
                Instant.now(clock));
        apply(inv, s, req);
        rejectDuplicate(inv);
        invoices.saveAndFlush(inv);
        audit.record("AP_INVOICE_CREATED", DOCUMENT, inv.getId(), Map.of("supplier", s.getCode(),
                "invoiceNumber", inv.getInvoiceNumber(), "payable", inv.getPayableAmount().toPlainString()));
        return view(inv);
    }

    @Transactional
    public InvoiceView update(UUID id, long version, InvoiceRequest req) {
        SupplierInvoice inv = lockVersioned(id, version);
        requirePreparer(inv);
        if (!inv.isEditable()) {
            throw state("Only draft or returned invoices can be edited.");
        }
        if (!inv.getSupplierId().equals(req.supplierId())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The supplier of a registered invoice cannot change; cancel it instead.");
        }
        Supplier s = activeSupplier(req.supplierId());
        inv.clearLines();
        invoices.flush();
        apply(inv, s, req);
        rejectDuplicate(inv);
        inv.moveTo("DRAFT", null, Instant.now(clock), CurrentActor.username());
        invoices.flush();
        audit.record("AP_INVOICE_UPDATED", DOCUMENT, id, Map.of("payable", inv.getPayableAmount().toPlainString()));
        return view(inv);
    }

    @Transactional
    public InvoiceView submit(UUID id, long version, String comment) {
        SupplierInvoice inv = lockVersioned(id, version);
        requirePreparer(inv);
        if (!inv.isEditable()) {
            throw state("Only draft or returned invoices can be submitted.");
        }
        validateForLedger(inv);
        if (documents.activeCount(DOCUMENT, id) == 0) {
            throw rule("Attach the supplier invoice and supporting documents before submitting.");
        }
        budget.reserve(DOCUMENT, id, calendar.period(inv.getPeriodId()).getFiscalYearId(), demands(inv));
        DocumentWorkflow.Step first = workflow.submit(DOCUMENT, id, inv.getPayableAmount(), comment);
        inv.moveTo(first == null ? "APPROVED" : "SUBMITTED", first == null ? null : (short) first.order(), Instant.now(clock),
                CurrentActor.username());
        invoices.flush();
        audit.record("AP_INVOICE_SUBMITTED", DOCUMENT, id, Map.of("payable", inv.getPayableAmount().toPlainString()));
        return view(inv);
    }

    @Transactional
    public InvoiceView approve(UUID id, long version, String comment) {
        SupplierInvoice inv = lockVersioned(id, version);
        if (!"SUBMITTED".equals(inv.getStatus())) {
            throw state("The invoice is not awaiting approval.");
        }
        DocumentWorkflow.Step next = workflow.approve(DOCUMENT, id, inv.getPayableAmount(), toInt(inv.getCurrentStepOrder()),
                inv.getPreparedById(), comment);
        inv.moveTo(next == null ? "APPROVED" : "SUBMITTED", next == null ? null : (short) next.order(), Instant.now(clock),
                CurrentActor.username());
        invoices.flush();
        audit.record("AP_INVOICE_APPROVED", DOCUMENT, id, Map.of("final", next == null));
        return view(inv);
    }

    @Transactional
    public InvoiceView returnOrReject(UUID id, long version, String comment, boolean reject) {
        SupplierInvoice inv = lockVersioned(id, version);
        if ("SUBMITTED".equals(inv.getStatus())) {
            workflow.returnOrReject(DOCUMENT, id, inv.getPayableAmount(), toInt(inv.getCurrentStepOrder()),
                    inv.getPreparedById(), comment, reject);
        } else if ("APPROVED".equals(inv.getStatus())) {
            // Accounting may send an approved invoice back instead of posting it.
            CurrentActor.requirePermission("JOURNAL_POST");
            if (comment == null || comment.trim().length() < 5) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "A comment of at least 5 characters is required.");
            }
            workflow.record(DOCUMENT, id, reject ? "REJECT" : "RETURN", comment.trim());
        } else {
            throw state("Only invoices awaiting approval or posting can be " + (reject ? "rejected." : "returned."));
        }
        budget.release(DOCUMENT, id);
        inv.moveTo(reject ? "REJECTED" : "RETURNED", null, Instant.now(clock), CurrentActor.username());
        invoices.flush();
        audit.record(reject ? "AP_INVOICE_REJECTED" : "AP_INVOICE_RETURNED", DOCUMENT, id, Map.of("comment", comment.trim()));
        return view(inv);
    }

    @Transactional
    public InvoiceView cancel(UUID id, long version, String comment) {
        SupplierInvoice inv = lockVersioned(id, version);
        requirePreparer(inv);
        if (!inv.isEditable()) {
            throw state("Only draft or returned invoices can be cancelled.");
        }
        workflow.record(DOCUMENT, id, "CANCEL", comment);
        inv.moveTo("CANCELLED", null, Instant.now(clock), CurrentActor.username());
        invoices.flush();
        audit.record("AP_INVOICE_CANCELLED", DOCUMENT, id, Map.of());
        return view(inv);
    }

    /** Recognises the payable in the general ledger and converts the budget reservation into actual spending. */
    @Transactional
    public InvoiceView post(UUID id, long version) {
        SupplierInvoice inv = lockVersioned(id, version);
        if (!"APPROVED".equals(inv.getStatus())) {
            throw state("POSTED".equals(inv.getStatus()) ? "The invoice has already been posted." : "Only approved invoices can be posted.");
        }
        Supplier s = suppliers.load(inv.getSupplierId());
        PostingService.PostedEntry entry = posting.postSubledger(new PostingService.SubledgerEntry(SOURCE, id.toString(),
                inv.getAccountingDate(), "AP " + s.getCode() + " invoice " + inv.getInvoiceNumber() + ": " + inv.getDescription(),
                ledgerLines(inv, s), inv.getPreparedById(), inv.getPreparedBy(), true));
        budget.consume(DOCUMENT, id);
        workflow.record(DOCUMENT, id, "POST", entry.entryNumber());
        inv.markPosted(entry.entryId(), Instant.now(clock), CurrentActor.username());
        invoices.flush();
        audit.record("AP_INVOICE_POSTED", DOCUMENT, id, Map.of("entryNumber", entry.entryNumber(),
                "payable", inv.getPayableAmount().toPlainString()));
        return view(inv);
    }

    // ------------------------------------------------------------------ document policy

    @Override
    public String ownerType() {
        return DOCUMENT;
    }

    @Override
    public void assertCanModify(UUID ownerId) {
        SupplierInvoice inv = load(ownerId);
        CurrentActor.requirePermission("DOCUMENT_UPLOAD");
        if (!CurrentActor.userId().equals(inv.getPreparedById())) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Only the preparer can change this invoice's documents.");
        }
        if (!inv.isEditable()) {
            throw state("Documents can only change while the invoice is a draft or returned.");
        }
    }

    @Override
    public void assertCanView(UUID ownerId) {
        load(ownerId);
        if (!CurrentActor.hasPermission("AP_VIEW")) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "You do not have permission to view payables.");
        }
    }

    // ------------------------------------------------------------------ helpers

    private void apply(SupplierInvoice inv, Supplier s, InvoiceRequest req) {
        if (req.dueDate().isBefore(req.invoiceDate())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The due date cannot precede the invoice date.");
        }
        AccountingPeriod period = calendar.resolve(req.accountingDate(), false);
        if (period.getStatus() == PeriodStatus.CLOSED) {
            throw rule("Period " + period.getName() + " is closed.");
        }
        TaxCode vat = null;
        if (req.vatCodeId() != null) {
            if (!s.isVatRegistered()) {
                throw rule("Supplier " + s.getCode() + " is not VAT-registered; input VAT cannot be claimed.");
            }
            vat = taxCodes.requireActive(req.vatCodeId(), "VAT_INPUT");
        }
        inv.setHeader(req.invoiceNumber().trim(), req.invoiceDate(), req.dueDate(), req.accountingDate(), period.getId(),
                req.description().trim(), req.vatCodeId());
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal ewt = BigDecimal.ZERO;
        int n = 1;
        for (InvoiceLineRequest l : req.lines()) {
            UUID ewtCode = l.ewtCodeId() != null ? l.ewtCodeId() : s.getDefaultEwtCodeId();
            BigDecimal lineNet = Money.of(l.netAmount());
            BigDecimal lineEwt = Money.ZERO;
            if (ewtCode != null) {
                lineEwt = TaxCodeService.compute(lineNet, taxCodes.requireActive(ewtCode, "EWT").rate());
            }
            AccountPurpose.require(jdbc, l.accountId(), AccountPurpose.SUPPLIER_INVOICE_LINE, "Line " + n + " account");
            inv.addLine(n++, l.accountId(), l.costCenterId(), SupplierService.blank(l.description()), lineNet, ewtCode, lineEwt);
            net = net.add(lineNet);
            ewt = ewt.add(lineEwt);
        }
        BigDecimal vatAmount = vat == null ? BigDecimal.ZERO : TaxCodeService.compute(net, vat.rate());
        inv.setTotals(net, vatAmount, ewt);
        if (inv.getPayableAmount().signum() <= 0) {
            throw rule("The amount payable to the supplier must be positive.");
        }
    }

    private void rejectDuplicate(SupplierInvoice inv) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM supplier_invoices WHERE supplier_id = ? AND lower(invoice_number) = "
                + "lower(?) AND id <> ? AND status NOT IN ('REJECTED', 'CANCELLED')", Integer.class, inv.getSupplierId(),
                inv.getInvoiceNumber(), inv.getId());
        if (n != null && n > 0) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "Invoice " + inv.getInvoiceNumber()
                    + " from this supplier is already registered.");
        }
    }

    /** Same supplier, same payable amount, invoice date within 7 days: shown as a warning to approvers. */
    private List<String> possibleDuplicates(SupplierInvoice inv) {
        if (inv.getInvoiceDate() == null) {
            return List.of();
        }
        return jdbc.queryForList("""
                SELECT invoice_number || ' (' || invoice_date || ', ' || status || ')' FROM supplier_invoices
                WHERE supplier_id = ? AND id <> ? AND payable_amount = ? AND status NOT IN ('REJECTED', 'CANCELLED')
                  AND invoice_date BETWEEN ? AND ?""", String.class, inv.getSupplierId(), inv.getId(), inv.getPayableAmount(),
                Date.valueOf(inv.getInvoiceDate().minusDays(7)), Date.valueOf(inv.getInvoiceDate().plusDays(7)));
    }

    private List<LineData> ledgerLines(SupplierInvoice inv, Supplier s) {
        List<LineData> lines = new ArrayList<>();
        for (SupplierInvoiceLine l : inv.getLines()) {
            lines.add(new LineData(l.getAccountId(), l.getCostCenterId(), null, l.getDescription(), l.getNetAmount(), Money.ZERO));
        }
        if (inv.getVatAmount().signum() > 0) {
            lines.add(new LineData(taxCodes.get(inv.getVatCodeId()).accountId(), null, null, "Input VAT", inv.getVatAmount(),
                    Money.ZERO));
        }
        Map<UUID, BigDecimal> ewtByAccount = new LinkedHashMap<>();
        for (SupplierInvoiceLine l : inv.getLines()) {
            if (l.getEwtCodeId() != null && l.getEwtAmount().signum() > 0) {
                ewtByAccount.merge(taxCodes.get(l.getEwtCodeId()).accountId(), l.getEwtAmount(), BigDecimal::add);
            }
        }
        ewtByAccount.forEach((account, amt) -> lines.add(new LineData(account, null, null, "Expanded withholding tax",
                Money.ZERO, Money.of(amt))));
        lines.add(new LineData(mappings.require("AP_TRADE_PAYABLE"), null, s.getSubsidiaryAccountId(),
                "Payable to " + s.getName(), Money.ZERO, inv.getPayableAmount()));
        return lines;
    }

    private void validateForLedger(SupplierInvoice inv) {
        Supplier s = suppliers.load(inv.getSupplierId());
        validator.validate(VoucherValidator.Level.FULL, inv.getId(), VoucherType.SUBLEDGER, inv.getAccountingDate(),
                calendar.period(inv.getPeriodId()), ledgerLines(inv, s));
    }

    private List<Demand> demands(SupplierInvoice inv) {
        return inv.getLines().stream().map(l -> new Demand(l.getAccountId(), l.getCostCenterId(), l.getNetAmount())).toList();
    }

    private Supplier activeSupplier(UUID id) {
        Supplier s = suppliers.load(id);
        if (!s.isActive()) {
            throw rule("Supplier " + s.getCode() + " is inactive.");
        }
        return s;
    }

    SupplierInvoice load(UUID id) {
        return invoices.findById(id).orElseThrow(() -> ApiException.notFound("Supplier invoice"));
    }

    private SupplierInvoice lockVersioned(UUID id, long version) {
        SupplierInvoice inv = invoices.findByIdForUpdate(id).orElseThrow(() -> ApiException.notFound("Supplier invoice"));
        if (inv.getVersion() != version) {
            throw ApiException.staleVersion();
        }
        return inv;
    }

    private static void requirePreparer(SupplierInvoice inv) {
        CurrentActor.requirePermission("AP_INVOICE_PREPARE");
        if (!CurrentActor.userId().equals(inv.getPreparedById())) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Only the preparer can change this invoice.");
        }
    }

    private InvoiceSummary summary(SupplierInvoice inv) {
        Supplier s = suppliers.load(inv.getSupplierId());
        return new InvoiceSummary(inv.getId(), s.getCode(), s.getName(), inv.getInvoiceNumber(), inv.getInvoiceDate(),
                inv.getDueDate(), inv.getStatus(), inv.getPayableAmount(), inv.getPaidAmount(), inv.openBalance(),
                inv.getPreparedBy(), inv.getUpdatedAt(), inv.getVersion());
    }

    private InvoiceView view(SupplierInvoice inv) {
        Supplier s = suppliers.load(inv.getSupplierId());
        List<InvoiceLineView> lines = inv.getLines().stream().map(l -> jdbc.queryForObject("""
                SELECT a.code, a.name, cc.code AS cc, t.code AS ewt FROM chart_of_accounts a
                LEFT JOIN cost_centers cc ON cc.id = ? LEFT JOIN tax_codes t ON t.id = ? WHERE a.id = ?""",
                (rs, i) -> new InvoiceLineView(l.getLineNo(), l.getAccountId(), rs.getString("code"), rs.getString("name"),
                        l.getCostCenterId(), rs.getString("cc"), l.getDescription(), l.getNetAmount(), l.getEwtCodeId(),
                        rs.getString("ewt"), l.getEwtAmount()), l.getCostCenterId(), l.getEwtCodeId(), l.getAccountId())).toList();
        String entryNumber = inv.getPostedEntryId() == null ? null : jdbc.queryForObject(
                "SELECT entry_number FROM journal_entries WHERE id = ?", String.class, inv.getPostedEntryId());
        List<String> actions = new ArrayList<>();
        boolean preparer = CurrentActor.userId().equals(inv.getPreparedById());
        if (inv.isEditable() && preparer && CurrentActor.hasPermission("AP_INVOICE_PREPARE")) {
            actions.addAll(List.of("EDIT", "SUBMIT", "CANCEL"));
            if (CurrentActor.hasPermission("DOCUMENT_UPLOAD")) {
                actions.add("UPLOAD");
            }
        }
        if ("SUBMITTED".equals(inv.getStatus()) && workflow.canAct(DOCUMENT, inv.getId(), inv.getPayableAmount(),
                toInt(inv.getCurrentStepOrder()), inv.getPreparedById())) {
            actions.addAll(List.of("APPROVE", "RETURN", "REJECT"));
        }
        if ("APPROVED".equals(inv.getStatus()) && !preparer && CurrentActor.hasPermission("JOURNAL_POST")) {
            actions.addAll(List.of("POST", "RETURN"));
        }
        String vatCode = inv.getVatCodeId() == null ? null : taxCodes.get(inv.getVatCodeId()).code();
        return new InvoiceView(inv.getId(), s.getId(), s.getCode(), s.getName(), s.getTin(), inv.getInvoiceNumber(),
                inv.getInvoiceDate(), inv.getDueDate(), inv.getAccountingDate(), calendar.period(inv.getPeriodId()).getName(),
                inv.getDescription(), inv.getStatus(), workflow.currentStepCode(DOCUMENT, inv.getPayableAmount(),
                toInt(inv.getCurrentStepOrder())), inv.getVatCodeId(), vatCode, inv.getNetAmount(), inv.getVatAmount(),
                inv.getEwtAmount(), inv.getPayableAmount(), inv.getPaidAmount(), inv.openBalance(), inv.getPreparedBy(),
                inv.getPostedEntryId(), entryNumber, lines, workflow.history(DOCUMENT, inv.getId()),
                documents.list(DOCUMENT, inv.getId()), possibleDuplicates(inv), actions, inv.getVersion());
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
