package com.miaa.cas.budgeting;

import com.miaa.cas.approvals.DocumentWorkflow;
import com.miaa.cas.audit.AuditService;
import com.miaa.cas.budgeting.BudgetDtos.AdjustmentRequest;
import com.miaa.cas.budgeting.BudgetDtos.AdjustmentView;
import com.miaa.cas.budgeting.BudgetDtos.AllocationLine;
import com.miaa.cas.budgeting.BudgetDtos.AllocationRequest;
import com.miaa.cas.budgeting.BudgetDtos.BudgetView;
import com.miaa.cas.budgeting.domain.Budget;
import com.miaa.cas.budgeting.domain.BudgetRepository;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.money.Money;
import com.miaa.cas.common.web.JdbcPaging;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.generalledger.FiscalCalendarService;
import com.miaa.cas.generalledger.domain.FiscalYear;
import com.miaa.cas.notifications.NotificationService;
import com.miaa.cas.security.CurrentActor;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Corporate Operating Budget: preparation, approval (BUDGET_APPROVE, never the preparer), utilisation
 * and two-person adjustments (realignment between allocations or supplemental increases).
 */
@Service
public class BudgetService {

    public static final String DOCUMENT = "BUDGET";

    private final BudgetRepository budgets;
    private final BudgetControl control;
    private final DocumentWorkflow workflow;
    private final FiscalCalendarService calendar;
    private final AuditService audit;
    private final NotificationService notifications;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public BudgetService(BudgetRepository budgets, BudgetControl control, DocumentWorkflow workflow,
            FiscalCalendarService calendar, AuditService audit, NotificationService notifications, JdbcTemplate jdbc, Clock clock) {
        this.budgets = budgets;
        this.control = control;
        this.workflow = workflow;
        this.calendar = calendar;
        this.audit = audit;
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<BudgetView> list(int page, int size) {
        return JdbcPaging.ids(jdbc, "SELECT id FROM budgets ORDER BY created_at DESC, id", page, size).map(id -> view(load(id)));
    }

    @Transactional(readOnly = true)
    public BudgetView get(UUID id) {
        return view(load(id));
    }

    @Transactional
    public BudgetView create(UUID fiscalYearId, String name) {
        FiscalYear fy = calendar.fiscalYear(fiscalYearId);
        if (budgets.findByFiscalYearId(fiscalYearId).isPresent()) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "A budget for fiscal year " + fy.getFiscalYear()
                    + " already exists; use adjustments to change it.");
        }
        Budget b = budgets.saveAndFlush(new Budget(UUID.randomUUID(), fiscalYearId, name.trim(), CurrentActor.userId(),
                CurrentActor.username(), Instant.now(clock)));
        audit.record("BUDGET_CREATED", "BUDGET", b.getId(), Map.of("fiscalYear", fy.getFiscalYear()));
        return view(b);
    }

    @Transactional
    public BudgetView setAllocations(UUID id, long version, List<AllocationRequest> allocations) {
        Budget b = lockVersioned(id, version);
        if (!b.isEditable()) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Allocations can only be edited while the budget is a draft.");
        }
        requirePreparer(b);
        List<String> problems = new ArrayList<>();
        Map<UUID, Boolean> accountLevel = new java.util.HashMap<>();
        Set<String> seen = new HashSet<>();
        for (AllocationRequest a : allocations) {
            Map<String, Object> acct = jdbc.queryForList("SELECT code, postable, budget_controlled FROM chart_of_accounts "
                    + "WHERE id = ?", a.accountId()).stream().findFirst().orElse(null);
            if (acct == null) {
                problems.add("Unknown account " + a.accountId());
                continue;
            }
            if (!Boolean.TRUE.equals(acct.get("postable")) || !Boolean.TRUE.equals(acct.get("budget_controlled"))) {
                problems.add("Account " + acct.get("code") + " is not a postable budget-controlled account.");
            }
            if (!seen.add(a.accountId() + "|" + a.costCenterId())) {
                problems.add("Account " + acct.get("code") + " is allocated twice for the same cost centre.");
            }
            Boolean previous = accountLevel.put(a.accountId(), a.costCenterId() == null);
            if (previous != null && previous != (a.costCenterId() == null)) {
                problems.add("Account " + acct.get("code") + " mixes account-level and cost-centre allocations; use one.");
            }
        }
        if (!problems.isEmpty()) {
            throw new ApiException(ErrorCode.ACCOUNTING_RULE_VIOLATION, "The allocations are not valid.",
                    Map.of("violations", problems));
        }
        jdbc.update("DELETE FROM budget_allocations WHERE budget_id = ?", id);
        BigDecimal total = BigDecimal.ZERO;
        for (AllocationRequest a : allocations) {
            jdbc.update("INSERT INTO budget_allocations (id, budget_id, account_id, cost_center_id, amount) VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID(), id, a.accountId(), a.costCenterId(), Money.of(a.amount()));
            total = total.add(a.amount());
        }
        b.touch(Instant.now(clock), CurrentActor.username());
        budgets.flush();
        audit.record("BUDGET_ALLOCATIONS_SET", "BUDGET", id, Map.of("lines", allocations.size(), "total", Money.of(total).toPlainString()));
        return view(b);
    }

    @Transactional
    public BudgetView submit(UUID id, long version, String comment) {
        Budget b = lockVersioned(id, version);
        requirePreparer(b);
        if (!b.isEditable()) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Only draft or returned budgets can be submitted.");
        }
        Integer count = jdbc.queryForObject("SELECT count(*) FROM budget_allocations WHERE budget_id = ?", Integer.class, id);
        if (count == null || count == 0) {
            throw new ApiException(ErrorCode.ACCOUNTING_RULE_VIOLATION, "Add at least one allocation before submitting.",
                    Map.of("violations", List.of("The budget has no allocations.")));
        }
        DocumentWorkflow.Step first = workflow.submit(DOCUMENT, id, total(id), comment);
        Instant now = Instant.now(clock);
        b.moveTo(first == null ? "APPROVED" : "SUBMITTED", first == null ? null : (short) first.order(), now,
                CurrentActor.username());
        budgets.flush();
        audit.record("BUDGET_SUBMITTED", "BUDGET", id, Map.of("total", total(id).toPlainString()));
        return view(b);
    }

    @Transactional
    public BudgetView approve(UUID id, long version, String comment) {
        Budget b = lockVersioned(id, version);
        if (!"SUBMITTED".equals(b.getStatus())) {
            throw new ApiException(ErrorCode.INVALID_STATE, "The budget is not awaiting approval.");
        }
        DocumentWorkflow.Step next = workflow.approve(DOCUMENT, id, total(id), toInt(b.getCurrentStepOrder()),
                b.getPreparedById(), comment);
        b.moveTo(next == null ? "APPROVED" : "SUBMITTED", next == null ? null : (short) next.order(), Instant.now(clock),
                CurrentActor.username());
        budgets.flush();
        audit.record(next == null ? "BUDGET_APPROVED" : "BUDGET_STEP_APPROVED", "BUDGET", id, Map.of("total",
                total(id).toPlainString()));
        return view(b);
    }

    @Transactional
    public BudgetView returnBudget(UUID id, long version, String comment) {
        Budget b = lockVersioned(id, version);
        if (!"SUBMITTED".equals(b.getStatus())) {
            throw new ApiException(ErrorCode.INVALID_STATE, "The budget is not awaiting approval.");
        }
        workflow.returnOrReject(DOCUMENT, id, total(id), toInt(b.getCurrentStepOrder()), b.getPreparedById(), comment, false);
        b.moveTo("RETURNED", null, Instant.now(clock), CurrentActor.username());
        budgets.flush();
        audit.record("BUDGET_RETURNED", "BUDGET", id, Map.of("comment", comment));
        return view(b);
    }

    @Transactional
    public AdjustmentView requestAdjustment(UUID budgetId, AdjustmentRequest req) {
        Budget b = load(budgetId);
        if (!"APPROVED".equals(b.getStatus())) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Adjustments apply to approved budgets.");
        }
        requireAllocation(budgetId, req.toAllocationId());
        if (req.fromAllocationId() != null) {
            requireAllocation(budgetId, req.fromAllocationId());
            if (req.fromAllocationId().equals(req.toAllocationId())) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Choose two different allocations.");
            }
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO budget_adjustments (id, budget_id, from_allocation_id, to_allocation_id, amount, reason, status,
                    requested_by_id, requested_by, requested_at) VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?)""",
                id, budgetId, req.fromAllocationId(), req.toAllocationId(), Money.of(req.amount()), req.reason().trim(),
                CurrentActor.userId(), CurrentActor.username(), Timestamp.from(Instant.now(clock)));
        audit.record("BUDGET_ADJUSTMENT_REQUESTED", "BUDGET", budgetId, Map.of("adjustmentId", id.toString(),
                "amount", Money.of(req.amount()).toPlainString(), "type", req.fromAllocationId() == null ? "SUPPLEMENTAL" : "REALIGNMENT"));
        return adjustment(id);
    }

    /** Applies or rejects an adjustment. A realignment may only move budget that is still available. */
    @Transactional
    public AdjustmentView decideAdjustment(UUID adjustmentId, boolean approve) {
        CurrentActor.requirePermission("BUDGET_APPROVE");
        Map<String, Object> adj = jdbc.queryForList("SELECT * FROM budget_adjustments WHERE id = ? FOR UPDATE", adjustmentId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Budget adjustment"));
        if (!"PENDING".equals(adj.get("status"))) {
            throw new ApiException(ErrorCode.INVALID_STATE, "The adjustment has already been decided.");
        }
        if (CurrentActor.userId().equals(adj.get("requested_by_id"))) {
            throw new ApiException(ErrorCode.SEGREGATION_OF_DUTIES, "Adjustments must be decided by someone other than the requester.");
        }
        UUID budgetId = (UUID) adj.get("budget_id");
        BigDecimal amount = (BigDecimal) adj.get("amount");
        UUID from = (UUID) adj.get("from_allocation_id");
        UUID to = (UUID) adj.get("to_allocation_id");
        if (approve) {
            UUID fyId = load(budgetId).getFiscalYearId();
            List<UUID> lockIds = new ArrayList<>(from == null ? List.of(to) : List.of(from, to));
            lockIds.sort(UUID::compareTo);
            lockIds.forEach(l -> jdbc.queryForObject("SELECT amount FROM budget_allocations WHERE id = ? FOR UPDATE",
                    BigDecimal.class, l));
            if (from != null) {
                AllocationLine source = allocationLine(from, fyId);
                if (source.available().compareTo(amount) < 0) {
                    throw new ApiException(ErrorCode.ACCOUNTING_RULE_VIOLATION, "The source allocation does not have enough available budget.",
                            Map.of("violations", List.of("Available " + source.available().toPlainString() + ", requested "
                                    + amount.toPlainString() + ".")));
                }
                jdbc.update("UPDATE budget_allocations SET amount = amount - ?, version = version + 1 WHERE id = ?", amount, from);
            }
            jdbc.update("UPDATE budget_allocations SET amount = amount + ?, version = version + 1 WHERE id = ?", amount, to);
        }
        jdbc.update("UPDATE budget_adjustments SET status = ?, decided_by_id = ?, decided_by = ?, decided_at = ? WHERE id = ?",
                approve ? "APPROVED" : "REJECTED", CurrentActor.userId(), CurrentActor.username(),
                Timestamp.from(Instant.now(clock)), adjustmentId);
        notifications.notify((UUID) adj.get("requested_by_id"), approve ? "REQUEST_APPROVED" : "REQUEST_REJECTED", "Budget adjustment of "
                + amount.toPlainString() + " was " + (approve ? "approved" : "rejected") + " by " + CurrentActor.username(),
                (String) adj.get("reason"), "/budgets/" + budgetId);
        audit.record(approve ? "BUDGET_ADJUSTMENT_APPROVED" : "BUDGET_ADJUSTMENT_REJECTED", "BUDGET", budgetId,
                Map.of("adjustmentId", adjustmentId.toString(), "amount", amount.toPlainString()));
        return adjustment(adjustmentId);
    }

    @Transactional(readOnly = true)
    public List<AdjustmentView> adjustments(UUID budgetId) {
        return jdbc.queryForList("SELECT id FROM budget_adjustments WHERE budget_id = ? ORDER BY requested_at DESC",
                UUID.class, budgetId).stream().map(this::adjustment).toList();
    }

    private AdjustmentView adjustment(UUID id) {
        return jdbc.queryForObject("""
                SELECT j.*, fa.code || coalesce(' / ' || fc.code, '') AS from_label, ta.code || coalesce(' / ' || tc.code, '') AS to_label
                FROM budget_adjustments j
                LEFT JOIN budget_allocations f ON f.id = j.from_allocation_id LEFT JOIN chart_of_accounts fa ON fa.id = f.account_id
                LEFT JOIN cost_centers fc ON fc.id = f.cost_center_id
                JOIN budget_allocations t ON t.id = j.to_allocation_id JOIN chart_of_accounts ta ON ta.id = t.account_id
                LEFT JOIN cost_centers tc ON tc.id = t.cost_center_id WHERE j.id = ?""", (rs, i) -> new AdjustmentView(
                rs.getObject("id", UUID.class), rs.getObject("from_allocation_id", UUID.class),
                rs.getString("from_label") == null ? "Supplemental" : rs.getString("from_label"),
                rs.getObject("to_allocation_id", UUID.class), rs.getString("to_label"), rs.getBigDecimal("amount"),
                rs.getString("reason"), rs.getString("status"), rs.getString("requested_by"),
                rs.getTimestamp("requested_at").toInstant(), rs.getString("decided_by"),
                rs.getTimestamp("decided_at") == null ? null : rs.getTimestamp("decided_at").toInstant()), id);
    }

    private BudgetView view(Budget b) {
        FiscalYear fy = calendar.fiscalYear(b.getFiscalYearId());
        List<AllocationLine> lines = jdbc.queryForList("SELECT ba.id FROM budget_allocations ba JOIN chart_of_accounts a "
                + "ON a.id = ba.account_id LEFT JOIN cost_centers cc ON cc.id = ba.cost_center_id WHERE ba.budget_id = ? "
                + "ORDER BY a.code, cc.code NULLS FIRST", UUID.class, b.getId()).stream()
                .map(id -> allocationLine(id, b.getFiscalYearId())).toList();
        BigDecimal allot = sum(lines, AllocationLine::allotment);
        BigDecimal res = sum(lines, AllocationLine::reserved);
        BigDecimal act = sum(lines, AllocationLine::actual);
        BigDecimal avail = sum(lines, AllocationLine::available);
        List<String> actions = new ArrayList<>();
        boolean preparer = CurrentActor.userId().equals(b.getPreparedById());
        if (b.isEditable() && preparer && CurrentActor.hasPermission("BUDGET_PREPARE")) {
            actions.add("EDIT");
            actions.add("SUBMIT");
        }
        if ("SUBMITTED".equals(b.getStatus()) && workflow.canAct(DOCUMENT, b.getId(), allot, toInt(b.getCurrentStepOrder()),
                b.getPreparedById())) {
            actions.add("APPROVE");
            actions.add("RETURN");
        }
        if ("APPROVED".equals(b.getStatus()) && CurrentActor.hasPermission("BUDGET_PREPARE")) {
            actions.add("REQUEST_ADJUSTMENT");
        }
        return new BudgetView(b.getId(), b.getFiscalYearId(), fy.getFiscalYear(), b.getName(), b.getStatus(),
                workflow.currentStepCode(DOCUMENT, allot, toInt(b.getCurrentStepOrder())), b.getPreparedBy(), b.getApprovedAt(),
                b.getApprovedBy(), lines, allot, res, act, avail, actions, b.getVersion());
    }

    private AllocationLine allocationLine(UUID allocationId, UUID fiscalYearId) {
        return jdbc.queryForObject("""
                SELECT ba.id, ba.account_id, a.code, a.name, ba.cost_center_id, cc.code AS cc_code, ba.amount
                FROM budget_allocations ba JOIN chart_of_accounts a ON a.id = ba.account_id
                LEFT JOIN cost_centers cc ON cc.id = ba.cost_center_id WHERE ba.id = ?""", (rs, i) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    UUID account = rs.getObject("account_id", UUID.class);
                    UUID cc = rs.getObject("cost_center_id", UUID.class);
                    BigDecimal allot = rs.getBigDecimal("amount");
                    BigDecimal reserved = control.reserved(id);
                    BigDecimal actual = control.actual(account, cc, fiscalYearId);
                    BigDecimal available = Money.of(allot.subtract(reserved).subtract(actual));
                    BigDecimal pct = allot.signum() == 0 ? BigDecimal.ZERO
                            : reserved.add(actual).multiply(BigDecimal.valueOf(100)).divide(allot, 1, RoundingMode.HALF_EVEN);
                    return new AllocationLine(id, account, rs.getString("code"), rs.getString("name"), cc, rs.getString("cc_code"),
                            Money.of(allot), Money.of(reserved), Money.of(actual), available, pct);
                }, allocationId);
    }

    private BigDecimal total(UUID budgetId) {
        return Money.of(jdbc.queryForObject("SELECT coalesce(sum(amount), 0) FROM budget_allocations WHERE budget_id = ?",
                BigDecimal.class, budgetId));
    }

    private void requireAllocation(UUID budgetId, UUID allocationId) {
        Boolean ok = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM budget_allocations WHERE id = ? AND budget_id = ?)",
                Boolean.class, allocationId, budgetId);
        if (!Boolean.TRUE.equals(ok)) {
            throw ApiException.notFound("Budget allocation");
        }
    }

    private static void requirePreparer(Budget b) {
        CurrentActor.requirePermission("BUDGET_PREPARE");
        if (!CurrentActor.userId().equals(b.getPreparedById())) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Only the preparer can change this budget.");
        }
    }

    private Budget load(UUID id) {
        return budgets.findById(id).orElseThrow(() -> ApiException.notFound("Budget"));
    }

    private Budget lockVersioned(UUID id, long version) {
        Budget b = budgets.findByIdForUpdate(id).orElseThrow(() -> ApiException.notFound("Budget"));
        if (b.getVersion() != version) {
            throw ApiException.staleVersion();
        }
        return b;
    }

    private static Integer toInt(Short s) {
        return s == null ? null : s.intValue();
    }

    private static BigDecimal sum(List<AllocationLine> lines, java.util.function.Function<AllocationLine, BigDecimal> f) {
        return Money.of(lines.stream().map(f).reduce(BigDecimal.ZERO, BigDecimal::add));
    }
}
