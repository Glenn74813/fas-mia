package com.miaa.cas.fixedassets;

import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.money.Money;
import com.miaa.cas.common.web.JdbcPaging;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.fixedassets.FaDtos.RunLine;
import com.miaa.cas.fixedassets.FaDtos.RunSummary;
import com.miaa.cas.fixedassets.FaDtos.RunView;
import com.miaa.cas.generalledger.FiscalCalendarService;
import com.miaa.cas.generalledger.domain.AccountingPeriod;
import com.miaa.cas.generalledger.domain.PeriodStatus;
import com.miaa.cas.journal.PostingService;
import com.miaa.cas.journal.VoucherValidator;
import com.miaa.cas.journal.domain.LineData;
import com.miaa.cas.journal.domain.VoucherType;
import com.miaa.cas.notifications.NotificationService;
import com.miaa.cas.security.CurrentActor;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Monthly depreciation runs. A run computes, per asset, the depreciation still due up to the end of the
 * period (so a missed month is caught up, and nothing is charged twice), is prepared by one person and
 * posted by another: Dr depreciation expense (per category and cost centre) / Cr accumulated depreciation.
 */
@Service
public class DepreciationService {

    public static final String SOURCE = "SL_FA_DEPRECIATION";

    private final FixedAssetService assets;
    private final FiscalCalendarService calendar;
    private final VoucherValidator validator;
    private final PostingService posting;
    private final AuditService audit;
    private final NotificationService notifications;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final String frequency;

    public DepreciationService(FixedAssetService assets, FiscalCalendarService calendar, VoucherValidator validator,
            PostingService posting, AuditService audit, NotificationService notifications, JdbcTemplate jdbc, Clock clock,
            @Value("${cas.fixed-assets.depreciation-frequency:MONTHLY}") String frequency) {
        if (!"MONTHLY".equals(frequency) && !"ANNUAL".equals(frequency)) {
            throw new IllegalStateException("cas.fixed-assets.depreciation-frequency must be MONTHLY or ANNUAL");
        }
        this.frequency = frequency;
        this.assets = assets;
        this.calendar = calendar;
        this.validator = validator;
        this.posting = posting;
        this.audit = audit;
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** MONTHLY (default) or ANNUAL, from {@code cas.fixed-assets.depreciation-frequency}; MIAA's policy is C-30. */
    public String frequency() {
        return frequency;
    }

    @Transactional(readOnly = true)
    public PageResponse<RunSummary> list(int page, int size) {
        return JdbcPaging.query(jdbc, """
                SELECT r.*, p.name, p.end_date FROM depreciation_runs r JOIN accounting_periods p ON p.id = r.period_id
                ORDER BY p.end_date DESC, r.created_at DESC, r.id""", (rs, i) -> new RunSummary(rs.getObject("id", UUID.class),
                rs.getString("name"), rs.getDate("end_date").toLocalDate(), rs.getString("status"), rs.getBigDecimal("total_amount"),
                rs.getInt("asset_count"), rs.getString("prepared_by"), rs.getTimestamp("updated_at").toInstant()), page, size);
    }

    @Transactional(readOnly = true)
    public RunView get(UUID id) {
        return view(id);
    }

    @Transactional
    public RunView prepare(UUID periodId) {
        CurrentActor.requirePermission("FA_MANAGE");
        AccountingPeriod period = calendar.period(periodId);
        if (!"REGULAR".equals(period.getPeriodType())) {
            throw FixedAssetService.rule("Depreciation is run for regular monthly periods, not the adjustment period.");
        }
        if ("ANNUAL".equals(frequency)) {
            // Annual mode (C-30): one run in the year's last regular period; the monthly calculation catches up the whole year.
            String last = jdbc.queryForObject("SELECT name FROM accounting_periods WHERE fiscal_year_id = ? AND period_type = 'REGULAR' "
                    + "ORDER BY end_date DESC LIMIT 1", String.class, period.getFiscalYearId());
            if (!period.getName().equals(last)) {
                throw FixedAssetService.rule("Depreciation is run once a year, in the year's last regular period (" + last + ").");
            }
        }
        if (period.getStatus() != PeriodStatus.OPEN) {
            throw FixedAssetService.rule("Period " + period.getName() + " is not open.");
        }
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext('fa-depreciation'))", Object.class);
        List<String> live = jdbc.queryForList("SELECT status FROM depreciation_runs WHERE period_id = ? AND status <> 'CANCELLED'",
                String.class, periodId);
        if (!live.isEmpty()) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "Period " + period.getName() + " already has a "
                    + live.get(0).toLowerCase() + " depreciation run.");
        }
        YearMonth month = YearMonth.from(period.getEndDate());
        List<Map<String, Object>> due = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> a : jdbc.queryForList("SELECT a.*, " + FixedAssetService.ACCUMULATED + " AS acc FROM fixed_assets a "
                + "WHERE a.status = 'ACTIVE' AND a.in_service_date <= ? ORDER BY a.asset_number", Date.valueOf(period.getEndDate()))) {
            BigDecimal expected = expected(a, month);
            BigDecimal amount = Money.of(expected.subtract((BigDecimal) a.get("acc")));
            if (amount.signum() > 0) {
                a.put("amount", amount);
                due.add(a);
                total = total.add(amount);
            }
        }
        if (due.isEmpty()) {
            throw FixedAssetService.rule("No depreciation is due for " + period.getName() + ".");
        }
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now(clock));
        jdbc.update("INSERT INTO depreciation_runs (id, period_id, status, total_amount, asset_count, prepared_by_id, prepared_by, "
                + "created_at, updated_at, updated_by) VALUES (?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?)", id, periodId, Money.of(total),
                due.size(), CurrentActor.userId(), CurrentActor.username(), now, now, CurrentActor.username());
        for (Map<String, Object> a : due) {
            jdbc.update("INSERT INTO depreciation_lines (run_id, asset_id, period_id, amount) VALUES (?, ?, ?, ?)", id, a.get("id"),
                    periodId, a.get("amount"));
        }
        validator.validate(VoucherValidator.Level.FULL, id, VoucherType.SUBLEDGER, period.getEndDate(), period, ledgerLines(id));
        audit.record("DEPRECIATION_RUN_PREPARED", "DEPRECIATION_RUN", id, Map.of("period", period.getName(),
                "total", Money.of(total).toPlainString(), "assets", due.size()));
        return view(id);
    }

    @Transactional
    public RunView post(UUID id, long version) {
        Map<String, Object> r = lockVersioned(id, version);
        if (!"DRAFT".equals(r.get("status"))) {
            throw FixedAssetService.state("Only draft runs can be posted.");
        }
        List<String> changed = jdbc.queryForList("""
                SELECT coalesce(a.asset_number, a.description) FROM depreciation_lines dl JOIN fixed_assets a ON a.id = dl.asset_id
                WHERE dl.run_id = ? AND a.status <> 'ACTIVE'""", String.class, id);
        if (!changed.isEmpty()) {
            throw FixedAssetService.rule("Asset " + changed.get(0) + " is no longer active; cancel and prepare the run again.");
        }
        AccountingPeriod period = calendar.period((UUID) r.get("period_id"));
        PostingService.PostedEntry entry = posting.postSubledger(new PostingService.SubledgerEntry(SOURCE, id.toString(),
                period.getEndDate(), "Depreciation for " + period.getName(), ledgerLines(id), (UUID) r.get("prepared_by_id"),
                (String) r.get("prepared_by"), false));
        Timestamp now = Timestamp.from(Instant.now(clock));
        jdbc.update("UPDATE depreciation_runs SET status = 'POSTED', posted_entry_id = ?, version = version + 1, updated_at = ?, "
                + "updated_by = ? WHERE id = ?", entry.entryId(), now, CurrentActor.username(), id);
        int full = jdbc.update("""
                UPDATE fixed_assets a SET status = 'FULLY_DEPRECIATED', version = version + 1, updated_at = ?, updated_by = ?
                WHERE a.id IN (SELECT asset_id FROM depreciation_lines WHERE run_id = ?) AND a.status = 'ACTIVE'
                  AND """ + " " + FixedAssetService.ACCUMULATED + " + a.accumulated_impairment >= a.cost + a.cost_adjustment - a.residual_value", now, CurrentActor.username(), id);
        notifications.notify((UUID) r.get("prepared_by_id"), "DOCUMENT_POSTED", "Depreciation for " + period.getName() + " was posted by "
                + CurrentActor.username(), "Journal entry " + entry.entryNumber() + ".", "/fixed-assets/depreciation/" + id);
        audit.record("DEPRECIATION_RUN_POSTED", "DEPRECIATION_RUN", id, Map.of("entryNumber", entry.entryNumber(),
                "fullyDepreciated", full));
        return view(id);
    }

    @Transactional
    public RunView cancel(UUID id, long version) {
        Map<String, Object> r = lockVersioned(id, version);
        if (!"DRAFT".equals(r.get("status"))) {
            throw FixedAssetService.state("Only draft runs can be cancelled; posted depreciation is corrected by a later run or a reversal voucher.");
        }
        boolean preparer = CurrentActor.userId().equals(r.get("prepared_by_id")) && CurrentActor.hasPermission("FA_MANAGE");
        if (!preparer && !CurrentActor.hasPermission("JOURNAL_POST")) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Only the preparer or accounting can cancel this run.");
        }
        jdbc.update("UPDATE depreciation_lines SET live = FALSE WHERE run_id = ?", id);
        jdbc.update("UPDATE depreciation_runs SET status = 'CANCELLED', version = version + 1, updated_at = ?, updated_by = ? WHERE id = ?",
                Timestamp.from(Instant.now(clock)), CurrentActor.username(), id);
        audit.record("DEPRECIATION_RUN_CANCELLED", "DEPRECIATION_RUN", id, Map.of());
        return view(id);
    }

    /**
     * Expected accumulated depreciation of an asset at the end of a month: from its latest impairment or revaluation
     * when there is one, migrated assets from their cutover basis, others from the in-service date.
     */
    BigDecimal expected(Map<String, Object> asset, YearMonth month) {
        Date remeasured = (Date) asset.get("basis_month");
        if (remeasured != null) {
            return DepreciationMath.expectedFromBasis((BigDecimal) asset.get("basis_accumulated"), (BigDecimal) asset.get("basis_depreciable"),
                    ((Number) asset.get("basis_remaining_months")).intValue(), YearMonth.from(remeasured.toLocalDate()), month);
        }
        Date basis = (Date) asset.get("depreciation_basis_date");
        if (basis != null) {
            return DepreciationMath.expectedFromOpening((BigDecimal) asset.get("cost"), (BigDecimal) asset.get("residual_value"),
                    (BigDecimal) asset.get("opening_accumulated"), ((Number) asset.get("remaining_life_months")).intValue(),
                    YearMonth.from(basis.toLocalDate()), month);
        }
        return DepreciationMath.expectedAccumulated((BigDecimal) asset.get("cost"), (BigDecimal) asset.get("residual_value"),
                ((Number) asset.get("useful_life_months")).intValue(), ((Date) asset.get("in_service_date")).toLocalDate(), month,
                assets.depreciationStart());
    }

    private List<LineData> ledgerLines(UUID runId) {
        Map<String, BigDecimal> debit = new LinkedHashMap<>();
        Map<String, BigDecimal> credit = new LinkedHashMap<>();
        for (Map<String, Object> l : jdbc.queryForList("""
                SELECT c.expense_account_id, c.accumulated_account_id, a.cost_center_id, dl.amount FROM depreciation_lines dl
                JOIN fixed_assets a ON a.id = dl.asset_id JOIN asset_categories c ON c.id = a.category_id
                WHERE dl.run_id = ? ORDER BY c.code""", runId)) {
            UUID expense = (UUID) l.get("expense_account_id");
            UUID cc = assets.costCenterFor(expense, (UUID) l.get("cost_center_id"));
            debit.merge(expense + "|" + (cc == null ? "" : cc), (BigDecimal) l.get("amount"), BigDecimal::add);
            UUID accumulated = (UUID) l.get("accumulated_account_id");
            UUID accCc = assets.costCenterFor(accumulated, (UUID) l.get("cost_center_id"));
            credit.merge(accumulated + "|" + (accCc == null ? "" : accCc), (BigDecimal) l.get("amount"), BigDecimal::add);
        }
        List<LineData> lines = new ArrayList<>();
        debit.forEach((k, v) -> {
            String[] p = k.split("\\|", -1);
            lines.add(new LineData(UUID.fromString(p[0]), p[1].isEmpty() ? null : UUID.fromString(p[1]), null, "Depreciation",
                    Money.of(v), Money.ZERO));
        });
        credit.forEach((k, v) -> {
            String[] p = k.split("\\|", -1);
            lines.add(new LineData(UUID.fromString(p[0]), p[1].isEmpty() ? null : UUID.fromString(p[1]), null, "Accumulated depreciation",
                    Money.ZERO, Money.of(v)));
        });
        return lines;
    }

    private Map<String, Object> lockVersioned(UUID id, long version) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM depreciation_runs WHERE id = ? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Depreciation run");
        }
        if (((Number) rows.get(0).get("version")).longValue() != version) {
            throw ApiException.staleVersion();
        }
        return rows.get(0);
    }

    private RunView view(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT r.*, p.name, p.end_date, e.entry_number FROM depreciation_runs r JOIN accounting_periods p ON p.id = r.period_id
                LEFT JOIN journal_entries e ON e.id = r.posted_entry_id WHERE r.id = ?""", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Depreciation run");
        }
        Map<String, Object> r = rows.get(0);
        String status = (String) r.get("status");
        LocalDate end = ((Date) r.get("end_date")).toLocalDate();
        List<RunLine> lines = jdbc.query("""
                SELECT a.id, a.asset_number, a.description, c.code, dl.amount, a.cost,
                       coalesce((SELECT sum(x.amount) FROM depreciation_lines x JOIN depreciation_runs xr ON xr.id = x.run_id
                                 JOIN accounting_periods xp ON xp.id = x.period_id
                                 WHERE x.asset_id = a.id AND x.live AND (xr.status = 'POSTED' OR xr.id = dl.run_id) AND xp.end_date <= ?), 0) AS acc_after
                FROM depreciation_lines dl JOIN fixed_assets a ON a.id = dl.asset_id JOIN asset_categories c ON c.id = a.category_id
                WHERE dl.run_id = ? ORDER BY a.asset_number""", (rs, i) -> new RunLine(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getString(3), rs.getString(4), rs.getBigDecimal(5), Money.of(rs.getBigDecimal("acc_after")),
                Money.of(rs.getBigDecimal("cost").subtract(rs.getBigDecimal("acc_after")))), Date.valueOf(end), id);
        boolean preparer = CurrentActor.userId().equals(r.get("prepared_by_id"));
        List<String> actions = new ArrayList<>();
        if ("DRAFT".equals(status)) {
            if (!preparer && CurrentActor.hasPermission("JOURNAL_POST")) {
                actions.add("POST");
            }
            if ((preparer && CurrentActor.hasPermission("FA_MANAGE")) || CurrentActor.hasPermission("JOURNAL_POST")) {
                actions.add("CANCEL");
            }
        }
        return new RunView(id, (UUID) r.get("period_id"), (String) r.get("name"), end, status, (BigDecimal) r.get("total_amount"),
                ((Number) r.get("asset_count")).intValue(), (String) r.get("prepared_by"), (UUID) r.get("posted_entry_id"),
                (String) r.get("entry_number"), lines, actions, ((Number) r.get("version")).longValue());
    }
}
