package com.miaa.cas.journal;

import com.miaa.cas.approvals.ApprovalLimitService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.web.PageRequests;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.documents.DocumentService;
import com.miaa.cas.generalledger.domain.AccountingPeriod;
import com.miaa.cas.generalledger.domain.AccountingPeriodRepository;
import com.miaa.cas.journal.DecisionLog.Decision;
import com.miaa.cas.journal.domain.JournalVoucher;
import com.miaa.cas.journal.domain.JournalVoucherRepository;
import com.miaa.cas.journal.domain.VoucherStatus;
import com.miaa.cas.journal.domain.VoucherType;
import com.miaa.cas.journal.web.JournalDtos.LineResponse;
import com.miaa.cas.journal.web.JournalDtos.StepState;
import com.miaa.cas.journal.web.JournalDtos.VoucherDetail;
import com.miaa.cas.journal.web.JournalDtos.VoucherSummary;
import com.miaa.cas.security.CurrentActor;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of journal vouchers: lists, the approval queue and the full detail with available actions. */
@Service
public class VoucherQueryService {

    private static final Map<String, String> SORTS = Map.of("accountingDate", "accountingDate", "updatedAt", "updatedAt",
            "voucherNumber", "voucherNumber", "total", "totalDebit");

    private final JournalVoucherRepository vouchers;
    private final AccountingPeriodRepository periods;
    private final WorkflowConfig workflow;
    private final DecisionLog decisions;
    private final DocumentService documents;
    private final JdbcTemplate jdbc;
    private final ApprovalLimitService limits;

    public VoucherQueryService(JournalVoucherRepository vouchers, AccountingPeriodRepository periods, WorkflowConfig workflow,
            DecisionLog decisions, DocumentService documents, JdbcTemplate jdbc, ApprovalLimitService limits) {
        this.limits = limits;
        this.vouchers = vouchers;
        this.periods = periods;
        this.workflow = workflow;
        this.decisions = decisions;
        this.documents = documents;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public PageResponse<VoucherSummary> list(String status, String type, LocalDate from, LocalDate to, String q,
            boolean mine, int page, int size, String sort) {
        Pageable pageable = PageRequests.of(page, size, sort, SORTS, "-updatedAt");
        Specification<JournalVoucher> spec = (root, query, cb) -> cb.conjunction();
        if (status != null && !status.isBlank()) {
            VoucherStatus s = parse(VoucherStatus.class, status);
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), s));
        }
        if (type != null && !type.isBlank()) {
            VoucherType t = parse(VoucherType.class, type);
            spec = spec.and((root, query, cb) -> cb.equal(root.get("voucherType"), t));
        }
        if (from != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("accountingDate"), from));
        }
        if (to != null) {
            spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("accountingDate"), to));
        }
        if (q != null && !q.isBlank()) {
            String pattern = "%" + q.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            spec = spec.and((root, query, cb) -> cb.or(cb.like(cb.lower(root.get("description")), pattern, '\\'),
                    cb.like(cb.lower(root.get("voucherNumber")), pattern, '\\')));
        }
        if (mine) {
            UUID me = CurrentActor.userId();
            spec = spec.and((root, query, cb) -> cb.equal(root.get("preparedById"), me));
        }
        Page<JournalVoucher> result = vouchers.findAll(spec, pageable);
        Map<UUID, String> periodNames = periodNames(result.getContent());
        return PageResponse.of(result, v -> summary(v, periodNames));
    }

    /** Vouchers on which the current user can act now: an approval step they may take, or posting. */
    @Transactional(readOnly = true)
    public List<VoucherSummary> awaitingMyAction() {
        return awaitingMyAction(null);
    }

    /**
     * As {@link #awaitingMyAction()}, oldest first, limited to vouchers whose number, description or preparer contains
     * {@code search} (case-insensitive) when it is given, so a long queue can still be searched.
     */
    @Transactional(readOnly = true)
    public List<VoucherSummary> awaitingMyAction(String search) {
        UUID me = CurrentActor.userId();
        BigDecimal limit = limits.limitOf(DecisionLog.JOURNAL_VOUCHER).orElse(null);
        String q = search == null || search.isBlank() ? null : search.trim().toLowerCase(Locale.ROOT);
        List<JournalVoucher> candidates = vouchers.findByStatusIn(List.of(VoucherStatus.SUBMITTED, VoucherStatus.APPROVED)).stream()
                .filter(v -> q == null || contains(v.getVoucherNumber(), q) || contains(v.getDescription(), q) || contains(v.getPreparedBy(), q))
                .sorted(Comparator.comparing(JournalVoucher::getUpdatedAt)).toList();
        List<JournalVoucher> mine = new ArrayList<>();
        for (JournalVoucher v : candidates) {
            if (me.equals(v.getPreparedById())) {
                continue;
            }
            if (v.getStatus() == VoucherStatus.APPROVED && CurrentActor.hasPermission("JOURNAL_POST")) {
                mine.add(v);
            } else if (v.getStatus() == VoucherStatus.SUBMITTED && v.getCurrentStepOrder() != null) {
                WorkflowConfig.Step step = workflow.steps(v.getVoucherType(), v.getTotalDebit()).stream()
                        .filter(s -> s.order() == v.getCurrentStepOrder()).findFirst().orElse(null);
                if (step != null && CurrentActor.hasPermission(step.permission()) && (limit == null || v.getTotalDebit().compareTo(limit) <= 0)
                        && !decisions.approversInCurrentCycle(v.getId()).contains(me)) {
                    mine.add(v);
                }
            }
        }
        Map<UUID, String> names = periodNames(mine);
        return mine.stream().limit(500).map(v -> summary(v, names)).toList();
    }

    @Transactional(readOnly = true)
    public VoucherDetail detail(UUID id) {
        JournalVoucher v = vouchers.findById(id).orElseThrow(() -> ApiException.notFound("Journal voucher"));
        AccountingPeriod period = periods.findById(v.getPeriodId()).orElseThrow();
        List<Decision> history = decisions.history(id);
        List<WorkflowConfig.Step> steps = workflow.steps(v.getVoucherType(), v.getTotalDebit());
        String reversalOfNumber = v.getReversalOfEntryId() == null ? null : jdbc.queryForObject(
                "SELECT entry_number FROM journal_entries WHERE id = ?", String.class, v.getReversalOfEntryId());
        return new VoucherDetail(v.getId(), v.getVoucherNumber(), v.getVoucherType().name(), v.getStatus().name(),
                v.getAccountingDate(), period.getId(), period.getName(), period.getStatus().name(), v.getDescription(),
                v.getSupportingReference(), v.getSourceType(), v.getSourceReference(), v.getCurrencyCode(), v.getTotalDebit(),
                v.getTotalCredit(), v.getTotalDebit().compareTo(v.getTotalCredit()) == 0 && v.getTotalDebit().signum() > 0,
                v.getPreparedBy(), v.getPreparedById(), v.getSubmittedAt(), v.getPostedEntryId(), v.getReversalOfEntryId(),
                reversalOfNumber, workflow.requiresAttachment(v.getVoucherType()), lines(id),
                stepStates(v, steps, history), history, documents.list("JOURNAL_VOUCHER", id), actions(v, steps),
                v.getCreatedAt(), v.getUpdatedAt(), v.getVersion());
    }

    private List<LineResponse> lines(UUID voucherId) {
        return jdbc.query("""
                SELECT l.id, l.line_no, a.id AS account_id, a.code, a.name, a.placeholder, cc.id AS cc_id, cc.code AS cc_code,
                       sl.id AS sl_id, sl.code AS sl_code, sl.name AS sl_name, l.description, l.debit, l.credit
                FROM journal_voucher_lines l JOIN chart_of_accounts a ON a.id = l.account_id
                LEFT JOIN cost_centers cc ON cc.id = l.cost_center_id
                LEFT JOIN subsidiary_ledger_accounts sl ON sl.id = l.subsidiary_account_id
                WHERE l.voucher_id = ? ORDER BY l.line_no""", (rs, i) -> new LineResponse(rs.getObject("id", UUID.class),
                rs.getInt("line_no"), rs.getObject("account_id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getBoolean("placeholder"), rs.getObject("cc_id", UUID.class), rs.getString("cc_code"),
                rs.getObject("sl_id", UUID.class), rs.getString("sl_code"), rs.getString("sl_name"), rs.getString("description"),
                rs.getBigDecimal("debit"), rs.getBigDecimal("credit")), voucherId);
    }

    private static List<StepState> stepStates(JournalVoucher v, List<WorkflowConfig.Step> steps, List<Decision> history) {
        int lastSubmit = -1;
        for (int i = 0; i < history.size(); i++) {
            if ("SUBMIT".equals(history.get(i).action())) {
                lastSubmit = i;
            }
        }
        List<Decision> cycle = history.subList(lastSubmit + 1, history.size());
        List<StepState> result = new ArrayList<>();
        for (WorkflowConfig.Step s : steps) {
            Decision done = cycle.stream().filter(d -> "APPROVE".equals(d.action()) && Integer.valueOf(s.order()).equals(d.stepOrder()))
                    .reduce((a, b) -> b).orElse(null);
            String state = done != null ? "DONE"
                    : v.getStatus() == VoucherStatus.SUBMITTED && v.getCurrentStepOrder() != null
                            && v.getCurrentStepOrder() == s.order() ? "CURRENT" : "PENDING";
            result.add(new StepState(s.order(), s.code(), s.permission(), lastSubmit < 0 ? "PENDING" : state,
                    done == null ? null : done.actorUsername(), done == null ? null : done.decidedAt()));
        }
        Decision posted = history.stream().filter(d -> "POST".equals(d.action())).findFirst().orElse(null);
        result.add(new StepState(steps.size() + 1, "POST", "JOURNAL_POST",
                posted != null ? "DONE" : v.getStatus() == VoucherStatus.APPROVED ? "CURRENT" : "PENDING",
                posted == null ? null : posted.actorUsername(), posted == null ? null : posted.decidedAt()));
        return result;
    }

    /** UI hints only; every action is re-authorised by the command services. */
    private List<String> actions(JournalVoucher v, List<WorkflowConfig.Step> steps) {
        List<String> a = new ArrayList<>();
        UUID me = CurrentActor.userId();
        boolean preparer = me.equals(v.getPreparedById());
        if (v.getStatus().isEditable() && preparer && CurrentActor.hasPermission("JOURNAL_CREATE")) {
            if (v.getVoucherType().isManuallyCreatable()) {
                a.add("EDIT");
            }
            a.add("CANCEL");
            if (CurrentActor.hasPermission("JOURNAL_SUBMIT")) {
                a.add("SUBMIT");
            }
            if (CurrentActor.hasPermission("DOCUMENT_UPLOAD")) {
                a.add("UPLOAD");
            }
        }
        if (v.getStatus() == VoucherStatus.SUBMITTED && !preparer && v.getCurrentStepOrder() != null) {
            steps.stream().filter(s -> s.order() == v.getCurrentStepOrder()).findFirst().ifPresent(s -> {
                if (CurrentActor.hasPermission(s.permission())) {
                    if (!decisions.approversInCurrentCycle(v.getId()).contains(me) && limits.allows(DecisionLog.JOURNAL_VOUCHER, v.getTotalDebit())) {
                        a.add("APPROVE");
                    }
                    a.add("RETURN");
                    a.add("REJECT");
                }
            });
        }
        if (v.getStatus() == VoucherStatus.APPROVED && !preparer && CurrentActor.hasPermission("JOURNAL_POST")) {
            a.add("POST");
            a.add("RETURN");
            a.add("REJECT");
        }
        if (v.getStatus() == VoucherStatus.POSTED && CurrentActor.hasPermission("JOURNAL_REVERSE")
                && v.getVoucherType() != VoucherType.REVERSAL && v.getVoucherType() != VoucherType.SUBLEDGER) {
            Boolean reversed = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM journal_entries WHERE reversal_of_entry_id = ?) "
                    + "OR EXISTS (SELECT 1 FROM journal_vouchers WHERE reversal_of_entry_id = ? AND status NOT IN ('REJECTED', 'CANCELLED'))",
                    Boolean.class, v.getPostedEntryId(), v.getPostedEntryId());
            if (!Boolean.TRUE.equals(reversed)) {
                a.add("REVERSE");
            }
        }
        return a;
    }

    private Map<UUID, String> periodNames(List<JournalVoucher> list) {
        Map<UUID, String> names = new HashMap<>();
        periods.findAllById(list.stream().map(JournalVoucher::getPeriodId).distinct().toList())
                .forEach(p -> names.put(p.getId(), p.getName()));
        return names;
    }

    private VoucherSummary summary(JournalVoucher v, Map<UUID, String> periodNames) {
        String step = null;
        if (v.getStatus() == VoucherStatus.SUBMITTED && v.getCurrentStepOrder() != null) {
            step = workflow.steps(v.getVoucherType(), v.getTotalDebit()).stream()
                    .filter(s -> s.order() == v.getCurrentStepOrder()).map(WorkflowConfig.Step::code).findFirst().orElse(null);
        } else if (v.getStatus() == VoucherStatus.APPROVED) {
            step = "POST";
        }
        return new VoucherSummary(v.getId(), v.getVoucherNumber(), v.getVoucherType().name(), v.getStatus().name(),
                v.getAccountingDate(), periodNames.get(v.getPeriodId()), v.getDescription(), v.getTotalDebit(),
                v.getTotalCredit(), v.getPreparedBy(), step, v.getUpdatedAt(), v.getVersion());
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException(com.miaa.cas.common.error.ErrorCode.VALIDATION_FAILED, "Unsupported value '" + value + "'.");
        }
    }

    private static boolean contains(String value, String q) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(q);
    }
}
