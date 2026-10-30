package com.miaa.cas.audit.anomaly;

import com.miaa.cas.notifications.AlertService;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Suspicious-activity detection (TOR §2.2.3). Explainable rules over posted and in-process records, run every hour over
 * the last {@code cas.anomalies.lookback-days} days (and on demand). A finding is written once (its fingerprint) with the
 * document, amount, the reason in plain words and the people involved; new HIGH and MEDIUM findings alert the reviewers
 * (ANOMALY_REVIEW) in the app and by e-mail. Rules:
 * <ul>
 * <li>DUPLICATE_INVOICE (HIGH): two live invoices from one supplier with the same payable amount within 30 days;</li>
 * <li>BANK_CHANGE_PAYMENT (HIGH): a payment within 7 days after the supplier's bank account was entered or verified;</li>
 * <li>NEAR_THRESHOLD (MEDIUM): an amount within 5 % below an approval step or approval limit of its document type;</li>
 * <li>BACKDATED_ENTRY (MEDIUM): an entry posted more than 45 days after its accounting date;</li>
 * <li>BENFORD (MEDIUM): a period whose first-digit distribution deviates from Benford's law (Nigrini's MAD over 0.015,
 * at least 300 amounts);</li>
 * <li>OFF_HOURS_POSTING (LOW): posted at night, on a weekend or on a business holiday (Manila time);</li>
 * <li>ROUND_AMOUNT (LOW): a manual entry of at least {@code round-amount-min} in exact multiples of 10,000.</li>
 * </ul>
 * Findings are signals for review, not conclusions; the thresholds are proposals for AVTECH's internal audit to tune.
 */
@Service
public class AnomalyDetectionService {

    private static final Logger log = LoggerFactory.getLogger(AnomalyDetectionService.class);
    static final ZoneId MANILA = ZoneId.of("Asia/Manila");
    static final double BENFORD_MAD_LIMIT = 0.015;
    static final int BENFORD_MIN_COUNT = 300;
    private static final String INSERT = """
            INSERT INTO anomaly_findings (id, rule_code, severity, fingerprint, document_type, document_id, reference, amount,
                occurred_on, title, detail, involved_users, detected_at)
            """;

    public record RunResult(int high, int medium, int low) {
        public int total() {
            return high + medium + low;
        }
    }

    private final JdbcTemplate jdbc;
    private final AlertService alerts;
    private final Clock clock;
    private final boolean enabled;
    private final int lookbackDays;
    private final BigDecimal roundAmountMin;

    public AnomalyDetectionService(JdbcTemplate jdbc, AlertService alerts, Clock clock,
            @Value("${cas.anomalies.enabled:true}") boolean enabled, @Value("${cas.anomalies.lookback-days:90}") int lookbackDays,
            @Value("${cas.anomalies.round-amount-min:100000}") BigDecimal roundAmountMin) {
        this.jdbc = jdbc;
        this.alerts = alerts;
        this.clock = clock;
        this.enabled = enabled;
        this.lookbackDays = lookbackDays;
        this.roundAmountMin = roundAmountMin;
    }

    @Scheduled(cron = "${cas.anomalies.cron:0 20 * * * *}", zone = "Asia/Manila")
    public void scheduled() {
        if (enabled) {
            try {
                run();
            } catch (RuntimeException e) {
                log.error("Anomaly detection failed", e);
            }
        }
    }

    /** Runs every rule over the lookback window; returns how many findings are new, and alerts on HIGH or MEDIUM ones. */
    @Transactional
    public RunResult run() {
        Instant now = Instant.now(clock);
        Date since = Date.valueOf(LocalDate.now(clock.withZone(MANILA)).minusDays(lookbackDays));
        Timestamp at = Timestamp.from(now);
        int high = duplicateInvoices(since, at) + bankChangeBeforePayment(since, at);
        int medium = nearThreshold(since, at) + backdated(since, at) + benford(since, at);
        int low = offHours(since, at) + roundAmounts(since, at);
        RunResult r = new RunResult(high, medium, low);
        if (high + medium > 0) {
            alerts.raise(new AlertService.Alert("anomaly:new", "New anomaly findings to review",
                    high + " high and " + medium + " medium severity finding(s) were detected" + (low > 0 ? " (and " + low + " low)" : "")
                            + ". Review them under Audit → Anomalies.", "/audit/anomalies", "ANOMALY_REVIEW", Duration.ofMinutes(30), true));
        }
        if (r.total() > 0) {
            log.info("Anomaly detection: {} new finding(s) ({} high, {} medium, {} low)", r.total(), high, medium, low);
        }
        return r;
    }

    private int duplicateInvoices(Date since, Timestamp at) {
        return jdbc.update(INSERT + """
                SELECT gen_random_uuid(), 'DUPLICATE_INVOICE', 'HIGH',
                    'DUPLICATE_INVOICE:' || a.id || ':' || b.id, 'AP_INVOICE', b.id, b.invoice_number, b.payable_amount, b.invoice_date,
                    'Possible duplicate invoice from ' || s.name,
                    'Invoice ' || b.invoice_number || ' of ' || b.invoice_date || ' has the same payable amount, '
                        || to_char(b.payable_amount, 'FM999,999,999,990.00') || ', as invoice ' || a.invoice_number || ' of '
                        || a.invoice_date || ' from the same supplier, ' || (b.invoice_date - a.invoice_date) || ' day(s) apart.',
                    ARRAY[a.prepared_by, b.prepared_by], ?
                FROM supplier_invoices a
                JOIN supplier_invoices b ON b.supplier_id = a.supplier_id AND b.payable_amount = a.payable_amount
                    AND (a.invoice_date, a.id) < (b.invoice_date, b.id) AND b.invoice_date - a.invoice_date <= 30
                JOIN suppliers s ON s.id = b.supplier_id
                WHERE a.status NOT IN ('DRAFT', 'REJECTED', 'CANCELLED') AND b.status NOT IN ('DRAFT', 'REJECTED', 'CANCELLED')
                  AND b.payable_amount > 0 AND b.invoice_date >= ?
                ON CONFLICT (fingerprint) DO NOTHING""", at, since);
    }

    private int bankChangeBeforePayment(Date since, Timestamp at) {
        return jdbc.update(INSERT + """
                SELECT gen_random_uuid(), 'BANK_CHANGE_PAYMENT', 'HIGH', 'BANK_CHANGE_PAYMENT:' || d.id || ':' || sb.id,
                    'DISBURSEMENT_VOUCHER', d.id, d.dv_number, d.amount, d.payment_date,
                    'Payment soon after a bank-detail change: ' || s.name,
                    'DV ' || coalesce(d.dv_number, '(unnumbered)') || ' pays ' || s.name || ' on ' || d.payment_date || ', '
                        || (d.payment_date - CAST(greatest(sb.entered_at, coalesce(sb.verified_at, sb.entered_at)) AT TIME ZONE 'Asia/Manila' AS DATE))
                        || ' day(s) after bank account ****' || sb.account_number_last4 || ' (' || sb.bank_name || ') was entered or verified.',
                    ARRAY_REMOVE(ARRAY[d.prepared_by, d.instructed_by, sb.entered_by, sb.verified_by], NULL), ?
                FROM disbursement_vouchers d
                JOIN suppliers s ON s.id = d.supplier_id
                JOIN supplier_bank_accounts sb ON sb.supplier_id = d.supplier_id
                WHERE d.payment_date IS NOT NULL AND d.payment_date >= ? AND d.status IN ('PAYMENT_INSTRUCTED', 'PAID', 'POSTED')
                  AND CAST(greatest(sb.entered_at, coalesce(sb.verified_at, sb.entered_at)) AT TIME ZONE 'Asia/Manila' AS DATE)
                      BETWEEN d.payment_date - 7 AND d.payment_date
                ON CONFLICT (fingerprint) DO NOTHING""", at, since);
    }

    /** Thresholds per document type: approval steps with a minimum amount and approval limits. */
    private int nearThreshold(Date since, Timestamp at) {
        Map<String, List<BigDecimal>> thresholds = new LinkedHashMap<>();
        jdbc.query("SELECT 'JOURNAL_VOUCHER', min_amount FROM approval_workflow_steps WHERE min_amount > 0 "
                + "UNION SELECT document_type, min_amount FROM document_workflow_steps WHERE min_amount > 0 "
                + "UNION SELECT document_type, max_amount FROM approval_limits WHERE max_amount > 0",
                rs -> {
                    thresholds.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getBigDecimal(2));
                });
        Map<String, String> sources = Map.of(
                "JOURNAL_VOUCHER", "SELECT id, voucher_number AS ref, total_debit AS amount, accounting_date AS day, prepared_by FROM journal_vouchers "
                        + "WHERE status NOT IN ('DRAFT', 'CANCELLED', 'REJECTED') AND accounting_date >= ?",
                "AP_INVOICE", "SELECT id, invoice_number AS ref, payable_amount AS amount, invoice_date AS day, prepared_by FROM supplier_invoices "
                        + "WHERE status NOT IN ('DRAFT', 'CANCELLED', 'REJECTED') AND invoice_date >= ?",
                "DISBURSEMENT_VOUCHER", "SELECT id, dv_number AS ref, amount, CAST(created_at AT TIME ZONE 'Asia/Manila' AS DATE) AS day, prepared_by "
                        + "FROM disbursement_vouchers WHERE status NOT IN ('DRAFT', 'CANCELLED', 'REJECTED') AND created_at >= ?");
        int added = 0;
        for (Map.Entry<String, List<BigDecimal>> t : thresholds.entrySet()) {
            String sql = sources.get(t.getKey());
            if (sql == null) {
                continue;
            }
            for (BigDecimal limit : t.getValue()) {
                BigDecimal floor = limit.multiply(new BigDecimal("0.95"));
                for (Map<String, Object> d : jdbc.queryForList("SELECT * FROM (" + sql + ") x WHERE amount >= ? AND amount < ?",
                        since, floor, limit)) {
                    added += jdbc.update(INSERT + "VALUES (gen_random_uuid(), 'NEAR_THRESHOLD', 'MEDIUM', ?, ?, ?, ?, ?, ?, ?, ?, "
                            + "ARRAY_REMOVE(ARRAY[CAST(? AS VARCHAR)], NULL), ?) ON CONFLICT (fingerprint) DO NOTHING",
                            "NEAR_THRESHOLD:" + d.get("id") + ":" + limit.toPlainString(), t.getKey(), d.get("id"), d.get("ref"), d.get("amount"),
                            d.get("day"), "Amount just below an approval threshold",
                            label(t.getKey()) + " " + (d.get("ref") == null ? "(unnumbered)" : d.get("ref")) + " is "
                                    + money((BigDecimal) d.get("amount")) + ", within 5 % below the threshold of " + money(limit)
                                    + " that would require another approval.",
                            d.get("prepared_by"), at);
                }
            }
        }
        return added;
    }

    private int backdated(Date since, Timestamp at) {
        return jdbc.update(INSERT + """
                SELECT gen_random_uuid(), 'BACKDATED_ENTRY', 'MEDIUM', 'BACKDATED_ENTRY:' || e.id, 'JOURNAL_ENTRY', e.id, e.entry_number,
                    e.total_debit, e.accounting_date, 'Entry posted long after its accounting date',
                    'Entry ' || e.entry_number || ' is dated ' || e.accounting_date || ' but was posted on '
                        || CAST(e.posted_at AT TIME ZONE 'Asia/Manila' AS DATE) || ', '
                        || (CAST(e.posted_at AT TIME ZONE 'Asia/Manila' AS DATE) - e.accounting_date) || ' days later.',
                    ARRAY[e.prepared_by, e.posted_by], ?
                FROM journal_entries e
                WHERE e.posted_at >= ? AND CAST(e.posted_at AT TIME ZONE 'Asia/Manila' AS DATE) - e.accounting_date > 45
                ON CONFLICT (fingerprint) DO NOTHING""", at, since);
    }

    private int offHours(Date since, Timestamp at) {
        return jdbc.update(INSERT + """
                SELECT gen_random_uuid(), 'OFF_HOURS_POSTING', 'LOW', 'OFF_HOURS_POSTING:' || e.id, 'JOURNAL_ENTRY', e.id, e.entry_number,
                    e.total_debit, e.accounting_date, 'Posted outside business hours',
                    'Entry ' || e.entry_number || ' was posted by ' || e.posted_by || ' on '
                        || to_char(e.posted_at AT TIME ZONE 'Asia/Manila', 'Dy YYYY-MM-DD HH24:MI') || ' Manila time'
                        || CASE WHEN h.holiday_date IS NOT NULL THEN ' (' || h.name || ')' ELSE '' END || '.',
                    ARRAY[e.prepared_by, e.posted_by], ?
                FROM journal_entries e
                LEFT JOIN business_holidays h ON h.holiday_date = CAST(e.posted_at AT TIME ZONE 'Asia/Manila' AS DATE)
                WHERE e.posted_at >= ? AND e.posted_by <> 'system'
                  AND (EXTRACT(ISODOW FROM e.posted_at AT TIME ZONE 'Asia/Manila') IN (6, 7)
                       OR EXTRACT(HOUR FROM e.posted_at AT TIME ZONE 'Asia/Manila') < 6
                       OR EXTRACT(HOUR FROM e.posted_at AT TIME ZONE 'Asia/Manila') >= 20
                       OR h.holiday_date IS NOT NULL)
                ON CONFLICT (fingerprint) DO NOTHING""", at, since);
    }

    private int roundAmounts(Date since, Timestamp at) {
        return jdbc.update(INSERT + """
                SELECT gen_random_uuid(), 'ROUND_AMOUNT', 'LOW', 'ROUND_AMOUNT:' || e.id, 'JOURNAL_ENTRY', e.id, e.entry_number,
                    e.total_debit, e.accounting_date, 'Large round-sum manual entry',
                    'Manual entry ' || e.entry_number || ' totals ' || to_char(e.total_debit, 'FM999,999,999,990.00')
                        || ', an exact multiple of 10,000.',
                    ARRAY[e.prepared_by, e.posted_by], ?
                FROM journal_entries e
                WHERE e.posted_at >= ? AND e.source_type = 'MANUAL' AND e.total_debit >= ? AND mod(e.total_debit, 10000) = 0
                ON CONFLICT (fingerprint) DO NOTHING""", at, since, roundAmountMin);
    }

    /** First-digit test per accounting period (Nigrini): mean absolute deviation from Benford's proportions. */
    private int benford(Date since, Timestamp at) {
        Map<UUID, long[]> counts = new LinkedHashMap<>();
        Map<UUID, String> names = new LinkedHashMap<>();
        jdbc.query("""
                SELECT p.id, p.name, CAST(left(CAST(floor(l.debit + l.credit) AS BIGINT)::text, 1) AS INT) AS digit, count(*) AS n
                FROM journal_entry_lines l JOIN journal_entries e ON e.id = l.entry_id JOIN accounting_periods p ON p.id = e.period_id
                WHERE p.end_date >= ? AND l.debit + l.credit >= 10
                GROUP BY p.id, p.name, digit""", rs -> {
                    UUID id = rs.getObject(1, UUID.class);
                    names.put(id, rs.getString(2));
                    counts.computeIfAbsent(id, k -> new long[10])[rs.getInt(3)] += rs.getLong(4);
                }, since);
        int added = 0;
        for (Map.Entry<UUID, long[]> e : counts.entrySet()) {
            Benford b = Benford.of(e.getValue());
            if (b.count() < BENFORD_MIN_COUNT || b.mad() <= BENFORD_MAD_LIMIT) {
                continue;
            }
            added += jdbc.update(INSERT + "VALUES (gen_random_uuid(), 'BENFORD', 'MEDIUM', ?, 'PERIOD', ?, ?, NULL, NULL, ?, ?, '{}', ?) "
                    + "ON CONFLICT (fingerprint) DO NOTHING",
                    "BENFORD:" + e.getKey(), e.getKey(), names.get(e.getKey()), "Unusual first-digit pattern in " + names.get(e.getKey()),
                    String.format(Locale.ROOT, "The first digits of %d amounts posted in %s deviate from Benford's law (mean absolute deviation "
                            + "%.4f, nonconformity above %.3f). Most over-represented: digit %d (%.1f %% against %.1f %% expected). Look for "
                            + "repeated amounts or invented figures.", b.count(), names.get(e.getKey()), b.mad(), BENFORD_MAD_LIMIT, b.worstDigit(),
                            100 * b.observed(b.worstDigit()), 100 * Benford.expected(b.worstDigit())), at);
        }
        return added;
    }

    /** First-digit proportions against Benford's law. */
    record Benford(long[] counts, long count, double mad) {

        static double expected(int digit) {
            return Math.log10(1 + 1.0 / digit);
        }

        static Benford of(long[] counts) {
            long n = 0;
            for (int d = 1; d <= 9; d++) {
                n += counts[d];
            }
            double sum = 0;
            for (int d = 1; d <= 9; d++) {
                sum += Math.abs((n == 0 ? 0 : (double) counts[d] / n) - expected(d));
            }
            return new Benford(counts, n, sum / 9);
        }

        double observed(int digit) {
            return count == 0 ? 0 : (double) counts[digit] / count;
        }

        int worstDigit() {
            int worst = 1;
            for (int d = 2; d <= 9; d++) {
                if (observed(d) - expected(d) > observed(worst) - expected(worst)) {
                    worst = d;
                }
            }
            return worst;
        }
    }

    private static String money(BigDecimal v) {
        return String.format(Locale.ROOT, "%,.2f", v);
    }

    private static String label(String type) {
        return switch (type) {
            case "JOURNAL_VOUCHER" -> "Voucher";
            case "AP_INVOICE" -> "Invoice";
            default -> "DV";
        };
    }
}
