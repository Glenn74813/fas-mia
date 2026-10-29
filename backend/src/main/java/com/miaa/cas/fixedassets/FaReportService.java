package com.miaa.cas.fixedassets;

import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.money.Money;
import com.miaa.cas.fixedassets.FaDtos.FaReconciliation;
import com.miaa.cas.fixedassets.FaDtos.PpeSchedule;
import com.miaa.cas.fixedassets.FaDtos.ReconciliationRow;
import com.miaa.cas.fixedassets.FaDtos.ScheduleRow;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PPE roll-forward schedule and register-to-ledger reconciliation, computed from the register. */
@Service
public class FaReportService {

    private final JdbcTemplate jdbc;

    public FaReportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Gross carrying amount, accumulated depreciation and accumulated impairment rolled forward per category: additions
     * by in-service date, impairments and revaluations by their date, disposals by disposal date, depreciation by the
     * period it was charged to.
     */
    @Transactional(readOnly = true)
    public PpeSchedule schedule(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "'to' cannot precede 'from'.");
        }
        Date f = Date.valueOf(from);
        Date t = Date.valueOf(to);
        List<ScheduleRow> rows = jdbc.query("""
                WITH reg AS (
                    SELECT a.id, a.category_id, a.cost, a.in_service_date, a.opening_accumulated, d.disposal_date, d.cost AS disposed_cost,
                           d.accumulated AS disposed_acc, coalesce(d.accumulated_impairment, 0) AS disposed_imp
                    FROM fixed_assets a LEFT JOIN asset_disposals d ON d.asset_id = a.id AND d.status = 'POSTED'
                    WHERE a.status IN ('ACTIVE', 'FULLY_DEPRECIATED', 'DISPOSED')),
                dep AS (
                    SELECT dl.asset_id, p.end_date, dl.amount FROM depreciation_lines dl
                    JOIN depreciation_runs r ON r.id = dl.run_id AND r.status = 'POSTED' JOIN accounting_periods p ON p.id = dl.period_id
                    WHERE dl.live),
                rm AS (
                    SELECT asset_id, remeasured_on, cost_change, depreciation_eliminated, impairment_change FROM asset_remeasurements
                    WHERE status = 'POSTED')
                SELECT c.code, c.name,
                    coalesce(sum(CASE WHEN r.in_service_date < ? AND (r.disposal_date IS NULL OR r.disposal_date >= ?) THEN r.cost
                        + (SELECT coalesce(sum(cost_change), 0) FROM rm WHERE rm.asset_id = r.id AND rm.remeasured_on < ?) END), 0) AS open_cost,
                    coalesce(sum(CASE WHEN r.in_service_date BETWEEN ? AND ? THEN r.cost END), 0) AS additions,
                    coalesce(sum((SELECT coalesce(sum(cost_change), 0) FROM rm WHERE rm.asset_id = r.id AND rm.remeasured_on BETWEEN ? AND ?)), 0)
                        AS revaluations,
                    coalesce(sum(CASE WHEN r.disposal_date BETWEEN ? AND ? THEN coalesce(r.disposed_cost, r.cost) END), 0) AS disp_cost,
                    coalesce(sum(CASE WHEN r.disposal_date IS NULL OR r.disposal_date >= ? THEN
                        r.opening_accumulated + (SELECT coalesce(sum(amount), 0) FROM dep WHERE dep.asset_id = r.id AND dep.end_date < ?)
                        - (SELECT coalesce(sum(depreciation_eliminated), 0) FROM rm WHERE rm.asset_id = r.id AND rm.remeasured_on < ?) END), 0)
                        AS open_acc,
                    coalesce(sum((SELECT coalesce(sum(amount), 0) FROM dep WHERE dep.asset_id = r.id AND dep.end_date BETWEEN ? AND ?)), 0) AS charge,
                    coalesce(sum((SELECT coalesce(sum(depreciation_eliminated), 0) FROM rm WHERE rm.asset_id = r.id
                        AND rm.remeasured_on BETWEEN ? AND ?)), 0) AS eliminated,
                    coalesce(sum(CASE WHEN r.disposal_date BETWEEN ? AND ? THEN r.disposed_acc END), 0) AS disp_acc,
                    coalesce(sum(CASE WHEN r.disposal_date IS NULL OR r.disposal_date >= ? THEN
                        (SELECT coalesce(sum(impairment_change), 0) FROM rm WHERE rm.asset_id = r.id AND rm.remeasured_on < ?) END), 0) AS open_imp,
                    coalesce(sum((SELECT coalesce(sum(impairment_change), 0) FROM rm WHERE rm.asset_id = r.id
                        AND rm.remeasured_on BETWEEN ? AND ?)), 0) AS imp,
                    coalesce(sum(CASE WHEN r.disposal_date BETWEEN ? AND ? THEN r.disposed_imp END), 0) AS disp_imp
                FROM asset_categories c LEFT JOIN reg r ON r.category_id = c.id
                GROUP BY c.code, c.name ORDER BY c.code""", (rs, i) -> {
            BigDecimal oc = Money.of(rs.getBigDecimal("open_cost"));
            BigDecimal add = Money.of(rs.getBigDecimal("additions"));
            BigDecimal rev = Money.of(rs.getBigDecimal("revaluations"));
            BigDecimal dc = Money.of(rs.getBigDecimal("disp_cost"));
            BigDecimal oa = Money.of(rs.getBigDecimal("open_acc"));
            BigDecimal ch = Money.of(rs.getBigDecimal("charge"));
            BigDecimal el = Money.of(rs.getBigDecimal("eliminated"));
            BigDecimal da = Money.of(rs.getBigDecimal("disp_acc"));
            BigDecimal oi = Money.of(rs.getBigDecimal("open_imp"));
            BigDecimal im = Money.of(rs.getBigDecimal("imp"));
            BigDecimal di = Money.of(rs.getBigDecimal("disp_imp"));
            BigDecimal cc = Money.of(oc.add(add).add(rev).subtract(dc));
            BigDecimal ca = Money.of(oa.add(ch).subtract(el).subtract(da));
            BigDecimal ci = Money.of(oi.add(im).subtract(di));
            return new ScheduleRow(rs.getString("code"), rs.getString("name"), oc, add, rev, dc, cc, oa, ch, el, da, ca, oi, im, di, ci,
                    Money.of(cc.subtract(ca).subtract(ci)));
        }, f, f, f, f, t, f, t, f, t, f, f, f, f, t, f, t, f, t, f, f, f, t, f, t);
        ScheduleRow totals = new ScheduleRow(null, "Total", sum(rows, ScheduleRow::openingCost), sum(rows, ScheduleRow::additions),
                sum(rows, ScheduleRow::revaluations), sum(rows, ScheduleRow::disposalsCost), sum(rows, ScheduleRow::closingCost),
                sum(rows, ScheduleRow::openingAccumulated), sum(rows, ScheduleRow::depreciation), sum(rows, ScheduleRow::eliminatedAccumulated),
                sum(rows, ScheduleRow::disposalsAccumulated), sum(rows, ScheduleRow::closingAccumulated), sum(rows, ScheduleRow::openingImpairment),
                sum(rows, ScheduleRow::impairment), sum(rows, ScheduleRow::disposalsImpairment), sum(rows, ScheduleRow::closingImpairment),
                sum(rows, ScheduleRow::netBookValue));
        return new PpeSchedule(from, to, rows, totals, "Statutory PPE schedule layout and asset classes follow the approved chart "
                + "of accounts (C-07); categories here are configuration.");
    }

    /** Register gross carrying amount, accumulated depreciation and accumulated impairment per ledger account, against the balances. */
    @Transactional(readOnly = true)
    public FaReconciliation reconciliation() {
        List<ReconciliationRow> rows = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT a.id, a.code, a.name, coalesce((SELECT sum(f.cost + f.cost_adjustment) FROM fixed_assets f
                    JOIN asset_categories c ON c.id = f.category_id
                    WHERE c.asset_account_id = a.id AND f.status IN ('ACTIVE', 'FULLY_DEPRECIATED')), 0) AS reg,
                    coalesce((SELECT sum(debit - credit) FROM journal_entry_lines l WHERE l.account_id = a.id), 0) AS gl
                FROM chart_of_accounts a WHERE a.id IN (SELECT asset_account_id FROM asset_categories) ORDER BY a.code""")) {
            rows.add(row(r, "COST"));
        }
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT a.id, a.code, a.name, coalesce((SELECT sum(dl.amount) FROM depreciation_lines dl
                    JOIN depreciation_runs dr ON dr.id = dl.run_id AND dr.status = 'POSTED'
                    JOIN fixed_assets f ON f.id = dl.asset_id JOIN asset_categories c ON c.id = f.category_id
                    WHERE dl.live AND c.accumulated_account_id = a.id AND f.status IN ('ACTIVE', 'FULLY_DEPRECIATED')), 0)
                  + coalesce((SELECT sum(f.opening_accumulated - f.depreciation_eliminated) FROM fixed_assets f
                    JOIN asset_categories c ON c.id = f.category_id
                    WHERE c.accumulated_account_id = a.id AND f.status IN ('ACTIVE', 'FULLY_DEPRECIATED')), 0) AS reg,
                    coalesce((SELECT sum(credit - debit) FROM journal_entry_lines l WHERE l.account_id = a.id), 0) AS gl
                FROM chart_of_accounts a WHERE a.id IN (SELECT accumulated_account_id FROM asset_categories) ORDER BY a.code""")) {
            rows.add(row(r, "ACCUMULATED_DEPRECIATION"));
        }
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT a.id, a.code, a.name, coalesce((SELECT sum(f.accumulated_impairment) FROM fixed_assets f
                    JOIN asset_categories c ON c.id = f.category_id
                    WHERE c.accumulated_impairment_account_id = a.id AND f.status IN ('ACTIVE', 'FULLY_DEPRECIATED')), 0) AS reg,
                    coalesce((SELECT sum(credit - debit) FROM journal_entry_lines l WHERE l.account_id = a.id), 0) AS gl
                FROM chart_of_accounts a WHERE a.id IN (SELECT accumulated_impairment_account_id FROM asset_categories) ORDER BY a.code""")) {
            rows.add(row(r, "ACCUMULATED_IMPAIRMENT"));
        }
        return new FaReconciliation(rows, rows.stream().allMatch(r -> r.difference().signum() == 0));
    }

    private static ReconciliationRow row(Map<String, Object> r, String side) {
        BigDecimal reg = Money.of((BigDecimal) r.get("reg"));
        BigDecimal gl = Money.of((BigDecimal) r.get("gl"));
        return new ReconciliationRow((String) r.get("code"), (String) r.get("name"), side, reg, gl, Money.of(gl.subtract(reg)));
    }

    private static BigDecimal sum(List<ScheduleRow> rows, java.util.function.Function<ScheduleRow, BigDecimal> f) {
        return Money.of(rows.stream().map(f).reduce(BigDecimal.ZERO, BigDecimal::add));
    }
}
