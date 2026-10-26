package com.miaa.cas.accountsreceivable;

import com.miaa.cas.accountsreceivable.ArDtos.AllocationView;
import com.miaa.cas.accountsreceivable.ArDtos.OpenItem;
import com.miaa.cas.accountsreceivable.ArDtos.ReceiptAllocationRequest;
import com.miaa.cas.accountsreceivable.ArDtos.ReceiptRequest;
import com.miaa.cas.accountsreceivable.ArDtos.ReceiptSummary;
import com.miaa.cas.accountsreceivable.ArDtos.ReceiptView;
import com.miaa.cas.accountsreceivable.domain.BillingDocument;
import com.miaa.cas.accountsreceivable.domain.BillingDocumentRepository;
import com.miaa.cas.accountsreceivable.domain.Customer;
import com.miaa.cas.accountsreceivable.domain.Receipt;
import com.miaa.cas.accountsreceivable.domain.ReceiptRepository;
import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.money.Money;
import com.miaa.cas.common.web.PageRequests;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.documents.DocumentOwnerPolicy;
import com.miaa.cas.documents.DocumentService;
import com.miaa.cas.generalledger.AccountMappingService;
import com.miaa.cas.journal.NumberingService;
import com.miaa.cas.journal.PostingService;
import com.miaa.cas.journal.domain.LineData;
import com.miaa.cas.notifications.NotificationService;
import com.miaa.cas.security.CurrentActor;
import java.math.BigDecimal;
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
 * Collections. A cashier records the receipt and its application to open invoices; accounting posts
 * it (never the person who recorded it):
 * Dr cash on hand (amount) + Dr creditable withholding tax (tax withheld) /
 * Cr accounts receivable (customer SL, applied amounts) + Cr unapplied collections (customer SL, remainder).
 * Unapplied amounts can later be applied to invoices: Dr unapplied collections / Cr accounts receivable.
 */
@Service
public class ReceiptService implements DocumentOwnerPolicy {

    public static final String OWNER = "AR_RECEIPT";
    public static final String SOURCE = "SL_AR_RECEIPT";
    public static final String APPLICATION_SOURCE = "SL_AR_APPLICATION";
    private static final Map<String, String> SORTS = Map.of("receiptDate", "receiptDate", "updatedAt", "updatedAt",
            "amount", "amount");

    private final ReceiptRepository receipts;
    private final BillingDocumentRepository billings;
    private final BillingService billing;
    private final CustomerService customers;
    private final PostingService posting;
    private final NumberingService numbering;
    private final AccountMappingService mappings;
    private final DocumentService documents;
    private final AuditService audit;
    private final NotificationService notifications;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ReceiptService(ReceiptRepository receipts, BillingDocumentRepository billings, BillingService billing,
            CustomerService customers, PostingService posting, NumberingService numbering, AccountMappingService mappings,
            DocumentService documents, AuditService audit, NotificationService notifications, JdbcTemplate jdbc, Clock clock) {
        this.receipts = receipts;
        this.billings = billings;
        this.billing = billing;
        this.customers = customers;
        this.posting = posting;
        this.numbering = numbering;
        this.mappings = mappings;
        this.documents = documents;
        this.audit = audit;
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public PageResponse<ReceiptSummary> list(String status, UUID customerId, LocalDate from, LocalDate to, int page, int size,
            String sort) {
        return PageResponse.of(receipts.findAll(filter(status, customerId, from, to),
                PageRequests.of(page, size, sort, SORTS, "-updatedAt")), this::summary);
    }

    Specification<Receipt> filter(String status, UUID customerId, LocalDate from, LocalDate to) {
        Specification<Receipt> spec = (root, query, cb) -> cb.conjunction();
        if (status != null && !status.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status.trim().toUpperCase()));
        }
        if (customerId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("customerId"), customerId));
        }
        if (from != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("receiptDate"), from));
        }
        if (to != null) {
            spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("receiptDate"), to));
        }
        return spec;
    }

    @Transactional(readOnly = true)
    public ReceiptView get(UUID id) {
        return view(load(id));
    }

    /** Posted invoices and debit memos of a customer with something left to collect. */
    @Transactional(readOnly = true)
    public List<OpenItem> openItems(UUID customerId) {
        customers.load(customerId);
        return jdbc.query("""
                SELECT d.id, d.kind, d.document_number, d.document_date, d.due_date, d.gross_amount,
                       d.gross_amount - d.settled_amount AS open,
                       coalesce((SELECT sum(a.amount) FROM receipt_allocations a JOIN receipts r ON r.id = a.receipt_id
                                 WHERE a.document_id = d.id AND r.status = 'RECORDED' AND a.posted_entry_id IS NULL), 0) AS pending
                FROM billing_documents d
                WHERE d.customer_id = ? AND d.status = 'POSTED' AND d.kind <> 'CREDIT_MEMO' AND d.gross_amount > d.settled_amount
                ORDER BY d.due_date, d.document_number""", (rs, i) -> {
            BigDecimal open = Money.of(rs.getBigDecimal("open"));
            BigDecimal pending = Money.of(rs.getBigDecimal("pending"));
            return new OpenItem(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("document_number"),
                    rs.getDate("document_date").toLocalDate(), rs.getDate("due_date").toLocalDate(),
                    rs.getBigDecimal("gross_amount"), open, pending, Money.of(open.subtract(pending)));
        }, customerId);
    }

    // ------------------------------------------------------------------ commands

    @Transactional
    public ReceiptView record(ReceiptRequest req) {
        CurrentActor.requirePermission("COLLECTION_RECORD");
        Customer c = customers.load(req.customerId());
        if (!"CASH".equals(req.paymentMethod()) && CustomerService.blank(req.paymentReference()) == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Check and bank-transfer receipts need the check or transfer reference.");
        }
        BigDecimal amount = Money.of(req.amount());
        BigDecimal tax = Money.of(req.taxWithheld());
        BigDecimal allocated = checkAllocations(c, req.allocations(), amount.add(tax));
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        Receipt r = new Receipt(id, numbering.next("RCPT", req.receiptDate().getYear()), c.getId(), req.receiptDate(),
                req.paymentMethod(), CustomerService.blank(req.paymentReference()), amount, tax,
                Money.of(amount.add(tax).subtract(allocated)), CustomerService.blank(req.remarks()), CurrentActor.userId(),
                CurrentActor.username(), now);
        receipts.saveAndFlush(r);
        insertAllocations(id, req.allocations(), false, null, now);
        audit.record("RECEIPT_RECORDED", OWNER, id, Map.of("receiptNumber", r.getReceiptNumber(), "customer", c.getCode(),
                "amount", amount.toPlainString(), "taxWithheld", tax.toPlainString(), "allocated", allocated.toPlainString()));
        return view(r);
    }

    @Transactional
    public ReceiptView cancel(UUID id, long version, String reason) {
        Receipt r = lockVersioned(id, version);
        if (!"RECORDED".equals(r.getStatus())) {
            throw BillingService.state("Only receipts not yet posted can be cancelled; posted receipts are corrected by reversal.");
        }
        boolean recorder = CurrentActor.userId().equals(r.getRecordedById()) && CurrentActor.hasPermission("COLLECTION_RECORD");
        if (!recorder && !CurrentActor.hasPermission("JOURNAL_POST")) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Only the recorder or accounting can cancel this receipt.");
        }
        // The number stays used (gap-free series); the receipt is kept with its cancellation reason.
        r.cancel(reason.trim(), Instant.now(clock), CurrentActor.username());
        receipts.flush();
        audit.record("RECEIPT_CANCELLED", OWNER, id, Map.of("receiptNumber", r.getReceiptNumber(), "reason", reason.trim()));
        return view(r);
    }

    @Transactional
    public ReceiptView post(UUID id, long version) {
        Receipt r = lockVersioned(id, version);
        if (!"RECORDED".equals(r.getStatus())) {
            throw BillingService.state("POSTED".equals(r.getStatus()) ? "The receipt has already been posted."
                    : "Only recorded receipts can be posted.");
        }
        Customer c = customers.load(r.getCustomerId());
        List<Map<String, Object>> allocations = jdbc.queryForList("SELECT document_id, amount FROM receipt_allocations "
                + "WHERE receipt_id = ? AND posted_entry_id IS NULL", id);
        List<BillingDocument> locked = lockDocuments(allocations);
        BigDecimal applied = BigDecimal.ZERO;
        for (Map<String, Object> a : allocations) {
            BillingDocument d = find(locked, (UUID) a.get("document_id"));
            BigDecimal amt = (BigDecimal) a.get("amount");
            if (!"POSTED".equals(d.getStatus()) || d.openBalance().compareTo(amt) < 0) {
                throw BillingService.rule("Document " + d.getDocumentNumber() + " no longer has enough open balance.");
            }
            applied = applied.add(amt);
        }
        List<LineData> lines = new ArrayList<>();
        lines.add(new LineData(mappings.require("CASH_ON_HAND"), null, null, r.getPaymentMethod() + " receipt "
                + r.getReceiptNumber(), r.getAmount(), Money.ZERO));
        if (r.getTaxWithheld().signum() > 0) {
            lines.add(new LineData(mappings.require("CREDITABLE_WHT"), null, null, "Tax withheld by " + c.getName(),
                    r.getTaxWithheld(), Money.ZERO));
        }
        if (applied.signum() > 0) {
            lines.add(new LineData(mappings.require("AR_TRADE_RECEIVABLE"), null, c.getSubsidiaryAccountId(),
                    "Collection from " + c.getName(), Money.ZERO, Money.of(applied)));
        }
        if (r.getUnappliedAmount().signum() > 0) {
            lines.add(new LineData(mappings.require("UNAPPLIED_COLLECTIONS"), null, c.getSubsidiaryAccountId(),
                    "Unapplied collection from " + c.getName(), Money.ZERO, r.getUnappliedAmount()));
        }
        PostingService.PostedEntry entry = posting.postSubledger(new PostingService.SubledgerEntry(SOURCE, id.toString(),
                r.getReceiptDate(), "Receipt " + r.getReceiptNumber() + " from " + c.getCode() + " " + c.getName(), lines,
                r.getRecordedById(), r.getRecordedBy(), false));
        for (Map<String, Object> a : allocations) {
            find(locked, (UUID) a.get("document_id")).settle((BigDecimal) a.get("amount"));
        }
        jdbc.update("UPDATE receipt_allocations SET posted_entry_id = ? WHERE receipt_id = ? AND posted_entry_id IS NULL",
                entry.entryId(), id);
        r.markPosted(entry.entryId(), Instant.now(clock), CurrentActor.username());
        receipts.flush();
        billings.flush();
        notifications.notify(r.getRecordedById(), "DOCUMENT_POSTED", "Receipt " + r.getReceiptNumber() + " was posted by "
                + CurrentActor.username(), "Journal entry " + entry.entryNumber() + ".", "/receivables/receipts/" + id);
        audit.record("RECEIPT_POSTED", OWNER, id, Map.of("receiptNumber", r.getReceiptNumber(), "entryNumber", entry.entryNumber(),
                "applied", Money.of(applied).toPlainString(), "unapplied", r.getUnappliedAmount().toPlainString()));
        return view(r);
    }

    /**
     * Applies part of a posted receipt's unapplied amount to open invoices, posting
     * Dr unapplied collections / Cr accounts receivable on the given date. Requires the posting permission.
     */
    @Transactional
    public ReceiptView applyUnapplied(UUID id, long version, LocalDate accountingDate,
            List<ReceiptAllocationRequest> allocations) {
        CurrentActor.requirePermission("JOURNAL_POST");
        Receipt r = lockVersioned(id, version);
        if (!"POSTED".equals(r.getStatus())) {
            throw BillingService.state("Only posted receipts with an unapplied amount can be applied.");
        }
        Customer c = customers.load(r.getCustomerId());
        BigDecimal total = checkAllocations(c, allocations, r.getUnappliedAmount());
        if (accountingDate.isBefore(r.getReceiptDate())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "An application cannot be dated before the receipt.");
        }
        List<BillingDocument> locked = billings.lockAll(ids(allocations));
        UUID batch = UUID.randomUUID();
        PostingService.PostedEntry entry = posting.postSubledger(new PostingService.SubledgerEntry(APPLICATION_SOURCE,
                batch.toString(), accountingDate, "Application of receipt " + r.getReceiptNumber() + " (" + c.getCode() + ")", List.of(
                        new LineData(mappings.require("UNAPPLIED_COLLECTIONS"), null, c.getSubsidiaryAccountId(),
                                "Applied from receipt " + r.getReceiptNumber(), total, Money.ZERO),
                        new LineData(mappings.require("AR_TRADE_RECEIVABLE"), null, c.getSubsidiaryAccountId(),
                                "Collection from " + c.getName(), Money.ZERO, total)),
                r.getRecordedById(), r.getRecordedBy(), false));
        for (ReceiptAllocationRequest a : allocations) {
            find(locked, a.documentId()).settle(Money.of(a.amount()));
        }
        Instant now = Instant.now(clock);
        insertAllocations(id, allocations, true, entry.entryId(), now);
        r.reduceUnapplied(total, now, CurrentActor.username());
        receipts.flush();
        billings.flush();
        audit.record("RECEIPT_APPLIED", OWNER, id, Map.of("receiptNumber", r.getReceiptNumber(), "entryNumber", entry.entryNumber(),
                "applied", total.toPlainString()));
        return view(r);
    }

    // ------------------------------------------------------------------ document policy

    @Override
    public String ownerType() {
        return OWNER;
    }

    @Override
    public void assertCanModify(UUID ownerId) {
        Receipt r = load(ownerId);
        CurrentActor.requirePermission("DOCUMENT_UPLOAD");
        if (!CurrentActor.userId().equals(r.getRecordedById())) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Only the recorder can change this receipt's attachments.");
        }
        if (!"RECORDED".equals(r.getStatus())) {
            throw BillingService.state("Attachments can only change before the receipt is posted.");
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

    /**
     * Each document once, of this customer, posted, receivable, and with enough balance not already
     * committed to other recorded receipts. Returns the total allocated, which may not exceed {@code limit}.
     */
    private BigDecimal checkAllocations(Customer c, List<ReceiptAllocationRequest> allocations, BigDecimal limit) {
        Set<UUID> ids = ids(allocations);
        if (ids.size() != allocations.size()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Each document may appear once on a receipt.");
        }
        if (ids.isEmpty()) {
            return Money.ZERO;
        }
        List<BillingDocument> locked = billings.lockAll(ids);
        List<String> problems = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (ReceiptAllocationRequest a : allocations) {
            BillingDocument d = locked.stream().filter(x -> x.getId().equals(a.documentId())).findFirst().orElse(null);
            if (d == null || !d.getCustomerId().equals(c.getId())) {
                problems.add("Document " + a.documentId() + " is not a document of customer " + c.getCode() + ".");
                continue;
            }
            if (!"POSTED".equals(d.getStatus()) || !d.isReceivable()) {
                problems.add("Document " + (d.getDocumentNumber() == null ? d.getId() : d.getDocumentNumber())
                        + " is not a posted invoice or debit memo.");
                continue;
            }
            BigDecimal available = d.openBalance().subtract(billing.pendingCollections(d.getId()));
            if (Money.of(a.amount()).compareTo(available) > 0) {
                problems.add("Document " + d.getDocumentNumber() + " has only " + Money.of(available).toPlainString()
                        + " available to collect.");
            }
            total = total.add(Money.of(a.amount()));
        }
        if (total.compareTo(limit) > 0) {
            problems.add("Allocations (" + Money.of(total).toPlainString() + ") exceed the amount available ("
                    + Money.of(limit).toPlainString() + ").");
        }
        if (!problems.isEmpty()) {
            throw new ApiException(ErrorCode.ACCOUNTING_RULE_VIOLATION, problems.get(0), Map.of("violations", problems));
        }
        return Money.of(total);
    }

    private void insertAllocations(UUID receiptId, List<ReceiptAllocationRequest> allocations, boolean later, UUID entryId,
            Instant now) {
        for (ReceiptAllocationRequest a : allocations) {
            jdbc.update("INSERT INTO receipt_allocations (id, receipt_id, document_id, amount, applied_later, posted_entry_id, "
                    + "created_at, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", UUID.randomUUID(), receiptId, a.documentId(),
                    Money.of(a.amount()), later, entryId, Timestamp.from(now), CurrentActor.username());
        }
    }

    private List<BillingDocument> lockDocuments(List<Map<String, Object>> allocations) {
        Set<UUID> ids = new TreeSet<>();
        allocations.forEach(a -> ids.add((UUID) a.get("document_id")));
        return ids.isEmpty() ? List.of() : billings.lockAll(ids);
    }

    private static Set<UUID> ids(List<ReceiptAllocationRequest> allocations) {
        Set<UUID> ids = new TreeSet<>();
        allocations.forEach(a -> ids.add(a.documentId()));
        return ids;
    }

    private static BillingDocument find(List<BillingDocument> docs, UUID id) {
        return docs.stream().filter(d -> d.getId().equals(id)).findFirst().orElseThrow();
    }

    Receipt load(UUID id) {
        return receipts.findById(id).orElseThrow(() -> ApiException.notFound("Receipt"));
    }

    private Receipt lockVersioned(UUID id, long version) {
        Receipt r = receipts.findByIdForUpdate(id).orElseThrow(() -> ApiException.notFound("Receipt"));
        if (r.getVersion() != version) {
            throw ApiException.staleVersion();
        }
        return r;
    }

    ReceiptSummary summary(Receipt r) {
        Customer c = customers.load(r.getCustomerId());
        return new ReceiptSummary(r.getId(), r.getReceiptNumber(), c.getCode(), c.getName(), r.getReceiptDate(),
                r.getPaymentMethod(), r.getAmount(), r.getTaxWithheld(), r.getUnappliedAmount(), r.getStatus(),
                r.getRecordedBy(), r.getUpdatedAt(), r.getVersion());
    }

    List<AllocationView> allocations(UUID receiptId) {
        return jdbc.query("""
                SELECT a.document_id, d.document_number, d.kind, a.amount, a.applied_later, e.entry_number, a.created_at
                FROM receipt_allocations a JOIN billing_documents d ON d.id = a.document_id
                LEFT JOIN journal_entries e ON e.id = a.posted_entry_id WHERE a.receipt_id = ? ORDER BY a.created_at, d.document_number""",
                (rs, i) -> new AllocationView(rs.getObject("document_id", UUID.class), rs.getString("document_number"),
                        rs.getString("kind"), rs.getBigDecimal("amount"), rs.getBoolean("applied_later"),
                        rs.getString("entry_number"), rs.getTimestamp("created_at").toInstant()), receiptId);
    }

    private ReceiptView view(Receipt r) {
        Customer c = customers.load(r.getCustomerId());
        List<AllocationView> allocs = allocations(r.getId());
        BigDecimal allocated = Money.of(allocs.stream().map(AllocationView::amount).reduce(BigDecimal.ZERO, BigDecimal::add));
        String entryNumber = r.getPostedEntryId() == null ? null : jdbc.queryForObject(
                "SELECT entry_number FROM journal_entries WHERE id = ?", String.class, r.getPostedEntryId());
        List<String> actions = new ArrayList<>();
        boolean recorder = CurrentActor.userId().equals(r.getRecordedById());
        if ("RECORDED".equals(r.getStatus())) {
            if (!recorder && CurrentActor.hasPermission("JOURNAL_POST")) {
                actions.add("POST");
            }
            if ((recorder && CurrentActor.hasPermission("COLLECTION_RECORD")) || CurrentActor.hasPermission("JOURNAL_POST")) {
                actions.add("CANCEL");
            }
            if (recorder && CurrentActor.hasPermission("DOCUMENT_UPLOAD")) {
                actions.add("UPLOAD");
            }
        }
        if ("POSTED".equals(r.getStatus()) && r.getUnappliedAmount().signum() > 0 && !recorder
                && CurrentActor.hasPermission("JOURNAL_POST")) {
            actions.add("APPLY");
        }
        return new ReceiptView(r.getId(), r.getReceiptNumber(), c.getId(), c.getCode(), c.getName(), r.getReceiptDate(),
                r.getPaymentMethod(), r.getPaymentReference(), r.getAmount(), r.getTaxWithheld(), allocated,
                r.getUnappliedAmount(), r.getStatus(), r.getRemarks(), r.getRecordedBy(), r.getCancelReason(),
                r.getPostedEntryId(), entryNumber, allocs, documents.list(OWNER, r.getId()), actions, r.getVersion());
    }
}
