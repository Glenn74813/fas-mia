package com.miaa.cas.generalledger;

import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.money.Money;
import com.miaa.cas.generalledger.domain.Account;
import com.miaa.cas.generalledger.domain.AccountingPeriod;
import com.miaa.cas.generalledger.domain.FiscalYear;
import com.miaa.cas.generalledger.domain.FiscalYearRepository;
import com.miaa.cas.generalledger.domain.NormalBalance;
import com.miaa.cas.generalledger.web.LedgerDtos.AccountLedger;
import com.miaa.cas.generalledger.web.LedgerDtos.EntryDetail;
import com.miaa.cas.generalledger.web.LedgerDtos.EntryLine;
import com.miaa.cas.generalledger.web.LedgerDtos.LedgerLine;
import com.miaa.cas.generalledger.web.LedgerDtos.SubsidiaryReconciliationRow;
import com.miaa.cas.generalledger.web.LedgerDtos.TrialBalance;
import com.miaa.cas.generalledger.web.LedgerDtos.TrialBalanceRow;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only ledger inquiries computed from posted journal lines (the source of truth). Real accounts
 * accumulate across years; revenue and expense accounts accumulate within the fiscal year only.
 */
@Service
public class LedgerInquiryService {

    private final JdbcTemplate jdbc;
    private final AccountService accounts;
    private final FiscalCalendarService calendar;
    private final FiscalYearRepository years;

    public LedgerInquiryService(JdbcTemplate jdbc, AccountService accounts, FiscalCalendarService calendar,
            FiscalYearRepository years) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.calendar = calendar;
        this.years = years;
    }

    @Transactional(readOnly = true)
    public TrialBalance trialBalance(UUID periodId) {
        AccountingPeriod p = calendar.period(periodId);
        int key = p.getSequenceKey();
        int fyStart = (key / 100) * 100;
        List<TrialBalanceRow> rows = jdbc.query("""
                WITH m AS (
                    SELECT l.account_id,
                        sum(CASE WHEN ap.sequence_key < ? AND (a.account_type IN ('ASSET','LIABILITY','EQUITY') OR ap.sequence_key > ?)
                                 THEN l.debit - l.credit ELSE 0 END) AS opening_net,
                        sum(CASE WHEN ap.sequence_key = ? THEN l.debit ELSE 0 END) AS period_debit,
                        sum(CASE WHEN ap.sequence_key = ? THEN l.credit ELSE 0 END) AS period_credit
                    FROM journal_entry_lines l
                    JOIN accounting_periods ap ON ap.id = l.period_id
                    JOIN chart_of_accounts a ON a.id = l.account_id
                    WHERE ap.sequence_key <= ?
                    GROUP BY l.account_id)
                SELECT a.id, a.code, a.name, a.account_type, a.normal_balance, a.placeholder,
                       m.opening_net, m.period_debit, m.period_credit
                FROM m JOIN chart_of_accounts a ON a.id = m.account_id
                WHERE m.opening_net <> 0 OR m.period_debit <> 0 OR m.period_credit <> 0
                ORDER BY a.code""", (rs, i) -> {
                    BigDecimal opening = rs.getBigDecimal("opening_net");
                    BigDecimal pd = rs.getBigDecimal("period_debit");
                    BigDecimal pc = rs.getBigDecimal("period_credit");
                    BigDecimal closing = opening.add(pd).subtract(pc);
                    return new TrialBalanceRow(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                            rs.getString("account_type"), rs.getString("normal_balance"), rs.getBoolean("placeholder"),
                            debitSide(opening), creditSide(opening), Money.of(pd), Money.of(pc), debitSide(closing),
                            creditSide(closing));
                }, key, fyStart, key, key, key);
        // Revenue/expense results of earlier years that were never closed to equity would otherwise vanish
        // from the opening balances; show them explicitly so the trial balance stays complete and balanced.
        BigDecimal unclosed = jdbc.queryForObject("""
                SELECT coalesce(sum(l.debit - l.credit), 0) FROM journal_entry_lines l
                JOIN accounting_periods ap ON ap.id = l.period_id JOIN chart_of_accounts a ON a.id = l.account_id
                WHERE ap.sequence_key < ? AND a.account_type IN ('REVENUE', 'EXPENSE')""", BigDecimal.class, fyStart);
        if (unclosed != null && unclosed.signum() != 0) {
            rows = new ArrayList<>(rows);
            rows.add(new TrialBalanceRow(null, "UNCLOSED", "Unclosed result of prior fiscal years (year-end closing not posted)",
                    "EQUITY", "CREDIT", false, debitSide(unclosed), creditSide(unclosed), Money.ZERO, Money.ZERO,
                    debitSide(unclosed), creditSide(unclosed)));
        }
        BigDecimal od = sum(rows, TrialBalanceRow::openingDebit);
        BigDecimal oc = sum(rows, TrialBalanceRow::openingCredit);
        BigDecimal pd = sum(rows, TrialBalanceRow::periodDebit);
        BigDecimal pc = sum(rows, TrialBalanceRow::periodCredit);
        BigDecimal cd = sum(rows, TrialBalanceRow::closingDebit);
        BigDecimal cc = sum(rows, TrialBalanceRow::closingCredit);
        boolean balanced = od.compareTo(oc) == 0 && pd.compareTo(pc) == 0 && cd.compareTo(cc) == 0;
        return new TrialBalance(calendar.toResponse(p), rows, od, oc, pd, pc, cd, cc, balanced,
                rows.stream().anyMatch(TrialBalanceRow::placeholder), "POSTED_JOURNAL_LINES");
    }

    @Transactional(readOnly = true)
    public AccountLedger accountLedger(UUID accountId, LocalDate from, LocalDate to, UUID subsidiaryAccountId, int page, int size) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Provide a valid from/to date range.");
        }
        if (page < 0 || size < 1 || size > 500) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "page must be >= 0 and size between 1 and 500");
        }
        Account account = accounts.load(accountId);
        boolean real = account.getAccountType().isBalanceSheet();
        LocalDate openingFrom = real ? LocalDate.of(1900, 1, 1)
                : years.findContaining(from).map(FiscalYear::getStartDate).orElse(from);
        String slFilter = subsidiaryAccountId == null ? "" : " AND l.subsidiary_account_id = ?";
        List<Object> openArgs = new ArrayList<>(List.of(accountId, Date.valueOf(openingFrom), Date.valueOf(from)));
        if (subsidiaryAccountId != null) {
            openArgs.add(subsidiaryAccountId);
        }
        BigDecimal openingNet = jdbc.queryForObject("SELECT coalesce(sum(l.debit - l.credit), 0) FROM journal_entry_lines l "
                + "WHERE l.account_id = ? AND l.accounting_date >= ? AND l.accounting_date < ?" + slFilter, BigDecimal.class,
                openArgs.toArray());
        List<Object> args = new ArrayList<>(List.of(accountId, Date.valueOf(from), Date.valueOf(to)));
        if (subsidiaryAccountId != null) {
            args.add(subsidiaryAccountId);
        }
        Map<String, Object> totals = jdbc.queryForMap("SELECT count(*) AS n, coalesce(sum(l.debit), 0) AS d, "
                + "coalesce(sum(l.credit), 0) AS c FROM journal_entry_lines l WHERE l.account_id = ? "
                + "AND l.accounting_date BETWEEN ? AND ?" + slFilter, args.toArray());
        int sign = account.getNormalBalance() == NormalBalance.DEBIT ? 1 : -1;
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<LedgerLine> lines = jdbc.query("""
                SELECT * FROM (
                    SELECT l.id, e.id AS entry_id, e.entry_number, e.voucher_id, l.accounting_date, p.name AS period_name,
                           coalesce(l.description, e.description) AS description, cc.code AS cc_code, sl.code AS sl_code,
                           l.debit, l.credit,
                           sum(l.debit - l.credit) OVER (ORDER BY l.accounting_date, e.entry_number, l.line_no
                               ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS cumulative
                    FROM journal_entry_lines l
                    JOIN journal_entries e ON e.id = l.entry_id
                    JOIN accounting_periods p ON p.id = l.period_id
                    LEFT JOIN cost_centers cc ON cc.id = l.cost_center_id
                    LEFT JOIN subsidiary_ledger_accounts sl ON sl.id = l.subsidiary_account_id
                    WHERE l.account_id = ? AND l.accounting_date BETWEEN ? AND ?""" + slFilter + """
                ) t ORDER BY accounting_date, entry_number, id LIMIT ? OFFSET ?""", (rs, i) -> new LedgerLine(
                        rs.getObject("id", UUID.class), rs.getObject("entry_id", UUID.class), rs.getString("entry_number"),
                        rs.getObject("voucher_id", UUID.class), rs.getDate("accounting_date").toLocalDate(),
                        rs.getString("period_name"), rs.getString("description"), rs.getString("cc_code"),
                        rs.getString("sl_code"), rs.getBigDecimal("debit"), rs.getBigDecimal("credit"),
                        Money.of(openingNet.add(rs.getBigDecimal("cumulative")).multiply(BigDecimal.valueOf(sign)))),
                pageArgs.toArray());
        BigDecimal d = (BigDecimal) totals.get("d");
        BigDecimal c = (BigDecimal) totals.get("c");
        BigDecimal signedOpening = Money.of(openingNet.multiply(BigDecimal.valueOf(sign)));
        BigDecimal signedClosing = Money.of(openingNet.add(d).subtract(c).multiply(BigDecimal.valueOf(sign)));
        return new AccountLedger(accounts.get(accountId), from, to, signedOpening, Money.of(d), Money.of(c), signedClosing,
                account.getNormalBalance().name(), lines, page, size, ((Number) totals.get("n")).longValue());
    }

    @Transactional(readOnly = true)
    public EntryDetail entry(UUID entryId) {
        List<EntryDetail> found = jdbc.query("""
                SELECT e.*, p.name AS period_name, r.entry_number AS reversal_of_number,
                       rb.id AS reversed_by_id, rb.entry_number AS reversed_by_number
                FROM journal_entries e JOIN accounting_periods p ON p.id = e.period_id
                LEFT JOIN journal_entries r ON r.id = e.reversal_of_entry_id
                LEFT JOIN journal_entries rb ON rb.reversal_of_entry_id = e.id
                WHERE e.id = ?""", (rs, i) -> new EntryDetail(rs.getObject("id", UUID.class), rs.getString("entry_number"),
                rs.getObject("voucher_id", UUID.class), rs.getString("voucher_type"),
                rs.getDate("accounting_date").toLocalDate(), rs.getString("period_name"), rs.getString("description"),
                rs.getString("source_type"), rs.getString("source_reference"), rs.getBigDecimal("total_debit"),
                rs.getBigDecimal("total_credit"), rs.getObject("reversal_of_entry_id", UUID.class),
                rs.getString("reversal_of_number"), rs.getObject("reversed_by_id", UUID.class),
                rs.getString("reversed_by_number"), rs.getString("prepared_by"), rs.getString("posted_by"),
                rs.getTimestamp("posted_at").toInstant(), entryLines(entryId)), entryId);
        if (found.isEmpty()) {
            throw ApiException.notFound("Journal entry");
        }
        return found.get(0);
    }

    @Transactional(readOnly = true)
    public List<SubsidiaryReconciliationRow> subsidiaryReconciliation(UUID periodId) {
        AccountingPeriod p = calendar.period(periodId);
        return jdbc.query("""
                SELECT a.id, a.code, a.name, a.subsidiary_type,
                       coalesce(sum(l.debit - l.credit), 0) AS gl,
                       coalesce(sum(CASE WHEN l.subsidiary_account_id IS NOT NULL THEN l.debit - l.credit ELSE 0 END), 0) AS sl
                FROM chart_of_accounts a
                LEFT JOIN journal_entry_lines l ON l.account_id = a.id
                     AND l.period_id IN (SELECT id FROM accounting_periods WHERE sequence_key <= ?)
                WHERE a.subsidiary_type IS NOT NULL
                GROUP BY a.id, a.code, a.name, a.subsidiary_type ORDER BY a.code""", (rs, i) -> {
                    BigDecimal gl = rs.getBigDecimal("gl");
                    BigDecimal sl = rs.getBigDecimal("sl");
                    BigDecimal unassigned = gl.subtract(sl);
                    return new SubsidiaryReconciliationRow(rs.getObject("id", UUID.class), rs.getString("code"),
                            rs.getString("name"), rs.getString("subsidiary_type"), Money.of(gl), Money.of(sl),
                            Money.of(unassigned), unassigned.signum() == 0);
                }, p.getSequenceKey());
    }

    private List<EntryLine> entryLines(UUID entryId) {
        return jdbc.query("""
                SELECT l.line_no, a.id AS account_id, a.code, a.name, cc.code AS cc_code, sl.code AS sl_code,
                       sl.name AS sl_name, l.description, l.debit, l.credit
                FROM journal_entry_lines l JOIN chart_of_accounts a ON a.id = l.account_id
                LEFT JOIN cost_centers cc ON cc.id = l.cost_center_id
                LEFT JOIN subsidiary_ledger_accounts sl ON sl.id = l.subsidiary_account_id
                WHERE l.entry_id = ? ORDER BY l.line_no""", (rs, i) -> new EntryLine(rs.getInt("line_no"),
                rs.getObject("account_id", UUID.class), rs.getString("code"), rs.getString("name"), rs.getString("cc_code"),
                rs.getString("sl_code"), rs.getString("sl_name"), rs.getString("description"), rs.getBigDecimal("debit"),
                rs.getBigDecimal("credit")), entryId);
    }

    private static BigDecimal debitSide(BigDecimal net) {
        return net.signum() > 0 ? Money.of(net) : Money.ZERO;
    }

    private static BigDecimal creditSide(BigDecimal net) {
        return net.signum() < 0 ? Money.of(net.negate()) : Money.ZERO;
    }

    private static BigDecimal sum(List<TrialBalanceRow> rows, java.util.function.Function<TrialBalanceRow, BigDecimal> f) {
        return Money.of(rows.stream().map(f).reduce(BigDecimal.ZERO, BigDecimal::add));
    }
}
