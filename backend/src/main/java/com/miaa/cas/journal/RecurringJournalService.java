package com.miaa.cas.journal;

import com.miaa.cas.audit.Actor;
import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.web.JdbcPaging;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.config.AccountingProperties;
import com.miaa.cas.generalledger.FiscalCalendarService;
import com.miaa.cas.generalledger.domain.AccountingPeriod;
import com.miaa.cas.generalledger.domain.PeriodStatus;
import com.miaa.cas.identity.RoleAssignments;
import com.miaa.cas.identity.domain.UserRepository;
import com.miaa.cas.journal.domain.JournalVoucher;
import com.miaa.cas.journal.domain.JournalVoucherRepository;
import com.miaa.cas.journal.domain.LineData;
import com.miaa.cas.journal.domain.RecurringJournal;
import com.miaa.cas.journal.domain.RecurringJournalRepository;
import com.miaa.cas.journal.domain.VoucherType;
import com.miaa.cas.journal.web.JournalDtos.RecurringRequest;
import com.miaa.cas.journal.web.JournalDtos.RecurringResponse;
import com.miaa.cas.journal.web.JournalDtos.RunDueResult;
import com.miaa.cas.security.CurrentActor;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recurring journal templates. Due templates generate DRAFT vouchers owned by the template owner, so
 * every occurrence still passes submission, review, approval and posting with the usual controls.
 */
@Service
public class RecurringJournalService {

    private static final Logger log = LoggerFactory.getLogger(RecurringJournalService.class);

    private final RecurringJournalRepository templates;
    private final JournalVoucherRepository vouchers;
    private final FiscalCalendarService calendar;
    private final VoucherValidator validator;
    private final UserRepository users;
    private final RoleAssignments roles;
    private final AccountingProperties properties;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public RecurringJournalService(RecurringJournalRepository templates, JournalVoucherRepository vouchers,
            FiscalCalendarService calendar, VoucherValidator validator, UserRepository users, RoleAssignments roles,
            AccountingProperties properties, AuditService audit, JdbcTemplate jdbc, Clock clock) {
        this.templates = templates;
        this.vouchers = vouchers;
        this.calendar = calendar;
        this.validator = validator;
        this.users = users;
        this.roles = roles;
        this.properties = properties;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<RecurringResponse> list(int page, int size) {
        return JdbcPaging.ids(jdbc, "SELECT id FROM recurring_journals ORDER BY name, id", page, size)
                .map(id -> toResponse(templates.findById(id).orElseThrow()));
    }

    @Transactional
    public RecurringResponse create(RecurringRequest req) {
        List<LineData> lines = JournalVoucherService.toLines(req.lines());
        BigDecimal d = lines.stream().map(LineData::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal c = lines.stream().map(LineData::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (d.compareTo(c) != 0 || d.signum() == 0) {
            throw JournalVoucherService.rule("Template debits and credits must be equal and greater than zero.");
        }
        if (req.endDate() != null && req.endDate().isBefore(req.firstRunDate())) {
            throw JournalVoucherService.rule("The end date cannot precede the first run date.");
        }
        LocalDate first = req.firstRunDate().withDayOfMonth(Math.min(req.dayOfMonth(), req.firstRunDate().lengthOfMonth()));
        AccountingPeriod period = calendar.resolve(first, false);
        validator.validate(VoucherValidator.Level.DRAFT, UUID.randomUUID(), VoucherType.RECURRING, first, period, lines);
        RecurringJournal t = templates.saveAndFlush(new RecurringJournal(UUID.randomUUID(), req.name().trim(),
                req.description().trim(), req.frequency(), req.dayOfMonth(), first, req.endDate(), CurrentActor.userId(),
                CurrentActor.username(), lines, Instant.now(clock)));
        audit.record("RECURRING_JOURNAL_CREATED", "RECURRING_JOURNAL", t.getId(), Map.of("name", t.getName(),
                "frequency", t.getFrequency(), "total", d.toPlainString()));
        return toResponse(t);
    }

    @Transactional
    public RecurringResponse setStatus(UUID id, long version, boolean active) {
        RecurringJournal t = templates.findById(id).orElseThrow(() -> ApiException.notFound("Recurring journal"));
        if (t.getVersion() != version) {
            throw ApiException.staleVersion();
        }
        if ("ENDED".equals(t.getStatus())) {
            throw JournalVoucherService.state("The template has ended.");
        }
        t.setStatus(active ? "ACTIVE" : "PAUSED");
        templates.flush();
        audit.record(active ? "RECURRING_JOURNAL_RESUMED" : "RECURRING_JOURNAL_PAUSED", "RECURRING_JOURNAL", id, Map.of());
        return toResponse(t);
    }

    @Scheduled(cron = "${cas.accounting.recurring-cron:0 0 1 * * *}", zone = "Asia/Manila")
    public void scheduledRun() {
        if (properties.recurringEnabled()) {
            RunDueResult r = runDue(LocalDate.now(clock.withZone(ZoneId.of("Asia/Manila"))), Actor.SYSTEM);
            log.info("Recurring journals: {} generated, {} skipped", r.generated(), r.skipped().size());
        }
    }

    /** Generates vouchers for every due ACTIVE template. Safe to run on several instances (SKIP LOCKED). */
    @Transactional
    public RunDueResult runDue(LocalDate today, Actor actor) {
        List<UUID> due = jdbc.queryForList("SELECT id FROM recurring_journals WHERE status = 'ACTIVE' AND next_run_date <= ? "
                + "ORDER BY next_run_date FOR UPDATE SKIP LOCKED", UUID.class, java.sql.Date.valueOf(today));
        int generated = 0;
        List<String> skipped = new ArrayList<>();
        for (UUID id : due) {
            RecurringJournal t = templates.findById(id).orElseThrow();
            while ("ACTIVE".equals(t.getStatus()) && !t.getNextRunDate().isAfter(today)) {
                String problem = generate(t);
                if (problem != null) {
                    skipped.add(t.getName() + " (" + t.getNextRunDate() + "): " + problem);
                    break;
                }
                generated++;
                t.advance();
            }
        }
        templates.flush();
        audit.record(actor, "RECURRING_JOURNALS_RUN", "RECURRING_JOURNAL", null, Map.of("generated", generated,
                "skipped", skipped));
        return new RunDueResult(generated, skipped);
    }

    private String generate(RecurringJournal t) {
        var owner = users.findById(t.getOwnerId()).orElse(null);
        if (owner == null || !owner.canSignIn(Instant.now(clock))
                || !roles.effectivePermissions(owner.getId()).contains("JOURNAL_CREATE")) {
            return "the template owner can no longer prepare vouchers";
        }
        AccountingPeriod period;
        try {
            period = calendar.resolve(t.getNextRunDate(), false);
        } catch (ApiException e) {
            return e.getMessage();
        }
        if (period.getStatus() == PeriodStatus.CLOSED) {
            return "period " + period.getName() + " is closed";
        }
        JournalVoucher v = new JournalVoucher(UUID.randomUUID(), VoucherType.RECURRING, t.getNextRunDate(), period.getId(),
                t.getDescription() + " (" + t.getNextRunDate() + ")", null, "RECURRING", t.getName(), owner.getId(),
                owner.getUsername(), null, t.getId(), Instant.now(clock));
        v.addLines(t.lineData());
        vouchers.saveAndFlush(v);
        return null;
    }

    static RecurringResponse toResponse(RecurringJournal t) {
        BigDecimal total = t.lineData().stream().map(LineData::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new RecurringResponse(t.getId(), t.getName(), t.getDescription(), t.getFrequency(), t.getDayOfMonth(),
                t.getNextRunDate(), t.getEndDate(), t.getStatus(), t.getOwnerUsername(), total, t.getVersion());
    }
}
