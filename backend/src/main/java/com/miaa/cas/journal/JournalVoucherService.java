package com.miaa.cas.journal;

import com.miaa.cas.approvals.ApprovalLimitService;
import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.money.Money;
import com.miaa.cas.generalledger.FiscalCalendarService;
import com.miaa.cas.generalledger.domain.Account;
import com.miaa.cas.generalledger.domain.AccountRepository;
import com.miaa.cas.generalledger.domain.AccountType;
import com.miaa.cas.generalledger.domain.AccountingPeriod;
import com.miaa.cas.generalledger.domain.FiscalYear;
import com.miaa.cas.journal.domain.JournalVoucher;
import com.miaa.cas.journal.domain.JournalVoucherRepository;
import com.miaa.cas.journal.domain.LineData;
import com.miaa.cas.journal.domain.VoucherStatus;
import com.miaa.cas.journal.domain.VoucherType;
import com.miaa.cas.journal.web.JournalDtos.LineRequest;
import com.miaa.cas.journal.web.JournalDtos.UpdateVoucherRequest;
import com.miaa.cas.journal.web.JournalDtos.VoucherDetail;
import com.miaa.cas.journal.web.JournalDtos.VoucherRequest;
import com.miaa.cas.security.CurrentActor;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Journal Entry Voucher workflow (docs/06-accounting-posting-design.md): DRAFT -> SUBMITTED (one
 * approval step at a time) -> APPROVED -> POSTED, with RETURNED, REJECTED and CANCELLED branches.
 * Every step actor must differ from the preparer and from earlier approvers in the same cycle.
 */
@Service
public class JournalVoucherService {

    private final JournalVoucherRepository vouchers;
    private final AccountRepository accounts;
    private final FiscalCalendarService calendar;
    private final VoucherValidator validator;
    private final WorkflowConfig workflow;
    private final DecisionLog decisions;
    private final VoucherQueryService queries;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ApprovalLimitService limits;

    public JournalVoucherService(JournalVoucherRepository vouchers, AccountRepository accounts,
            FiscalCalendarService calendar, VoucherValidator validator, WorkflowConfig workflow, DecisionLog decisions,
            VoucherQueryService queries, AuditService audit, JdbcTemplate jdbc, Clock clock, ApprovalLimitService limits) {
        this.limits = limits;
        this.vouchers = vouchers;
        this.accounts = accounts;
        this.calendar = calendar;
        this.validator = validator;
        this.workflow = workflow;
        this.decisions = decisions;
        this.queries = queries;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public VoucherDetail create(VoucherRequest req) {
        VoucherType type = VoucherType.valueOf(req.voucherType());
        AccountingPeriod period = calendar.resolve(req.accountingDate(), req.adjustmentPeriod());
        List<LineData> lines = toLines(req.lines());
        UUID id = UUID.randomUUID();
        validator.validate(VoucherValidator.Level.DRAFT, id, type, req.accountingDate(), period, lines);
        JournalVoucher v = new JournalVoucher(id, type, req.accountingDate(), period.getId(), req.description().trim(),
                blank(req.supportingReference()), "MANUAL", null, CurrentActor.userId(), CurrentActor.username(), null, null,
                Instant.now(clock));
        v.addLines(lines);
        vouchers.saveAndFlush(v);
        audit.record("JOURNAL_VOUCHER_CREATED", "JOURNAL_VOUCHER", id, Map.of("type", type.name(),
                "total", v.getTotalDebit().toPlainString(), "lines", lines.size()));
        return queries.detail(id);
    }

    @Transactional
    public VoucherDetail update(UUID id, UpdateVoucherRequest req) {
        JournalVoucher v = lockVersioned(id, req.version());
        requirePreparer(v, "edit");
        if (!v.getStatus().isEditable()) {
            throw state("Only draft or returned vouchers can be edited.");
        }
        if (!v.getVoucherType().isManuallyCreatable()) {
            throw state("System-generated vouchers cannot be edited; return or reject them instead.");
        }
        VoucherType type = VoucherType.valueOf(req.voucherType());
        AccountingPeriod period = calendar.resolve(req.accountingDate(), req.adjustmentPeriod());
        List<LineData> lines = toLines(req.lines());
        validator.validate(VoucherValidator.Level.DRAFT, id, type, req.accountingDate(), period, lines);
        BigDecimal before = v.getTotalDebit();
        v.edit(type, req.accountingDate(), period.getId(), req.description().trim(), blank(req.supportingReference()));
        v.clearLines();
        vouchers.flush();
        v.addLines(lines);
        v.moveTo(VoucherStatus.DRAFT, null, Instant.now(clock), CurrentActor.username());
        vouchers.flush();
        audit.record("JOURNAL_VOUCHER_UPDATED", "JOURNAL_VOUCHER", id, Map.of("totalBefore", before.toPlainString(),
                "totalAfter", v.getTotalDebit().toPlainString(), "lines", lines.size()));
        return queries.detail(id);
    }

    @Transactional
    public VoucherDetail submit(UUID id, long version, String comment) {
        CurrentActor.requirePermission("JOURNAL_SUBMIT");
        JournalVoucher v = lockVersioned(id, version);
        requirePreparer(v, "submit");
        if (!v.getStatus().isEditable()) {
            throw state("Only draft or returned vouchers can be submitted.");
        }
        validateFull(v);
        startWorkflow(v, comment);
        return queries.detail(id);
    }

    @Transactional
    public VoucherDetail approveStep(UUID id, long version, String comment) {
        JournalVoucher v = lockVersioned(id, version);
        if (v.getStatus() != VoucherStatus.SUBMITTED || v.getCurrentStepOrder() == null) {
            throw state("The voucher is not awaiting approval.");
        }
        WorkflowConfig.Step step = currentStep(v);
        CurrentActor.requirePermission(step.permission());
        UUID actor = CurrentActor.userId();
        if (actor.equals(v.getPreparedById())) {
            throw sod("You prepared this voucher and cannot " + step.code().toLowerCase() + " it.");
        }
        if (decisions.approversInCurrentCycle(id).contains(actor)) {
            throw sod("You already acted on an earlier step of this voucher; another person must complete this step.");
        }
        limits.require(DecisionLog.JOURNAL_VOUCHER, v.getTotalDebit());
        validateFull(v);
        decisions.record(id, "APPROVE", step.order(), step.code(), actor, CurrentActor.username(), blank(comment));
        WorkflowConfig.Step next = workflow.steps(v.getVoucherType(), v.getTotalDebit()).stream()
                .filter(s -> s.order() > step.order()).findFirst().orElse(null);
        Instant now = Instant.now(clock);
        if (next == null) {
            v.moveTo(VoucherStatus.APPROVED, null, now, CurrentActor.username());
        } else {
            v.moveTo(VoucherStatus.SUBMITTED, (short) next.order(), now, CurrentActor.username());
        }
        vouchers.flush();
        audit.record("JOURNAL_VOUCHER_STEP_APPROVED", "JOURNAL_VOUCHER", id, Map.of("step", step.code(),
                "nextStatus", v.getStatus().name()));
        return queries.detail(id);
    }

    @Transactional
    public VoucherDetail returnOrReject(UUID id, long version, String comment, boolean reject) {
        if (comment == null || comment.trim().length() < 5) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A comment of at least 5 characters is required.");
        }
        JournalVoucher v = lockVersioned(id, version);
        String permission;
        Integer stepOrder = null;
        String stepCode = null;
        if (v.getStatus() == VoucherStatus.SUBMITTED && v.getCurrentStepOrder() != null) {
            WorkflowConfig.Step step = currentStep(v);
            permission = step.permission();
            stepOrder = step.order();
            stepCode = step.code();
        } else if (v.getStatus() == VoucherStatus.APPROVED) {
            permission = "JOURNAL_POST";
            stepCode = "POST";
        } else {
            throw state("Only vouchers awaiting approval or posting can be " + (reject ? "rejected." : "returned."));
        }
        CurrentActor.requirePermission(permission);
        if (CurrentActor.userId().equals(v.getPreparedById())) {
            throw sod("The preparer cannot " + (reject ? "reject" : "return") + " their own voucher; cancel it instead.");
        }
        decisions.record(id, reject ? "REJECT" : "RETURN", stepOrder, stepCode, CurrentActor.userId(),
                CurrentActor.username(), comment.trim());
        v.moveTo(reject ? VoucherStatus.REJECTED : VoucherStatus.RETURNED, null, Instant.now(clock), CurrentActor.username());
        vouchers.flush();
        audit.record(reject ? "JOURNAL_VOUCHER_REJECTED" : "JOURNAL_VOUCHER_RETURNED", "JOURNAL_VOUCHER", id,
                Map.of("comment", comment.trim()));
        return queries.detail(id);
    }

    @Transactional
    public VoucherDetail cancel(UUID id, long version, String comment) {
        JournalVoucher v = lockVersioned(id, version);
        requirePreparer(v, "cancel");
        if (!v.getStatus().isEditable()) {
            throw state("Only draft or returned vouchers can be cancelled.");
        }
        decisions.record(id, "CANCEL", null, null, CurrentActor.userId(), CurrentActor.username(), blank(comment));
        v.moveTo(VoucherStatus.CANCELLED, null, Instant.now(clock), CurrentActor.username());
        vouchers.flush();
        audit.record("JOURNAL_VOUCHER_CANCELLED", "JOURNAL_VOUCHER", id, Map.of());
        return queries.detail(id);
    }

    /**
     * Creates and submits a reversal of a posted entry: debits and credits swapped, dated in an open
     * period on or after the original. The original entry is never modified.
     */
    @Transactional
    public VoucherDetail createReversal(UUID entryId, LocalDate date, boolean adjustmentPeriod, String reason) {
        Map<String, Object> entry = jdbc.queryForList("SELECT entry_number, accounting_date, voucher_type FROM journal_entries "
                + "WHERE id = ?", entryId).stream().findFirst().orElseThrow(() -> ApiException.notFound("Journal entry"));
        Boolean reversed = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM journal_entries WHERE reversal_of_entry_id = ?) "
                + "OR EXISTS (SELECT 1 FROM journal_vouchers WHERE reversal_of_entry_id = ? AND status NOT IN ('REJECTED', 'CANCELLED'))",
                Boolean.class, entryId, entryId);
        if (Boolean.TRUE.equals(reversed)) {
            throw state("This entry has already been reversed or has a reversal in progress.");
        }
        if ("SUBLEDGER".equals(entry.get("voucher_type"))) {
            throw rule("This entry was generated by a sub-ledger document; reverse or cancel it from that document.");
        }
        LocalDate original = ((java.sql.Date) entry.get("accounting_date")).toLocalDate();
        if (date.isBefore(original)) {
            throw rule("The reversal date cannot be earlier than the original entry date (" + original + ").");
        }
        List<LineData> lines = jdbc.query("SELECT account_id, cost_center_id, subsidiary_account_id, description, debit, credit "
                + "FROM journal_entry_lines WHERE entry_id = ? ORDER BY line_no", (rs, i) -> new LineData(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4),
                        rs.getBigDecimal(5), rs.getBigDecimal(6)).mirrored(), entryId);
        AccountingPeriod period = calendar.resolve(date, adjustmentPeriod);
        UUID id = UUID.randomUUID();
        String number = (String) entry.get("entry_number");
        JournalVoucher v = new JournalVoucher(id, VoucherType.REVERSAL, date, period.getId(),
                "Reversal of " + number + ": " + reason.trim(), number, "REVERSAL", number, CurrentActor.userId(),
                CurrentActor.username(), entryId, null, Instant.now(clock));
        v.addLines(lines);
        vouchers.saveAndFlush(v);
        validateFull(v);
        startWorkflow(v, reason.trim());
        audit.record("JOURNAL_REVERSAL_REQUESTED", "JOURNAL_ENTRY", number, Map.of("reversalVoucherId", id.toString(),
                "reason", reason.trim()));
        return queries.detail(id);
    }

    /**
     * Generates (and submits) the year-end closing voucher: every revenue and expense balance of the
     * fiscal year is zeroed against the chosen equity (accumulated surplus) account in period 13.
     */
    @Transactional
    public VoucherDetail createClosingVoucher(UUID fiscalYearId, UUID surplusAccountId) {
        FiscalYear fy = calendar.fiscalYear(fiscalYearId);
        Account surplus = accounts.findById(surplusAccountId).orElseThrow(() -> ApiException.notFound("Account"));
        if (surplus.getAccountType() != AccountType.EQUITY || !surplus.isPostable()) {
            throw rule("The closing account must be a postable equity account.");
        }
        Boolean pending = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM journal_vouchers v JOIN accounting_periods p "
                + "ON p.id = v.period_id WHERE p.fiscal_year_id = ? AND v.voucher_type = 'CLOSING' "
                + "AND v.status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'RETURNED'))", Boolean.class, fiscalYearId);
        if (Boolean.TRUE.equals(pending)) {
            throw state("A closing voucher for this fiscal year is already in progress.");
        }
        List<LineData> lines = new ArrayList<>();
        BigDecimal net = BigDecimal.ZERO;
        for (Map<String, Object> row : calendar.nominalBalances(fiscalYearId)) {
            BigDecimal balance = (BigDecimal) row.get("net");
            net = net.add(balance);
            UUID accountId = (UUID) row.get("account_id");
            UUID cc = (UUID) row.get("cost_center_id");
            UUID sl = (UUID) row.get("subsidiary_account_id");
            lines.add(balance.signum() > 0
                    ? new LineData(accountId, cc, sl, "Year-end closing", Money.ZERO, Money.of(balance))
                    : new LineData(accountId, cc, sl, "Year-end closing", Money.of(balance.negate()), Money.ZERO));
        }
        if (lines.isEmpty()) {
            throw rule("Revenue and expense accounts have no balances to close for " + fy.getFiscalYear() + ".");
        }
        if (net.signum() != 0) {
            // net > 0: expenses exceed revenue (deficit) -> debit surplus; net < 0: surplus -> credit.
            lines.add(net.signum() > 0
                    ? new LineData(surplusAccountId, null, null, "Net deficit for the year", Money.of(net), Money.ZERO)
                    : new LineData(surplusAccountId, null, null, "Net surplus for the year", Money.ZERO, Money.of(net.negate())));
        }
        AccountingPeriod period = calendar.resolve(fy.getEndDate(), true);
        UUID id = UUID.randomUUID();
        JournalVoucher v = new JournalVoucher(id, VoucherType.CLOSING, fy.getEndDate(), period.getId(),
                "Year-end closing of revenue and expense accounts for " + fy.getFiscalYear(), null, "YEAR_END_CLOSE",
                String.valueOf(fy.getFiscalYear()), CurrentActor.userId(), CurrentActor.username(), null, null,
                Instant.now(clock));
        v.addLines(lines);
        vouchers.saveAndFlush(v);
        validateFull(v);
        startWorkflow(v, "Generated year-end closing voucher");
        audit.record("CLOSING_VOUCHER_GENERATED", "FISCAL_YEAR", fy.getFiscalYear(), Map.of("voucherId", id.toString(),
                "lines", lines.size(), "netToSurplus", net.negate().toPlainString()));
        return queries.detail(id);
    }

    private void startWorkflow(JournalVoucher v, String comment) {
        List<WorkflowConfig.Step> steps = workflow.steps(v.getVoucherType(), v.getTotalDebit());
        Instant now = Instant.now(clock);
        decisions.record(v.getId(), "SUBMIT", null, null, CurrentActor.userId(), CurrentActor.username(), blank(comment));
        v.markSubmitted(now);
        if (steps.isEmpty()) {
            v.moveTo(VoucherStatus.APPROVED, null, now, CurrentActor.username());
        } else {
            v.moveTo(VoucherStatus.SUBMITTED, (short) steps.get(0).order(), now, CurrentActor.username());
        }
        vouchers.flush();
        audit.record("JOURNAL_VOUCHER_SUBMITTED", "JOURNAL_VOUCHER", v.getId(), Map.of("total",
                v.getTotalDebit().toPlainString(), "steps", steps.size()));
    }

    private void validateFull(JournalVoucher v) {
        validator.validate(VoucherValidator.Level.FULL, v.getId(), v.getVoucherType(), v.getAccountingDate(),
                calendar.period(v.getPeriodId()), v.lineData());
    }

    private WorkflowConfig.Step currentStep(JournalVoucher v) {
        return workflow.steps(v.getVoucherType(), v.getTotalDebit()).stream()
                .filter(s -> s.order() == v.getCurrentStepOrder()).findFirst()
                .orElseThrow(() -> state("The voucher's approval step is no longer configured; return it to the preparer."));
    }

    private JournalVoucher lockVersioned(UUID id, long version) {
        JournalVoucher v = vouchers.findByIdForUpdate(id).orElseThrow(() -> ApiException.notFound("Journal voucher"));
        if (v.getVersion() != version) {
            throw ApiException.staleVersion();
        }
        return v;
    }

    private static void requirePreparer(JournalVoucher v, String action) {
        CurrentActor.requirePermission("JOURNAL_CREATE");
        if (!CurrentActor.userId().equals(v.getPreparedById())) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Only the preparer can " + action + " this voucher.");
        }
    }

    static List<LineData> toLines(List<LineRequest> lines) {
        return lines.stream().map(l -> new LineData(l.accountId(), l.costCenterId(), l.subsidiaryAccountId(),
                blank(l.description()), Money.of(l.debit()), Money.of(l.credit()))).toList();
    }

    static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static ApiException state(String message) {
        return new ApiException(ErrorCode.INVALID_STATE, message);
    }

    static ApiException rule(String message) {
        return new ApiException(ErrorCode.ACCOUNTING_RULE_VIOLATION, message, Map.of("violations", List.of(message)));
    }

    static ApiException sod(String message) {
        return new ApiException(ErrorCode.SEGREGATION_OF_DUTIES, message);
    }
}
