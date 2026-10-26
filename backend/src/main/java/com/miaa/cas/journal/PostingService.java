package com.miaa.cas.journal;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.idempotency.IdempotencyService;
import com.miaa.cas.generalledger.FiscalCalendarService;
import com.miaa.cas.generalledger.LedgerBalanceService;
import com.miaa.cas.generalledger.LedgerBalanceService.Movement;
import com.miaa.cas.generalledger.PostingBudgetGuard;
import com.miaa.cas.generalledger.domain.AccountingPeriod;
import com.miaa.cas.journal.domain.JournalVoucher;
import com.miaa.cas.journal.domain.JournalVoucherRepository;
import com.miaa.cas.journal.domain.LineData;
import com.miaa.cas.journal.domain.VoucherStatus;
import com.miaa.cas.journal.domain.VoucherType;
import com.miaa.cas.security.CurrentActor;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single writer of journal entries. Posting is one transaction: lock the voucher, take a shared
 * lock on its period (so the period cannot close mid-post), re-validate, apply budget control, draw a
 * gap-free number, insert the immutable entry and lines (a deferred trigger re-checks balance at
 * commit), update the balance cache, mark the voucher posted, and record decision and audit event.
 * Manual vouchers come through {@link #post}; sub-ledger documents through {@link #postSubledger}.
 */
@Service
public class PostingService {

    public static final String OPERATION = "JOURNAL_VOUCHER_POST";

    /** A posted entry's identity, returned to sub-ledger modules for their own records. */
    public record PostedEntry(UUID entryId, String entryNumber, UUID voucherId) {
    }

    /**
     * A balanced entry produced by an approved sub-ledger document. {@code sourceType} must start with
     * "SL_"; (sourceType, sourceReference) is unique in the ledger, so one event posts at most once.
     */
    public record SubledgerEntry(String sourceType, String sourceReference, LocalDate accountingDate, String description,
            List<LineData> lines, UUID preparedById, String preparedBy, boolean budgetReservedBySource) {
    }

    private final JournalVoucherRepository vouchers;
    private final FiscalCalendarService calendar;
    private final VoucherValidator validator;
    private final NumberingService numbering;
    private final LedgerBalanceService balances;
    private final DecisionLog decisions;
    private final IdempotencyService idempotency;
    private final VoucherQueryService queries;
    private final ObjectProvider<PostingBudgetGuard> budgetGuard;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public PostingService(JournalVoucherRepository vouchers, FiscalCalendarService calendar, VoucherValidator validator,
            NumberingService numbering, LedgerBalanceService balances, DecisionLog decisions, IdempotencyService idempotency,
            VoucherQueryService queries, ObjectProvider<PostingBudgetGuard> budgetGuard, AuditService audit,
            JdbcTemplate jdbc, Clock clock) {
        this.vouchers = vouchers;
        this.calendar = calendar;
        this.validator = validator;
        this.numbering = numbering;
        this.balances = balances;
        this.decisions = decisions;
        this.idempotency = idempotency;
        this.queries = queries;
        this.budgetGuard = budgetGuard;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** @return the posted voucher detail, or the stored response when this is a retry of the same request */
    @Transactional
    public Object post(UUID voucherId, long version, String idempotencyKey) {
        CurrentActor.requirePermission("JOURNAL_POST");
        String key = IdempotencyService.requireKey(idempotencyKey);
        UUID actorId = CurrentActor.userId();
        String requestHash = IdempotencyService.hash(OPERATION, voucherId, version);

        JournalVoucher v = vouchers.findByIdForUpdate(voucherId).orElseThrow(() -> ApiException.notFound("Journal voucher"));
        Optional<JsonNode> replay = idempotency.find(actorId, OPERATION, key, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (v.getVersion() != version) {
            throw ApiException.staleVersion();
        }
        if (v.getVoucherType() == VoucherType.SUBLEDGER) {
            throw JournalVoucherService.state("Sub-ledger vouchers are posted from their source document.");
        }
        if (v.getStatus() != VoucherStatus.APPROVED) {
            throw JournalVoucherService.state(v.getStatus() == VoucherStatus.POSTED
                    ? "This voucher has already been posted as " + v.getVoucherNumber() + "."
                    : "Only approved vouchers can be posted.");
        }
        if (actorId.equals(v.getPreparedById())) {
            throw JournalVoucherService.sod("The preparer of a voucher cannot post it.");
        }
        postCore(v, false);
        Object response = queries.detail(v.getId());
        idempotency.record(actorId, OPERATION, key, requestHash, 200, response);
        return response;
    }

    /**
     * Posts a sub-ledger event inside the caller's transaction. The caller has already authorised the
     * document through its own workflow; the poster must hold JOURNAL_POST and not be the preparer.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PostedEntry postSubledger(SubledgerEntry e) {
        if (!e.sourceType().startsWith("SL_")) {
            throw new IllegalArgumentException("Sub-ledger source types must start with SL_");
        }
        CurrentActor.requirePermission("JOURNAL_POST");
        if (CurrentActor.userId().equals(e.preparedById())) {
            throw JournalVoucherService.sod("The preparer of a document cannot post it to the ledger.");
        }
        AccountingPeriod period = calendar.resolve(e.accountingDate(), false);
        Instant now = Instant.now(clock);
        JournalVoucher v = new JournalVoucher(UUID.randomUUID(), VoucherType.SUBLEDGER, e.accountingDate(), period.getId(),
                e.description(), e.sourceReference(), e.sourceType(), e.sourceReference(), e.preparedById(), e.preparedBy(),
                null, null, now);
        v.addLines(e.lines());
        v.moveTo(VoucherStatus.APPROVED, null, now, CurrentActor.username());
        vouchers.saveAndFlush(v);
        return postCore(v, e.budgetReservedBySource());
    }

    private PostedEntry postCore(JournalVoucher v, boolean budgetReservedBySource) {
        UUID actorId = CurrentActor.userId();
        jdbc.queryForObject("SELECT status FROM accounting_periods WHERE id = ? FOR SHARE", String.class, v.getPeriodId());
        AccountingPeriod period = calendar.period(v.getPeriodId());
        List<LineData> lines = v.lineData();
        validator.validate(VoucherValidator.Level.FULL, v.getId(), v.getVoucherType(), v.getAccountingDate(), period, lines);
        budgetGuard.ifAvailable(g -> g.onPosting(period.getFiscalYearId(), v.getSourceType(), v.getSourceReference(),
                lines.stream().map(l -> new PostingBudgetGuard.GuardLine(l.accountId(), l.costCenterId(), l.debit(),
                        l.credit())).toList(), budgetReservedBySource));

        int fiscalYear = period.getSequenceKey() / 100;
        String number = numbering.next(FiscalCalendarService.JEV_DOCUMENT, fiscalYear);
        UUID entryId = UUID.randomUUID();
        Instant now = Instant.now(clock);
        BigDecimal total = v.getTotalDebit();
        jdbc.update("""
                INSERT INTO journal_entries (id, entry_number, voucher_id, voucher_type, fiscal_year_id, period_id,
                    accounting_date, description, source_type, source_reference, currency_code, total_debit, total_credit,
                    reversal_of_entry_id, prepared_by, posted_by_id, posted_by, posted_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                entryId, number, v.getId(), v.getVoucherType().name(), period.getFiscalYearId(), period.getId(),
                Date.valueOf(v.getAccountingDate()), v.getDescription(), v.getSourceType(), v.getSourceReference(),
                v.getCurrencyCode(), total, v.getTotalCredit(), v.getReversalOfEntryId(), v.getPreparedBy(), actorId,
                CurrentActor.username(), Timestamp.from(now));
        SortedMap<UUID, Movement> movements = new TreeMap<>();
        int n = 1;
        for (LineData l : lines) {
            jdbc.update("""
                    INSERT INTO journal_entry_lines (id, entry_id, line_no, account_id, cost_center_id, subsidiary_account_id,
                        description, debit, credit, period_id, accounting_date) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    UUID.randomUUID(), entryId, n++, l.accountId(), l.costCenterId(), l.subsidiaryAccountId(), l.description(),
                    l.debit(), l.credit(), period.getId(), Date.valueOf(v.getAccountingDate()));
            movements.merge(l.accountId(), new Movement(l.debit(), l.credit()),
                    (a, b) -> new Movement(a.debit().add(b.debit()), a.credit().add(b.credit())));
        }
        balances.apply(period.getId(), movements);
        v.markPosted(number, entryId, now, CurrentActor.username());
        vouchers.flush();
        decisions.record(v.getId(), "POST", null, "POST", actorId, CurrentActor.username(), null);
        audit.record("JOURNAL_VOUCHER_POSTED", "JOURNAL_VOUCHER", v.getId(), Map.of("entryNumber", number,
                "entryId", entryId.toString(), "total", total.toPlainString(), "period", period.getName(),
                "source", v.getSourceType() + ":" + String.valueOf(v.getSourceReference()),
                "reversalOf", String.valueOf(v.getReversalOfEntryId())));
        return new PostedEntry(entryId, number, v.getId());
    }
}
