package com.miaa.cas.approvals;

import com.miaa.cas.accountspayable.ApInvoiceService;
import com.miaa.cas.accountspayable.DisbursementService;
import com.miaa.cas.budgeting.BudgetService;
import com.miaa.cas.closing.CloseCalendarService;
import com.miaa.cas.journal.VoucherQueryService;
import com.miaa.cas.journal.web.JournalDtos.VoucherSummary;
import com.miaa.cas.security.CurrentActor;
import com.miaa.cas.treasury.BankReconciliationService;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One inbox across document types: every document waiting for a step the signed-in user may take. Nothing is stored;
 * each source applies the same rule as its module (permission, never the preparer, one workflow step per person per
 * cycle through {@link DocumentWorkflow#canAct}), so the inbox and the document's own action buttons agree. The action
 * itself is taken on the document's page, where its lines, attachments and history are reviewed.
 */
@Service
public class ApprovalInboxService {

    public record InboxItem(String queue, String documentType, UUID id, String number, String description, String counterparty,
            BigDecimal amount, LocalDate date, String status, String task, String preparedBy, Instant waitingSince, String link) {
    }

    public record QueueCount(String queue, long count) {
    }

    public record Inbox(List<InboxItem> items, List<QueueCount> queues, boolean truncated) {
    }

    /** Candidates read per source; a source reaching it marks the inbox as truncated. */
    static final int SOURCE_LIMIT = 200;

    static final String POST_QUEUE = "Documents to post";

    /**
     * A source query returns, in order: id, number, description, counterparty, amount, document date, status, preparer
     * name and waiting-since; workflow sources add the current step order. The last parameter is always the caller's id.
     */
    private record Source(String permission, String documentType, String workflowType, String sql, String queue, String task,
            Function<Map<String, Object>, String> link) {
    }

    private final JdbcTemplate jdbc;
    private final DocumentWorkflow workflow;
    private final VoucherQueryService vouchers;
    private final CloseCalendarService close;

    public ApprovalInboxService(JdbcTemplate jdbc, DocumentWorkflow workflow, VoucherQueryService vouchers, CloseCalendarService close) {
        this.jdbc = jdbc;
        this.workflow = workflow;
        this.vouchers = vouchers;
        this.close = close;
    }

    @Transactional(readOnly = true)
    public Inbox mine() {
        return mine(null);
    }

    /**
     * The caller's inbox; with {@code search}, only items whose number, description, counterparty or preparer contains it.
     * The search runs before the per-source limit, so any waiting document can be found however long the queue is.
     */
    @Transactional(readOnly = true)
    public Inbox mine(String search) {
        String q = search == null || search.isBlank() ? "" : search.trim();
        List<InboxItem> items = new ArrayList<>();
        boolean truncated = false;
        if (CurrentActor.hasPermission("JOURNAL_REVIEW") || CurrentActor.hasPermission("JOURNAL_APPROVE")
                || CurrentActor.hasPermission("JOURNAL_POST")) {
            List<VoucherSummary> jvs = vouchers.awaitingMyAction(q);
            truncated = jvs.size() >= 500;
            for (VoucherSummary v : jvs) {
                boolean post = "APPROVED".equals(v.status());
                items.add(new InboxItem(post ? POST_QUEUE : "Journal vouchers to review or approve", "JOURNAL_VOUCHER", v.id(),
                        v.voucherNumber(), v.description(), null, v.totalDebit(), v.accountingDate(), v.status(),
                        post ? "Post" : label(v.currentStep()), v.preparedBy(), v.updatedAt(), "/journal-vouchers/" + v.id()));
            }
        }
        // Close tasks due in the last week (or today) that the caller can tick off.
        for (CloseCalendarService.ScheduleItem t : close.dueForCurrentUser()) {
            if (!q.isEmpty() && !(t.taskName() + " " + t.scopeLabel()).toLowerCase().contains(q.toLowerCase())) {
                continue;
            }
            items.add(new InboxItem("Close tasks", "CLOSE_TASK", t.taskId(), null, t.taskName(), t.scopeLabel(), null, t.dueDate(), t.overdue()
                    ? "OVERDUE" : "DUE", "Complete", null, t.dueDate().atStartOfDay().toInstant(ZoneOffset.UTC),
                    "/close?frequency=" + t.frequency() + "&scope=" + t.scopeKey()));
        }
        for (Source s : SOURCES) {
            if (!CurrentActor.hasPermission(s.permission())) {
                continue;
            }
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM (" + s.sql() + ") s WHERE ? = '' OR concat_ws(' ', s.number, "
                    + "s.description, s.counterparty, s.prepared_by) ILIKE ? ESCAPE '\\' ORDER BY s.since LIMIT " + SOURCE_LIMIT,
                    CurrentActor.userId(), q, "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
            truncated |= rows.size() >= SOURCE_LIMIT;
            for (Map<String, Object> r : rows) {
                String task = s.task();
                String queue = s.queue();
                if (s.workflowType() != null) {
                    String type = s.workflowType().startsWith("=") ? (String) r.get("wf") : s.workflowType();
                    BigDecimal total = r.get("amount") == null ? BigDecimal.ZERO : ((BigDecimal) r.get("amount")).abs();
                    Integer step = r.get("step") == null ? null : ((Number) r.get("step")).intValue();
                    if (!workflow.canAct(type, (UUID) r.get("id"), total, step, (UUID) r.get("preparer_id"))) {
                        continue;
                    }
                    String code = workflow.currentStepCode(type, total, step);
                    task = label(code);
                    queue = queue.replace("{step}", code == null ? "approve" : code.toLowerCase());
                }
                items.add(new InboxItem(queue, s.documentType(), (UUID) r.get("id"), (String) r.get("number"),
                        (String) r.get("description"), (String) r.get("counterparty"), (BigDecimal) r.get("amount"),
                        r.get("doc_date") == null ? null : ((Date) r.get("doc_date")).toLocalDate(), (String) r.get("status"), task,
                        (String) r.get("prepared_by"), ((Timestamp) r.get("since")).toInstant(), s.link().apply(r)));
            }
        }
        items.sort(Comparator.comparing(InboxItem::waitingSince));
        Map<String, Long> counts = new LinkedHashMap<>();
        items.forEach(i -> counts.merge(i.queue(), 1L, Long::sum));
        return new Inbox(items, counts.entrySet().stream().map(e -> new QueueCount(e.getKey(), e.getValue())).toList(), truncated);
    }

    private static String label(String code) {
        if (code == null || code.isBlank()) {
            return "Approve";
        }
        return code.charAt(0) + code.substring(1).toLowerCase().replace('_', ' ');
    }

    private static Function<Map<String, Object>, String> to(String prefix) {
        return r -> prefix + r.get("id");
    }

    private static final String COLS = "id, number, description, counterparty, amount, doc_date, status, preparer_id, prepared_by, since";

    private static final List<Source> SOURCES = List.of(
            // Workflow approvals (configurable chains in document_workflow_steps).
            new Source("AP_INVOICE_APPROVE", "AP_INVOICE", ApInvoiceService.DOCUMENT, """
                    SELECT i.id, i.invoice_number AS number, i.description, s.name AS counterparty, i.payable_amount AS amount,
                        i.invoice_date AS doc_date, i.status, i.prepared_by_id AS preparer_id, i.prepared_by, i.updated_at AS since,
                        i.current_step_order AS step
                    FROM supplier_invoices i JOIN suppliers s ON s.id = i.supplier_id
                    WHERE i.status = 'SUBMITTED' AND i.prepared_by_id <> ? ORDER BY i.updated_at""",
                    "Supplier invoices to {step}", null, to("/payables/invoices/")),
            new Source("AP_VIEW", "DISBURSEMENT_VOUCHER", DisbursementService.DOCUMENT, """
                    SELECT d.id, d.dv_number AS number, d.particulars AS description, s.name AS counterparty, d.amount,
                        CAST(d.created_at AS DATE) AS doc_date, d.status, d.prepared_by_id AS preparer_id, d.prepared_by,
                        d.updated_at AS since, d.current_step_order AS step
                    FROM disbursement_vouchers d JOIN suppliers s ON s.id = d.supplier_id
                    WHERE d.status = 'SUBMITTED' AND d.prepared_by_id <> ? ORDER BY d.updated_at""",
                    "Disbursement vouchers to {step}", null, to("/payables/disbursements/")),
            new Source("AR_BILLING_APPROVE", "BILLING", "=", """
                    SELECT b.id, b.document_number AS number, b.description, c.name AS counterparty, b.gross_amount AS amount,
                        b.document_date AS doc_date, b.status, b.prepared_by_id AS preparer_id, b.prepared_by, b.updated_at AS since,
                        b.current_step_order AS step, CASE WHEN b.kind = 'INVOICE' THEN 'AR_INVOICE' ELSE 'AR_MEMO' END AS wf
                    FROM billing_documents b JOIN customers c ON c.id = b.customer_id
                    WHERE b.status = 'SUBMITTED' AND b.prepared_by_id <> ? ORDER BY b.updated_at""",
                    "Billings to {step}", null, to("/receivables/billings/")),
            new Source("BUDGET_APPROVE", "BUDGET", BudgetService.DOCUMENT, """
                    SELECT b.id, NULL AS number, b.name AS description, NULL AS counterparty,
                        (SELECT COALESCE(sum(amount), 0) FROM budget_allocations WHERE budget_id = b.id) AS amount, NULL AS doc_date,
                        b.status, b.prepared_by_id AS preparer_id, b.prepared_by, b.updated_at AS since, b.current_step_order AS step
                    FROM budgets b WHERE b.status = 'SUBMITTED' AND b.prepared_by_id <> ? ORDER BY b.updated_at""",
                    "Budgets to {step}", null, to("/budgets/")),
            new Source("BANK_RECONCILIATION_APPROVE", "BANK_RECONCILIATION", BankReconciliationService.DOCUMENT, """
                    SELECT r.id, NULL AS number, 'Reconciliation as of ' || r.as_of_date AS description, ba.code || ' ' || ba.bank_name AS counterparty,
                        r.bank_balance AS amount, r.as_of_date AS doc_date, r.status, r.prepared_by_id AS preparer_id, r.prepared_by,
                        r.updated_at AS since, r.current_step_order AS step
                    FROM bank_reconciliations r JOIN bank_accounts ba ON ba.id = r.bank_account_id
                    WHERE r.status = 'SUBMITTED' AND r.prepared_by_id <> ? ORDER BY r.updated_at""",
                    "Bank reconciliations to {step}", null, to("/treasury/reconciliations/")),

            // Payment release (treasury), never by the preparer.
            new Source("PAYMENT_RELEASE", "DISBURSEMENT_VOUCHER", null, """
                    SELECT d.id, d.dv_number AS number, d.particulars AS description, s.name AS counterparty, d.amount,
                        CAST(d.created_at AS DATE) AS doc_date, d.status, d.prepared_by_id AS preparer_id, d.prepared_by, d.updated_at AS since
                    FROM disbursement_vouchers d JOIN suppliers s ON s.id = d.supplier_id
                    WHERE d.status IN ('APPROVED', 'PAYMENT_INSTRUCTED') AND d.prepared_by_id <> ?
                      AND NOT EXISTS (SELECT 1 FROM check_register c WHERE c.dv_id = d.id AND c.status = 'PREPARED')
                    ORDER BY d.updated_at""",
                    "Payments to issue or confirm", "Release payment", to("/payables/disbursements/")),

            // Check approval (treasury), by someone other than the voucher's and the check's preparers.
            new Source("CHECK_APPROVE", "DISBURSEMENT_VOUCHER", null, """
                    SELECT d.id, d.dv_number AS number, 'Check ' || c.check_number || ': ' || d.particulars AS description,
                        s.name AS counterparty, d.amount, c.issue_date AS doc_date, d.status, d.prepared_by_id AS preparer_id,
                        d.prepared_by, c.updated_at AS since
                    FROM disbursement_vouchers d JOIN suppliers s ON s.id = d.supplier_id
                    JOIN check_register c ON c.dv_id = d.id AND c.status = 'PREPARED'
                    WHERE d.status = 'PAYMENT_INSTRUCTED' AND d.prepared_by_id <> ? AND c.prepared_by_id IS DISTINCT FROM d.prepared_by_id
                    ORDER BY c.updated_at""",
                    "Checks to approve", "Approve check", to("/payables/disbursements/")),

            // Posting: accounting posts what someone else prepared.
            new Source("JOURNAL_POST", "AP_INVOICE", null, """
                    SELECT i.id, i.invoice_number AS number, i.description, s.name AS counterparty, i.payable_amount AS amount,
                        i.invoice_date AS doc_date, i.status, i.prepared_by_id AS preparer_id, i.prepared_by, i.updated_at AS since
                    FROM supplier_invoices i JOIN suppliers s ON s.id = i.supplier_id
                    WHERE i.status = 'APPROVED' AND i.prepared_by_id <> ? ORDER BY i.updated_at""",
                    POST_QUEUE, "Post", to("/payables/invoices/")),
            new Source("JOURNAL_POST", "DISBURSEMENT_VOUCHER", null, """
                    SELECT d.id, d.dv_number AS number, d.particulars AS description, s.name AS counterparty, d.amount,
                        d.payment_date AS doc_date, d.status, d.prepared_by_id AS preparer_id, d.prepared_by, d.updated_at AS since
                    FROM disbursement_vouchers d JOIN suppliers s ON s.id = d.supplier_id
                    WHERE d.status = 'PAID' AND d.prepared_by_id <> ? ORDER BY d.updated_at""",
                    POST_QUEUE, "Post", to("/payables/disbursements/")),
            new Source("JOURNAL_POST", "BILLING", null, """
                    SELECT b.id, b.document_number AS number, b.description, c.name AS counterparty, b.gross_amount AS amount,
                        b.document_date AS doc_date, b.status, b.prepared_by_id AS preparer_id, b.prepared_by, b.updated_at AS since
                    FROM billing_documents b JOIN customers c ON c.id = b.customer_id
                    WHERE b.status = 'APPROVED' AND b.prepared_by_id <> ? ORDER BY b.updated_at""",
                    POST_QUEUE, "Post", to("/receivables/billings/")),
            new Source("JOURNAL_POST", "RECEIPT", null, """
                    SELECT r.id, r.receipt_number AS number, replace(r.payment_method, '_', ' ') || COALESCE(' ' || r.payment_reference, '') AS description,
                        c.name AS counterparty, r.amount, r.receipt_date AS doc_date, r.status, r.recorded_by_id AS preparer_id,
                        r.recorded_by AS prepared_by, r.updated_at AS since
                    FROM receipts r JOIN customers c ON c.id = r.customer_id
                    WHERE r.status = 'RECORDED' AND r.recorded_by_id <> ? ORDER BY r.updated_at""",
                    POST_QUEUE, "Post", to("/receivables/receipts/")),
            new Source("JOURNAL_POST", "DEPOSIT", null, """
                    SELECT d.id, d.deposit_number AS number, COALESCE(d.bank_reference, 'Deposit') AS description,
                        ba.code || ' ' || ba.bank_name AS counterparty, d.amount, d.deposit_date AS doc_date, d.status,
                        d.prepared_by_id AS preparer_id, d.prepared_by, d.updated_at AS since
                    FROM deposits d JOIN bank_accounts ba ON ba.id = d.bank_account_id
                    WHERE d.status = 'RECORDED' AND d.prepared_by_id <> ? ORDER BY d.updated_at""",
                    POST_QUEUE, "Post", to("/treasury/deposits/")),
            new Source("JOURNAL_POST", "FIXED_ASSET", null, """
                    SELECT a.id, a.asset_number AS number, a.description, NULL AS counterparty, a.cost AS amount,
                        a.acquisition_date AS doc_date, a.status, a.prepared_by_id AS preparer_id, a.prepared_by, a.updated_at AS since
                    FROM fixed_assets a WHERE a.status = 'DRAFT' AND a.prepared_by_id <> ? ORDER BY a.updated_at""",
                    POST_QUEUE, "Activate", to("/fixed-assets/")),
            new Source("JOURNAL_POST", "DEPRECIATION_RUN", null, """
                    SELECT r.id, NULL AS number, 'Depreciation for ' || p.name AS description, NULL AS counterparty,
                        r.total_amount AS amount, p.end_date AS doc_date, r.status, r.prepared_by_id AS preparer_id, r.prepared_by,
                        r.updated_at AS since
                    FROM depreciation_runs r JOIN accounting_periods p ON p.id = r.period_id
                    WHERE r.status = 'DRAFT' AND r.prepared_by_id <> ? ORDER BY r.updated_at""",
                    POST_QUEUE, "Post", to("/fixed-assets/depreciation/")),
            new Source("JOURNAL_POST", "ASSET_TRANSFER", null, """
                    SELECT t.id, a.asset_number AS number, 'Transfer of ' || a.description AS description, NULL AS counterparty,
                        NULL::numeric AS amount, t.transfer_date AS doc_date, t.status, t.prepared_by_id AS preparer_id, t.prepared_by,
                        t.updated_at AS since, t.asset_id
                    FROM asset_transfers t JOIN fixed_assets a ON a.id = t.asset_id
                    WHERE t.status = 'DRAFT' AND t.prepared_by_id <> ? ORDER BY t.updated_at""",
                    POST_QUEUE, "Post", r -> "/fixed-assets/" + r.get("asset_id")),
            new Source("JOURNAL_POST", "ASSET_DISPOSAL", null, """
                    SELECT d.id, a.asset_number AS number, 'Disposal (' || lower(d.method) || ') of ' || a.description AS description,
                        NULL AS counterparty, d.proceeds AS amount, d.disposal_date AS doc_date, d.status,
                        d.prepared_by_id AS preparer_id, d.prepared_by, d.updated_at AS since, d.asset_id
                    FROM asset_disposals d JOIN fixed_assets a ON a.id = d.asset_id
                    WHERE d.status = 'DRAFT' AND d.prepared_by_id <> ? ORDER BY d.updated_at""",
                    POST_QUEUE, "Post", r -> "/fixed-assets/" + r.get("asset_id")),
            new Source("JOURNAL_POST", "INVENTORY_DOCUMENT", null, """
                    SELECT d.id, d.document_number AS number, COALESCE(d.description, replace(d.kind, '_', ' ')) AS description,
                        s.name AS counterparty, d.total_value AS amount, d.document_date AS doc_date, d.status,
                        d.prepared_by_id AS preparer_id, d.prepared_by, d.updated_at AS since
                    FROM inventory_documents d LEFT JOIN suppliers s ON s.id = d.supplier_id
                    WHERE d.status = 'DRAFT' AND d.prepared_by_id <> ? ORDER BY d.updated_at""",
                    POST_QUEUE, "Post", to("/inventory/documents/")),

            // Two-person decisions outside the document workflow.
            new Source("BUDGET_APPROVE", "BUDGET_ADJUSTMENT", null, """
                    SELECT j.id, NULL AS number, j.reason AS description, b.name AS counterparty, j.amount, NULL AS doc_date, j.status,
                        j.requested_by_id AS preparer_id, j.requested_by AS prepared_by, j.requested_at AS since, j.budget_id
                    FROM budget_adjustments j JOIN budgets b ON b.id = j.budget_id
                    WHERE j.status = 'PENDING' AND j.requested_by_id <> ? ORDER BY j.requested_at""",
                    "Budget adjustments to decide", "Decide", r -> "/budgets/" + r.get("budget_id")),
            new Source("PERIOD_REOPEN", "PERIOD_REOPEN", null, """
                    SELECT c.id, p.name AS number, c.reason AS description, NULL AS counterparty, NULL::numeric AS amount,
                        p.end_date AS doc_date, c.request_status AS status, c.requested_by_id AS preparer_id, c.requested_by AS prepared_by,
                        c.requested_at AS since
                    FROM period_status_changes c JOIN accounting_periods p ON p.id = c.period_id
                    WHERE c.request_status = 'PENDING' AND c.change_type = 'REOPEN' AND c.requested_by_id <> ? ORDER BY c.requested_at""",
                    "Period reopen requests to decide", "Decide", r -> "/fiscal-periods"),
            new Source("PORTAL_ACCESS_APPROVE", "PORTAL_ACCESS", null, """
                    SELECT r.id, u.username AS number, 'Portal access for ' || u.display_name AS description, c.name AS counterparty,
                        NULL::numeric AS amount, NULL AS doc_date, r.status, r.requested_by_id AS preparer_id, r.requested_by AS prepared_by,
                        r.requested_at AS since
                    FROM portal_link_requests r JOIN users u ON u.id = r.user_id JOIN customers c ON c.id = r.customer_id
                    WHERE r.status = 'PENDING' AND r.requested_by_id <> ? ORDER BY r.requested_at""",
                    "Portal access requests to decide", "Decide", r -> "/receivables/portal-access"),
            new Source("PORTAL_INQUIRY_RESPOND", "PORTAL_INQUIRY", null, """
                    SELECT i.id, i.reference AS number, i.subject AS description, c.name AS counterparty, i.payment_amount AS amount,
                        i.payment_date AS doc_date, i.status, i.submitted_by_id AS preparer_id, i.submitted_by AS prepared_by,
                        i.created_at AS since
                    FROM portal_inquiries i JOIN customers c ON c.id = i.customer_id
                    WHERE i.status = 'OPEN' AND i.submitted_by_id <> ? ORDER BY i.created_at""",
                    "Concessionaire inquiries to answer", "Reply", r -> "/receivables/inquiries"),
            new Source("ACCESS_GRANT_APPROVE", "ROLE_GRANT", null, """
                    SELECT g.id, u.username AS number, 'Grant of ' || replace(g.financial_added, ',', ', ') AS description,
                        u.display_name AS counterparty, NULL::numeric AS amount, NULL AS doc_date, g.status, g.requested_by_id AS preparer_id,
                        g.requested_by AS prepared_by, g.requested_at AS since
                    FROM role_grant_requests g JOIN users u ON u.id = g.user_id
                    WHERE g.status = 'PENDING' AND ? NOT IN (g.requested_by_id, g.user_id) ORDER BY g.requested_at""",
                    "Financial access grants to approve", "Decide", r -> "/admin/access-grants"),
            new Source("ACCESS_REVIEW_PERFORM", "ACCESS_REVIEW", null, """
                    SELECT a.id, u.username AS number, 'Roles: ' || replace(coalesce(a.roles_snapshot, ''), ',', ', ') AS description,
                        u.display_name AS counterparty, NULL::numeric AS amount, a.review_due AS doc_date, 'OPEN' AS status,
                        NULL::uuid AS preparer_id, c.started_by AS prepared_by, c.started_at AS since, a.cycle_id
                    FROM access_reviews a JOIN users u ON u.id = a.user_id JOIN access_review_cycles c ON c.id = a.cycle_id
                    WHERE c.status = 'OPEN' AND a.outcome IS NULL AND a.user_id <> ? ORDER BY a.financial DESC, u.username""",
                    "Access reviews to complete", "Review", r -> "/admin/access-reviews/" + r.get("cycle_id")),
            new Source("USER_ADMINISTER", "ACCESS_REVIEW_ACTION", null, """
                    SELECT a.id, u.username AS number, initcap(lower(a.outcome)) || ': ' || coalesce(a.notes, '') AS description,
                        u.display_name AS counterparty, NULL::numeric AS amount, a.review_due AS doc_date, a.outcome AS status,
                        a.reviewer_id AS preparer_id, a.reviewer AS prepared_by, a.reviewed_at AS since, a.cycle_id
                    FROM access_reviews a JOIN users u ON u.id = a.user_id JOIN access_review_cycles c ON c.id = a.cycle_id
                    WHERE c.status = 'OPEN' AND a.outcome IN ('MODIFY', 'REVOKE') AND a.actioned_at IS NULL AND ? IS NOT NULL
                    ORDER BY a.reviewed_at""",
                    "Access review changes to apply", "Apply", r -> "/admin/access-reviews/" + r.get("cycle_id")),
            new Source("MIGRATION_APPROVE", "MIGRATION_BATCH", null, """
                    SELECT m.id, NULL AS number, replace(m.kind, '_', ' ') || ': ' || m.description AS description, NULL AS counterparty,
                        m.total_amount AS amount, m.cutover_date AS doc_date, m.status, m.uploaded_by_id AS preparer_id,
                        m.uploaded_by AS prepared_by, m.uploaded_at AS since
                    FROM migration_batches m
                    WHERE m.status = 'VALIDATED' AND m.kind <> 'GL_OPENING' AND m.uploaded_by_id <> ? ORDER BY m.uploaded_at""",
                    "Migration batches and parallel runs", "Apply", to("/migration/")),
            new Source("MIGRATION_APPROVE", "PARALLEL_RUN", null, """
                    SELECT r.id, p.name AS number, 'Parallel run against ' || r.legacy_system AS description, NULL AS counterparty,
                        r.legacy_total AS amount, p.end_date AS doc_date, r.status, r.uploaded_by_id AS preparer_id,
                        r.uploaded_by AS prepared_by, r.uploaded_at AS since
                    FROM parallel_runs r JOIN accounting_periods p ON p.id = r.period_id
                    WHERE r.status = 'OPEN' AND r.uploaded_by_id <> ? ORDER BY r.uploaded_at""",
                    "Migration batches and parallel runs", "Sign off", to("/migration/parallel-runs/")));

    static {
        // Guard: every source selects the columns the mapper reads.
        for (Source s : SOURCES) {
            for (String col : COLS.split(", ")) {
                if (!s.sql().contains(col)) {
                    throw new IllegalStateException("Inbox source " + s.documentType() + " lacks column " + col);
                }
            }
        }
    }
}
