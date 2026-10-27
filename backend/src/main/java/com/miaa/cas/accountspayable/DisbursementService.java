package com.miaa.cas.accountspayable;

import com.miaa.cas.accountspayable.ApDtos.AllocationRequest;
import com.miaa.cas.accountspayable.ApDtos.DvBudgetLine;
import com.miaa.cas.accountspayable.ApDtos.DvCheck;
import com.miaa.cas.accountspayable.ApDtos.DvLineView;
import com.miaa.cas.accountspayable.ApDtos.DvSummary;
import com.miaa.cas.accountspayable.ApDtos.DvView;
import com.miaa.cas.accountspayable.ApDtos.PaymentInstructionRequest;
import com.miaa.cas.accountspayable.domain.DisbursementVoucher;
import com.miaa.cas.accountspayable.domain.DisbursementVoucherLine;
import com.miaa.cas.accountspayable.domain.DisbursementVoucherRepository;
import com.miaa.cas.accountspayable.domain.Supplier;
import com.miaa.cas.accountspayable.domain.SupplierInvoice;
import com.miaa.cas.accountspayable.domain.SupplierInvoiceRepository;
import com.miaa.cas.approvals.DocumentWorkflow;
import com.miaa.cas.audit.AuditService;
import com.miaa.cas.budgeting.BudgetControl;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.money.Money;
import com.miaa.cas.common.web.PageRequests;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.documents.DocumentOwnerPolicy;
import com.miaa.cas.documents.DocumentService;
import com.miaa.cas.generalledger.AccountMappingService;
import com.miaa.cas.generalledger.FiscalCalendarService;
import com.miaa.cas.journal.NumberingService;
import com.miaa.cas.journal.PostingService;
import com.miaa.cas.journal.domain.LineData;
import com.miaa.cas.security.CurrentActor;
import com.miaa.cas.treasury.BankAccountService;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Disbursement Vouchers paying posted supplier invoices. Life cycle (C-09): DRAFT -> SUBMITTED
 * (certify, approve) -> APPROVED (DV number assigned) -> PAYMENT_INSTRUCTED (check issued / ADA
 * reference) -> PAID (release confirmed) -> POSTED (Dr AP / Cr cash in bank). A check may be voided
 * before release, returning the DV to APPROVED. Invoices are locked when allocated so no invoice can
 * be paid beyond its open balance, even by concurrent vouchers.
 */
@Service
public class DisbursementService implements DocumentOwnerPolicy {

    public static final String DOCUMENT = "DISBURSEMENT_VOUCHER";
    public static final String SOURCE = "SL_DV_PAYMENT";
    private static final Set<String> LIVE = Set.of("DRAFT", "SUBMITTED", "APPROVED", "PAYMENT_INSTRUCTED", "PAID", "RETURNED");
    private static final Map<String, String> SORTS = Map.of("updatedAt", "updatedAt", "amount", "amount", "dvNumber", "dvNumber");

    private final DisbursementVoucherRepository vouchers;
    private final SupplierInvoiceRepository invoices;
    private final SupplierService suppliers;
    private final BankAccountService banks;
    private final DocumentWorkflow workflow;
    private final NumberingService numbering;
    private final PostingService posting;
    private final AccountMappingService mappings;
    private final DocumentService documents;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final BudgetControl budget;
    private final FiscalCalendarService calendar;

    public DisbursementService(DisbursementVoucherRepository vouchers, SupplierInvoiceRepository invoices,
            SupplierService suppliers, BankAccountService banks, DocumentWorkflow workflow, NumberingService numbering,
            PostingService posting, AccountMappingService mappings, DocumentService documents,
            AuditService audit, JdbcTemplate jdbc, Clock clock, BudgetControl budget, FiscalCalendarService calendar) {
        this.budget = budget;
        this.calendar = calendar;
        this.vouchers = vouchers;
        this.invoices = invoices;
        this.suppliers = suppliers;
        this.banks = banks;
        this.workflow = workflow;
        this.numbering = numbering;
        this.posting = posting;
        this.mappings = mappings;
        this.documents = documents;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<DvSummary> list(String status, int page, int size, String sort) {
        var pageable = PageRequests.of(page, size, sort, SORTS, "-updatedAt");
        Specification<DisbursementVoucher> spec = (root, query, cb) -> cb.conjunction();
        if (status != null && !status.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status.trim().toUpperCase()));
        }
        return PageResponse.of(vouchers.findAll(spec, pageable), this::summary);
    }

    @Transactional(readOnly = true)
    public DvView get(UUID id) {
        return view(load(id));
    }

    @Transactional
    public DvView create(UUID supplierId, String particulars, List<AllocationRequest> allocations) {
        CurrentActor.requirePermission("DISBURSEMENT_PREPARE");
        Supplier s = suppliers.load(supplierId);
        if (!s.isActive()) {
            throw ApInvoiceService.rule("Supplier " + s.getCode() + " is inactive.");
        }
        DisbursementVoucher dv = new DisbursementVoucher(UUID.randomUUID(), supplierId, particulars.trim(),
                CurrentActor.userId(), CurrentActor.username(), Instant.now(clock));
        allocate(dv, allocations);
        vouchers.saveAndFlush(dv);
        audit.record("DV_CREATED", DOCUMENT, dv.getId(), Map.of("supplier", s.getCode(), "amount", dv.getAmount().toPlainString()));
        return view(dv);
    }

    @Transactional
    public DvView update(UUID id, long version, String particulars, List<AllocationRequest> allocations) {
        DisbursementVoucher dv = lockVersioned(id, version);
        requirePreparer(dv);
        if (!dv.isEditable()) {
            throw ApInvoiceService.state("Only draft or returned vouchers can be edited.");
        }
        dv.setParticulars(particulars.trim());
        dv.clearLines();
        vouchers.flush();
        allocate(dv, allocations);
        dv.moveTo("DRAFT", null, Instant.now(clock), CurrentActor.username());
        vouchers.flush();
        audit.record("DV_UPDATED", DOCUMENT, id, Map.of("amount", dv.getAmount().toPlainString()));
        return view(dv);
    }

    @Transactional
    public DvView submit(UUID id, long version, String comment) {
        DisbursementVoucher dv = lockVersioned(id, version);
        requirePreparer(dv);
        if (!dv.isEditable()) {
            throw ApInvoiceService.state("Only draft or returned vouchers can be submitted.");
        }
        recheckAllocations(dv);
        if (documents.activeCount(DOCUMENT, id) == 0) {
            throw ApInvoiceService.rule("Attach the supporting documents (e.g. obligation request, receiving report) before submitting.");
        }
        DocumentWorkflow.Step first = workflow.submit(DOCUMENT, id, dv.getAmount(), comment);
        Instant now = Instant.now(clock);
        if (first == null) {
            approveFinal(dv, now);
        } else {
            dv.moveTo("SUBMITTED", (short) first.order(), now, CurrentActor.username());
        }
        vouchers.flush();
        audit.record("DV_SUBMITTED", DOCUMENT, id, Map.of("amount", dv.getAmount().toPlainString()));
        return view(dv);
    }

    @Transactional
    public DvView approve(UUID id, long version, String comment) {
        DisbursementVoucher dv = lockVersioned(id, version);
        if (!"SUBMITTED".equals(dv.getStatus())) {
            throw ApInvoiceService.state("The voucher is not awaiting approval.");
        }
        DocumentWorkflow.Step next = workflow.approve(DOCUMENT, id, dv.getAmount(), ApInvoiceService.toInt(dv.getCurrentStepOrder()),
                dv.getPreparedById(), comment);
        Instant now = Instant.now(clock);
        if (next == null) {
            approveFinal(dv, now);
        } else {
            dv.moveTo("SUBMITTED", (short) next.order(), now, CurrentActor.username());
        }
        vouchers.flush();
        audit.record(next == null ? "DV_APPROVED" : "DV_CERTIFIED", DOCUMENT, id, Map.of("dvNumber", String.valueOf(dv.getDvNumber())));
        return view(dv);
    }

    @Transactional
    public DvView returnOrReject(UUID id, long version, String comment, boolean reject) {
        DisbursementVoucher dv = lockVersioned(id, version);
        if (!"SUBMITTED".equals(dv.getStatus())) {
            throw ApInvoiceService.state("Only vouchers awaiting approval can be " + (reject ? "rejected." : "returned."));
        }
        workflow.returnOrReject(DOCUMENT, id, dv.getAmount(), ApInvoiceService.toInt(dv.getCurrentStepOrder()),
                dv.getPreparedById(), comment, reject);
        dv.moveTo(reject ? "REJECTED" : "RETURNED", null, Instant.now(clock), CurrentActor.username());
        vouchers.flush();
        audit.record(reject ? "DV_REJECTED" : "DV_RETURNED", DOCUMENT, id, Map.of("comment", comment.trim()));
        return view(dv);
    }

    @Transactional
    public DvView cancel(UUID id, long version, String comment) {
        DisbursementVoucher dv = lockVersioned(id, version);
        requirePreparer(dv);
        if (!dv.isEditable()) {
            throw ApInvoiceService.state("Only draft or returned vouchers can be cancelled.");
        }
        workflow.record(DOCUMENT, id, "CANCEL", comment);
        dv.moveTo("CANCELLED", null, Instant.now(clock), CurrentActor.username());
        vouchers.flush();
        audit.record("DV_CANCELLED", DOCUMENT, id, Map.of());
        return view(dv);
    }

    /** Records the payment instruction: a check from the register, or an ADA / bank transfer reference. */
    @Transactional
    public DvView instruct(UUID id, PaymentInstructionRequest req) {
        CurrentActor.requirePermission("PAYMENT_RELEASE");
        DisbursementVoucher dv = lockVersioned(id, req.version());
        if (!"APPROVED".equals(dv.getStatus())) {
            throw ApInvoiceService.state("Only approved vouchers can be paid.");
        }
        sod(dv, "issue payment for");
        var bank = banks.requireActive(req.bankAccountId());
        Instant now = Instant.now(clock);
        if ("CHECK".equals(req.mode())) {
            Boolean used = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM check_register WHERE bank_account_id = ? "
                    + "AND check_number = ?)", Boolean.class, bank.id(), req.reference());
            if (Boolean.TRUE.equals(used)) {
                throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "Check " + req.reference() + " is already in the register for "
                        + bank.code() + ".");
            }
            Supplier s = suppliers.load(dv.getSupplierId());
            jdbc.update("""
                    INSERT INTO check_register (id, bank_account_id, check_number, dv_id, payee, amount, issue_date, status,
                        prepared_by_id, created_at, created_by, updated_at, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'PREPARED', ?, ?, ?, ?, ?)""",
                    UUID.randomUUID(), bank.id(), req.reference(), id, s.getName(), dv.getAmount(),
                    Date.valueOf(req.paymentDate()), CurrentActor.userId(), Timestamp.from(now), CurrentActor.username(),
                    Timestamp.from(now), CurrentActor.username());
        }
        dv.instruct(req.mode(), bank.id(), req.reference(), req.paymentDate(), now, CurrentActor.username());
        workflow.record(DOCUMENT, id, "INSTRUCT", req.mode() + " " + req.reference());
        vouchers.flush();
        audit.record("DV_PAYMENT_INSTRUCTED", DOCUMENT, id, Map.of("mode", req.mode(), "reference", req.reference(),
                "bank", bank.code()));
        return view(dv);
    }

    @Transactional
    public DvView voidPayment(UUID id, long version, String reason) {
        CurrentActor.requirePermission("PAYMENT_RELEASE");
        DisbursementVoucher dv = lockVersioned(id, version);
        if (!"PAYMENT_INSTRUCTED".equals(dv.getStatus())) {
            throw ApInvoiceService.state("Only an issued, unreleased payment can be voided.");
        }
        Instant now = Instant.now(clock);
        jdbc.update("UPDATE check_register SET status = 'VOIDED', void_reason = ?, updated_at = ?, updated_by = ? "
                + "WHERE dv_id = ? AND status IN ('PREPARED', 'ISSUED')", reason.trim(), Timestamp.from(now), CurrentActor.username(), id);
        workflow.record(DOCUMENT, id, "VOID", reason.trim());
        dv.clearInstruction(now, CurrentActor.username());
        vouchers.flush();
        audit.record("DV_PAYMENT_VOIDED", DOCUMENT, id, Map.of("reason", reason.trim()));
        return view(dv);
    }

    /**
     * Approves the prepared check of a DV (TOR Annex A, Treasury): a second person holding CHECK_APPROVE, neither the
     * check's preparer nor the DV's preparer. The check becomes ISSUED and may then be released.
     */
    @Transactional
    public DvView approveCheck(UUID id, long version) {
        CurrentActor.requirePermission("CHECK_APPROVE");
        DisbursementVoucher dv = lockVersioned(id, version);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, check_number, prepared_by_id FROM check_register WHERE dv_id = ? AND status = 'PREPARED' FOR UPDATE", id);
        if (!"PAYMENT_INSTRUCTED".equals(dv.getStatus()) || rows.isEmpty()) {
            throw ApInvoiceService.state("There is no prepared check awaiting approval on this voucher.");
        }
        Map<String, Object> check = rows.get(0);
        if (CurrentActor.userId().equals(check.get("prepared_by_id")) || CurrentActor.userId().equals(dv.getPreparedById())) {
            throw new ApiException(ErrorCode.SEGREGATION_OF_DUTIES,
                    "You prepared this check or its voucher; another person must approve the check.");
        }
        Instant now = Instant.now(clock);
        jdbc.update("UPDATE check_register SET status = 'ISSUED', approved_by_id = ?, approved_by = ?, approved_at = ?, updated_at = ?, "
                + "updated_by = ? WHERE id = ?", CurrentActor.userId(), CurrentActor.username(), Timestamp.from(now), Timestamp.from(now),
                CurrentActor.username(), check.get("id"));
        workflow.record(DOCUMENT, id, "APPROVE_CHECK", "Check " + check.get("check_number"));
        dv.touch(now, CurrentActor.username());
        vouchers.flush();
        audit.record("DV_CHECK_APPROVED", DOCUMENT, id, Map.of("checkNumber", check.get("check_number")));
        return view(dv);
    }

    private String checkStatus(UUID dvId) {
        List<String> s = jdbc.queryForList("SELECT status FROM check_register WHERE dv_id = ? AND status IN ('PREPARED', 'ISSUED', 'RELEASED')",
                String.class, dvId);
        return s.isEmpty() ? null : s.get(0);
    }

    /** Confirms the payment was actually released (check handed over / bank confirmation received). */
    @Transactional
    public DvView confirm(UUID id, long version, LocalDate releasedOn) {
        CurrentActor.requirePermission("PAYMENT_RELEASE");
        DisbursementVoucher dv = lockVersioned(id, version);
        if (!"PAYMENT_INSTRUCTED".equals(dv.getStatus())) {
            throw ApInvoiceService.state("Only instructed payments can be confirmed.");
        }
        sod(dv, "confirm payment of");
        if ("PREPARED".equals(checkStatus(id))) {
            throw ApInvoiceService.state("The check must be approved by a second person before it is released.");
        }
        Instant now = Instant.now(clock);
        jdbc.update("UPDATE check_register SET status = 'RELEASED', updated_at = ?, updated_by = ? WHERE dv_id = ? "
                + "AND status = 'ISSUED'", Timestamp.from(now), CurrentActor.username(), id);
        dv.confirm(releasedOn, now, CurrentActor.username());
        workflow.record(DOCUMENT, id, "CONFIRM", "Released " + releasedOn);
        vouchers.flush();
        audit.record("DV_PAYMENT_CONFIRMED", DOCUMENT, id, Map.of("releasedOn", releasedOn.toString()));
        return view(dv);
    }

    /** Posts the confirmed payment: Dr accounts payable (supplier SL) / Cr the bank's cash account. */
    @Transactional
    public DvView post(UUID id, long version) {
        DisbursementVoucher dv = lockVersioned(id, version);
        if (!"PAID".equals(dv.getStatus())) {
            throw ApInvoiceService.state("POSTED".equals(dv.getStatus()) ? "The payment has already been posted."
                    : "Only confirmed payments can be posted.");
        }
        Supplier s = suppliers.load(dv.getSupplierId());
        var bank = banks.requireActive(dv.getBankAccountId());
        List<SupplierInvoice> locked = invoices.lockAll(invoiceIds(dv));
        for (DisbursementVoucherLine l : dv.getLines()) {
            SupplierInvoice inv = locked.stream().filter(i -> i.getId().equals(l.getInvoiceId())).findFirst().orElseThrow();
            if (inv.openBalance().compareTo(l.getAmount()) < 0) {
                throw ApInvoiceService.rule("Invoice " + inv.getInvoiceNumber() + " no longer has enough open balance.");
            }
        }
        List<LineData> lines = List.of(
                new LineData(mappings.require("AP_TRADE_PAYABLE"), null, s.getSubsidiaryAccountId(), "Payment to " + s.getName(),
                        dv.getAmount(), Money.ZERO),
                new LineData(bank.glAccountId(), null, null, dv.getPaymentMode() + " " + dv.getPaymentReference(), Money.ZERO,
                        dv.getAmount()));
        PostingService.PostedEntry entry = posting.postSubledger(new PostingService.SubledgerEntry(SOURCE, id.toString(),
                dv.getPaymentDate(), "Payment " + dv.getDvNumber() + " to " + s.getName() + ": " + dv.getParticulars(),
                lines, dv.getPreparedById(), dv.getPreparedBy(), false));
        for (DisbursementVoucherLine l : dv.getLines()) {
            locked.stream().filter(i -> i.getId().equals(l.getInvoiceId())).findFirst().orElseThrow().addPayment(l.getAmount());
        }
        workflow.record(DOCUMENT, id, "POST", entry.entryNumber());
        dv.markPosted(entry.entryId(), Instant.now(clock), CurrentActor.username());
        vouchers.flush();
        audit.record("DV_POSTED", DOCUMENT, id, Map.of("entryNumber", entry.entryNumber(), "amount", dv.getAmount().toPlainString()));
        return view(dv);
    }

    // ------------------------------------------------------------------ document policy

    @Override
    public String ownerType() {
        return DOCUMENT;
    }

    @Override
    public void assertCanModify(UUID ownerId) {
        DisbursementVoucher dv = load(ownerId);
        if (!CurrentActor.userId().equals(dv.getPreparedById()) || !CurrentActor.hasPermission("DISBURSEMENT_PREPARE")) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Only the preparer can change this voucher's documents.");
        }
        if (!dv.isEditable()) {
            throw ApInvoiceService.state("Documents can only change while the voucher is a draft or returned.");
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

    private void approveFinal(DisbursementVoucher dv, Instant now) {
        int year = LocalDate.now(clock.withZone(java.time.ZoneId.of("Asia/Manila"))).getYear();
        dv.assignNumber(numbering.next("DV", year));
        dv.moveTo("APPROVED", null, now, CurrentActor.username());
    }

    /** Allocates amounts to posted invoices of the same supplier, never beyond their uncommitted balance. */
    private void allocate(DisbursementVoucher dv, List<AllocationRequest> allocations) {
        Set<UUID> ids = new TreeSet<>();
        for (AllocationRequest a : allocations) {
            if (!ids.add(a.invoiceId())) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Each invoice may appear once on a voucher.");
            }
        }
        List<SupplierInvoice> locked = invoices.lockAll(ids);
        List<String> problems = new ArrayList<>();
        for (AllocationRequest a : allocations) {
            SupplierInvoice inv = locked.stream().filter(i -> i.getId().equals(a.invoiceId())).findFirst().orElse(null);
            if (inv == null) {
                problems.add("Invoice " + a.invoiceId() + " does not exist.");
                continue;
            }
            if (!inv.getSupplierId().equals(dv.getSupplierId())) {
                problems.add("Invoice " + inv.getInvoiceNumber() + " belongs to another supplier.");
            }
            if (!"POSTED".equals(inv.getStatus())) {
                problems.add("Invoice " + inv.getInvoiceNumber() + " is not posted yet.");
            }
            BigDecimal available = uncommitted(inv, dv.getId());
            if (Money.of(a.amount()).compareTo(available) > 0) {
                problems.add("Invoice " + inv.getInvoiceNumber() + " has only " + available.toPlainString()
                        + " available for payment.");
            }
            dv.addLine(a.invoiceId(), Money.of(a.amount()));
        }
        if (!problems.isEmpty()) {
            throw new ApiException(ErrorCode.ACCOUNTING_RULE_VIOLATION, "The allocations are not valid.", Map.of("violations", problems));
        }
    }

    private void recheckAllocations(DisbursementVoucher dv) {
        List<AllocationRequest> current = dv.getLines().stream().map(l -> new AllocationRequest(l.getInvoiceId(), l.getAmount())).toList();
        List<SupplierInvoice> locked = invoices.lockAll(invoiceIds(dv));
        List<String> problems = new ArrayList<>();
        for (AllocationRequest a : current) {
            SupplierInvoice inv = locked.stream().filter(i -> i.getId().equals(a.invoiceId())).findFirst().orElseThrow();
            if (a.amount().compareTo(uncommitted(inv, dv.getId())) > 0) {
                problems.add("Invoice " + inv.getInvoiceNumber() + " no longer has enough uncommitted balance.");
            }
        }
        if (!problems.isEmpty()) {
            throw new ApiException(ErrorCode.ACCOUNTING_RULE_VIOLATION, "The allocations are not valid.", Map.of("violations", problems));
        }
    }

    /** Open balance minus amounts on other live (unposted, uncancelled) vouchers. */
    private BigDecimal uncommitted(SupplierInvoice inv, UUID excludingDv) {
        BigDecimal committed = jdbc.queryForObject("""
                SELECT coalesce(sum(l.amount), 0) FROM disbursement_voucher_lines l JOIN disbursement_vouchers d ON d.id = l.dv_id
                WHERE l.invoice_id = ? AND d.id <> ? AND d.status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'PAYMENT_INSTRUCTED',
                    'PAID', 'RETURNED')""", BigDecimal.class, inv.getId(), excludingDv);
        return Money.of(inv.openBalance().subtract(committed));
    }

    private static List<UUID> invoiceIds(DisbursementVoucher dv) {
        return dv.getLines().stream().map(DisbursementVoucherLine::getInvoiceId).toList();
    }

    private static void sod(DisbursementVoucher dv, String action) {
        if (CurrentActor.userId().equals(dv.getPreparedById())) {
            throw new ApiException(ErrorCode.SEGREGATION_OF_DUTIES, "The preparer of a voucher cannot " + action + " it.");
        }
    }

    DisbursementVoucher load(UUID id) {
        return vouchers.findById(id).orElseThrow(() -> ApiException.notFound("Disbursement voucher"));
    }

    private DisbursementVoucher lockVersioned(UUID id, long version) {
        DisbursementVoucher dv = vouchers.findByIdForUpdate(id).orElseThrow(() -> ApiException.notFound("Disbursement voucher"));
        if (dv.getVersion() != version) {
            throw ApiException.staleVersion();
        }
        return dv;
    }

    private static void requirePreparer(DisbursementVoucher dv) {
        CurrentActor.requirePermission("DISBURSEMENT_PREPARE");
        if (!CurrentActor.userId().equals(dv.getPreparedById())) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Only the preparer can change this voucher.");
        }
    }

    private DvSummary summary(DisbursementVoucher dv) {
        return new DvSummary(dv.getId(), dv.getDvNumber(), suppliers.load(dv.getSupplierId()).getName(), dv.getParticulars(),
                dv.getStatus(), workflow.currentStepCode(DOCUMENT, dv.getAmount(), ApInvoiceService.toInt(dv.getCurrentStepOrder())),
                dv.getAmount(), dv.getPreparedBy(), dv.getUpdatedAt(), dv.getVersion());
    }

    private DvView view(DisbursementVoucher dv) {
        Supplier s = suppliers.load(dv.getSupplierId());
        List<DvLineView> lines = dv.getLines().stream().map(l -> {
            SupplierInvoice inv = invoices.findById(l.getInvoiceId()).orElseThrow();
            return new DvLineView(inv.getId(), inv.getInvoiceNumber(), inv.getDueDate(), inv.getPayableAmount(), l.getAmount());
        }).toList();
        String bankCode = dv.getBankAccountId() == null ? null
                : jdbc.queryForObject("SELECT code FROM bank_accounts WHERE id = ?", String.class, dv.getBankAccountId());
        String entryNumber = dv.getPostedEntryId() == null ? null
                : jdbc.queryForObject("SELECT entry_number FROM journal_entries WHERE id = ?", String.class, dv.getPostedEntryId());
        boolean preparer = CurrentActor.userId().equals(dv.getPreparedById());
        List<String> actions = new ArrayList<>();
        if (dv.isEditable() && preparer && CurrentActor.hasPermission("DISBURSEMENT_PREPARE")) {
            actions.addAll(List.of("EDIT", "SUBMIT", "CANCEL"));
            if (CurrentActor.hasPermission("DOCUMENT_UPLOAD")) {
                actions.add("UPLOAD");
            }
        }
        if ("SUBMITTED".equals(dv.getStatus()) && workflow.canAct(DOCUMENT, dv.getId(), dv.getAmount(),
                ApInvoiceService.toInt(dv.getCurrentStepOrder()), dv.getPreparedById())) {
            actions.addAll(List.of("APPROVE", "RETURN", "REJECT"));
        }
        DvCheck check = check(dv.getId());
        boolean awaitingCheckApproval = check != null && "PREPARED".equals(check.status());
        if (!preparer && CurrentActor.hasPermission("PAYMENT_RELEASE")) {
            if ("APPROVED".equals(dv.getStatus())) {
                actions.add("INSTRUCT");
            }
            if ("PAYMENT_INSTRUCTED".equals(dv.getStatus())) {
                if (!awaitingCheckApproval) {
                    actions.add("CONFIRM");
                }
                actions.add("VOID");
            }
        }
        if (awaitingCheckApproval && "PAYMENT_INSTRUCTED".equals(dv.getStatus()) && !preparer
                && CurrentActor.hasPermission("CHECK_APPROVE") && !CurrentActor.username().equals(check.preparedBy())) {
            actions.add("APPROVE_CHECK");
        }
        if ("PAID".equals(dv.getStatus()) && !preparer && CurrentActor.hasPermission("JOURNAL_POST")) {
            actions.add("POST");
        }
        return new DvView(dv.getId(), dv.getDvNumber(), s.getId(), s.getCode(), s.getName(), dv.getParticulars(), dv.getStatus(),
                workflow.currentStepCode(DOCUMENT, dv.getAmount(), ApInvoiceService.toInt(dv.getCurrentStepOrder())),
                dv.getAmount(), dv.getPaymentMode(), dv.getBankAccountId(), bankCode, dv.getPaymentReference(),
                dv.getPaymentDate(), dv.getInstructedBy(), dv.getInstructedAt(), dv.getConfirmedBy(), dv.getConfirmedAt(),
                dv.getPreparedBy(), dv.getPostedEntryId(), entryNumber, lines, budgetLines(dv), check, workflow.history(DOCUMENT, dv.getId()),
                documents.list(DOCUMENT, dv.getId()), actions, dv.getVersion());
    }

    private DvCheck check(UUID dvId) {
        List<DvCheck> rows = jdbc.query("""
                SELECT b.code, c.check_number, c.status, c.created_by, c.approved_by, c.approved_at
                FROM check_register c JOIN bank_accounts b ON b.id = c.bank_account_id
                WHERE c.dv_id = ? ORDER BY c.created_at DESC LIMIT 1""", (rs, i) -> new DvCheck(rs.getString(1), rs.getString(2),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant()),
                dvId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Each budget line the DV pays: its share of the invoice lines (by the part of each invoice it pays) and the COB position. */
    private List<DvBudgetLine> budgetLines(DisbursementVoucher dv) {
        record Key(UUID fiscalYearId, UUID accountId, UUID costCenterId) {
        }
        Map<Key, BigDecimal> charged = new java.util.LinkedHashMap<>();
        for (DisbursementVoucherLine l : dv.getLines()) {
            SupplierInvoice inv = invoices.findById(l.getInvoiceId()).orElseThrow();
            if (inv.getPayableAmount().signum() == 0 || inv.getPeriodId() == null) {
                continue;
            }
            BigDecimal share = l.getAmount().divide(inv.getPayableAmount(), 10, java.math.RoundingMode.HALF_EVEN);
            UUID fy = calendar.period(inv.getPeriodId()).getFiscalYearId();
            for (var il : inv.getLines()) {
                charged.merge(new Key(fy, il.getAccountId(), il.getCostCenterId()), il.getNetAmount().multiply(share), BigDecimal::add);
            }
        }
        List<DvBudgetLine> out = new ArrayList<>();
        for (var e : charged.entrySet()) {
            BudgetControl.Position p = budget.position(e.getKey().fiscalYearId(), e.getKey().accountId(), e.getKey().costCenterId());
            if ("NOT_CONTROLLED".equals(p.status())) {
                continue;
            }
            Map<String, Object> a = jdbc.queryForMap("SELECT code, name FROM chart_of_accounts WHERE id = ?", e.getKey().accountId());
            String cc = e.getKey().costCenterId() == null ? null
                    : jdbc.queryForObject("SELECT code FROM cost_centers WHERE id = ?", String.class, e.getKey().costCenterId());
            out.add(new DvBudgetLine((String) a.get("code"), (String) a.get("name"), cc, Money.of(e.getValue()), p.status(),
                    p.allocation(), p.allotment(), p.used(), p.available()));
        }
        return out;
    }

    static boolean isLive(String status) {
        return LIVE.contains(status);
    }
}
