package com.miaa.cas.treasury;

import com.miaa.cas.approvals.DocumentWorkflow;
import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.money.Money;
import com.miaa.cas.common.web.JdbcPaging;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.security.CurrentActor;
import com.miaa.cas.treasury.TreasuryDtos.AutoMatchResult;
import com.miaa.cas.treasury.TreasuryDtos.LedgerItem;
import com.miaa.cas.treasury.TreasuryDtos.MatchView;
import com.miaa.cas.treasury.TreasuryDtos.ReconciliationFigures;
import com.miaa.cas.treasury.TreasuryDtos.ReconciliationView;
import com.miaa.cas.treasury.TreasuryDtos.StatementLineView;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bank reconciliation of one imported statement against the bank's cash-in-bank ledger account.
 * Statement lines are matched to ledger lines (automatically by reference or by unique amount and
 * date, or manually in groups of equal total). The reconciliation shows
 * bank balance + deposits in transit - outstanding checks = book balance + unrecorded bank credits -
 * unrecorded bank debits; it can be submitted only when the difference is zero, and is approved by a
 * second person. Bank charges and interest are booked with an ordinary journal voucher, then matched.
 */
@Service
public class BankReconciliationService {

    public static final String DOCUMENT = "BANK_RECONCILIATION";

    private static final String LEDGER_SQL = """
            SELECT l.id, e.entry_number, l.accounting_date, coalesce(l.description, e.description) AS descr,
                   l.debit - l.credit AS amount,
                   CASE e.source_type
                     WHEN 'SL_DV_PAYMENT' THEN coalesce(
                         (SELECT c.check_number FROM check_register c WHERE c.dv_id::text = e.source_reference
                            AND c.status IN ('RELEASED', 'CLEARED') ORDER BY c.created_at DESC LIMIT 1),
                         (SELECT v.payment_reference FROM disbursement_vouchers v WHERE v.id::text = e.source_reference))
                     WHEN 'SL_TR_DEPOSIT' THEN
                         (SELECT coalesce(d.bank_reference, d.deposit_number) FROM deposits d WHERE d.id::text = e.source_reference)
                     ELSE NULL END AS ref
            FROM journal_entry_lines l JOIN journal_entries e ON e.id = l.entry_id""";

    private final StatementImportService statements;
    private final DocumentWorkflow workflow;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final int windowDays;

    public BankReconciliationService(StatementImportService statements, DocumentWorkflow workflow, AuditService audit,
            JdbcTemplate jdbc, Clock clock, @Value("${cas.treasury.match-window-days:3}") int windowDays) {
        this.statements = statements;
        this.workflow = workflow;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
        this.windowDays = windowDays;
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public PageResponse<Map<String, Object>> list(UUID bankAccountId, int page, int size) {
        return JdbcPaging.query(jdbc, """
                SELECT r.id, b.code AS "bankAccountCode", s.period_start AS "periodStart", r.as_of_date AS "asOfDate", r.status,
                       r.difference, r.prepared_by AS "preparedBy", r.approved_by AS "approvedBy", r.updated_at AS "updatedAt"
                FROM bank_reconciliations r JOIN bank_accounts b ON b.id = r.bank_account_id JOIN bank_statements s ON s.id = r.statement_id
                WHERE (?::uuid IS NULL OR r.bank_account_id = ?::uuid) ORDER BY b.code, r.as_of_date DESC, r.id""", new ColumnMapRowMapper(),
                page, size, bankAccountId, bankAccountId);
    }

    @Transactional(readOnly = true)
    public ReconciliationView get(UUID id) {
        return view(row(id));
    }

    /** A reconciliation as a statement for reporting: figures and outstanding items, independent of who views it. */
    public record ReconciliationStatement(String bankAccountCode, String bankName, LocalDate periodStart, LocalDate asOfDate, String status,
            ReconciliationFigures figures, List<StatementLineView> unmatchedStatementLines, List<LedgerItem> unmatchedLedgerLines,
            String preparedBy, String approvedBy) {
    }

    @Transactional(readOnly = true)
    public ReconciliationStatement statement(UUID id) {
        Map<String, Object> r = row(id);
        UUID bank = (UUID) r.get("bank_account_id");
        LocalDate asOf = DepositService.date(r.get("as_of_date"));
        Map<String, Object> b = jdbc.queryForMap("SELECT code, bank_name FROM bank_accounts WHERE id = ?", bank);
        LocalDate start = DepositService.date(jdbc.queryForObject("SELECT period_start FROM bank_statements WHERE id = ?", Object.class,
                r.get("statement_id")));
        return new ReconciliationStatement((String) b.get("code"), (String) b.get("bank_name"), start, asOf, (String) r.get("status"),
                figures(r), unmatchedStatementLines(bank, asOf), unmatchedLedger(glAccount(bank), asOf), (String) r.get("prepared_by"),
                (String) r.get("approved_by"));
    }

    // ------------------------------------------------------------------ commands

    @Transactional
    public ReconciliationView start(UUID statementId) {
        CurrentActor.requirePermission("BANK_RECONCILIATION_PREPARE");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM bank_statements WHERE id = ?", statementId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Bank statement");
        }
        Map<String, Object> s = rows.get(0);
        UUID bank = (UUID) s.get("bank_account_id");
        lockBank(bank);
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM bank_reconciliations WHERE statement_id = ?)",
                Boolean.class, statementId))) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "This statement already has a reconciliation.");
        }
        List<String> pending = jdbc.queryForList("""
                SELECT s.period_start || ' to ' || s.period_end FROM bank_statements s
                LEFT JOIN bank_reconciliations r ON r.statement_id = s.id
                WHERE s.bank_account_id = ? AND s.period_end < ? AND (r.id IS NULL OR r.status <> 'APPROVED')
                ORDER BY s.period_end LIMIT 1""", String.class, bank, s.get("period_start"));
        if (!pending.isEmpty()) {
            throw rule("Reconcile the earlier statement " + pending.get(0) + " first; statements are reconciled in order.");
        }
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now(clock));
        jdbc.update("""
                INSERT INTO bank_reconciliations (id, bank_account_id, statement_id, as_of_date, status, prepared_by_id, prepared_by,
                    created_at, updated_at, updated_by) VALUES (?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?)""", id, bank, statementId,
                s.get("period_end"), CurrentActor.userId(), CurrentActor.username(), now, now, CurrentActor.username());
        audit.record("BANK_RECONCILIATION_STARTED", DOCUMENT, id, Map.of("statement", statementId.toString()));
        return get(id);
    }

    /** Matches by reference first (check, ADA or deposit reference), then by unique amount within the date window. */
    @Transactional
    public AutoMatchResult autoMatch(UUID id, long version) {
        Map<String, Object> r = editable(id, version);
        UUID bank = (UUID) r.get("bank_account_id");
        lockBank(bank);
        LocalDate asOf = DepositService.date(r.get("as_of_date"));
        List<StatementLineView> stmt = unmatchedStatementLines(bank, asOf);
        List<LedgerItem> ledger = unmatchedLedger(glAccount(bank), asOf);
        Set<UUID> usedStmt = new HashSet<>();
        Set<UUID> usedLedger = new HashSet<>();
        int matched = 0;
        // Rule 1: the ledger item's reference appears on exactly one statement line of the same amount, and vice versa.
        for (LedgerItem l : ledger) {
            String ref = norm(l.reference());
            if (ref.length() < 3) {
                continue;
            }
            List<StatementLineView> cands = stmt.stream().filter(s -> !usedStmt.contains(s.id()) && s.amount().compareTo(l.amount()) == 0
                    && !s.valueDate().isBefore(l.accountingDate().minusDays(windowDays)) && mentions(s, ref)).toList();
            if (cands.size() != 1) {
                continue;
            }
            StatementLineView s = cands.get(0);
            long rivals = ledger.stream().filter(o -> !usedLedger.contains(o.journalLineId()) && o.amount().compareTo(s.amount()) == 0
                    && norm(o.reference()).length() >= 3 && mentions(s, norm(o.reference()))).count();
            if (rivals != 1) {
                continue;
            }
            createMatch(id, bank, "AUTO", "REFERENCE", List.of(s.id()), List.of(l));
            usedStmt.add(s.id());
            usedLedger.add(l.journalLineId());
            matched++;
        }
        // Rule 2: one statement line and one ledger item with the same amount within the window, and nothing else competing.
        for (StatementLineView s : stmt) {
            if (usedStmt.contains(s.id())) {
                continue;
            }
            List<LedgerItem> cands = ledger.stream().filter(l -> !usedLedger.contains(l.journalLineId())
                    && l.amount().compareTo(s.amount()) == 0 && within(l.accountingDate(), s.valueDate())).toList();
            if (cands.size() != 1) {
                continue;
            }
            LedgerItem l = cands.get(0);
            long rivals = stmt.stream().filter(o -> !usedStmt.contains(o.id()) && o.amount().compareTo(l.amount()) == 0
                    && within(l.accountingDate(), o.valueDate())).count();
            if (rivals != 1) {
                continue;
            }
            createMatch(id, bank, "AUTO", "AMOUNT_DATE", List.of(s.id()), List.of(l));
            usedStmt.add(s.id());
            usedLedger.add(l.journalLineId());
            matched++;
        }
        touch(id);
        audit.record("BANK_RECONCILIATION_AUTO_MATCHED", DOCUMENT, id, Map.of("matched", matched));
        return new AutoMatchResult(matched, get(id));
    }

    @Transactional
    public ReconciliationView match(UUID id, long version, List<UUID> statementLineIds, List<UUID> journalLineIds) {
        Map<String, Object> r = editable(id, version);
        UUID bank = (UUID) r.get("bank_account_id");
        lockBank(bank);
        LocalDate asOf = DepositService.date(r.get("as_of_date"));
        Set<UUID> sIds = new HashSet<>(statementLineIds);
        Set<UUID> lIds = new HashSet<>(journalLineIds);
        List<StatementLineView> s = unmatchedStatementLines(bank, asOf).stream().filter(x -> sIds.contains(x.id())).toList();
        List<LedgerItem> l = unmatchedLedger(glAccount(bank), asOf).stream().filter(x -> lIds.contains(x.journalLineId())).toList();
        if (s.size() != sIds.size() || l.size() != lIds.size()) {
            throw rule("Every selected line must be unmatched, belong to this bank account and be dated on or before " + asOf + ".");
        }
        BigDecimal st = s.stream().map(StatementLineView::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal lt = l.stream().map(LedgerItem::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (st.compareTo(lt) != 0) {
            throw rule("The bank lines total " + Money.of(st).toPlainString() + " but the ledger lines total "
                    + Money.of(lt).toPlainString() + "; a match must balance.");
        }
        UUID matchId = createMatch(id, bank, "MANUAL", s.size() == 1 && l.size() == 1 ? "ONE_TO_ONE" : "GROUP",
                s.stream().map(StatementLineView::id).toList(), l);
        touch(id);
        audit.record("BANK_LINES_MATCHED", DOCUMENT, id, Map.of("match", matchId.toString(), "amount", Money.of(st).toPlainString(),
                "statementLines", s.size(), "ledgerLines", l.size()));
        return get(id);
    }

    @Transactional
    public ReconciliationView unmatch(UUID id, long version, UUID matchId) {
        Map<String, Object> r = editable(id, version);
        lockBank((UUID) r.get("bank_account_id"));
        if (locked(matchId)) {
            throw rule("This match belongs to a submitted or approved reconciliation and can no longer be undone.");
        }
        Integer own = jdbc.queryForObject("SELECT count(*) FROM bank_matches WHERE id = ? AND reconciliation_id = ?", Integer.class,
                matchId, id);
        if (own == null || own == 0) {
            throw ApiException.notFound("Match");
        }
        jdbc.update("UPDATE bank_statement_lines SET match_id = NULL WHERE match_id = ?", matchId);
        jdbc.update("DELETE FROM bank_match_ledger_lines WHERE match_id = ?", matchId);
        jdbc.update("DELETE FROM bank_matches WHERE id = ?", matchId);
        touch(id);
        audit.record("BANK_LINES_UNMATCHED", DOCUMENT, id, Map.of("match", matchId.toString()));
        return get(id);
    }

    @Transactional
    public ReconciliationView submit(UUID id, long version, String comment) {
        Map<String, Object> r = editable(id, version);
        lockBank((UUID) r.get("bank_account_id"));
        ReconciliationFigures f = figures(r);
        if (f.difference().signum() != 0) {
            throw rule("The reconciliation has an unexplained difference of " + f.difference().toPlainString()
                    + "; match or book the remaining items first.");
        }
        DocumentWorkflow.Step first = workflow.submit(DOCUMENT, id, f.bankBalance().abs(), comment);
        jdbc.update("""
                UPDATE bank_reconciliations SET status = ?, current_step_order = ?, bank_balance = ?, deposits_in_transit = ?,
                    outstanding_checks = ?, book_balance = ?, unrecorded_credits = ?, unrecorded_debits = ?, difference = ?,
                    version = version + 1, updated_at = ?, updated_by = ? WHERE id = ?""",
                first == null ? "APPROVED" : "SUBMITTED", first == null ? null : (short) first.order(), f.bankBalance(),
                f.depositsInTransit(), f.outstandingChecks(), f.bookBalance(), f.unrecordedCredits(), f.unrecordedDebits(),
                f.difference(), Timestamp.from(Instant.now(clock)), CurrentActor.username(), id);
        audit.record("BANK_RECONCILIATION_SUBMITTED", DOCUMENT, id, Map.of("bankBalance", f.bankBalance().toPlainString(),
                "bookBalance", f.bookBalance().toPlainString()));
        if (first == null) {
            clearChecks(id);
        }
        return get(id);
    }

    @Transactional
    public ReconciliationView approve(UUID id, long version, String comment) {
        Map<String, Object> r = lockVersioned(id, version);
        if (!"SUBMITTED".equals(r.get("status"))) {
            throw new ApiException(ErrorCode.INVALID_STATE, "The reconciliation is not awaiting approval.");
        }
        BigDecimal total = ((BigDecimal) r.get("bank_balance")).abs();
        DocumentWorkflow.Step next = workflow.approve(DOCUMENT, id, total, toInt(r.get("current_step_order")),
                (UUID) r.get("prepared_by_id"), comment);
        Timestamp now = Timestamp.from(Instant.now(clock));
        if (next == null) {
            jdbc.update("UPDATE bank_reconciliations SET status = 'APPROVED', current_step_order = NULL, approved_by = ?, approved_at = ?, "
                    + "version = version + 1, updated_at = ?, updated_by = ? WHERE id = ?", CurrentActor.username(), now, now,
                    CurrentActor.username(), id);
            clearChecks(id);
        } else {
            jdbc.update("UPDATE bank_reconciliations SET current_step_order = ?, version = version + 1, updated_at = ?, updated_by = ? "
                    + "WHERE id = ?", (short) next.order(), now, CurrentActor.username(), id);
        }
        audit.record("BANK_RECONCILIATION_APPROVED", DOCUMENT, id, Map.of("final", next == null));
        return get(id);
    }

    @Transactional
    public ReconciliationView returnToPreparer(UUID id, long version, String comment) {
        Map<String, Object> r = lockVersioned(id, version);
        if (!"SUBMITTED".equals(r.get("status"))) {
            throw new ApiException(ErrorCode.INVALID_STATE, "The reconciliation is not awaiting approval.");
        }
        workflow.returnOrReject(DOCUMENT, id, ((BigDecimal) r.get("bank_balance")).abs(), toInt(r.get("current_step_order")),
                (UUID) r.get("prepared_by_id"), comment, false);
        jdbc.update("UPDATE bank_reconciliations SET status = 'RETURNED', current_step_order = NULL, version = version + 1, "
                + "updated_at = ?, updated_by = ? WHERE id = ?", Timestamp.from(Instant.now(clock)), CurrentActor.username(), id);
        audit.record("BANK_RECONCILIATION_RETURNED", DOCUMENT, id, Map.of("comment", comment.trim()));
        return get(id);
    }

    // ------------------------------------------------------------------ helpers

    /** Checks paid out through DVs and matched in this reconciliation are marked CLEARED in the register. */
    private void clearChecks(UUID reconciliationId) {
        int n = jdbc.update("""
                UPDATE check_register c SET status = 'CLEARED', updated_at = ?, updated_by = ?
                WHERE c.status = 'RELEASED' AND EXISTS (
                    SELECT 1 FROM bank_matches m JOIN bank_match_ledger_lines ml ON ml.match_id = m.id
                    JOIN journal_entry_lines jl ON jl.id = ml.journal_line_id JOIN journal_entries e ON e.id = jl.entry_id
                    WHERE m.reconciliation_id = ? AND e.source_type = 'SL_DV_PAYMENT' AND e.source_reference = c.dv_id::text)""",
                Timestamp.from(Instant.now(clock)), CurrentActor.username(), reconciliationId);
        if (n > 0) {
            audit.record("CHECKS_CLEARED", DOCUMENT, reconciliationId, Map.of("count", n));
        }
    }

    private UUID createMatch(UUID reconciliationId, UUID bank, String method, String rule, List<UUID> statementLineIds,
            List<LedgerItem> ledger) {
        UUID matchId = UUID.randomUUID();
        jdbc.update("INSERT INTO bank_matches (id, bank_account_id, reconciliation_id, method, rule, created_at, created_by) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)", matchId, bank, reconciliationId, method, rule, Timestamp.from(Instant.now(clock)),
                CurrentActor.username());
        for (UUID s : statementLineIds) {
            if (jdbc.update("UPDATE bank_statement_lines SET match_id = ? WHERE id = ? AND match_id IS NULL", matchId, s) != 1) {
                throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION, "A statement line was matched by someone else; refresh.");
            }
        }
        for (LedgerItem l : ledger) {
            jdbc.update("INSERT INTO bank_match_ledger_lines (match_id, journal_line_id, amount) VALUES (?, ?, ?)", matchId,
                    l.journalLineId(), l.amount());
        }
        return matchId;
    }

    ReconciliationFigures figures(Map<String, Object> r) {
        String status = (String) r.get("status");
        if (("SUBMITTED".equals(status) || "APPROVED".equals(status)) && r.get("bank_balance") != null) {
            BigDecimal bank = (BigDecimal) r.get("bank_balance");
            BigDecimal dit = (BigDecimal) r.get("deposits_in_transit");
            BigDecimal oc = (BigDecimal) r.get("outstanding_checks");
            BigDecimal book = (BigDecimal) r.get("book_balance");
            BigDecimal uc = (BigDecimal) r.get("unrecorded_credits");
            BigDecimal ud = (BigDecimal) r.get("unrecorded_debits");
            return new ReconciliationFigures(bank, dit, oc, Money.of(bank.add(dit).subtract(oc)), book, uc, ud,
                    Money.of(book.add(uc).subtract(ud)), (BigDecimal) r.get("difference"));
        }
        UUID bankId = (UUID) r.get("bank_account_id");
        LocalDate asOf = DepositService.date(r.get("as_of_date"));
        BigDecimal bank = jdbc.queryForObject("SELECT closing_balance FROM bank_statements WHERE id = ?", BigDecimal.class,
                r.get("statement_id"));
        UUID gl = glAccount(bankId);
        BigDecimal book = Money.of(jdbc.queryForObject("SELECT coalesce(sum(debit - credit), 0) FROM journal_entry_lines "
                + "WHERE account_id = ? AND accounting_date <= ?", BigDecimal.class, gl, Date.valueOf(asOf)));
        BigDecimal dit = BigDecimal.ZERO;
        BigDecimal oc = BigDecimal.ZERO;
        for (LedgerItem l : unmatchedLedger(gl, asOf)) {
            if (l.amount().signum() > 0) {
                dit = dit.add(l.amount());
            } else {
                oc = oc.add(l.amount().negate());
            }
        }
        BigDecimal uc = BigDecimal.ZERO;
        BigDecimal ud = BigDecimal.ZERO;
        for (StatementLineView s : unmatchedStatementLines(bankId, asOf)) {
            if (s.amount().signum() > 0) {
                uc = uc.add(s.amount());
            } else {
                ud = ud.add(s.amount().negate());
            }
        }
        BigDecimal adjBank = Money.of(bank.add(dit).subtract(oc));
        BigDecimal adjBook = Money.of(book.add(uc).subtract(ud));
        return new ReconciliationFigures(Money.of(bank), Money.of(dit), Money.of(oc), adjBank, book, Money.of(uc), Money.of(ud),
                adjBook, Money.of(adjBank.subtract(adjBook)));
    }

    List<StatementLineView> unmatchedStatementLines(UUID bank, LocalDate asOf) {
        return statements.lines("s.bank_account_id = ? AND l.match_id IS NULL AND l.value_date <= ?", bank, Date.valueOf(asOf));
    }

    List<LedgerItem> unmatchedLedger(UUID glAccount, LocalDate asOf) {
        return jdbc.query(LEDGER_SQL + """
                 WHERE l.account_id = ? AND l.accounting_date <= ?
                  AND NOT EXISTS (SELECT 1 FROM bank_match_ledger_lines m WHERE m.journal_line_id = l.id)
                ORDER BY l.accounting_date, e.entry_number, l.line_no""", (rs, i) -> ledgerItem(rs), glAccount, Date.valueOf(asOf));
    }

    private static LedgerItem ledgerItem(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new LedgerItem(rs.getObject("id", UUID.class), rs.getString("entry_number"), rs.getDate("accounting_date").toLocalDate(),
                rs.getString("descr"), rs.getString("ref"), Money.of(rs.getBigDecimal("amount")));
    }

    private boolean locked(UUID matchId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM bank_matches m JOIN bank_reconciliations r ON r.id = m.reconciliation_id
                               WHERE m.id = ? AND r.status IN ('SUBMITTED', 'APPROVED'))""", Boolean.class, matchId));
    }

    /** Matches touching this statement's lines or made in this reconciliation. */
    private List<MatchView> matches(UUID reconciliationId, UUID statementId) {
        List<UUID> ids = jdbc.queryForList("SELECT id FROM bank_matches WHERE reconciliation_id = ? UNION SELECT DISTINCT match_id "
                + "FROM bank_statement_lines WHERE statement_id = ? AND match_id IS NOT NULL", UUID.class, reconciliationId, statementId);
        List<MatchView> out = new ArrayList<>();
        for (UUID m : ids) {
            Map<String, Object> row = jdbc.queryForMap("SELECT * FROM bank_matches WHERE id = ?", m);
            List<LedgerItem> ledger = jdbc.query(LEDGER_SQL + " WHERE l.id IN (SELECT journal_line_id FROM bank_match_ledger_lines "
                    + "WHERE match_id = ?) ORDER BY l.accounting_date", (rs, i) -> ledgerItem(rs), m);
            out.add(new MatchView(m, (String) row.get("method"), (String) row.get("rule"), (String) row.get("created_by"),
                    ((Timestamp) row.get("created_at")).toInstant(), statements.lines("l.match_id = ?", m), ledger, locked(m)));
        }
        out.sort((a, b) -> a.statementLines().get(0).valueDate().compareTo(b.statementLines().get(0).valueDate()));
        return out;
    }

    private ReconciliationView view(Map<String, Object> r) {
        UUID id = (UUID) r.get("id");
        UUID bank = (UUID) r.get("bank_account_id");
        UUID statementId = (UUID) r.get("statement_id");
        LocalDate asOf = DepositService.date(r.get("as_of_date"));
        Map<String, Object> s = jdbc.queryForMap("SELECT period_start, opening_balance FROM bank_statements WHERE id = ?", statementId);
        LocalDate start = DepositService.date(s.get("period_start"));
        String status = (String) r.get("status");
        ReconciliationFigures f = figures(r);
        List<String> warnings = new ArrayList<>();
        boolean first = Boolean.FALSE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM bank_statements WHERE bank_account_id = ? "
                + "AND period_end < ?)", Boolean.class, bank, Date.valueOf(start)));
        if (first) {
            BigDecimal glAtStart = Money.of(jdbc.queryForObject("SELECT coalesce(sum(debit - credit), 0) FROM journal_entry_lines "
                    + "WHERE account_id = ? AND accounting_date < ?", BigDecimal.class, glAccount(bank), Date.valueOf(start)));
            BigDecimal opening = (BigDecimal) s.get("opening_balance");
            if (glAtStart.compareTo(opening) != 0) {
                warnings.add("First statement for this account: the ledger balance before " + start + " (" + glAtStart.toPlainString()
                        + ") differs from the bank's opening balance (" + opening.toPlainString() + "). Items outstanding at go-live "
                        + "must be identified (Phase 5 migration) or matched.");
            }
        }
        boolean preparer = CurrentActor.userId().equals(r.get("prepared_by_id"));
        boolean editable = "DRAFT".equals(status) || "RETURNED".equals(status);
        List<String> actions = new ArrayList<>();
        if (editable && preparer && CurrentActor.hasPermission("BANK_RECONCILIATION_PREPARE")) {
            actions.addAll(List.of("AUTO_MATCH", "MATCH", "UNMATCH"));
            if (f.difference().signum() == 0) {
                actions.add("SUBMIT");
            }
        }
        BigDecimal total = f.bankBalance().abs();
        if ("SUBMITTED".equals(status) && workflow.canAct(DOCUMENT, id, total, toInt(r.get("current_step_order")),
                (UUID) r.get("prepared_by_id"))) {
            actions.addAll(List.of("APPROVE", "RETURN"));
        }
        String bankCode = jdbc.queryForObject("SELECT code FROM bank_accounts WHERE id = ?", String.class, bank);
        Timestamp approvedAt = (Timestamp) r.get("approved_at");
        return new ReconciliationView(id, bank, bankCode, statementId, start, asOf, status,
                workflow.currentStepCode(DOCUMENT, total, toInt(r.get("current_step_order"))), f,
                unmatchedStatementLines(bank, asOf), unmatchedLedger(glAccount(bank), asOf), matches(id, statementId), warnings,
                (String) r.get("prepared_by"), (String) r.get("approved_by"), approvedAt == null ? null : approvedAt.toInstant(),
                workflow.history(DOCUMENT, id), actions, ((Number) r.get("version")).longValue());
    }

    private Map<String, Object> row(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM bank_reconciliations WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Bank reconciliation");
        }
        return rows.get(0);
    }

    private Map<String, Object> lockVersioned(UUID id, long version) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM bank_reconciliations WHERE id = ? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Bank reconciliation");
        }
        if (((Number) rows.get(0).get("version")).longValue() != version) {
            throw ApiException.staleVersion();
        }
        return rows.get(0);
    }

    private Map<String, Object> editable(UUID id, long version) {
        CurrentActor.requirePermission("BANK_RECONCILIATION_PREPARE");
        Map<String, Object> r = lockVersioned(id, version);
        if (!CurrentActor.userId().equals(r.get("prepared_by_id"))) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Only the preparer can change this reconciliation.");
        }
        String status = (String) r.get("status");
        if (!"DRAFT".equals(status) && !"RETURNED".equals(status)) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Only draft or returned reconciliations can be changed.");
        }
        return r;
    }

    private void touch(UUID id) {
        jdbc.update("UPDATE bank_reconciliations SET version = version + 1, updated_at = ?, updated_by = ? WHERE id = ?",
                Timestamp.from(Instant.now(clock)), CurrentActor.username(), id);
    }

    private void lockBank(UUID bank) {
        jdbc.queryForObject("SELECT id FROM bank_accounts WHERE id = ? FOR UPDATE", UUID.class, bank);
    }

    private UUID glAccount(UUID bank) {
        return jdbc.queryForObject("SELECT gl_account_id FROM bank_accounts WHERE id = ?", UUID.class, bank);
    }

    private boolean within(LocalDate a, LocalDate b) {
        return Math.abs(ChronoUnit.DAYS.between(a, b)) <= windowDays;
    }

    private static boolean mentions(StatementLineView s, String ref) {
        return norm(s.bankReference()).equals(ref) || norm(s.bankReference()).contains(ref) || norm(s.description()).contains(ref);
    }

    static String norm(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private static Integer toInt(Object o) {
        return o == null ? null : ((Number) o).intValue();
    }

    private static ApiException rule(String message) {
        return new ApiException(ErrorCode.ACCOUNTING_RULE_VIOLATION, message, Map.of("violations", List.of(message)));
    }
}
