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
     * Cost and accumulated depreciation rolled forward per category: additions by in-service date,
     * disposals by disposal date, depreciation by the period it was charged to.
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
                    SELECT a.id, a.category_id, a.cost, a.in_service_date, a.opening_accumulated, d.disposal_date, d.accumulated AS disposed_acc
                    FROM fixed_assets a LEFT JOIN asset_disposals d ON d.asset_id = a.id AND d.status = 'POSTED'
                    WHERE a.status IN ('ACTIVE', 'FULLY_DEPRECIATED', 'DISPOSED')),
                dep AS (
                    SELECT dl.asset_id, p.end_date, dl.amount FROM depreciation_lines dl
                    JOIN depreciation_runs r ON r.id = dl.run_id AND r.status = 'POSTED' JOIN accounting_periods p ON p.id = dl.period_id
                    WHERE dl.live)
                SELECT c.code, c.name,
                    coalesce(sum(CASE WHEN r.in_service_date < ? AND (r.disposal_date IS NULL OR r.disposal_date >= ?) THEN r.cost END), 0) AS open_cost,
                    coalesce(sum(CASE WHEN r.in_service_date BETWEEN ? AND ? THEN r.cost END), 0) AS additions,
                    coalesce(sum(CASE WHEN r.disposal_date BETWEEN ? AND ? THEN r.cost END), 0) AS disp_cost,
                    coalesce(sum(CASE WHEN r.disposal_date IS NULL OR r.disposal_date >= ? THEN
                        r.opening_accumulated + (SELECT coalesce(sum(amount), 0) FROM dep WHERE dep.asset_id = r.id AND dep.end_date < ?) END), 0)
                        AS open_acc,
                    coalesce(sum((SELECT coalesce(sum(amount), 0) FROM dep WHERE dep.asset_id = r.id AND dep.end_date BETWEEN ? AND ?)), 0) AS charge,
                    coalesce(sum(CASE WHEN r.disposal_date BETWEEN ? AND ? THEN r.disposed_acc END), 0) AS disp_acc
                FROM asset_categories c LEFT JOIN reg r ON r.category_id = c.id
                GROUP BY c.code, c.name ORDER BY c.code""", (rs, i) -> {
            BigDecimal oc = Money.of(rs.getBigDecimal("open_cost"));
            BigDecimal add = Money.of(rs.getBigDecimal("additions"));
            BigDecimal dc = Money.of(rs.getBigDecimal("disp_cost"));
            BigDecimal oa = Money.of(rs.getBigDecimal("open_acc"));
            BigDecimal ch = Money.of(rs.getBigDecimal("charge"));
            BigDecimal da = Money.of(rs.getBigDecimal("disp_acc"));
            BigDecimal cc = Money.of(oc.add(add).subtract(dc));
            BigDecimal ca = Money.of(oa.add(ch).subtract(da));
            return new ScheduleRow(rs.getString("code"), rs.getString("name"), oc, add, dc, cc, oa, ch, da, ca, Money.of(cc.subtract(ca)));
        }, f, f, f, t, f, t, f, f, f, t, f, t);
        ScheduleRow totals = new ScheduleRow(null, "Total", sum(rows, ScheduleRow::openingCost), sum(rows, ScheduleRow::additions),
                sum(rows, ScheduleRow::disposalsCost), sum(rows, ScheduleRow::closingCost), sum(rows, ScheduleRow::openingAccumulated),
                sum(rows, ScheduleRow::depreciation), sum(rows, ScheduleRow::disposalsAccumulated), sum(rows, ScheduleRow::closingAccumulated),
                sum(rows, ScheduleRow::netBookValue));
        return new PpeSchedule(from, to, rows, totals, "Statutory PPE schedule layout and asset classes follow the approved chart "
                + "of accounts (C-07); categories here are configuration.");
    }

    /** Register cost and accumulated depreciation per ledger account, against the account balances. */
    @Transactional(readOnly = true)
    public FaReconciliation reconciliation() {
        List<ReconciliationRow> rows = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT a.id, a.code, a.name, coalesce((SELECT sum(f.cost) FROM fixed_assets f JOIN asset_categories c ON c.id = f.category_id
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
                  + coalesce((SELECT sum(f.opening_accumulated) FROM fixed_assets f JOIN asset_categories c ON c.id = f.category_id
                    WHERE c.accumulated_account_id = a.id AND f.status IN ('ACTIVE', 'FULLY_DEPRECIATED')), 0) AS reg,
                    coalesce((SELECT sum(credit - debit) FROM journal_entry_lines l WHERE l.account_id = a.id), 0) AS gl
                FROM chart_of_accounts a WHERE a.id IN (SELECT accumulated_account_id FROM asset_categories) ORDER BY a.code""")) {
            rows.add(row(r, "ACCUMULATED_DEPRECIATION"));
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
