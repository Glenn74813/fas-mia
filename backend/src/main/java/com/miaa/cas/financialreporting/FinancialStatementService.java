package com.miaa.cas.financialreporting;

import com.miaa.cas.common.money.Money;
import com.miaa.cas.financialreporting.ReportDtos.StatementLineResult;
import com.miaa.cas.financialreporting.ReportDtos.StatementResult;
import com.miaa.cas.financialreporting.ReportDtos.TemplateLine;
import com.miaa.cas.financialreporting.ReportDtos.TemplateView;
import com.miaa.cas.financialreporting.ReportDtos.UnclassifiedMovement;
import com.miaa.cas.financialreporting.ReportDtos.UnmappedAccount;
import com.miaa.cas.financialreporting.export.ReportTable;
import com.miaa.cas.generalledger.FiscalCalendarService;
import com.miaa.cas.generalledger.domain.AccountingPeriod;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Financial statements computed from posted ledger lines through a template. The statement of financial
 * position uses balances to date (real accounts) plus the results not yet closed to equity; the statement
 * of financial performance uses the fiscal year to date. Accounts whose statement line code is not in the
 * template are listed as unmapped, so an incomplete template can never silently drop amounts.
 *
 * <p>Movement statements (cash flow, changes in equity) cover the fiscal year to date. OPENING and CLOSING lines are
 * balances before the year and at the period end; a MOVEMENT line is the change in its accounts, classified by the
 * statement line codes of the other lines of each entry. Every entry balances, so the change of a group of accounts
 * splits exactly by counterpart code. Parts of a change that no line classifies are listed as unclassified.
 */
@Service
public class FinancialStatementService {

    private final StatementTemplateService templates;
    private final FiscalCalendarService calendar;
    private final JdbcTemplate jdbc;

    public FinancialStatementService(StatementTemplateService templates, FiscalCalendarService calendar, JdbcTemplate jdbc) {
        this.templates = templates;
        this.calendar = calendar;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public StatementResult generate(String templateIdOrCode, UUID periodId, boolean comparative) {
        TemplateView t = templates.get(templates.resolve(templateIdOrCode));
        AccountingPeriod period = calendar.period(periodId);
        AccountingPeriod prior = comparative ? priorYearPeriod(period) : null;
        boolean sfp = "SFP".equals(t.statementType());
        boolean movement = StatementTemplateService.MOVEMENT_STATEMENTS.contains(t.statementType());
        List<UnclassifiedMovement> unclassified = new ArrayList<>();
        Map<String, BigDecimal> cur = movement ? movementAmounts(t, period, unclassified) : amounts(t, period, sfp);
        Map<String, BigDecimal> cmp = prior == null ? null : movement ? movementAmounts(t, prior, new ArrayList<>()) : amounts(t, prior, sfp);
        List<StatementLineResult> lines = new ArrayList<>();
        for (TemplateLine l : t.lines()) {
            lines.add(new StatementLineResult(l.code(), l.label(), l.type(), l.indent(), "HEADER".equals(l.type()) ? null : cur.get(l.code()),
                    cmp == null || "HEADER".equals(l.type()) ? null : cmp.get(l.code())));
        }
        Boolean balanced = null;
        if (t.balanceCheck() != null) {
            String[] p = t.balanceCheck().split("=");
            balanced = cur.get(p[0].trim()).compareTo(cur.get(p[1].trim())) == 0;
        }
        List<UnmappedAccount> unmapped = movement ? List.of() : unmapped(t, period, sfp);
        boolean placeholder = t.placeholder() || Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM journal_entry_lines l JOIN chart_of_accounts a ON a.id = l.account_id WHERE a.placeholder)",
                Boolean.class));
        List<String> notes = new ArrayList<>();
        if (placeholder) {
            notes.add("PLACEHOLDER: this statement uses placeholder accounts or a placeholder template and is not an official statement.");
        }
        if (!unmapped.isEmpty()) {
            notes.add(unmapped.size() + " account(s) with balances are not mapped to any line of this template.");
        }
        if (!unclassified.isEmpty()) {
            notes.add(unclassified.size() + " part(s) of a movement are not classified by any line of this template.");
        }
        if (Boolean.FALSE.equals(balanced)) {
            notes.add("The balance check " + t.balanceCheck() + " does not agree.");
        }
        notes.add("Template " + t.code() + " version " + t.version() + ". Presentation follows MIAA's approved framework once configured (C-07).");
        return new StatementResult(t.id(), t.code(), t.version(), t.name(), t.statementType(), period.getName(), period.getEndDate(),
                prior == null ? null : prior.getName(), lines, balanced, unmapped, unclassified, placeholder, notes);
    }

    /** Movement statements: values by line code over the fiscal year to date of the period. */
    private Map<String, BigDecimal> movementAmounts(TemplateView t, AccountingPeriod period, List<UnclassifiedMovement> unclassified) {
        int key = period.getSequenceKey();
        int fyStart = (key / 100) * 100;
        // Per group of movement accounts: the counterpart codes its lines name, whether a line takes the whole change,
        // and whether a line takes everything else ('*').
        Map<Set<String>, Set<String>> claimed = new HashMap<>();
        Set<Set<String>> whole = new HashSet<>();
        Set<Set<String>> rest = new HashSet<>();
        for (TemplateLine l : t.lines()) {
            if ("MOVEMENT".equals(l.type())) {
                Set<String> g = Set.copyOf(l.fsLineCodes());
                if (l.counterpartCodes().isEmpty()) {
                    whole.add(g);
                } else if (l.counterpartCodes().contains("*")) {
                    rest.add(g);
                } else {
                    claimed.computeIfAbsent(g, k -> new HashSet<>()).addAll(l.counterpartCodes());
                }
            }
        }
        Map<Set<String>, Map<String, BigDecimal>> byGroup = new HashMap<>();
        Map<String, BigDecimal> out = new HashMap<>();
        for (TemplateLine l : t.lines()) {
            BigDecimal sign = BigDecimal.valueOf(l.sign());
            BigDecimal v = switch (l.type()) {
                case "OPENING" -> balance(l.fsLineCodes(), fyStart).multiply(sign);
                case "CLOSING" -> balance(l.fsLineCodes(), key).multiply(sign);
                case "MOVEMENT" -> {
                    Set<String> g = Set.copyOf(l.fsLineCodes());
                    Map<String, BigDecimal> split = byGroup.computeIfAbsent(g, x -> changeByCounterpart(x, fyStart, key));
                    Set<String> named = claimed.getOrDefault(g, Set.of());
                    BigDecimal sum;
                    if (l.counterpartCodes().isEmpty()) {
                        sum = split.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                    } else if (l.counterpartCodes().contains("*")) {
                        sum = split.entrySet().stream().filter(e -> !named.contains(e.getKey())).map(Map.Entry::getValue)
                                .reduce(BigDecimal.ZERO, BigDecimal::add);
                    } else {
                        sum = l.counterpartCodes().stream().map(c -> split.getOrDefault(c, BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add);
                    }
                    yield sum.multiply(sign);
                }
                // Results of the year not yet closed to equity; the year-end closing entry zeroes them once posted.
                case "SURPLUS" -> Money.of(jdbc.queryForObject("""
                        SELECT coalesce(sum(l.debit - l.credit), 0) FROM journal_entry_lines l JOIN chart_of_accounts a ON a.id = l.account_id
                        JOIN accounting_periods p ON p.id = l.period_id
                        WHERE a.account_type IN ('REVENUE', 'EXPENSE') AND p.sequence_key > ? AND p.sequence_key <= ?""",
                        BigDecimal.class, fyStart, key)).multiply(sign);
                case "TOTAL" -> l.totalOf().stream().map(c -> c.startsWith("-") ? out.getOrDefault(c.substring(1), BigDecimal.ZERO).negate()
                        : out.getOrDefault(c, BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add);
                default -> BigDecimal.ZERO;
            };
            out.put(l.code(), Money.of(v));
        }
        // A group with classified lines, no whole-change line and no '*' line: list what none of its lines claims.
        claimed.forEach((g, codes) -> {
            if (!whole.contains(g) && !rest.contains(g)) {
                byGroup.get(g).entrySet().stream().filter(e -> !codes.contains(e.getKey()) && e.getValue().signum() != 0)
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(e -> unclassified.add(new UnclassifiedMovement(g.stream().sorted().toList(), e.getKey(), Money.of(e.getValue()))));
            }
        });
        return out;
    }

    /** Debit-minus-credit balance of the accounts with the given statement line codes, up to and including a period key. */
    private BigDecimal balance(List<String> codes, int maxKey) {
        return Money.of(jdbc.queryForObject("""
                SELECT coalesce(sum(l.debit - l.credit), 0) FROM journal_entry_lines l JOIN chart_of_accounts a ON a.id = l.account_id
                JOIN accounting_periods p ON p.id = l.period_id WHERE a.fs_line_code = ANY (?::varchar[]) AND p.sequence_key <= ?""",
                BigDecimal.class, StatementTemplateService.pgArray(codes), maxKey));
    }

    /**
     * The change (debit positive) in the accounts of a group within (fromKey, toKey], split by the statement line code of
     * the other lines of each entry that touches the group ('(none)' for accounts without one). The parts add up to the
     * group's change because every entry balances.
     */
    private Map<String, BigDecimal> changeByCounterpart(Set<String> group, int fromKey, int toKey) {
        Map<String, BigDecimal> m = new HashMap<>();
        String codes = StatementTemplateService.pgArray(List.copyOf(group));
        jdbc.query("""
                SELECT coalesce(a.fs_line_code, '(none)') AS code, -sum(l.debit - l.credit) AS change FROM journal_entry_lines l
                JOIN chart_of_accounts a ON a.id = l.account_id JOIN accounting_periods p ON p.id = l.period_id
                WHERE p.sequence_key > ? AND p.sequence_key <= ?
                  AND (a.fs_line_code IS NULL OR NOT (a.fs_line_code = ANY (?::varchar[])))
                  AND EXISTS (SELECT 1 FROM journal_entry_lines g JOIN chart_of_accounts ga ON ga.id = g.account_id
                              WHERE g.entry_id = l.entry_id AND ga.fs_line_code = ANY (?::varchar[]))
                GROUP BY 1""", rs -> {
            m.put(rs.getString(1), rs.getBigDecimal(2));
        }, fromKey, toKey, codes, codes);
        return m;
    }

    /** Values by line code; TOTAL lines add earlier lines in template order. */
    private Map<String, BigDecimal> amounts(TemplateView t, AccountingPeriod period, boolean sfp) {
        Map<String, BigDecimal> byFs = byStatementLineCode(period, sfp);
        BigDecimal surplus = sfp ? Money.of(jdbc.queryForObject("""
                SELECT coalesce(sum(l.debit - l.credit), 0) FROM journal_entry_lines l JOIN chart_of_accounts a ON a.id = l.account_id
                JOIN accounting_periods p ON p.id = l.period_id WHERE a.account_type IN ('REVENUE', 'EXPENSE') AND p.sequence_key <= ?""",
                BigDecimal.class, period.getSequenceKey())) : BigDecimal.ZERO;
        Map<String, BigDecimal> out = new HashMap<>();
        for (TemplateLine l : t.lines()) {
            BigDecimal v = switch (l.type()) {
                case "LINE" -> l.fsLineCodes().stream().map(c -> byFs.getOrDefault(c, BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add)
                        .multiply(BigDecimal.valueOf(l.sign()));
                case "SURPLUS" -> surplus.multiply(BigDecimal.valueOf(l.sign()));
                // A leading '-' subtracts the line (e.g. revenue less expenses).
                case "TOTAL" -> l.totalOf().stream().map(c -> c.startsWith("-") ? out.getOrDefault(c.substring(1), BigDecimal.ZERO).negate()
                        : out.getOrDefault(c, BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add);
                default -> BigDecimal.ZERO;
            };
            out.put(l.code(), Money.of(v));
        }
        return out;
    }

    /** Net debit-minus-credit per statement line code: to date for real accounts, fiscal year to date for nominal ones. */
    private Map<String, BigDecimal> byStatementLineCode(AccountingPeriod period, boolean sfp) {
        int key = period.getSequenceKey();
        int fyStart = (key / 100) * 100;
        Map<String, BigDecimal> m = new HashMap<>();
        jdbc.query("""
                SELECT a.fs_line_code, sum(l.debit - l.credit) AS net FROM journal_entry_lines l
                JOIN chart_of_accounts a ON a.id = l.account_id JOIN accounting_periods p ON p.id = l.period_id
                WHERE a.fs_line_code IS NOT NULL AND p.sequence_key <= ?
                  AND (CASE WHEN ? THEN a.account_type IN ('ASSET', 'LIABILITY', 'EQUITY')
                            ELSE a.account_type IN ('REVENUE', 'EXPENSE') AND p.sequence_key > ? END)
                GROUP BY a.fs_line_code""", rs -> {
            m.put(rs.getString(1), rs.getBigDecimal(2));
        }, key, sfp, fyStart);
        return m;
    }

    private List<UnmappedAccount> unmapped(TemplateView t, AccountingPeriod period, boolean sfp) {
        Set<String> mapped = new HashSet<>();
        t.lines().forEach(l -> mapped.addAll(l.fsLineCodes()));
        int key = period.getSequenceKey();
        int fyStart = (key / 100) * 100;
        return jdbc.query("""
                SELECT a.code, a.name, a.fs_line_code, sum(l.debit - l.credit) AS net FROM journal_entry_lines l
                JOIN chart_of_accounts a ON a.id = l.account_id JOIN accounting_periods p ON p.id = l.period_id
                WHERE p.sequence_key <= ? AND (CASE WHEN ? THEN a.account_type IN ('ASSET', 'LIABILITY', 'EQUITY')
                                                    ELSE a.account_type IN ('REVENUE', 'EXPENSE') AND p.sequence_key > ? END)
                GROUP BY a.code, a.name, a.fs_line_code HAVING sum(l.debit - l.credit) <> 0 ORDER BY a.code""",
                (rs, i) -> new UnmappedAccount(rs.getString(1), rs.getString(2), rs.getString(3), Money.of(rs.getBigDecimal(4))),
                key, sfp, fyStart).stream().filter(u -> u.fsLineCode() == null || !mapped.contains(u.fsLineCode())).toList();
    }

    private AccountingPeriod priorYearPeriod(AccountingPeriod p) {
        int priorKey = p.getSequenceKey() - 100;
        List<UUID> ids = jdbc.queryForList("SELECT id FROM accounting_periods WHERE sequence_key = ?", UUID.class, priorKey);
        return ids.isEmpty() ? null : calendar.period(ids.get(0));
    }

    /** The statement as an exportable table. */
    public static ReportTable toTable(StatementResult s, String generatedBy, String generatedAt) {
        List<String> columns = new ArrayList<>(List.of(s.periodName()));
        if (s.comparativePeriodName() != null) {
            columns.add(s.comparativePeriodName());
        }
        List<ReportTable.Row> rows = new ArrayList<>();
        for (StatementLineResult l : s.lines()) {
            List<BigDecimal> values = new ArrayList<>();
            values.add(l.current());
            if (s.comparativePeriodName() != null) {
                values.add(l.comparative());
            }
            rows.add(new ReportTable.Row(l.label(), l.indent(), Set.of("HEADER", "TOTAL", "OPENING", "CLOSING").contains(l.type()), values));
        }
        List<String> notes = new ArrayList<>(s.notes());
        for (UnmappedAccount u : s.unmapped()) {
            notes.add("Unmapped: " + u.accountCode() + " " + u.accountName() + " (" + (u.fsLineCode() == null ? "no line code" : u.fsLineCode())
                    + ") " + u.balance().toPlainString());
        }
        for (UnclassifiedMovement u : s.unclassified()) {
            notes.add("Unclassified movement of " + String.join(", ", u.fsLineCodes()) + " against " + u.counterpartCode() + ": "
                    + u.amount().toPlainString());
        }
        notes.add("Generated " + generatedAt + " by " + generatedBy + ".");
        return new ReportTable(s.title(), List.of("Manila International Airport Authority", "As of / for the period ended " + s.periodEnd()),
                "Line", columns, rows, notes);
    }
}
