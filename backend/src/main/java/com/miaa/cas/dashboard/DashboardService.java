package com.miaa.cas.dashboard;

import com.miaa.cas.accountspayable.ApDtos.AgingReport;
import com.miaa.cas.accountspayable.ApDtos.AgingRow;
import com.miaa.cas.accountspayable.ApReportService;
import com.miaa.cas.accountsreceivable.ArDtos.ArAgingReport;
import com.miaa.cas.accountsreceivable.ArDtos.ArAgingRow;
import com.miaa.cas.accountsreceivable.ArReportService;
import com.miaa.cas.approvals.ApprovalInboxService;
import com.miaa.cas.budgeting.BudgetDtos.BudgetView;
import com.miaa.cas.budgeting.BudgetService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.money.Money;
import com.miaa.cas.security.CurrentActor;
import com.miaa.cas.treasury.TreasuryDtos.CashPosition;
import com.miaa.cas.treasury.TreasuryReportService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The signed-in user's dashboard (MS §11.3, §11.8, §12): each section appears only when the user holds the
 * permission behind it, so a cashier, an accountant, a budget officer and a finance manager each see their own
 * view. The indicators are proposed until AVTECH approves its KPIs; each is computed from posted records, never stored.
 */
@Service
public class DashboardService {

    static final ZoneId MANILA = ZoneId.of("Asia/Manila");
    private static final int MONTHS = 6;

    public record MonthAmount(String month, BigDecimal amount) {
    }

    public record NamedAmount(String code, String name, BigDecimal amount) {
    }

    public record Aging(BigDecimal current, BigDecimal days1to30, BigDecimal days31to60, BigDecimal days61to90, BigDecimal over90,
            BigDecimal total) {
    }

    public record CashSection(BigDecimal cashOnHand, BigDecimal undeposited, List<NamedAmount> banks, BigDecimal total) {
    }

    public record ReceivablesSection(Aging aging, List<NamedAmount> topCustomers, List<MonthAmount> collections, BigDecimal unapplied) {
    }

    public record PayablesSection(Aging aging, BigDecimal dueNext30Days, List<NamedAmount> topSuppliers, List<MonthAmount> disbursements) {
    }

    public record BudgetLine(String account, String costCenter, BigDecimal allotment, BigDecimal used, BigDecimal utilisationPercent) {
    }

    public record BudgetSection(int fiscalYear, BigDecimal allotment, BigDecimal reserved, BigDecimal actual, BigDecimal available,
            BigDecimal utilisationPercent, List<BudgetLine> highestUtilisation) {
    }

    public record WorkItem(String label, long count, String link) {
    }

    public record TicketSection(long openAssignedToMe, long openTotal, long breached) {
    }

    public record Dashboard(LocalDate asOf, CashSection cash, ReceivablesSection receivables, PayablesSection payables, BudgetSection budget,
            List<WorkItem> workQueue, TicketSection tickets, List<String> notes) {
    }

    private final JdbcTemplate jdbc;
    private final TreasuryReportService treasury;
    private final ArReportService ar;
    private final ApReportService ap;
    private final ApprovalInboxService inbox;
    private final BudgetService budgets;
    private final Clock clock;

    public DashboardService(JdbcTemplate jdbc, TreasuryReportService treasury, ArReportService ar, ApReportService ap,
            ApprovalInboxService inbox, BudgetService budgets, Clock clock) {
        this.budgets = budgets;
        this.jdbc = jdbc;
        this.treasury = treasury;
        this.ar = ar;
        this.ap = ap;
        this.inbox = inbox;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Dashboard mine() {
        LocalDate today = LocalDate.now(clock.withZone(MANILA));
        List<String> notes = new ArrayList<>(List.of("Indicators are proposed until AVTECH approves its key performance indicators. "
                + "All figures come from posted records."));
        CashSection cash = null;
        if (CurrentActor.hasPermission("TREASURY_VIEW")) {
            try {
                CashPosition p = treasury.cashPosition(today);
                List<NamedAmount> banks = p.banks().stream().map(b -> new NamedAmount(b.code(), b.bankName(), b.bookBalance())).toList();
                cash = new CashSection(p.cashOnHandBook(), p.undepositedCollections(), banks, Money.of(p.cashOnHandBook().add(p.totalBank())));
            } catch (ApiException e) {
                notes.add("Cash: " + e.getMessage());
            }
        }
        ReceivablesSection receivables = null;
        if (CurrentActor.hasPermission("AR_VIEW")) {
            ArAgingReport a = ar.aging(today, null);
            ArAgingRow t = a.totals();
            List<NamedAmount> top = a.rows().stream().sorted(Comparator.comparing(ArAgingRow::total).reversed()).limit(5)
                    .map(r -> new NamedAmount(r.customerCode(), r.customerName(), r.total())).toList();
            BigDecimal unapplied = Money.of(jdbc.queryForObject("SELECT coalesce(sum(unapplied_amount), 0) FROM receipts WHERE status = 'POSTED'",
                    BigDecimal.class));
            receivables = new ReceivablesSection(new Aging(t.current(), t.days1to30(), t.days31to60(), t.days61to90(), t.over90(), t.total()),
                    top, monthly("""
                            SELECT date_trunc('month', r.receipt_date)::date AS m, sum(r.amount) AS amt FROM receipts r
                            WHERE r.status = 'POSTED' AND r.migration_batch_id IS NULL AND r.receipt_date >= ? GROUP BY 1""", today), unapplied);
        }
        PayablesSection payables = null;
        if (CurrentActor.hasPermission("AP_VIEW")) {
            AgingReport a = ap.aging(today);
            AgingRow t = a.totals();
            List<NamedAmount> top = a.rows().stream().sorted(Comparator.comparing(AgingRow::total).reversed()).limit(5)
                    .map(r -> new NamedAmount(r.supplierCode(), r.supplierName(), r.total())).toList();
            BigDecimal due30 = Money.of(jdbc.queryForObject("""
                    SELECT coalesce(sum(payable_amount - paid_amount), 0) FROM supplier_invoices
                    WHERE status = 'POSTED' AND payable_amount > paid_amount AND due_date BETWEEN ? AND ?""", BigDecimal.class,
                    Date.valueOf(today), Date.valueOf(today.plusDays(30))));
            payables = new PayablesSection(new Aging(t.current(), t.days1to30(), t.days31to60(), t.days61to90(), t.over90(), t.total()),
                    due30, top, monthly("""
                            SELECT date_trunc('month', e.accounting_date)::date AS m, sum(v.amount) AS amt FROM disbursement_vouchers v
                            JOIN journal_entries e ON e.id = v.posted_entry_id WHERE v.status = 'POSTED' AND e.accounting_date >= ? GROUP BY 1""", today));
        }
        BudgetSection budget = CurrentActor.hasPermission("BUDGET_VIEW") ? budget(today) : null;
        TicketSection tickets = null;
        if (CurrentActor.hasPermission("TICKET_WORK") || CurrentActor.hasPermission("TICKET_VIEW_ALL")) {
            Map<String, Object> r = jdbc.queryForMap("""
                    SELECT count(*) FILTER (WHERE assignee_id = ?) AS mine, count(*) AS open,
                           count(*) FILTER (WHERE response_breached OR resolution_breached) AS breached
                    FROM tickets WHERE status NOT IN ('RESOLVED', 'CLOSED', 'CANCELLED')""", CurrentActor.userId());
            tickets = new TicketSection(((Number) r.get("mine")).longValue(), ((Number) r.get("open")).longValue(),
                    ((Number) r.get("breached")).longValue());
        }
        return new Dashboard(today, cash, receivables, payables, budget, workQueue(), tickets, notes);
    }

    /** The last six calendar months (oldest first), zero where nothing was recorded. */
    private List<MonthAmount> monthly(String sql, LocalDate today) {
        YearMonth first = YearMonth.from(today).minusMonths(MONTHS - 1);
        Map<YearMonth, BigDecimal> found = new HashMap<>();
        jdbc.query(sql, rs -> {
            found.put(YearMonth.from(rs.getDate("m").toLocalDate()), rs.getBigDecimal("amt"));
        }, Date.valueOf(first.atDay(1)));
        List<MonthAmount> out = new ArrayList<>();
        for (int i = 0; i < MONTHS; i++) {
            YearMonth m = first.plusMonths(i);
            out.add(new MonthAmount(m.toString(), Money.of(found.getOrDefault(m, BigDecimal.ZERO))));
        }
        return out;
    }

    /** The approved budget of the current fiscal year, from the budget module's own figures. */
    private BudgetSection budget(LocalDate today) {
        List<UUID> ids = jdbc.queryForList("""
                SELECT b.id FROM budgets b JOIN fiscal_years f ON f.id = b.fiscal_year_id
                WHERE b.status = 'APPROVED' AND ? BETWEEN f.start_date AND f.end_date""", UUID.class, Date.valueOf(today));
        if (ids.isEmpty()) {
            return null;
        }
        BudgetView b = budgets.get(ids.get(0));
        List<BudgetLine> lines = b.allocations().stream()
                .map(a -> new BudgetLine(a.accountCode(), a.costCenterCode(), a.allotment(), Money.of(a.reserved().add(a.actual())),
                        percent(a.reserved().add(a.actual()), a.allotment())))
                .sorted(Comparator.comparing(BudgetLine::utilisationPercent).reversed()).limit(5).toList();
        return new BudgetSection(b.fiscalYear(), b.totalAllotment(), b.totalReserved(), b.totalActual(), b.totalAvailable(),
                percent(b.totalReserved().add(b.totalActual()), b.totalAllotment()), lines);
    }

    private static BigDecimal percent(BigDecimal part, BigDecimal whole) {
        return whole.signum() == 0 ? BigDecimal.ZERO : part.multiply(BigDecimal.valueOf(100)).divide(whole, 1, RoundingMode.HALF_EVEN);
    }

    /** The approvals inbox grouped by queue, so the dashboard and the inbox always agree. */
    private List<WorkItem> workQueue() {
        return inbox.mine().queues().stream()
                .map(q -> new WorkItem(q.queue(), q.count(), "/inbox?queue=" + URLEncoder.encode(q.queue(), StandardCharsets.UTF_8)))
                .toList();
    }
}
