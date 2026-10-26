package com.miaa.cas.generalledger;

import com.miaa.cas.audit.Actor;
import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.generalledger.domain.AccountingPeriod;
import com.miaa.cas.generalledger.domain.AccountingPeriodRepository;
import com.miaa.cas.generalledger.domain.FiscalYear;
import com.miaa.cas.generalledger.domain.FiscalYearRepository;
import com.miaa.cas.generalledger.domain.PeriodStatus;
import com.miaa.cas.generalledger.domain.PeriodStatusChange;
import com.miaa.cas.generalledger.domain.PeriodStatusChangeRepository;
import com.miaa.cas.generalledger.web.LedgerDtos.FiscalYearResponse;
import com.miaa.cas.generalledger.web.LedgerDtos.PeriodResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fiscal years (calendar years, as for Philippine government entities) with twelve monthly periods and
 * a thirteenth adjustment period dated on the last day of the year.
 */
@Service
public class FiscalCalendarService {

    public static final String JEV_DOCUMENT = "JEV";

    private final FiscalYearRepository years;
    private final AccountingPeriodRepository periods;
    private final PeriodStatusChangeRepository changes;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Clock clock;

    public FiscalCalendarService(FiscalYearRepository years, AccountingPeriodRepository periods,
            PeriodStatusChangeRepository changes, JdbcTemplate jdbc, AuditService audit, Clock clock) {
        this.years = years;
        this.periods = periods;
        this.changes = changes;
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<FiscalYearResponse> list() {
        return years.findAllByOrderByFiscalYearDesc().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public FiscalYearResponse get(UUID id) {
        return toResponse(years.findById(id).orElseThrow(() -> ApiException.notFound("Fiscal year")));
    }

    @Transactional
    public FiscalYearResponse create(int year, Actor actor) {
        if (years.existsByFiscalYear(year)) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "Fiscal year " + year + " already exists.");
        }
        Instant now = Instant.now(clock);
        FiscalYear fy = years.saveAndFlush(new FiscalYear(UUID.randomUUID(), year, LocalDate.of(year, 1, 1),
                LocalDate.of(year, 12, 31), now, actor.username()));
        for (int m = 1; m <= 12; m++) {
            YearMonth ym = YearMonth.of(year, m);
            periods.save(new AccountingPeriod(UUID.randomUUID(), fy.getId(), year, m,
                    ym.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + year, ym.atDay(1), ym.atEndOfMonth(),
                    now, actor.username()));
        }
        periods.save(new AccountingPeriod(UUID.randomUUID(), fy.getId(), year, 13, "Adjustments " + year,
                fy.getEndDate(), fy.getEndDate(), now, actor.username()));
        jdbc.update("INSERT INTO numbering_sequences (document_type, fiscal_year, prefix, next_value) VALUES (?, ?, ?, 1)",
                JEV_DOCUMENT, year, "JEV-" + year + "-");
        periods.flush();
        audit.record(actor, "FISCAL_YEAR_CREATED", "FISCAL_YEAR", year, Map.of("periods", 13));
        return toResponse(fy);
    }

    @Transactional
    public PeriodResponse openPeriod(UUID periodId, long version, Actor actor) {
        AccountingPeriod p = lockVersioned(periodId, version);
        FiscalYear fy = years.findById(p.getFiscalYearId()).orElseThrow();
        if (!fy.isOpen()) {
            throw AccountService.rule("Fiscal year " + fy.getFiscalYear() + " is closed.");
        }
        if (p.getStatus() != PeriodStatus.FUTURE) {
            throw AccountService.rule("Only FUTURE periods can be opened; " + p.getName() + " is " + p.getStatus() + ".");
        }
        return transition(p, PeriodStatus.OPEN, "OPEN", null, "[]", actor);
    }

    @Transactional
    public PeriodResponse softClose(UUID periodId, long version, Actor actor) {
        AccountingPeriod p = lockVersioned(periodId, version);
        if (p.getStatus() != PeriodStatus.OPEN) {
            throw AccountService.rule("Only OPEN periods can be soft-closed.");
        }
        return transition(p, PeriodStatus.SOFT_CLOSED, "SOFT_CLOSE", null, "[]", actor);
    }

    @Transactional
    public FiscalYearResponse closeFiscalYear(UUID fiscalYearId, long version, Actor actor) {
        FiscalYear fy = years.findById(fiscalYearId).orElseThrow(() -> ApiException.notFound("Fiscal year"));
        if (fy.getVersion() != version) {
            throw ApiException.staleVersion();
        }
        if (!fy.isOpen()) {
            throw AccountService.rule("Fiscal year is already closed.");
        }
        List<AccountingPeriod> ps = periods.findByFiscalYearIdOrderByPeriodNumberAsc(fiscalYearId);
        List<String> open = ps.stream().filter(p -> p.getStatus() != PeriodStatus.CLOSED).map(AccountingPeriod::getName).toList();
        if (!open.isEmpty()) {
            throw AccountService.rule("All periods must be closed first: " + String.join(", ", open));
        }
        List<String> unclosedNominal = jdbc.queryForList("""
                SELECT a.code FROM journal_entry_lines l JOIN chart_of_accounts a ON a.id = l.account_id
                JOIN accounting_periods p ON p.id = l.period_id
                WHERE p.fiscal_year_id = ? AND a.account_type IN ('REVENUE', 'EXPENSE')
                GROUP BY a.code HAVING sum(l.debit) - sum(l.credit) <> 0 ORDER BY a.code""", String.class, fiscalYearId);
        if (!unclosedNominal.isEmpty()) {
            throw AccountService.rule("Revenue and expense accounts still carry balances (post the closing voucher): "
                    + String.join(", ", unclosedNominal));
        }
        fy.close(Instant.now(clock), actor.username());
        years.flush();
        audit.record(actor, "FISCAL_YEAR_CLOSED", "FISCAL_YEAR", fy.getFiscalYear(), Map.of());
        return toResponse(fy);
    }

    /** Period for an accounting date; the adjustment period only when requested and dated at year end. */
    @Transactional(readOnly = true)
    public AccountingPeriod resolve(LocalDate date, boolean adjustmentPeriod) {
        FiscalYear fy = years.findContaining(date).orElseThrow(() ->
                AccountService.rule("No fiscal year is defined for " + date + "."));
        if (adjustmentPeriod) {
            if (!date.equals(fy.getEndDate())) {
                throw AccountService.rule("Adjustment-period entries must be dated " + fy.getEndDate() + ".");
            }
            return periods.findByFiscalYearIdAndPeriodNumber(fy.getId(), (short) 13).orElseThrow();
        }
        return periods.findRegularContaining(date).orElseThrow(() ->
                AccountService.rule("No accounting period covers " + date + "."));
    }

    @Transactional(readOnly = true)
    public AccountingPeriod period(UUID id) {
        return periods.findById(id).orElseThrow(() -> ApiException.notFound("Accounting period"));
    }

    @Transactional(readOnly = true)
    public FiscalYear fiscalYear(UUID id) {
        return years.findById(id).orElseThrow(() -> ApiException.notFound("Fiscal year"));
    }

    /** Net (debit - credit) of every revenue/expense account and dimension combination for a fiscal year. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> nominalBalances(UUID fiscalYearId) {
        return jdbc.queryForList("""
                SELECT l.account_id, l.cost_center_id, l.subsidiary_account_id, sum(l.debit) - sum(l.credit) AS net
                FROM journal_entry_lines l JOIN chart_of_accounts a ON a.id = l.account_id
                JOIN accounting_periods p ON p.id = l.period_id
                WHERE p.fiscal_year_id = ? AND a.account_type IN ('REVENUE', 'EXPENSE')
                GROUP BY l.account_id, l.cost_center_id, l.subsidiary_account_id
                HAVING sum(l.debit) - sum(l.credit) <> 0""", fiscalYearId);
    }

    PeriodResponse transition(AccountingPeriod p, PeriodStatus to, String changeType, String reason, String waivedJson,
            Actor actor) {
        PeriodStatus from = p.getStatus();
        Instant now = Instant.now(clock);
        p.changeStatus(to, now, actor.username());
        changes.save(new PeriodStatusChange(UUID.randomUUID(), p.getId(), changeType, from, to, "APPLIED", reason, waivedJson,
                actor.userId(), actor.username(), now));
        periods.flush();
        audit.record(actor, "PERIOD_" + changeType, "ACCOUNTING_PERIOD", p.getName(),
                Map.of("from", from.name(), "to", to.name(), "reason", String.valueOf(reason), "waivedChecks", waivedJson));
        return toResponse(p);
    }

    AccountingPeriod lockVersioned(UUID periodId, long version) {
        AccountingPeriod p = periods.findByIdForUpdate(periodId).orElseThrow(() -> ApiException.notFound("Accounting period"));
        if (p.getVersion() != version) {
            throw ApiException.staleVersion();
        }
        return p;
    }

    public PeriodResponse toResponse(AccountingPeriod p) {
        int year = p.getSequenceKey() / 100;
        return new PeriodResponse(p.getId(), p.getFiscalYearId(), year, p.getPeriodNumber(), p.getName(), p.getPeriodType(),
                p.getStartDate(), p.getEndDate(), p.getStatus().name(), p.getVersion());
    }

    private FiscalYearResponse toResponse(FiscalYear fy) {
        return new FiscalYearResponse(fy.getId(), fy.getFiscalYear(), fy.getStartDate(), fy.getEndDate(), fy.getStatus(),
                fy.getClosedAt(), fy.getClosedBy(),
                periods.findByFiscalYearIdOrderByPeriodNumberAsc(fy.getId()).stream().map(this::toResponse).toList(),
                fy.getVersion());
    }

    static BigDecimal zeroIfNull(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
