package com.miaa.cas.financialreporting;

import com.miaa.cas.accountspayable.ApDtos.WithholdingRow;
import com.miaa.cas.accountspayable.ApDtos.WithholdingSchedule;
import com.miaa.cas.accountspayable.ApReportService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.money.Money;
import com.miaa.cas.financialreporting.ManagementReportService.Rendered;
import com.miaa.cas.financialreporting.export.ReportTable;
import com.miaa.cas.treasury.BankReconciliationService;
import com.miaa.cas.treasury.BankReconciliationService.ReconciliationStatement;
import com.miaa.cas.treasury.TreasuryDtos.DailyCollectionReport;
import com.miaa.cas.treasury.TreasuryDtos.DcrLine;
import com.miaa.cas.treasury.TreasuryDtos.LedgerItem;
import com.miaa.cas.treasury.TreasuryDtos.MethodTotal;
import com.miaa.cas.treasury.TreasuryDtos.ReconciliationFigures;
import com.miaa.cas.treasury.TreasuryDtos.StatementLineView;
import com.miaa.cas.treasury.TreasuryReportService;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operational reports as background jobs (MS §11.4, §11.8): Cash Disbursement Journal, collection summary, general and
 * subsidiary ledgers, daily collection report, bank reconciliation statement and the expanded-withholding schedule.
 * They render in the report worker, which has no signed-in user, so they read data directly and never through
 * methods that compute a viewer's actions. Layouts are CAS layouts until AVTECH's approved formats are confirmed (C-07,
 * C-29).
 */
@Service
public class OperationalReportService {

    static final Set<String> TYPES = Set.of("CASH_DISBURSEMENT_JOURNAL", "COLLECTION_SUMMARY", "GENERAL_LEDGER", "SUBSIDIARY_LEDGER",
            "DAILY_COLLECTION_REPORT", "BANK_RECONCILIATION_STATEMENT", "WITHHOLDING_SCHEDULE", "ABSTRACT_OF_BILLS");

    private static final String ORG = "Avtech Information Technology Solutions, Inc.";

    private final JdbcTemplate jdbc;
    private final TreasuryReportService treasury;
    private final BankReconciliationService reconciliations;
    private final ApReportService ap;
    private final int rowLimit;

    public OperationalReportService(JdbcTemplate jdbc, TreasuryReportService treasury, BankReconciliationService reconciliations,
            ApReportService ap, @Value("${cas.reports.ledger-row-limit:100000}") int rowLimit) {
        this.jdbc = jdbc;
        this.treasury = treasury;
        this.reconciliations = reconciliations;
        this.ap = ap;
        this.rowLimit = rowLimit;
    }

    static boolean handles(String type) {
        return TYPES.contains(type);
    }

    static String permissionFor(String type) {
        return switch (type) {
            case "CASH_DISBURSEMENT_JOURNAL", "WITHHOLDING_SCHEDULE" -> "AP_VIEW";
            case "COLLECTION_SUMMARY", "ABSTRACT_OF_BILLS" -> "AR_VIEW";
            case "GENERAL_LEDGER", "SUBSIDIARY_LEDGER" -> "LEDGER_VIEW";
            case "DAILY_COLLECTION_REPORT", "BANK_RECONCILIATION_STATEMENT" -> "TREASURY_VIEW";
            default -> throw new IllegalArgumentException(type);
        };
    }

    void validate(String type, Map<String, String> p) {
        switch (type) {
            case "CASH_DISBURSEMENT_JOURNAL", "COLLECTION_SUMMARY", "GENERAL_LEDGER", "SUBSIDIARY_LEDGER", "ABSTRACT_OF_BILLS" -> {
                if (date(p, "to").isBefore(date(p, "from"))) {
                    throw bad("'to' cannot precede 'from'.");
                }
                if ("GENERAL_LEDGER".equals(type)) {
                    optionalUuid(p, "accountId");
                    optionalUuid(p, "costCenterId");
                }
                if ("SUBSIDIARY_LEDGER".equals(type)) {
                    if (optionalUuid(p, "subsidiaryAccountId") == null && !Set.of("CUSTOMER", "SUPPLIER", "EMPLOYEE", "OTHER")
                            .contains(p.getOrDefault("slType", ""))) {
                        throw bad("Give parameters.subsidiaryAccountId, or parameters.slType (CUSTOMER, SUPPLIER, EMPLOYEE or OTHER).");
                    }
                }
            }
            case "DAILY_COLLECTION_REPORT" -> date(p, "date");
            case "BANK_RECONCILIATION_STATEMENT" -> {
                UUID id = uuid(p, "reconciliationId");
                if (jdbc.queryForList("SELECT id FROM bank_reconciliations WHERE id = ?", UUID.class, id).isEmpty()) {
                    throw ApiException.notFound("Bank reconciliation");
                }
            }
            case "WITHHOLDING_SCHEDULE" -> {
                int q = integer(p, "quarter");
                integer(p, "year");
                if (q < 1 || q > 4) {
                    throw bad("parameters.quarter must be 1 to 4.");
                }
            }
            default -> throw new IllegalArgumentException(type);
        }
    }

    @Transactional(readOnly = true)
    Rendered render(String type, Map<String, String> p, String by, String at) {
        return switch (type) {
            case "CASH_DISBURSEMENT_JOURNAL" -> cashDisbursementJournal(date(p, "from"), date(p, "to"), by, at);
            case "COLLECTION_SUMMARY" -> collectionSummary(date(p, "from"), date(p, "to"), by, at);
            case "GENERAL_LEDGER" -> generalLedger(date(p, "from"), date(p, "to"), optionalUuid(p, "accountId"),
                    optionalUuid(p, "costCenterId"), by, at);
            case "SUBSIDIARY_LEDGER" -> subsidiaryLedger(date(p, "from"), date(p, "to"), optionalUuid(p, "subsidiaryAccountId"),
                    p.get("slType"), by, at);
            case "DAILY_COLLECTION_REPORT" -> dailyCollectionReport(date(p, "date"), by, at);
            case "BANK_RECONCILIATION_STATEMENT" -> bankReconciliationStatement(uuid(p, "reconciliationId"), by, at);
            case "WITHHOLDING_SCHEDULE" -> withholding(integer(p, "year"), integer(p, "quarter"), by, at);
            case "ABSTRACT_OF_BILLS" -> abstractOfBills(date(p, "from"), date(p, "to"), by, at);
            default -> throw new IllegalArgumentException(type);
        };
    }

    // ------------------------------------------------------------------ reports

    /** Every posted disbursement voucher's ledger lines, by the accounting date of its entry. */
    private Rendered cashDisbursementJournal(LocalDate from, LocalDate to, String by, String at) {
        List<ReportTable.Row> rows = new ArrayList<>();
        BigDecimal[] totals = {BigDecimal.ZERO, BigDecimal.ZERO};
        String[] last = {null};
        jdbc.query("""
                SELECT v.dv_number, e.accounting_date, e.entry_number, s.name AS payee, v.particulars,
                       coalesce((SELECT c.check_number FROM check_register c WHERE c.dv_id = v.id AND c.status <> 'VOIDED' LIMIT 1),
                                v.payment_reference, v.payment_mode) AS reference,
                       a.code, a.name AS account, l.debit, l.credit
                FROM disbursement_vouchers v JOIN journal_entries e ON e.id = v.posted_entry_id JOIN suppliers s ON s.id = v.supplier_id
                JOIN journal_entry_lines l ON l.entry_id = e.id JOIN chart_of_accounts a ON a.id = l.account_id
                WHERE v.status = 'POSTED' AND e.accounting_date BETWEEN ? AND ?
                ORDER BY e.accounting_date, v.dv_number, l.line_no""", rs -> {
            String dv = rs.getString("dv_number");
            boolean first = !dv.equals(last[0]);
            last[0] = dv;
            BigDecimal d = rs.getBigDecimal("debit");
            BigDecimal c = rs.getBigDecimal("credit");
            totals[0] = totals[0].add(d);
            totals[1] = totals[1].add(c);
            rows.add(new ReportTable.Row(first ? dv : "", first ? 0 : 1, false, Arrays.asList(first ? rs.getDate("accounting_date").toString() : null,
                    first ? rs.getString("reference") : null, first ? rs.getString("payee") : null, first ? rs.getString("entry_number") : null,
                    rs.getString("code") + " " + rs.getString("account"), d.signum() == 0 ? null : d, c.signum() == 0 ? null : c)));
        }, Date.valueOf(from), Date.valueOf(to));
        rows.add(new ReportTable.Row("Total", 0, true, Arrays.asList(null, null, null, null, null, Money.of(totals[0]), Money.of(totals[1]))));
        return new Rendered(new ReportTable("Cash Disbursement Journal", List.of(ORG, "From " + from + " to " + to), "DV number",
                List.of("Date", "Check / reference", "Payee", "JEV", "Account", "Debit", "Credit"), rows,
                List.of("Posted disbursement vouchers by the accounting date of their ledger entry. CAS layout; the prescribed format awaits "
                        + "confirmation (C-07).", generated(by, at))), "cash_disbursement_journal_" + from + "_" + to);
    }

    /** Posted collections per day and payment method; migrated balances are excluded (they were collected before cutover). */
    private Rendered collectionSummary(LocalDate from, LocalDate to, String by, String at) {
        List<ReportTable.Row> rows = new ArrayList<>();
        BigDecimal[] t = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        int[] count = {0};
        jdbc.query("""
                SELECT r.receipt_date, count(*) AS n,
                       coalesce(sum(r.amount) FILTER (WHERE r.payment_method = 'CASH'), 0) AS cash,
                       coalesce(sum(r.amount) FILTER (WHERE r.payment_method = 'CHECK'), 0) AS chk,
                       coalesce(sum(r.amount) FILTER (WHERE r.payment_method = 'BANK_TRANSFER'), 0) AS transfer,
                       sum(r.amount) AS total, sum(r.tax_withheld) AS tax
                FROM receipts r WHERE r.status = 'POSTED' AND r.migration_batch_id IS NULL AND r.receipt_date BETWEEN ? AND ?
                GROUP BY r.receipt_date ORDER BY r.receipt_date""", rs -> {
            List<BigDecimal> v = List.of(rs.getBigDecimal("cash"), rs.getBigDecimal("chk"), rs.getBigDecimal("transfer"), rs.getBigDecimal("total"),
                    rs.getBigDecimal("tax"));
            for (int i = 0; i < 5; i++) {
                t[i] = t[i].add(v.get(i));
            }
            count[0] += rs.getInt("n");
            List<Object> cells = new ArrayList<>(List.of(String.valueOf(rs.getInt("n"))));
            cells.addAll(v);
            rows.add(new ReportTable.Row(rs.getDate("receipt_date").toString(), 0, false, cells));
        }, Date.valueOf(from), Date.valueOf(to));
        List<Object> cells = new ArrayList<>(List.of(String.valueOf(count[0])));
        for (BigDecimal x : t) {
            cells.add(Money.of(x));
        }
        rows.add(new ReportTable.Row("Total", 0, true, cells));
        return new Rendered(new ReportTable("Collection summary", List.of(ORG, "From " + from + " to " + to), "Date",
                List.of("Receipts", "Cash", "Check", "Bank transfer", "Total collected", "Tax withheld"), rows,
                List.of("Posted collections by receipt date. Balances migrated at cutover are not collections of the period and are "
                        + "excluded.", generated(by, at))), "collection_summary_" + from + "_" + to);
    }

    /**
     * Abstract of bills (TOR Annex A, Billing): every posted billing document dated in the period, by customer, with
     * subtotals per customer and totals per kind. Credit memos reduce the totals. The layout is a proposal until the
     * Billing Section confirms its form.
     */
    private Rendered abstractOfBills(LocalDate from, LocalDate to, String by, String at) {
        List<ReportTable.Row> rows = new ArrayList<>();
        Map<String, BigDecimal[]> byKind = new java.util.TreeMap<>();
        BigDecimal[] grand = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        String[] customer = {null};
        BigDecimal[] sub = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        int[] count = {0};
        Runnable closeCustomer = () -> {
            if (customer[0] != null) {
                rows.add(new ReportTable.Row("Subtotal " + customer[0], 1, true, java.util.Arrays.asList(null, null, null, null,
                        Money.of(sub[0]), Money.of(sub[1]), Money.of(sub[2]))));
                java.util.Arrays.fill(sub, BigDecimal.ZERO);
            }
        };
        jdbc.query("""
                SELECT c.code, c.name, b.document_number, b.kind, b.document_date, b.billing_period, b.due_date,
                       CASE WHEN b.kind = 'CREDIT_MEMO' THEN -1 ELSE 1 END AS sign, b.net_amount, b.vat_amount, b.gross_amount
                FROM billing_documents b JOIN customers c ON c.id = b.customer_id
                WHERE b.status = 'POSTED' AND b.document_date BETWEEN ? AND ?
                ORDER BY c.name, c.code, b.document_date, b.document_number""", rs -> {
            String who = rs.getString("code") + " " + rs.getString("name");
            if (!who.equals(customer[0])) {
                closeCustomer.run();
                customer[0] = who;
                rows.add(new ReportTable.Row(who, 0, true, java.util.Collections.nCopies(7, null)));
            }
            BigDecimal sign = BigDecimal.valueOf(rs.getInt("sign"));
            BigDecimal[] v = {rs.getBigDecimal("net_amount").multiply(sign), rs.getBigDecimal("vat_amount").multiply(sign),
                rs.getBigDecimal("gross_amount").multiply(sign)};
            String kind = rs.getString("kind");
            BigDecimal[] k = byKind.computeIfAbsent(kind, x -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            for (int i = 0; i < 3; i++) {
                sub[i] = sub[i].add(v[i]);
                grand[i] = grand[i].add(v[i]);
                k[i] = k[i].add(v[i]);
            }
            count[0]++;
            rows.add(new ReportTable.Row(rs.getString("document_number"), 1, false, java.util.Arrays.asList(kind.replace('_', ' '),
                    rs.getDate("document_date").toString(), rs.getString("billing_period") == null ? "" : rs.getString("billing_period"),
                    rs.getDate("due_date").toString(), Money.of(v[0]), Money.of(v[1]), Money.of(v[2]))));
        }, Date.valueOf(from), Date.valueOf(to));
        closeCustomer.run();
        byKind.forEach((kind, v) -> rows.add(new ReportTable.Row("Total " + kind.replace('_', ' ').toLowerCase(), 0, false,
                java.util.Arrays.asList(null, null, null, null, Money.of(v[0]), Money.of(v[1]), Money.of(v[2])))));
        rows.add(new ReportTable.Row("Total, " + count[0] + " bill(s)", 0, true, java.util.Arrays.asList(null, null, null, null,
                Money.of(grand[0]), Money.of(grand[1]), Money.of(grand[2]))));
        return new Rendered(new ReportTable("Abstract of bills", List.of(ORG, "Bills dated " + from + " to " + to), "Customer / bill",
                List.of("Kind", "Date", "Billing period", "Due", "Net", "VAT", "Gross"), rows,
                List.of("Posted invoices, debit memos and credit memos (credit memos as negative amounts).",
                        "PLACEHOLDER layout: to be confirmed by the Billing Section against AVTECH's abstract of bills form.",
                        generated(by, at))), "abstract_of_bills_" + from + "_" + to);
    }

    /** Opening balance, every posted line and the running balance of each account, in the account's normal-balance sign. */
    private Rendered generalLedger(LocalDate from, LocalDate to, UUID accountId, UUID costCenterId, String by, String at) {
        List<ReportTable.Row> rows = new ArrayList<>();
        int[] lines = {0};
        String cc = costCenterId == null ? null : costCenterId.toString();
        for (Map<String, Object> a : jdbc.queryForList("""
                SELECT a.id, a.code, a.name, a.normal_balance FROM chart_of_accounts a
                WHERE a.postable AND (?::uuid IS NULL OR a.id = ?::uuid)
                  AND EXISTS (SELECT 1 FROM journal_entry_lines l WHERE l.account_id = a.id AND l.accounting_date <= ?
                              AND (?::uuid IS NULL OR l.cost_center_id = ?::uuid))
                ORDER BY a.code""", accountId, accountId, Date.valueOf(to), cc, cc)) {
            if (lines[0] >= rowLimit) {
                break;
            }
            boolean debitNormal = "DEBIT".equals(a.get("normal_balance"));
            ledgerSection(rows, lines, (String) a.get("code") + " " + a.get("name"), debitNormal,
                    "l.account_id = ? AND (?::uuid IS NULL OR l.cost_center_id = ?::uuid)", new Object[] {a.get("id"), cc, cc}, from, to);
        }
        List<String> notes = new ArrayList<>(List.of("Balances are shown in each account's normal-balance sign (debit or credit)."));
        if (costCenterId != null) {
            notes.add("Filtered to one cost centre: opening and running balances are for that cost centre only.");
        }
        if (lines[0] >= rowLimit) {
            notes.add("Stopped at " + rowLimit + " lines (cas.reports.ledger-row-limit); narrow the date range or choose an account.");
        }
        notes.add(generated(by, at));
        return new Rendered(new ReportTable("General ledger", List.of(ORG, "From " + from + " to " + to), "Account / date",
                LEDGER_COLUMNS, rows, notes), "general_ledger_" + from + "_" + to);
    }

    /** The same per subsidiary account (customer, supplier, employee or other), within its control accounts. */
    private Rendered subsidiaryLedger(LocalDate from, LocalDate to, UUID subsidiaryAccountId, String slType, String by, String at) {
        List<ReportTable.Row> rows = new ArrayList<>();
        int[] lines = {0};
        for (Map<String, Object> s : jdbc.queryForList("""
                SELECT s.id, s.code, s.name, s.sl_type FROM subsidiary_ledger_accounts s
                WHERE (?::uuid IS NULL OR s.id = ?::uuid) AND (?::text IS NULL OR s.sl_type = ?::text)
                  AND EXISTS (SELECT 1 FROM journal_entry_lines l WHERE l.subsidiary_account_id = s.id AND l.accounting_date <= ?)
                ORDER BY s.sl_type, s.code""", subsidiaryAccountId, subsidiaryAccountId, slType, slType, Date.valueOf(to))) {
            if (lines[0] >= rowLimit) {
                break;
            }
            // Suppliers' balances are credits; customers', employees' and others' are debits.
            boolean debitNormal = !"SUPPLIER".equals(s.get("sl_type"));
            ledgerSection(rows, lines, s.get("sl_type") + " " + s.get("code") + " " + s.get("name"), debitNormal, "l.subsidiary_account_id = ?",
                    new Object[] {s.get("id")}, from, to);
        }
        List<String> notes = new ArrayList<>(List.of("Supplier balances are shown as credits, other subsidiary balances as debits."));
        if (lines[0] >= rowLimit) {
            notes.add("Stopped at " + rowLimit + " lines (cas.reports.ledger-row-limit); narrow the date range or choose an account.");
        }
        notes.add(generated(by, at));
        return new Rendered(new ReportTable("Subsidiary ledger", List.of(ORG, "From " + from + " to " + to), "Subsidiary account / date",
                LEDGER_COLUMNS, rows, notes), "subsidiary_ledger_" + from + "_" + to);
    }

    private static final List<String> LEDGER_COLUMNS = List.of("JEV", "Description", "Cost centre", "Debit", "Credit", "Balance");

    private void ledgerSection(List<ReportTable.Row> rows, int[] lines, String heading, boolean debitNormal, String where, Object[] args,
            LocalDate from, LocalDate to) {
        String sign = debitNormal ? "l.debit - l.credit" : "l.credit - l.debit";
        Object[] openingArgs = append(args, Date.valueOf(from));
        BigDecimal opening = Money.of(jdbc.queryForObject("SELECT coalesce(sum(" + sign + "), 0) FROM journal_entry_lines l WHERE " + where
                + " AND l.accounting_date < ?", BigDecimal.class, openingArgs));
        rows.add(new ReportTable.Row(heading, 0, true, Arrays.asList(null, "Opening balance", null, null, null, opening)));
        BigDecimal[] run = {opening, BigDecimal.ZERO, BigDecimal.ZERO};
        jdbc.query("SELECT l.accounting_date, e.entry_number, coalesce(l.description, e.description) AS description, c.code AS cc, l.debit, "
                + "l.credit FROM journal_entry_lines l JOIN journal_entries e ON e.id = l.entry_id LEFT JOIN cost_centers c ON c.id = l.cost_center_id "
                + "WHERE " + where + " AND l.accounting_date BETWEEN ? AND ? ORDER BY l.accounting_date, e.entry_number, l.line_no LIMIT ?", rs -> {
            BigDecimal d = rs.getBigDecimal("debit");
            BigDecimal c = rs.getBigDecimal("credit");
            run[0] = run[0].add(debitNormal ? d.subtract(c) : c.subtract(d));
            run[1] = run[1].add(d);
            run[2] = run[2].add(c);
            lines[0]++;
            rows.add(new ReportTable.Row(rs.getDate("accounting_date").toString(), 1, false, Arrays.asList(rs.getString("entry_number"),
                    rs.getString("description"), rs.getString("cc"), d.signum() == 0 ? null : d, c.signum() == 0 ? null : c, Money.of(run[0]))));
        }, append(append(append(args, Date.valueOf(from)), Date.valueOf(to)), Math.max(0, rowLimit - lines[0])));
        rows.add(new ReportTable.Row("Closing balance", 1, true, Arrays.asList(null, null, null, Money.of(run[1]), Money.of(run[2]), Money.of(run[0]))));
    }

    private Rendered dailyCollectionReport(LocalDate date, String by, String at) {
        DailyCollectionReport r = treasury.dailyCollections(date, null);
        List<ReportTable.Row> rows = new ArrayList<>();
        for (DcrLine l : r.lines()) {
            rows.add(new ReportTable.Row(l.receiptNumber(), 0, false, Arrays.asList(l.customerName(), l.paymentMethod(), l.paymentReference(),
                    l.status(), l.depositNumber(), "CANCELLED".equals(l.status()) ? null : l.amount(), "CANCELLED".equals(l.status()) ? null : l.taxWithheld())));
        }
        for (MethodTotal m : r.totals()) {
            rows.add(new ReportTable.Row("Total " + m.paymentMethod().toLowerCase().replace('_', ' ') + " (" + m.count() + ")", 0, true,
                    Arrays.asList(null, null, null, null, null, m.amount(), null)));
        }
        rows.add(new ReportTable.Row("Total collected", 0, true, Arrays.asList(null, null, null, null, null, r.totalCollected(), r.totalTaxWithheld())));
        List<String> notes = new ArrayList<>();
        notes.add("Receipts " + (r.firstNumber() == null ? "none" : r.firstNumber() + " to " + r.lastNumber()) + "; " + r.cancelled()
                + " cancelled; undeposited at report time: " + Money.of(r.undeposited()).toPlainString() + ".");
        if (r.note() != null) {
            notes.add(r.note());
        }
        notes.add(generated(by, at));
        return new Rendered(new ReportTable("Daily collection report", List.of(ORG, "Collections of " + date), "Receipt",
                List.of("Payor", "Method", "Reference", "Status", "Deposit", "Amount", "Tax withheld"), rows, notes), "daily_collection_report_" + date);
    }

    private Rendered bankReconciliationStatement(UUID id, String by, String at) {
        ReconciliationStatement s = reconciliations.statement(id);
        ReconciliationFigures f = s.figures();
        List<ReportTable.Row> rows = new ArrayList<>();
        rows.add(amountRow("Balance per bank statement", 0, true, f.bankBalance()));
        rows.add(amountRow("Add: deposits in transit", 1, false, f.depositsInTransit()));
        rows.add(amountRow("Less: outstanding checks", 1, false, f.outstandingChecks()));
        rows.add(amountRow("Adjusted bank balance", 0, true, f.adjustedBank()));
        rows.add(amountRow("Balance per books", 0, true, f.bookBalance()));
        rows.add(amountRow("Add: credits recorded by the bank, not yet in the books", 1, false, f.unrecordedCredits()));
        rows.add(amountRow("Less: debits recorded by the bank, not yet in the books", 1, false, f.unrecordedDebits()));
        rows.add(amountRow("Adjusted book balance", 0, true, f.adjustedBook()));
        rows.add(amountRow("Difference", 0, true, f.difference()));
        rows.add(new ReportTable.Row("Outstanding items in the books", 0, true, Arrays.asList((Object) null)));
        for (LedgerItem l : s.unmatchedLedgerLines()) {
            rows.add(new ReportTable.Row(l.accountingDate() + " " + l.entryNumber() + " " + nz(l.description()), 1, false, Arrays.asList(l.amount())));
        }
        rows.add(new ReportTable.Row("Items on the bank statement not yet in the books", 0, true, Arrays.asList((Object) null)));
        for (StatementLineView l : s.unmatchedStatementLines()) {
            rows.add(new ReportTable.Row(l.valueDate() + " " + nz(l.description()) + " " + nz(l.bankReference()), 1, false, Arrays.asList(l.amount())));
        }
        return new Rendered(new ReportTable("Bank reconciliation statement", List.of(ORG, s.bankAccountCode() + " " + s.bankName(),
                "As of " + s.asOfDate() + " (statement from " + s.periodStart() + ")", "Status: " + s.status()), "", List.of("Amount"), rows,
                List.of("Prepared by " + s.preparedBy() + (s.approvedBy() == null ? "; not yet approved." : "; approved by " + s.approvedBy() + "."),
                        generated(by, at))), "bank_reconciliation_" + s.bankAccountCode() + "_" + s.asOfDate());
    }

    private Rendered withholding(int year, int quarter, String by, String at) {
        WithholdingSchedule w = ap.withholding(year, quarter);
        List<ReportTable.Row> rows = new ArrayList<>();
        BigDecimal base = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        for (WithholdingRow r : w.rows()) {
            rows.add(new ReportTable.Row(r.supplierName(), 0, false, Arrays.asList(r.supplierTin(), r.taxCode(), r.month1Base(), r.month2Base(),
                    r.month3Base(), r.totalBase(), r.taxWithheld())));
            base = base.add(r.totalBase());
            tax = tax.add(r.taxWithheld());
        }
        rows.add(new ReportTable.Row("Total", 0, true, Arrays.asList(null, null, null, null, null, Money.of(base), Money.of(tax))));
        return new Rendered(new ReportTable("Expanded withholding tax schedule", List.of(ORG, year + " Q" + quarter), "Payee",
                List.of("TIN", "Tax code", "Month 1 base", "Month 2 base", "Month 3 base", "Total base", "Tax withheld"), rows,
                List.of(w.note(), generated(by, at))), "withholding_schedule_" + year + "_Q" + quarter);
    }

    // ------------------------------------------------------------------ helpers

    private static ReportTable.Row amountRow(String label, int indent, boolean bold, BigDecimal amount) {
        return new ReportTable.Row(label, indent, bold, Arrays.asList(amount));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static Object[] append(Object[] a, Object v) {
        Object[] out = Arrays.copyOf(a, a.length + 1);
        out[a.length] = v;
        return out;
    }

    private static String generated(String by, String at) {
        return "Generated by " + by + " on " + at + ".";
    }

    private static ApiException bad(String m) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, m);
    }

    private static LocalDate date(Map<String, String> p, String key) {
        String v = p.get(key);
        if (v == null) {
            throw bad("parameters." + key + " must be a date (yyyy-MM-dd).");
        }
        try {
            return LocalDate.parse(v);
        } catch (DateTimeParseException e) {
            throw bad("parameters." + key + " must be a date (yyyy-MM-dd).");
        }
    }

    private static UUID uuid(Map<String, String> p, String key) {
        UUID u = optionalUuid(p, key);
        if (u == null) {
            throw bad("parameters." + key + " is required.");
        }
        return u;
    }

    private static UUID optionalUuid(Map<String, String> p, String key) {
        String v = p.get(key);
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(v);
        } catch (IllegalArgumentException e) {
            throw bad("parameters." + key + " is not a valid id.");
        }
    }

    private static int integer(Map<String, String> p, String key) {
        String v = p.get(key);
        if (v == null) {
            throw bad("parameters." + key + " must be a whole number.");
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw bad("parameters." + key + " must be a whole number.");
        }
    }
}
