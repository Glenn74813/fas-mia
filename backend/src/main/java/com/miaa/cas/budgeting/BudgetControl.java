package com.miaa.cas.budgeting;

import com.miaa.cas.audit.AuditService;
import com.miaa.cas.budgeting.BudgetDtos.Demand;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.money.Money;
import com.miaa.cas.config.BudgetProperties;
import com.miaa.cas.generalledger.PostingBudgetGuard;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Budget availability (clarification C-10): available = allotment - active reservations - actual
 * spending posted in the fiscal year on the allocation's account (and cost centre, when the allocation
 * is per cost centre). Allocation rows are locked in id order before checking, so concurrent
 * reservations and postings cannot overspend. Only accounts flagged budget-controlled are checked.
 */
@Service
public class BudgetControl implements PostingBudgetGuard {

    record Allocation(UUID id, UUID accountId, UUID costCenterId, BigDecimal amount, String label) {
    }

    private final JdbcTemplate jdbc;
    private final BudgetProperties properties;
    private final AuditService audit;
    private final Clock clock;

    public BudgetControl(JdbcTemplate jdbc, BudgetProperties properties, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.audit = audit;
        this.clock = clock;
    }

    /** Reserves budget for an in-process document; throws (BLOCK) or warns when insufficient. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(String sourceType, UUID sourceId, UUID fiscalYearId, List<Demand> demands) {
        if (properties.off()) {
            return;
        }
        Map<Allocation, BigDecimal> needs = resolve(fiscalYearId, demands, sourceType + ":" + sourceId);
        Timestamp now = Timestamp.from(Instant.now(clock));
        for (Map.Entry<Allocation, BigDecimal> e : needs.entrySet()) {
            jdbc.update("INSERT INTO budget_reservations (id, allocation_id, source_type, source_id, amount, status, created_at, "
                    + "updated_at) VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?)", UUID.randomUUID(), e.getKey().id(), sourceType,
                    sourceId, e.getValue(), now, now);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void release(String sourceType, UUID sourceId) {
        setReservationStatus(sourceType, sourceId, "RELEASED");
    }

    /** Called after the source document's posting: the spending is now an actual, not a reservation. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(String sourceType, UUID sourceId) {
        setReservationStatus(sourceType, sourceId, "CONSUMED");
    }

    @Override
    public void onPosting(UUID fiscalYearId, String sourceType, String sourceReference, List<GuardLine> lines,
            boolean budgetReservedBySource) {
        if (properties.off() || budgetReservedBySource) {
            return;
        }
        Map<String, Demand> net = new LinkedHashMap<>();
        for (GuardLine l : lines) {
            String key = l.accountId() + "|" + l.costCenterId();
            Demand d = net.getOrDefault(key, new Demand(l.accountId(), l.costCenterId(), BigDecimal.ZERO));
            net.put(key, new Demand(l.accountId(), l.costCenterId(), d.amount().add(l.debit()).subtract(l.credit())));
        }
        List<Demand> increases = net.values().stream().filter(d -> d.amount().signum() > 0).toList();
        resolve(fiscalYearId, increases, sourceType + ":" + sourceReference);
    }

    /**
     * Locks the matching allocations and verifies availability for every budget-controlled demand.
     * Returns the amount to reserve per allocation.
     */
    private Map<Allocation, BigDecimal> resolve(UUID fiscalYearId, List<Demand> demands, String sourceLabel) {
        List<Demand> controlled = demands.stream().filter(d -> isBudgetControlled(d.accountId())).toList();
        Map<Allocation, BigDecimal> needs = new LinkedHashMap<>();
        if (controlled.isEmpty()) {
            return needs;
        }
        List<String> problems = new ArrayList<>();
        UUID budgetId = approvedBudget(fiscalYearId);
        Map<UUID, Allocation> byId = new HashMap<>();
        for (Demand d : controlled) {
            Allocation a = budgetId == null ? null : findAllocation(budgetId, d.accountId(), d.costCenterId());
            if (a == null) {
                problems.add("No approved budget allocation for account " + accountCode(d.accountId())
                        + (d.costCenterId() == null ? "" : " / cost centre " + costCenterCode(d.costCenterId())) + ".");
                continue;
            }
            byId.put(a.id(), a);
            needs.merge(a, Money.of(d.amount()), BigDecimal::add);
        }
        // Lock in a stable order, then compute availability under the lock.
        List<Allocation> ordered = new ArrayList<>(byId.values());
        ordered.sort(Comparator.comparing(Allocation::id));
        for (Allocation a : ordered) {
            jdbc.queryForObject("SELECT amount FROM budget_allocations WHERE id = ? FOR UPDATE", BigDecimal.class, a.id());
            BigDecimal available = available(a, fiscalYearId);
            BigDecimal need = needs.get(a);
            if (available.compareTo(need) < 0) {
                problems.add("Insufficient budget for " + a.label() + ": available " + available.toPlainString()
                        + ", required " + need.toPlainString() + ".");
            }
        }
        if (!problems.isEmpty()) {
            if (properties.blocks()) {
                throw new ApiException(ErrorCode.ACCOUNTING_RULE_VIOLATION, "Budget control: the expenditure is not covered.",
                        Map.of("violations", problems));
            }
            audit.record("BUDGET_EXCEEDED_WARNING", "BUDGET", fiscalYearId, Map.of("source", sourceLabel, "problems", problems));
        }
        return needs;
    }

    /**
     * Where an account and cost centre stand against the approved Corporate Operating Budget, for a reviewer (no locks, no
     * reservation). Status: NOT_CONTROLLED, NO_BUDGET (no approved budget for the year), NO_ALLOCATION or ALLOCATED.
     * "Used" is the active reservations plus posted actuals, so it already includes documents that reserved budget.
     */
    public record Position(String status, String allocation, BigDecimal allotment, BigDecimal used, BigDecimal available) {
    }

    public Position position(UUID fiscalYearId, UUID accountId, UUID costCenterId) {
        if (!isBudgetControlled(accountId)) {
            return new Position("NOT_CONTROLLED", null, null, null, null);
        }
        UUID budgetId = approvedBudget(fiscalYearId);
        if (budgetId == null) {
            return new Position("NO_BUDGET", null, null, null, null);
        }
        Allocation a = findAllocation(budgetId, accountId, costCenterId);
        if (a == null) {
            return new Position("NO_ALLOCATION", null, null, null, null);
        }
        BigDecimal available = available(a, fiscalYearId);
        return new Position("ALLOCATED", a.label(), Money.of(a.amount()), Money.of(a.amount().subtract(available)), available);
    }

    BigDecimal available(Allocation a, UUID fiscalYearId) {
        BigDecimal reserved = jdbc.queryForObject("SELECT coalesce(sum(amount), 0) FROM budget_reservations "
                + "WHERE allocation_id = ? AND status = 'ACTIVE'", BigDecimal.class, a.id());
        return Money.of(a.amount().subtract(reserved).subtract(actual(a.accountId(), a.costCenterId(), fiscalYearId)));
    }

    public BigDecimal actual(UUID accountId, UUID costCenterId, UUID fiscalYearId) {
        String ccFilter = costCenterId == null ? "" : " AND l.cost_center_id = ?";
        List<Object> args = new ArrayList<>(List.of(accountId, fiscalYearId));
        if (costCenterId != null) {
            args.add(costCenterId);
        }
        return jdbc.queryForObject("SELECT coalesce(sum(l.debit - l.credit), 0) FROM journal_entry_lines l "
                + "JOIN accounting_periods p ON p.id = l.period_id WHERE l.account_id = ? AND p.fiscal_year_id = ?" + ccFilter,
                BigDecimal.class, args.toArray());
    }

    public BigDecimal reserved(UUID allocationId) {
        return jdbc.queryForObject("SELECT coalesce(sum(amount), 0) FROM budget_reservations WHERE allocation_id = ? "
                + "AND status = 'ACTIVE'", BigDecimal.class, allocationId);
    }

    private void setReservationStatus(String sourceType, UUID sourceId, String status) {
        jdbc.update("UPDATE budget_reservations SET status = ?, updated_at = ? WHERE source_type = ? AND source_id = ? "
                + "AND status = 'ACTIVE'", status, Timestamp.from(Instant.now(clock)), sourceType, sourceId);
    }

    private boolean isBudgetControlled(UUID accountId) {
        Boolean b = jdbc.queryForObject("SELECT budget_controlled FROM chart_of_accounts WHERE id = ?", Boolean.class, accountId);
        return Boolean.TRUE.equals(b);
    }

    private UUID approvedBudget(UUID fiscalYearId) {
        List<UUID> ids = jdbc.queryForList("SELECT id FROM budgets WHERE fiscal_year_id = ? AND status = 'APPROVED'",
                UUID.class, fiscalYearId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** Per-cost-centre allocation first, then the account-level allocation. */
    private Allocation findAllocation(UUID budgetId, UUID accountId, UUID costCenterId) {
        List<Allocation> rows = jdbc.query("""
                SELECT ba.id, ba.account_id, ba.cost_center_id, ba.amount, a.code || coalesce(' / ' || cc.code, '') AS label
                FROM budget_allocations ba JOIN chart_of_accounts a ON a.id = ba.account_id
                LEFT JOIN cost_centers cc ON cc.id = ba.cost_center_id
                WHERE ba.budget_id = ? AND ba.account_id = ? AND (ba.cost_center_id = ? OR ba.cost_center_id IS NULL)
                ORDER BY ba.cost_center_id NULLS LAST""", (rs, i) -> new Allocation(rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getBigDecimal(4), rs.getString(5)),
                budgetId, accountId, costCenterId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String accountCode(UUID id) {
        return jdbc.queryForObject("SELECT code FROM chart_of_accounts WHERE id = ?", String.class, id);
    }

    private String costCenterCode(UUID id) {
        return jdbc.queryForObject("SELECT code FROM cost_centers WHERE id = ?", String.class, id);
    }
}
