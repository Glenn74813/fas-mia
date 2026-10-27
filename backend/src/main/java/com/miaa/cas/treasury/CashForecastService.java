package com.miaa.cas.treasury;

import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.money.Money;
import com.miaa.cas.treasury.TreasuryDtos.CashPosition;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cash-flow and expenditure forecast (TOR §3.5.1, Annex B.7), by week or month, computed from source records:
 * <ul>
 * <li>opening balance: the cash position (cash on hand and bank book balances) at the day before the forecast starts;</li>
 * <li>inflows: posted billings (invoices and debit memos) still unsettled, on their due dates;</li>
 * <li>outflows: disbursement vouchers approved but not yet posted as paid, on their payment date or else the earliest
 * due date of the invoices they pay; posted supplier invoices still unpaid and not on a live DV, on their due dates;</li>
 * <li>expenditure forecast: the budget not yet used or reserved, spread evenly over the rest of each fiscal year with an
 * approved budget. It is an estimate and can be left out.</li>
 * </ul>
 * Anything due before the forecast starts is overdue and falls in the first bucket, and is also reported separately.
 */
@Service
public class CashForecastService {

    public static final int MAX_BUCKETS = 53;
    private static final ZoneId MANILA = ZoneId.of("Asia/Manila");
    private static final int MAX_ITEMS = 1000;

    public enum Granularity { WEEK, MONTH }

    public record Bucket(LocalDate start, LocalDate end, BigDecimal opening, BigDecimal receivables, BigDecimal vouchers,
            BigDecimal payables, BigDecimal budget, BigDecimal net, BigDecimal closing) {
    }

    public record Item(String source, UUID id, String reference, String party, LocalDate date, BigDecimal amount, int bucket,
            boolean overdue) {
    }

    public record Forecast(LocalDate from, LocalDate to, Granularity granularity, boolean includeBudget, BigDecimal openingBalance,
            CashPosition position, BigDecimal overdueReceivables, BigDecimal overduePayables, List<Bucket> buckets, List<Item> items,
            boolean itemsTruncated, BigDecimal lowestClosing, LocalDate lowestClosingOn, List<String> notes) {
    }

    private final JdbcTemplate jdbc;
    private final TreasuryReportService reports;
    private final Clock clock;

    public CashForecastService(JdbcTemplate jdbc, TreasuryReportService reports, Clock clock) {
        this.jdbc = jdbc;
        this.reports = reports;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Forecast forecast(LocalDate from, Granularity granularity, int periods, boolean includeBudget) {
        if (periods < 1 || periods > MAX_BUCKETS) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "periods must be between 1 and " + MAX_BUCKETS + ".");
        }
        LocalDate start = from != null ? from : LocalDate.now(clock.withZone(MANILA));
        List<LocalDate[]> ranges = ranges(start, granularity, periods);
        LocalDate end = ranges.get(ranges.size() - 1)[1];

        CashPosition position = reports.cashPosition(start.minusDays(1));
        BigDecimal opening = Money.of(position.cashOnHandBook().add(position.totalBank()));

        List<Item> items = new ArrayList<>();
        items.addAll(jdbc.query("""
                SELECT b.id, b.document_number, c.name, b.due_date, b.gross_amount - b.settled_amount AS open
                FROM billing_documents b JOIN customers c ON c.id = b.customer_id
                WHERE b.kind IN ('INVOICE', 'DEBIT_MEMO') AND b.status = 'POSTED' AND b.gross_amount > b.settled_amount
                  AND b.due_date <= ?
                ORDER BY b.due_date, b.document_number""",
                (rs, i) -> item("RECEIVABLE", rs, start, ranges), Date.valueOf(end)));
        items.addAll(jdbc.query("""
                SELECT d.id, d.dv_number, s.name,
                       coalesce(d.payment_date, (SELECT min(i.due_date) FROM disbursement_voucher_lines l
                                                 JOIN supplier_invoices i ON i.id = l.invoice_id WHERE l.dv_id = d.id), ?) AS due,
                       d.amount AS open
                FROM disbursement_vouchers d JOIN suppliers s ON s.id = d.supplier_id
                WHERE d.status IN ('APPROVED', 'PAYMENT_INSTRUCTED', 'PAID') AND d.amount > 0
                ORDER BY due, d.dv_number""",
                (rs, i) -> item("VOUCHER", rs, start, ranges), Date.valueOf(start)).stream()
                .filter(it -> !it.date().isAfter(end)).toList());
        items.addAll(jdbc.query("""
                SELECT i.id, i.invoice_number, s.name, i.due_date, x.open
                FROM supplier_invoices i JOIN suppliers s ON s.id = i.supplier_id
                CROSS JOIN LATERAL (SELECT i.payable_amount - i.paid_amount - coalesce((
                        SELECT sum(l.amount) FROM disbursement_voucher_lines l JOIN disbursement_vouchers d ON d.id = l.dv_id
                        WHERE l.invoice_id = i.id AND d.status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'PAYMENT_INSTRUCTED', 'PAID', 'RETURNED')
                    ), 0) AS open) x
                WHERE i.status = 'POSTED' AND x.open > 0 AND i.due_date <= ?
                ORDER BY i.due_date, i.invoice_number""",
                (rs, i) -> item("PAYABLE", rs, start, ranges), Date.valueOf(end)));
        // DVs still being prepared or approved pay invoices counted above as payables, so they are not counted again.

        int n = ranges.size();
        BigDecimal[] in = zeros(n);
        BigDecimal[] dv = zeros(n);
        BigDecimal[] ap = zeros(n);
        BigDecimal overdueIn = BigDecimal.ZERO;
        BigDecimal overdueOut = BigDecimal.ZERO;
        for (Item it : items) {
            switch (it.source()) {
                case "RECEIVABLE" -> in[it.bucket()] = in[it.bucket()].add(it.amount());
                case "VOUCHER" -> dv[it.bucket()] = dv[it.bucket()].add(it.amount());
                default -> ap[it.bucket()] = ap[it.bucket()].add(it.amount());
            }
            if (it.overdue()) {
                if ("RECEIVABLE".equals(it.source())) {
                    overdueIn = overdueIn.add(it.amount());
                } else {
                    overdueOut = overdueOut.add(it.amount());
                }
            }
        }
        BigDecimal[] budget = includeBudget ? budget(start, end, ranges) : zeros(n);

        List<Bucket> buckets = new ArrayList<>();
        BigDecimal running = opening;
        BigDecimal lowest = null;
        LocalDate lowestOn = null;
        for (int b = 0; b < n; b++) {
            BigDecimal net = Money.of(in[b].subtract(dv[b]).subtract(ap[b]).subtract(budget[b]));
            BigDecimal closing = Money.of(running.add(net));
            buckets.add(new Bucket(ranges.get(b)[0], ranges.get(b)[1], running, Money.of(in[b]), Money.of(dv[b]), Money.of(ap[b]),
                    Money.of(budget[b]), net, closing));
            if (lowest == null || closing.compareTo(lowest) < 0) {
                lowest = closing;
                lowestOn = ranges.get(b)[1];
            }
            running = closing;
        }

        List<String> notes = new ArrayList<>();
        notes.add("Receivables are expected on their due dates and payables paid on theirs; actual timing will differ.");
        if (includeBudget) {
            notes.add("Budgeted spending is the approved budget not yet used or reserved, spread evenly over the rest of the fiscal "
                    + "year. It includes any non-cash items budgeted (for example depreciation), so it overstates cash outflows "
                    + "where those are budgeted.");
        }
        if (position.undepositedCollections().signum() > 0) {
            notes.add("Collections not yet deposited (" + money(position.undepositedCollections()) + ") are part of cash on hand.");
        }
        items.sort((a, b) -> a.date().compareTo(b.date()));
        boolean truncated = items.size() > MAX_ITEMS;
        return new Forecast(start, end, granularity, includeBudget, opening, position, Money.of(overdueIn), Money.of(overdueOut),
                buckets, truncated ? items.subList(0, MAX_ITEMS) : items, truncated, lowest, lowestOn, notes);
    }

    /** Consecutive buckets: weeks run Monday to Sunday and months by calendar month; the first is cut to start on {@code start}. */
    static List<LocalDate[]> ranges(LocalDate start, Granularity g, int periods) {
        List<LocalDate[]> out = new ArrayList<>();
        LocalDate s = start;
        for (int i = 0; i < periods; i++) {
            LocalDate e = g == Granularity.WEEK ? s.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
                    : s.with(TemporalAdjusters.lastDayOfMonth());
            out.add(new LocalDate[] {s, e});
            s = e.plusDays(1);
        }
        return out;
    }

    private static int bucketOf(LocalDate date, LocalDate start, List<LocalDate[]> ranges) {
        if (date.isBefore(start)) {
            return 0;
        }
        for (int i = 0; i < ranges.size(); i++) {
            if (!date.isAfter(ranges.get(i)[1])) {
                return i;
            }
        }
        return ranges.size() - 1;
    }

    private static Item item(String source, java.sql.ResultSet rs, LocalDate start, List<LocalDate[]> ranges) throws java.sql.SQLException {
        LocalDate date = rs.getDate(4).toLocalDate();
        return new Item(source, rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), date, Money.of(rs.getBigDecimal(5)),
                bucketOf(date, start, ranges), date.isBefore(start));
    }

    /** The unused, unreserved approved budget of each fiscal year, spread by day over the part of the year still ahead. */
    private BigDecimal[] budget(LocalDate start, LocalDate end, List<LocalDate[]> ranges) {
        BigDecimal[] out = zeros(ranges.size());
        List<Object[]> years = jdbc.query("""
                SELECT fy.start_date, fy.end_date, coalesce(sum(greatest(ba.amount
                        - coalesce((SELECT sum(r.amount) FROM budget_reservations r WHERE r.allocation_id = ba.id AND r.status = 'ACTIVE'), 0)
                        - coalesce((SELECT sum(l.debit - l.credit) FROM journal_entry_lines l JOIN accounting_periods p ON p.id = l.period_id
                                    WHERE l.account_id = ba.account_id AND p.fiscal_year_id = fy.id
                                      AND (ba.cost_center_id IS NULL OR l.cost_center_id = ba.cost_center_id)), 0), 0)), 0)
                FROM budgets b JOIN fiscal_years fy ON fy.id = b.fiscal_year_id JOIN budget_allocations ba ON ba.budget_id = b.id
                WHERE b.status = 'APPROVED' AND fy.end_date >= ? AND fy.start_date <= ?
                GROUP BY fy.start_date, fy.end_date""",
                (rs, i) -> new Object[] {rs.getDate(1).toLocalDate(), rs.getDate(2).toLocalDate(), rs.getBigDecimal(3)},
                Date.valueOf(start), Date.valueOf(end));
        for (Object[] y : years) {
            LocalDate from = ((LocalDate) y[0]).isBefore(start) ? start : (LocalDate) y[0];
            LocalDate to = (LocalDate) y[1];
            BigDecimal remaining = (BigDecimal) y[2];
            long days = ChronoUnit.DAYS.between(from, to) + 1;
            if (remaining.signum() <= 0 || days <= 0) {
                continue;
            }
            BigDecimal perDay = remaining.divide(BigDecimal.valueOf(days), 10, RoundingMode.HALF_UP);
            for (int b = 0; b < ranges.size(); b++) {
                LocalDate s = ranges.get(b)[0].isBefore(from) ? from : ranges.get(b)[0];
                LocalDate e = ranges.get(b)[1].isAfter(to) ? to : ranges.get(b)[1];
                if (!e.isBefore(s)) {
                    out[b] = out[b].add(perDay.multiply(BigDecimal.valueOf(ChronoUnit.DAYS.between(s, e) + 1)));
                }
            }
        }
        return out;
    }

    private static BigDecimal[] zeros(int n) {
        BigDecimal[] a = new BigDecimal[n];
        java.util.Arrays.fill(a, BigDecimal.ZERO);
        return a;
    }

    private static String money(BigDecimal v) {
        return String.format(java.util.Locale.ROOT, "₱%,.2f", v);
    }
}
