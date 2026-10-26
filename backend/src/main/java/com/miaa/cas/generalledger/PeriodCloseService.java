package com.miaa.cas.generalledger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.miaa.cas.generalledger.web.LedgerDtos.CloseCheck;
import com.miaa.cas.generalledger.web.LedgerDtos.CloseChecklist;
import com.miaa.cas.generalledger.web.LedgerDtos.PeriodChangeResponse;
import com.miaa.cas.generalledger.web.LedgerDtos.PeriodResponse;
import com.miaa.cas.notifications.NotificationService;
import java.math.BigDecimal;
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
 * Period close checklist, close (with documented waivers of waivable checks), and two-person reopen:
 * one user with PERIOD_CLOSE requests, a different user with PERIOD_REOPEN decides.
 */
@Service
public class PeriodCloseService {

    public static final String PASS = "PASS";
    public static final String FAIL = "FAIL";
    public static final String NOT_AVAILABLE = "NOT_AVAILABLE";

    private final FiscalCalendarService calendar;
    private final AccountingPeriodRepository periods;
    private final FiscalYearRepository years;
    private final PeriodStatusChangeRepository changes;
    private final LedgerBalanceService balances;
    private final List<PeriodCloseContributor> contributors;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final NotificationService notifications;
    private final ObjectMapper mapper;
    private final Clock clock;

    public PeriodCloseService(FiscalCalendarService calendar, AccountingPeriodRepository periods, FiscalYearRepository years,
            PeriodStatusChangeRepository changes, LedgerBalanceService balances, List<PeriodCloseContributor> contributors,
            JdbcTemplate jdbc, AuditService audit, NotificationService notifications, ObjectMapper mapper, Clock clock) {
        this.calendar = calendar;
        this.periods = periods;
        this.years = years;
        this.changes = changes;
        this.balances = balances;
        this.contributors = contributors;
        this.jdbc = jdbc;
        this.audit = audit;
        this.notifications = notifications;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CloseChecklist checklist(UUID periodId) {
        AccountingPeriod p = calendar.period(periodId);
        List<CloseCheck> checks = evaluate(p);
        return new CloseChecklist(calendar.toResponse(p), checks, checks.stream().noneMatch(c -> FAIL.equals(c.outcome())));
    }

    @Transactional
    public PeriodResponse close(UUID periodId, long version, List<String> waived, String justification, Actor actor) {
        AccountingPeriod p = calendar.lockVersioned(periodId, version);
        if (p.getStatus() != PeriodStatus.OPEN && p.getStatus() != PeriodStatus.SOFT_CLOSED) {
            throw AccountService.rule("Only OPEN or SOFT_CLOSED periods can be closed.");
        }
        Set<String> waivedSet = new HashSet<>(waived == null ? List.of() : waived);
        List<CloseCheck> checks = evaluate(p);
        List<String> blocking = new ArrayList<>();
        List<String> applied = new ArrayList<>();
        for (CloseCheck c : checks) {
            if (!FAIL.equals(c.outcome())) {
                continue;
            }
            if (c.waivable() && waivedSet.contains(c.code())) {
                applied.add(c.code());
            } else {
                blocking.add(c.code() + ": " + c.detail());
            }
        }
        if (!blocking.isEmpty()) {
            throw new ApiException(ErrorCode.ACCOUNTING_RULE_VIOLATION, "The period cannot be closed yet.",
                    Map.of("violations", blocking, "checks", checks));
        }
        if (!applied.isEmpty() && (justification == null || justification.trim().length() < 10)) {
            throw AccountService.rule("Waiving close checks requires a justification of at least 10 characters.");
        }
        return calendar.transition(p, PeriodStatus.CLOSED, "CLOSE", justification, json(applied), actor);
    }

    @Transactional
    public PeriodChangeResponse requestReopen(UUID periodId, String reason, Actor actor) {
        AccountingPeriod p = calendar.period(periodId);
        if (p.getStatus() != PeriodStatus.CLOSED) {
            throw AccountService.rule("Only CLOSED periods can be reopened.");
        }
        FiscalYear fy = years.findById(p.getFiscalYearId()).orElseThrow();
        if (!fy.isOpen()) {
            throw AccountService.rule("Periods of a closed fiscal year cannot be reopened.");
        }
        PeriodStatusChange c = changes.saveAndFlush(new PeriodStatusChange(UUID.randomUUID(), periodId, "REOPEN",
                PeriodStatus.CLOSED, PeriodStatus.OPEN, "PENDING", reason.trim(), "[]", actor.userId(), actor.username(),
                Instant.now(clock)));
        audit.record(actor, "PERIOD_REOPEN_REQUESTED", "ACCOUNTING_PERIOD", p.getName(), Map.of("reason", reason));
        return toResponse(c, p);
    }

    @Transactional
    public PeriodChangeResponse decideReopen(UUID changeId, boolean approve, String note, Actor actor) {
        PeriodStatusChange c = changes.findById(changeId).orElseThrow(() -> ApiException.notFound("Reopen request"));
        if (!"PENDING".equals(c.getRequestStatus()) || !"REOPEN".equals(c.getChangeType())) {
            throw new ApiException(ErrorCode.INVALID_STATE, "This request has already been decided.");
        }
        if (c.getRequestedById() != null && c.getRequestedById().equals(actor.userId())) {
            throw new ApiException(ErrorCode.SEGREGATION_OF_DUTIES,
                    "A reopen request must be decided by someone other than the requester.");
        }
        AccountingPeriod p = periods.findByIdForUpdate(c.getPeriodId()).orElseThrow();
        Instant now = Instant.now(clock);
        if (approve) {
            if (p.getStatus() != PeriodStatus.CLOSED) {
                throw new ApiException(ErrorCode.INVALID_STATE, "The period is no longer closed.");
            }
            p.changeStatus(PeriodStatus.OPEN, now, actor.username());
        }
        c.decide(approve ? "APPROVED" : "REJECTED", actor.userId(), actor.username(), note, now);
        changes.flush();
        notifications.notify(c.getRequestedById(), approve ? "REQUEST_APPROVED" : "REQUEST_REJECTED", "Reopening " + p.getName() + " was "
                + (approve ? "approved" : "rejected") + " by " + actor.username(), note, "/fiscal-periods");
        audit.record(actor, approve ? "PERIOD_REOPENED" : "PERIOD_REOPEN_REJECTED", "ACCOUNTING_PERIOD", p.getName(),
                Map.of("requestedBy", c.getRequestedBy(), "reason", String.valueOf(c.getReason()), "note", String.valueOf(note)));
        return toResponse(c, p);
    }

    @Transactional(readOnly = true)
    public List<PeriodChangeResponse> history(UUID periodId) {
        AccountingPeriod p = calendar.period(periodId);
        return changes.findByPeriodIdOrderByRequestedAtDesc(periodId).stream().map(c -> toResponse(c, p)).toList();
    }

    @Transactional(readOnly = true)
    public List<PeriodChangeResponse> pendingReopens() {
        return changes.findByRequestStatusOrderByRequestedAtAsc("PENDING").stream()
                .map(c -> toResponse(c, calendar.period(c.getPeriodId()))).toList();
    }

    private List<CloseCheck> evaluate(AccountingPeriod p) {
        List<CloseCheck> checks = new ArrayList<>();
        contributors.forEach(c -> checks.addAll(c.checks(p)));

        List<AccountingPeriod> earlier = periods.findUnclosedBefore(p.getSequenceKey());
        checks.add(new CloseCheck("PRIOR_PERIODS_CLOSED", "All earlier periods are closed", earlier.isEmpty() ? PASS : FAIL,
                true, earlier.isEmpty() ? "" : "Not closed: " + String.join(", ",
                        earlier.stream().limit(5).map(AccountingPeriod::getName).toList())));

        Map<String, Object> totals = jdbc.queryForMap("SELECT coalesce(sum(debit), 0) AS d, coalesce(sum(credit), 0) AS c "
                + "FROM journal_entry_lines WHERE period_id = ?", p.getId());
        BigDecimal d = (BigDecimal) totals.get("d");
        BigDecimal cr = (BigDecimal) totals.get("c");
        boolean balanced = d.compareTo(cr) == 0;
        checks.add(new CloseCheck("PERIOD_BALANCED", "Posted debits equal posted credits", balanced ? PASS : FAIL, false,
                "Debits " + d.toPlainString() + ", credits " + cr.toPlainString()));

        int diffs = balances.differences(p.getId()).size();
        checks.add(new CloseCheck("LEDGER_CACHE_CONSISTENT", "Ledger balance cache matches posted entries",
                diffs == 0 ? PASS : FAIL, false, diffs == 0 ? "" : diffs + " account(s) differ; run the balance rebuild"));
        return checks;
    }

    private String json(List<String> values) {
        try {
            return mapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private PeriodChangeResponse toResponse(PeriodStatusChange c, AccountingPeriod p) {
        List<String> waived;
        try {
            waived = mapper.readValue(c.getWaivedChecks(), new TypeReference<>() { });
        } catch (JsonProcessingException e) {
            waived = List.of();
        }
        return new PeriodChangeResponse(c.getId(), c.getPeriodId(), p.getName(), c.getChangeType(), c.getFromStatus(),
                c.getToStatus(), c.getRequestStatus(), c.getReason(), waived, c.getRequestedBy(), c.getRequestedAt(),
                c.getDecidedBy(), c.getDecidedAt(), c.getDecisionNote());
    }
}
