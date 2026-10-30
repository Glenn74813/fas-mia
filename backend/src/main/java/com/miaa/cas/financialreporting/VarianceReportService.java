package com.miaa.cas.financialreporting;

import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.money.Money;
import com.miaa.cas.financialreporting.ManagementReportService.Rendered;
import com.miaa.cas.financialreporting.export.ReportTable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Variance analysis (MS §11.4, §11.8): budget to date against posted actuals to the end of a chosen period, per budget
 * allocation, with the variance, its percentage and an assessment against a threshold.
 *
 * <p>Proposed layout until AVTECH confirms its budget definitions (C-10):
 * <ul>
 * <li>Budget to date spreads the annual allotment evenly over the twelve regular periods (no phasing is recorded).
 * Through the adjustment period (13) it is the whole allotment.</li>
 * <li>A positive variance is favourable: spending below budget on a debit-balance account, or more than budgeted on a
 * credit-balance (revenue) account.</li>
 * <li>An account-level allocation covers all the account's spending; a cost-centre allocation covers that cost
 * centre's. Budgets cannot mix the two for one account (BudgetService), so nothing is counted twice.</li>
 * <li>Spending to date on a budget-controlled account with no allocation is listed as "No allocation".</li>
 * </ul>
 * Rows are grouped by account type with subtotals, so spending and revenue are never added together.
 */
@Service
public class VarianceReportService {

    static final String TYPE = "VARIANCE_ANALYSIS";
    private static final String ORG = "Avtech Information Technology Solutions, Inc.";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal DEFAULT_THRESHOLD = BigDecimal.TEN;
    private static final int REGULAR_PERIODS = 12;
    private static final List<String> TYPE_ORDER = List.of("EXPENSE", "ASSET", "LIABILITY", "EQUITY", "REVENUE");

    private final JdbcTemplate jdbc;

    public VarianceReportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record Line(String type, String label, BigDecimal allotment, BigDecimal budgetToDate, BigDecimal actual,
            boolean creditNormal, boolean allocated) {
    }

    /** Fails fast on missing or malformed parameters when the job is requested. */
    public void validate(Map<String, String> p) {
        UUID fy = uuid(p, "fiscalYearId");
        if (jdbc.queryForList("SELECT id FROM budgets WHERE fiscal_year_id = ?", UUID.class, fy).isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "There is no budget for that fiscal year.");
        }
        int through = period(p);
        if (jdbc.queryForList("SELECT id FROM accounting_periods WHERE fiscal_year_id = ? AND period_number = ?", UUID.class, fy,
                through).isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "That fiscal year has no period " + through + ".");
        }
        if (!blank(p.get("costCenterId"))) {
            uuid(p, "costCenterId");
        }
        threshold(p);
    }

    public Rendered render(Map<String, String> p, String by, String at) {
        UUID fy = uuid(p, "fiscalYearId");
        int through = period(p);
        UUID costCenter = blank(p.get("costCenterId")) ? null : uuid(p, "costCenterId");
        BigDecimal threshold = threshold(p);

        Map<String, Object> b = jdbc.queryForMap("""
                SELECT b.id, b.name, b.status, y.fiscal_year FROM budgets b JOIN fiscal_years y ON y.id = b.fiscal_year_id
                WHERE b.fiscal_year_id = ?""", fy);
        Map<String, Object> period = jdbc.queryForMap(
                "SELECT name, end_date FROM accounting_periods WHERE fiscal_year_id = ? AND period_number = ?", fy, through);
        UUID budgetId = (UUID) b.get("id");
        int elapsed = Math.min(through, REGULAR_PERIODS);

        List<Line> lines = new ArrayList<>();
        for (Map<String, Object> a : jdbc.queryForList("""
                SELECT ba.account_id, a.code, a.name, a.account_type, a.normal_balance, ba.cost_center_id, cc.code AS cc, ba.amount
                FROM budget_allocations ba JOIN chart_of_accounts a ON a.id = ba.account_id
                LEFT JOIN cost_centers cc ON cc.id = ba.cost_center_id
                WHERE ba.budget_id = ? AND (CAST(? AS uuid) IS NULL OR ba.cost_center_id = ?)
                ORDER BY a.code, cc.code NULLS FIRST""", budgetId, costCenter, costCenter)) {
            UUID accountId = (UUID) a.get("account_id");
            UUID cc = (UUID) a.get("cost_center_id");
            boolean credit = "CREDIT".equals(a.get("normal_balance"));
            BigDecimal allot = Money.of((BigDecimal) a.get("amount"));
            BigDecimal toDate = through > REGULAR_PERIODS ? allot
                    : Money.of(allot.multiply(BigDecimal.valueOf(elapsed)).divide(BigDecimal.valueOf(REGULAR_PERIODS), 2, RoundingMode.HALF_EVEN));
            BigDecimal actual = actual(fy, through, accountId, cc, credit);
            lines.add(new Line((String) a.get("account_type"), a.get("code") + " " + a.get("name") + (a.get("cc") == null ? "" : " / " + a.get("cc")),
                    allot, toDate, actual, credit, true));
        }
        lines.addAll(unallocated(fy, through, budgetId, costCenter));

        List<ReportTable.Row> rows = new ArrayList<>();
        Map<String, List<Line>> byType = new LinkedHashMap<>();
        TYPE_ORDER.forEach(t -> byType.put(t, new ArrayList<>()));
        lines.forEach(l -> byType.get(l.type()).add(l));
        for (Map.Entry<String, List<Line>> group : byType.entrySet()) {
            if (group.getValue().isEmpty()) {
                continue;
            }
            rows.add(new ReportTable.Row(heading(group.getKey()), 0, true, java.util.Collections.nCopies(7, null)));
            BigDecimal[] t = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
            boolean credit = group.getValue().get(0).creditNormal();
            for (Line l : group.getValue()) {
                rows.add(new ReportTable.Row(l.label(), 1, false, values(l.allotment(), l.budgetToDate(), l.actual(), l.creditNormal(),
                        l.allocated(), threshold)));
                t[0] = t[0].add(l.allotment());
                t[1] = t[1].add(l.budgetToDate());
                t[2] = t[2].add(l.actual());
            }
            rows.add(new ReportTable.Row("Total " + heading(group.getKey()).toLowerCase(java.util.Locale.ROOT), 0, true,
                    values(Money.of(t[0]), Money.of(t[1]), Money.of(t[2]), credit, true, threshold)));
        }
        if (rows.isEmpty()) {
            rows.add(new ReportTable.Row("No allocations or budget-controlled spending for this selection.", 0, false,
                    java.util.Collections.nCopies(7, null)));
        }

        List<String> subtitles = new ArrayList<>(List.of(ORG,
                b.get("name") + " — fiscal year " + b.get("fiscal_year") + " (" + b.get("status") + ")",
                "Periods 1–" + through + ", to " + period.get("end_date") + " (" + period.get("name") + ")"));
        if (costCenter != null) {
            subtitles.add("Cost centre " + jdbc.queryForObject("SELECT code || ' ' || name FROM cost_centers WHERE id = ?", String.class,
                    costCenter));
        }
        String pct = threshold.stripTrailingZeros().toPlainString();
        return new Rendered(new ReportTable("Variance analysis: budget against actual", subtitles, "Account / cost centre",
                List.of("Annual allotment", "Budget to date", "Actual to date", "Variance", "Variance %", "Allotment left", "Assessment"),
                rows, List.of(
                        "Proposed layout until AVTECH confirms its budget definitions (C-10). Budget to date spreads the allotment evenly "
                                + "over 12 periods; through period 13 it is the whole allotment.",
                        "Actual = posted entries on the account (and cost centre) in periods 1–" + through + ". A positive variance is "
                                + "favourable: spending below budget, or revenue above it. Within ±" + pct + " % is shown as within threshold.",
                        "An allocation without a cost centre covers the account's spending on every cost centre. "
                                + "\"No allocation\" lists budget-controlled spending that no allocation covers.",
                        "Generated " + at + " by " + by + ".")),
                "variance_analysis_" + b.get("fiscal_year") + "_p" + String.format("%02d", through));
    }

    /** Posted amount in the account's normal direction, for one cost centre or (account-level) all of them. */
    private BigDecimal actual(UUID fy, int through, UUID accountId, UUID costCenterId, boolean credit) {
        String sign = credit ? "l.credit - l.debit" : "l.debit - l.credit";
        return Money.of(Objects.requireNonNull(jdbc.queryForObject("SELECT coalesce(sum(" + sign + "), 0) FROM journal_entry_lines l "
                + "JOIN accounting_periods p ON p.id = l.period_id WHERE l.account_id = ? AND p.fiscal_year_id = ? "
                + "AND p.period_number <= ? AND (CAST(? AS uuid) IS NULL OR l.cost_center_id = ?)", BigDecimal.class,
                accountId, fy, through, costCenterId, costCenterId)));
    }

    /** Budget-controlled spending to date that no allocation covers (neither its cost centre's nor the account's). */
    private List<Line> unallocated(UUID fy, int through, UUID budgetId, UUID costCenter) {
        return jdbc.query("""
                SELECT a.code, a.name, a.account_type, a.normal_balance, cc.code AS cc,
                       sum(CASE WHEN a.normal_balance = 'CREDIT' THEN l.credit - l.debit ELSE l.debit - l.credit END) AS amount
                FROM journal_entry_lines l
                JOIN accounting_periods p ON p.id = l.period_id
                JOIN chart_of_accounts a ON a.id = l.account_id
                LEFT JOIN cost_centers cc ON cc.id = l.cost_center_id
                WHERE p.fiscal_year_id = ? AND p.period_number <= ? AND a.budget_controlled
                  AND (CAST(? AS uuid) IS NULL OR l.cost_center_id = ?)
                  AND NOT EXISTS (SELECT 1 FROM budget_allocations ba WHERE ba.budget_id = ? AND ba.account_id = l.account_id
                                  AND (ba.cost_center_id IS NULL OR ba.cost_center_id = l.cost_center_id))
                GROUP BY a.code, a.name, a.account_type, a.normal_balance, cc.code
                HAVING sum(l.debit - l.credit) <> 0
                ORDER BY a.code, cc.code NULLS FIRST""", (rs, i) -> new Line(rs.getString("account_type"),
                rs.getString("code") + " " + rs.getString("name") + (rs.getString("cc") == null ? "" : " / " + rs.getString("cc")),
                Money.ZERO, Money.ZERO, Money.of(rs.getBigDecimal("amount")), "CREDIT".equals(rs.getString("normal_balance")), false),
                fy, through, costCenter, costCenter, budgetId);
    }

    static List<Object> values(BigDecimal allot, BigDecimal toDate, BigDecimal actual, boolean credit, boolean allocated,
            BigDecimal threshold) {
        BigDecimal variance = Money.of(credit ? actual.subtract(toDate) : toDate.subtract(actual));
        BigDecimal pct = toDate.signum() == 0 ? null : variance.multiply(HUNDRED).divide(toDate.abs(), 1, RoundingMode.HALF_EVEN);
        String assessment;
        if (!allocated) {
            assessment = "No allocation";
        } else if (pct == null) {
            assessment = actual.signum() == 0 ? "No activity" : variance.signum() > 0 ? "Favourable" : "Unfavourable";
        } else if (pct.abs().compareTo(threshold) < 0) {
            assessment = "Within threshold";
        } else {
            assessment = variance.signum() > 0 ? "Favourable" : "Unfavourable";
        }
        List<Object> v = new ArrayList<>(List.of(allot, toDate, actual, variance));
        v.add(pct);
        v.add(Money.of(allot.subtract(actual)));
        v.add(assessment);
        return v;
    }

    private static String heading(String accountType) {
        return switch (accountType) {
            case "EXPENSE" -> "Expenses";
            case "ASSET" -> "Capital outlay (asset accounts)";
            case "REVENUE" -> "Revenue";
            case "LIABILITY" -> "Liability accounts";
            default -> "Equity accounts";
        };
    }

    private static int period(Map<String, String> p) {
        String s = p.get("throughPeriod");
        try {
            int n = Integer.parseInt(s == null ? "" : s.trim());
            if (n >= 1 && n <= 13) {
                return n;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        throw new ApiException(ErrorCode.VALIDATION_FAILED, "parameters.throughPeriod must be a period number from 1 to 13.");
    }

    private static BigDecimal threshold(Map<String, String> p) {
        String s = p.get("thresholdPercent");
        if (blank(s)) {
            return DEFAULT_THRESHOLD;
        }
        try {
            BigDecimal t = new BigDecimal(s.trim());
            if (t.signum() >= 0 && t.compareTo(HUNDRED) <= 0) {
                return t;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        throw new ApiException(ErrorCode.VALIDATION_FAILED, "parameters.thresholdPercent must be between 0 and 100.");
    }

    private static UUID uuid(Map<String, String> p, String key) {
        String s = p.get(key);
        if (blank(s)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "parameters." + key + " is required.");
        }
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "parameters." + key + " is not a valid id.");
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
