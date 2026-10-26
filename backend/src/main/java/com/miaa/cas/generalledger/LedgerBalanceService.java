package com.miaa.cas.generalledger;

import com.miaa.cas.audit.AuditService;
import com.miaa.cas.generalledger.web.LedgerDtos.BalanceDifference;
import com.miaa.cas.generalledger.web.LedgerDtos.RebuildResult;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintains {@code ledger_balances}, a derived per-account/per-period cache of posted totals. It is
 * never the source of truth: statements read posted lines, and {@link #differences} /
 * {@link #rebuild} reconcile the cache against them.
 */
@Service
public class LedgerBalanceService {

    public record Movement(BigDecimal debit, BigDecimal credit) {
    }

    private static final String DIFF_SQL = """
            WITH e AS (
                SELECT account_id, period_id, sum(debit) AS d, sum(credit) AS c
                FROM journal_entry_lines %s GROUP BY account_id, period_id),
            b AS (SELECT account_id, period_id, debit_total AS d, credit_total AS c FROM ledger_balances %s)
            SELECT coalesce(e.account_id, b.account_id) AS account_id, coalesce(e.period_id, b.period_id) AS period_id,
                   a.code, p.name, coalesce(b.d, 0) AS bd, coalesce(b.c, 0) AS bc, coalesce(e.d, 0) AS ed, coalesce(e.c, 0) AS ec
            FROM e FULL JOIN b ON e.account_id = b.account_id AND e.period_id = b.period_id
            JOIN chart_of_accounts a ON a.id = coalesce(e.account_id, b.account_id)
            JOIN accounting_periods p ON p.id = coalesce(e.period_id, b.period_id)
            WHERE coalesce(b.d, 0) <> coalesce(e.d, 0) OR coalesce(b.c, 0) <> coalesce(e.c, 0)
            ORDER BY a.code, p.sequence_key""";

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Clock clock;

    public LedgerBalanceService(JdbcTemplate jdbc, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    /** Adds posted movements. Accounts are processed in sorted order so concurrent postings cannot deadlock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void apply(UUID periodId, SortedMap<UUID, Movement> movements) {
        Timestamp now = Timestamp.from(Instant.now(clock));
        movements.forEach((accountId, m) -> jdbc.update("""
                INSERT INTO ledger_balances (account_id, period_id, debit_total, credit_total, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (account_id, period_id) DO UPDATE SET
                    debit_total = ledger_balances.debit_total + EXCLUDED.debit_total,
                    credit_total = ledger_balances.credit_total + EXCLUDED.credit_total,
                    updated_at = EXCLUDED.updated_at""", accountId, periodId, m.debit(), m.credit(), now));
    }

    @Transactional(readOnly = true)
    public List<BalanceDifference> differences(UUID periodIdOrNull) {
        String filter = periodIdOrNull == null ? "" : "WHERE period_id = ?";
        String sql = DIFF_SQL.formatted(filter, filter);
        Object[] args = periodIdOrNull == null ? new Object[0] : new Object[] {periodIdOrNull, periodIdOrNull};
        return jdbc.query(sql, (rs, i) -> new BalanceDifference(rs.getObject("account_id", UUID.class), rs.getString("code"),
                rs.getObject("period_id", UUID.class), rs.getString("name"), rs.getBigDecimal("bd"), rs.getBigDecimal("bc"),
                rs.getBigDecimal("ed"), rs.getBigDecimal("ec")), args);
    }

    /** Recomputes the cache from posted lines while blocking concurrent postings. */
    @Transactional
    public RebuildResult rebuild() {
        jdbc.execute("LOCK TABLE ledger_balances IN EXCLUSIVE MODE");
        List<BalanceDifference> diffs = differences(null);
        jdbc.update("DELETE FROM ledger_balances");
        int rows = jdbc.update("""
                INSERT INTO ledger_balances (account_id, period_id, debit_total, credit_total, updated_at)
                SELECT account_id, period_id, sum(debit), sum(credit), ? FROM journal_entry_lines GROUP BY account_id, period_id""",
                Timestamp.from(Instant.now(clock)));
        audit.record("LEDGER_BALANCES_REBUILT", "LEDGER_BALANCES", null, Map.of("differencesFound", diffs.size(),
                "rowsRebuilt", rows));
        return new RebuildResult(diffs.size(), rows, diffs);
    }
}
