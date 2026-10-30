package com.miaa.cas.treasury;

import com.miaa.cas.common.money.Money;
import com.miaa.cas.generalledger.AccountMappingService;
import com.miaa.cas.treasury.TreasuryDtos.BankPosition;
import com.miaa.cas.treasury.TreasuryDtos.CashPosition;
import com.miaa.cas.treasury.TreasuryDtos.CheckRow;
import com.miaa.cas.treasury.TreasuryDtos.DailyCollectionReport;
import com.miaa.cas.treasury.TreasuryDtos.DcrLine;
import com.miaa.cas.treasury.TreasuryDtos.MethodTotal;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Daily collection report, cash position and check register, computed from source records and the ledger. */
@Service
public class TreasuryReportService {

    private final JdbcTemplate jdbc;
    private final AccountMappingService mappings;

    public TreasuryReportService(JdbcTemplate jdbc, AccountMappingService mappings) {
        this.jdbc = jdbc;
        this.mappings = mappings;
    }

    /**
     * Every receipt dated that day (optionally for one collecting officer), including cancelled ones with
     * their reasons, totals by payment method, and what remains undeposited.
     */
    @Transactional(readOnly = true)
    public DailyCollectionReport dailyCollections(LocalDate date, String collector) {
        String who = collector == null || collector.isBlank() ? null : collector.trim().toLowerCase();
        List<DcrLine> lines = jdbc.query("""
                SELECT r.receipt_number, c.name, r.payment_method, r.payment_reference, r.amount, r.tax_withheld, r.status,
                       r.recorded_by, r.cancel_reason,
                       (SELECT d.deposit_number FROM deposit_items i JOIN deposits d ON d.id = i.deposit_id
                        WHERE i.receipt_id = r.id AND i.live) AS deposit_number
                FROM receipts r JOIN customers c ON c.id = r.customer_id
                WHERE r.receipt_date = ? AND r.migration_batch_id IS NULL AND (?::text IS NULL OR r.recorded_by = ?::text)
                ORDER BY r.receipt_number""", (rs, i) -> new DcrLine(rs.getString("receipt_number"), rs.getString("name"),
                rs.getString("payment_method"), rs.getString("payment_reference"), rs.getBigDecimal("amount"),
                rs.getBigDecimal("tax_withheld"), rs.getString("status"), rs.getString("recorded_by"),
                rs.getString("deposit_number"), rs.getString("cancel_reason")), Date.valueOf(date), who, who);
        Map<String, BigDecimal> byMethod = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        BigDecimal undeposited = BigDecimal.ZERO;
        int cancelled = 0;
        for (DcrLine l : lines) {
            if ("CANCELLED".equals(l.status())) {
                cancelled++;
                continue;
            }
            byMethod.merge(l.paymentMethod(), l.amount(), BigDecimal::add);
            counts.merge(l.paymentMethod(), 1, Integer::sum);
            total = total.add(l.amount());
            tax = tax.add(l.taxWithheld());
            if (l.depositNumber() == null) {
                undeposited = undeposited.add(l.amount());
            }
        }
        List<MethodTotal> totals = new ArrayList<>();
        byMethod.forEach((m, a) -> totals.add(new MethodTotal(m, counts.get(m), Money.of(a))));
        return new DailyCollectionReport(date, who, lines.isEmpty() ? null : lines.get(0).receiptNumber(),
                lines.isEmpty() ? null : lines.get(lines.size() - 1).receiptNumber(), lines, totals, Money.of(total), Money.of(tax),
                Money.of(undeposited), cancelled, "Receipt numbers are the interim CAS series pending the BIR-registered official "
                        + "receipt series (C-29); the official DCR form layout awaits AVTECH's template.");
    }

    /** Ledger balances of cash accounts as of a date, plus collections not yet deposited. */
    @Transactional(readOnly = true)
    public CashPosition cashPosition(LocalDate asOf) {
        Date d = Date.valueOf(asOf);
        BigDecimal cashOnHand = balance(mappings.require("CASH_ON_HAND"), d);
        BigDecimal undeposited = Money.of(jdbc.queryForObject("""
                SELECT coalesce(sum(r.amount), 0) FROM receipts r WHERE r.status = 'POSTED' AND r.receipt_date <= ? AND r.migration_batch_id IS NULL
                  AND NOT EXISTS (SELECT 1 FROM deposit_items i JOIN deposits dp ON dp.id = i.deposit_id
                                  WHERE i.receipt_id = r.id AND i.live AND dp.deposit_date <= ?)""", BigDecimal.class, d, d));
        List<BankPosition> banks = jdbc.query("""
                SELECT b.id, b.code, b.bank_name, b.account_number_last4, b.gl_account_id, a.code AS gl_code,
                       (SELECT max(s.period_end) FROM bank_reconciliations r JOIN bank_statements s ON s.id = r.statement_id
                        WHERE r.bank_account_id = b.id AND r.status = 'APPROVED') AS reconciled
                FROM bank_accounts b JOIN chart_of_accounts a ON a.id = b.gl_account_id WHERE b.status = 'ACTIVE' ORDER BY b.code""",
                (rs, i) -> new BankPosition(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("bank_name"),
                        rs.getString("account_number_last4"), rs.getString("gl_code"),
                        balance(rs.getObject("gl_account_id", UUID.class), d), DepositService.date(rs.getDate("reconciled"))));
        BigDecimal totalBank = Money.of(banks.stream().map(BankPosition::bookBalance).reduce(BigDecimal.ZERO, BigDecimal::add));
        return new CashPosition(asOf, cashOnHand, undeposited, banks, totalBank);
    }

    @Transactional(readOnly = true)
    public List<CheckRow> checks(UUID bankAccountId, String status, LocalDate asOf) {
        String st = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        return jdbc.query("""
                SELECT c.id, b.code, c.check_number, v.dv_number, c.payee, c.amount, c.issue_date, c.status, c.void_reason,
                       (?::date - c.issue_date) AS age
                FROM check_register c JOIN bank_accounts b ON b.id = c.bank_account_id JOIN disbursement_vouchers v ON v.id = c.dv_id
                WHERE (?::uuid IS NULL OR c.bank_account_id = ?::uuid) AND (?::text IS NULL OR c.status = ?::text)
                ORDER BY c.issue_date DESC, c.check_number DESC LIMIT 1000""", (rs, i) -> new CheckRow(rs.getObject("id", UUID.class),
                rs.getString("code"), rs.getString("check_number"), rs.getString("dv_number"), rs.getString("payee"),
                rs.getBigDecimal("amount"), rs.getDate("issue_date").toLocalDate(), rs.getString("status"),
                rs.getString("void_reason"), rs.getLong("age")), Date.valueOf(asOf), bankAccountId, bankAccountId, st, st);
    }

    BigDecimal balance(UUID accountId, Date asOf) {
        return Money.of(jdbc.queryForObject("SELECT coalesce(sum(debit - credit), 0) FROM journal_entry_lines WHERE account_id = ? "
                + "AND accounting_date <= ?", BigDecimal.class, accountId, asOf));
    }
}
