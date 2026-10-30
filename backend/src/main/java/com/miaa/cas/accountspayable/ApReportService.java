package com.miaa.cas.accountspayable;

import com.miaa.cas.accountspayable.ApDtos.AdvanceRegister;
import com.miaa.cas.accountspayable.ApDtos.AdvanceRow;
import com.miaa.cas.accountspayable.ApDtos.AgingReport;
import com.miaa.cas.accountspayable.ApDtos.AgingRow;
import com.miaa.cas.accountspayable.ApDtos.ApReconciliation;
import com.miaa.cas.accountspayable.ApDtos.WithholdingRow;
import com.miaa.cas.accountspayable.ApDtos.WithholdingSchedule;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.money.Money;
import com.miaa.cas.generalledger.AccountMappingService;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Payables reports computed from posted invoices and payments. */
@Service
public class ApReportService {

    private final JdbcTemplate jdbc;
    private final AccountMappingService mappings;

    public ApReportService(JdbcTemplate jdbc, AccountMappingService mappings) {
        this.jdbc = jdbc;
        this.mappings = mappings;
    }

    /**
     * Open balances as of a date, aged by days past due. Payments count when their DV was posted on or
     * before the date.
     */
    @Transactional(readOnly = true)
    public AgingReport aging(LocalDate asOf) {
        Date d = Date.valueOf(asOf);
        List<AgingRow> rows = jdbc.query("""
                WITH open_items AS (
                    SELECT i.supplier_id, i.due_date,
                           i.payable_amount - coalesce((SELECT sum(l.amount) FROM disbursement_voucher_lines l
                               JOIN disbursement_vouchers v ON v.id = l.dv_id JOIN journal_entries e ON e.id = v.posted_entry_id
                               WHERE l.invoice_id = i.id AND v.status = 'POSTED' AND e.accounting_date <= ?), 0) AS open
                    FROM supplier_invoices i JOIN journal_entries ie ON ie.id = i.posted_entry_id
                    WHERE i.status = 'POSTED' AND ie.accounting_date <= ?)
                SELECT s.id, s.code, s.name,
                       sum(CASE WHEN ? - due_date <= 0 THEN open ELSE 0 END) AS cur,
                       sum(CASE WHEN ? - due_date BETWEEN 1 AND 30 THEN open ELSE 0 END) AS d30,
                       sum(CASE WHEN ? - due_date BETWEEN 31 AND 60 THEN open ELSE 0 END) AS d60,
                       sum(CASE WHEN ? - due_date BETWEEN 61 AND 90 THEN open ELSE 0 END) AS d90,
                       sum(CASE WHEN ? - due_date > 90 THEN open ELSE 0 END) AS over90,
                       sum(open) AS total
                FROM open_items o JOIN suppliers s ON s.id = o.supplier_id
                GROUP BY s.id, s.code, s.name HAVING sum(open) <> 0 ORDER BY s.name""",
                (rs, i) -> new AgingRow(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                        Money.of(rs.getBigDecimal("cur")), Money.of(rs.getBigDecimal("d30")), Money.of(rs.getBigDecimal("d60")),
                        Money.of(rs.getBigDecimal("d90")), Money.of(rs.getBigDecimal("over90")),
                        Money.of(rs.getBigDecimal("total"))), d, d, d, d, d, d, d);
        AgingRow totals = new AgingRow(null, null, "Total", sum(rows, 0), sum(rows, 1), sum(rows, 2), sum(rows, 3),
                sum(rows, 4), sum(rows, 5));
        return new AgingReport(asOf, rows, totals);
    }

    /** Sub-ledger (open invoice balances) against the AP control account; differences flag manual GL entries. */
    @Transactional(readOnly = true)
    public ApReconciliation reconciliation() {
        UUID control = mappings.require("AP_TRADE_PAYABLE");
        BigDecimal sub = jdbc.queryForObject("SELECT coalesce(sum(payable_amount - paid_amount), 0) FROM supplier_invoices "
                + "WHERE status = 'POSTED'", BigDecimal.class);
        BigDecimal gl = jdbc.queryForObject("SELECT coalesce(sum(credit - debit), 0) FROM journal_entry_lines WHERE account_id = ?",
                BigDecimal.class, control);
        String code = jdbc.queryForObject("SELECT code FROM chart_of_accounts WHERE id = ?", String.class, control);
        BigDecimal diff = Money.of(gl.subtract(sub));
        return new ApReconciliation(control, code, Money.of(sub), Money.of(gl), diff, diff.signum() == 0);
    }

    /**
     * Supplier advances outstanding. Only migrated balances exist: CAS does not yet pay or liquidate advances (C-31),
     * so a difference against the ledger means the account was moved by a journal voucher.
     */
    @Transactional(readOnly = true)
    public AdvanceRegister advances() {
        List<AdvanceRow> rows = jdbc.query("""
                SELECT a.id, s.code, s.name, a.reference, a.advance_date, a.description, a.amount, a.outstanding_amount, a.status,
                       e.entry_number FROM supplier_advances a JOIN suppliers s ON s.id = a.supplier_id
                JOIN journal_entries e ON e.id = a.posted_entry_id ORDER BY s.code, a.advance_date, a.reference""",
                (rs, i) -> new AdvanceRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getDate(5).toLocalDate(), rs.getString(6), rs.getBigDecimal(7), rs.getBigDecimal(8), rs.getString(9),
                        rs.getString(10)));
        BigDecimal sub = Money.of(rows.stream().filter(r -> "OPEN".equals(r.status())).map(AdvanceRow::outstandingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        List<UUID> mapped = jdbc.queryForList("SELECT account_id FROM gl_account_mappings WHERE mapping_key = 'SUPPLIER_ADVANCES'", UUID.class);
        ApReconciliation rec = null;
        if (!mapped.isEmpty()) {
            UUID account = mapped.get(0);
            BigDecimal gl = jdbc.queryForObject("SELECT coalesce(sum(debit - credit), 0) FROM journal_entry_lines WHERE account_id = ?",
                    BigDecimal.class, account);
            String code = jdbc.queryForObject("SELECT code FROM chart_of_accounts WHERE id = ?", String.class, account);
            BigDecimal diff = Money.of(gl.subtract(sub));
            rec = new ApReconciliation(account, code, sub, Money.of(gl), diff, diff.signum() == 0);
        }
        return new AdvanceRegister(rows, sub, rec, "Advances carried over from the legacy system at cutover. Paying and liquidating "
                + "advances in CAS awaits AVTECH's policy (clarification C-31).");
    }

    /**
     * Expanded-withholding schedule per supplier and tax code for a quarter, by the month the invoice was
     * recognised. This is the data behind BIR Form 2307; the printable form layout awaits the approved
     * template (clarification C-06).
     */
    @Transactional(readOnly = true)
    public WithholdingSchedule withholding(int year, int quarter) {
        if (quarter < 1 || quarter > 4) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "quarter must be 1-4");
        }
        LocalDate start = LocalDate.of(year, (quarter - 1) * 3 + 1, 1);
        LocalDate end = start.plusMonths(3).minusDays(1);
        int m1 = start.getMonthValue();
        List<WithholdingRow> rows = jdbc.query("""
                SELECT s.tin, s.name, t.code,
                       sum(CASE WHEN extract(month FROM i.accounting_date) = ? THEN l.net_amount ELSE 0 END) AS m1,
                       sum(CASE WHEN extract(month FROM i.accounting_date) = ? THEN l.net_amount ELSE 0 END) AS m2,
                       sum(CASE WHEN extract(month FROM i.accounting_date) = ? THEN l.net_amount ELSE 0 END) AS m3,
                       sum(l.net_amount) AS base, sum(l.ewt_amount) AS tax
                FROM supplier_invoice_lines l JOIN supplier_invoices i ON i.id = l.invoice_id
                JOIN suppliers s ON s.id = i.supplier_id JOIN tax_codes t ON t.id = l.ewt_code_id
                WHERE i.status = 'POSTED' AND i.accounting_date BETWEEN ? AND ? AND l.ewt_amount > 0
                GROUP BY s.tin, s.name, t.code ORDER BY s.name, t.code""", (rs, i) -> new WithholdingRow(rs.getString("tin"),
                rs.getString("name"), rs.getString("code"), Money.of(rs.getBigDecimal("m1")), Money.of(rs.getBigDecimal("m2")),
                Money.of(rs.getBigDecimal("m3")), Money.of(rs.getBigDecimal("base")), Money.of(rs.getBigDecimal("tax"))),
                m1, m1 + 1, m1 + 2, Date.valueOf(start), Date.valueOf(end));
        return new WithholdingSchedule(year, quarter, "Data for BIR Form 2307. Print layout pending the approved template; "
                + "tax codes and rates are AVTECH configuration and must be validated by the tax team.", rows);
    }

    private static BigDecimal sum(List<AgingRow> rows, int col) {
        BigDecimal t = BigDecimal.ZERO;
        for (AgingRow r : rows) {
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
