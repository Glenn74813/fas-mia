package com.miaa.cas.accountsreceivable;

import com.miaa.cas.accountsreceivable.ArDtos.ArAgingReport;
import com.miaa.cas.accountsreceivable.ArDtos.ArAgingRow;
import com.miaa.cas.accountsreceivable.ArDtos.ArReconciliation;
import com.miaa.cas.accountsreceivable.ArDtos.ControlReconciliation;
import com.miaa.cas.accountsreceivable.ArDtos.Statement;
import com.miaa.cas.accountsreceivable.ArDtos.StatementLine;
import com.miaa.cas.accountsreceivable.domain.Customer;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.money.Money;
import com.miaa.cas.generalledger.AccountMappingService;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Receivables reports computed from posted billing documents, collections and applications. */
@Service
public class ArReportService {

    /**
     * Open amount of each posted invoice / debit memo as of a date: gross less collections and credit
     * memos whose ledger entries are dated on or before it. Parameters: asOf (x3), optional customer (x2).
     */
    private static final String OPEN_ITEMS = """
            SELECT d.id, d.customer_id, d.due_date,
                   d.gross_amount
                   - coalesce((SELECT sum(a.amount) FROM receipt_allocations a JOIN journal_entries ae ON ae.id = a.posted_entry_id
                               WHERE a.document_id = d.id AND ae.accounting_date <= ?), 0)
                   - coalesce((SELECT sum(m.gross_amount) FROM billing_documents m JOIN journal_entries me ON me.id = m.posted_entry_id
                               WHERE m.related_invoice_id = d.id AND m.kind = 'CREDIT_MEMO' AND m.status = 'POSTED'
                                 AND me.accounting_date <= ?), 0) AS open
            FROM billing_documents d JOIN journal_entries de ON de.id = d.posted_entry_id
            WHERE d.status = 'POSTED' AND d.kind <> 'CREDIT_MEMO' AND de.accounting_date <= ?
              AND (?::uuid IS NULL OR d.customer_id = ?::uuid)""";

    private final JdbcTemplate jdbc;
    private final AccountMappingService mappings;
    private final CustomerService customers;

    public ArReportService(JdbcTemplate jdbc, AccountMappingService mappings, CustomerService customers) {
        this.jdbc = jdbc;
        this.mappings = mappings;
        this.customers = customers;
    }

    @Transactional(readOnly = true)
    public ArAgingReport aging(LocalDate asOf, UUID customerId) {
        Date d = Date.valueOf(asOf);
        List<ArAgingRow> rows = jdbc.query("WITH open_items AS (" + OPEN_ITEMS + """
                )
                SELECT c.id, c.code, c.name,
                       sum(CASE WHEN ? - due_date <= 0 THEN open ELSE 0 END) AS cur,
                       sum(CASE WHEN ? - due_date BETWEEN 1 AND 30 THEN open ELSE 0 END) AS d30,
                       sum(CASE WHEN ? - due_date BETWEEN 31 AND 60 THEN open ELSE 0 END) AS d60,
                       sum(CASE WHEN ? - due_date BETWEEN 61 AND 90 THEN open ELSE 0 END) AS d90,
                       sum(CASE WHEN ? - due_date > 90 THEN open ELSE 0 END) AS over90,
                       sum(open) AS total
                FROM open_items o JOIN customers c ON c.id = o.customer_id
                GROUP BY c.id, c.code, c.name HAVING sum(open) <> 0 ORDER BY c.name""",
                (rs, i) -> new ArAgingRow(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                        Money.of(rs.getBigDecimal("cur")), Money.of(rs.getBigDecimal("d30")), Money.of(rs.getBigDecimal("d60")),
                        Money.of(rs.getBigDecimal("d90")), Money.of(rs.getBigDecimal("over90")),
                        Money.of(rs.getBigDecimal("total"))), d, d, d, customerId, customerId, d, d, d, d, d);
        ArAgingRow totals = new ArAgingRow(null, null, "Total", sum(rows, 0), sum(rows, 1), sum(rows, 2), sum(rows, 3),
                sum(rows, 4), sum(rows, 5));
        return new ArAgingReport(asOf, rows, totals);
    }

    /**
     * Statement of account: charges (invoices, debit memos) and credits (credit memos, collections
     * including tax withheld), by ledger date. The balance is net of unapplied collections.
     */
    @Transactional(readOnly = true)
    public Statement statement(UUID customerId, LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "'to' cannot precede 'from'.");
        }
        Customer c = customers.load(customerId);
        String movements = """
                SELECT e.accounting_date AS dt, d.kind AS type, d.document_number AS ref, d.description AS descr,
                       CASE WHEN d.kind = 'CREDIT_MEMO' THEN 0 ELSE d.gross_amount END AS charges,
                       CASE WHEN d.kind = 'CREDIT_MEMO' THEN d.gross_amount ELSE 0 END AS credits, d.document_number AS sort_key
                FROM billing_documents d JOIN journal_entries e ON e.id = d.posted_entry_id
                WHERE d.customer_id = ? AND d.status = 'POSTED'
                UNION ALL
                SELECT e.accounting_date, 'RECEIPT', r.receipt_number,
                       r.payment_method || coalesce(' ' || r.payment_reference, '')
                         || CASE WHEN r.tax_withheld > 0 THEN ' (incl. tax withheld ' || r.tax_withheld || ')' ELSE '' END,
                       0, r.amount + r.tax_withheld, r.receipt_number
                FROM receipts r JOIN journal_entries e ON e.id = r.posted_entry_id
                WHERE r.customer_id = ? AND r.status = 'POSTED'""";
        BigDecimal opening = Money.of(jdbc.queryForObject("SELECT coalesce(sum(charges - credits), 0) FROM (" + movements
                + ") m WHERE dt < ?", BigDecimal.class, customerId, customerId, Date.valueOf(from)));
        List<StatementLine> lines = new ArrayList<>();
        BigDecimal[] running = {opening};
        jdbc.query("SELECT * FROM (" + movements + ") m WHERE dt BETWEEN ? AND ? ORDER BY dt, sort_key", rs -> {
            BigDecimal charges = Money.of(rs.getBigDecimal("charges"));
            BigDecimal credits = Money.of(rs.getBigDecimal("credits"));
            running[0] = running[0].add(charges).subtract(credits);
            lines.add(new StatementLine(rs.getDate("dt").toLocalDate(), rs.getString("type"), rs.getString("ref"),
                    rs.getString("descr"), charges, credits, Money.of(running[0])));
        }, customerId, customerId, Date.valueOf(from), Date.valueOf(to));
        BigDecimal unapplied = Money.of(jdbc.queryForObject("""
                SELECT coalesce(sum(r.amount + r.tax_withheld), 0)
                     - coalesce((SELECT sum(a.amount) FROM receipt_allocations a JOIN receipts r2 ON r2.id = a.receipt_id
                                 JOIN journal_entries ae ON ae.id = a.posted_entry_id
                                 WHERE r2.customer_id = ? AND ae.accounting_date <= ?), 0)
                FROM receipts r JOIN journal_entries e ON e.id = r.posted_entry_id
                WHERE r.customer_id = ? AND r.status = 'POSTED' AND e.accounting_date <= ?""", BigDecimal.class,
                customerId, Date.valueOf(to), customerId, Date.valueOf(to)));
        return new Statement(c.getId(), c.getCode(), c.getName(), from, to, opening, lines, Money.of(running[0]), unapplied);
    }

    /** Customer sub-ledger against the AR and unapplied-collections control accounts. */
    @Transactional(readOnly = true)
    public ArReconciliation reconciliation() {
        BigDecimal ar = jdbc.queryForObject("SELECT coalesce(sum(gross_amount - settled_amount), 0) FROM billing_documents "
                + "WHERE status = 'POSTED' AND kind <> 'CREDIT_MEMO'", BigDecimal.class);
        BigDecimal unapplied = jdbc.queryForObject("SELECT coalesce(sum(unapplied_amount), 0) FROM receipts WHERE status = 'POSTED'",
                BigDecimal.class);
        ControlReconciliation r1 = control("AR_TRADE_RECEIVABLE", ar, true);
        ControlReconciliation r2 = control("UNAPPLIED_COLLECTIONS", unapplied, false);
        return new ArReconciliation(r1, r2, r1.reconciled() && r2.reconciled());
    }

    private ControlReconciliation control(String key, BigDecimal sub, boolean debitNormal) {
        UUID account = mappings.require(key);
        BigDecimal gl = jdbc.queryForObject("SELECT coalesce(sum(" + (debitNormal ? "debit - credit" : "credit - debit")
                + "), 0) FROM journal_entry_lines WHERE account_id = ?", BigDecimal.class, account);
        String code = jdbc.queryForObject("SELECT code FROM chart_of_accounts WHERE id = ?", String.class, account);
        BigDecimal diff = Money.of(gl.subtract(sub));
        return new ControlReconciliation(key, account, code, Money.of(sub), Money.of(gl), diff, diff.signum() == 0);
    }

    private static BigDecimal sum(List<ArAgingRow> rows, int col) {
        BigDecimal t = BigDecimal.ZERO;
        for (ArAgingRow r : rows) {
            t = t.add(switch (col) {
                case 0 -> r.current();
                case 1 -> r.days1to30();
                case 2 -> r.days31to60();
                case 3 -> r.days61to90();
                case 4 -> r.over90();
                default -> r.total();
            });
        }
        return Money.of(t);
    }
}
