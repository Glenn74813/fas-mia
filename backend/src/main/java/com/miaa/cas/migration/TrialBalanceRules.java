package com.miaa.cas.migration;

import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.generalledger.FiscalCalendarService;
import com.miaa.cas.generalledger.domain.AccountingPeriod;
import com.miaa.cas.journal.web.JournalDtos.LineRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * GL opening trial balance: one row per account, cost centre and subsidiary account, balanced, dated in the first
 * period of a fiscal year. It is applied by creating a draft OPENING voucher (see {@link MigrationService}), so it
 * has no reconciliation of its own.
 */
@Component
class TrialBalanceRules implements KindRules {

    private final FiscalCalendarService calendar;
    private final JdbcTemplate jdbc;

    TrialBalanceRules(FiscalCalendarService calendar, JdbcTemplate jdbc) {
        this.calendar = calendar;
        this.jdbc = jdbc;
    }

    @Override
    public MigrationService.Kind kind() {
        return MigrationService.Kind.GL_OPENING;
    }

    @Override
    public String basis() {
        return null;
    }

    @Override
    public Map<String, Side> ledger(Entry entry) {
        throw new UnsupportedOperationException("A trial balance creates the opening entry; it does not reconcile with one.");
    }

    @Override
    public void apply(Context ctx, Checked checked) {
        throw new UnsupportedOperationException("A trial balance is applied by creating the opening voucher.");
    }

    @Override
    public Checked check(List<MigrationCsv.Row> rows, LocalDate cutover) {
        record Acct(UUID id, boolean postable, boolean active, boolean needsCostCenter, String slType) {
        }
        Map<String, Acct> accounts = new HashMap<>();
        jdbc.query("SELECT id, code, postable, status, requires_cost_center, subsidiary_type FROM chart_of_accounts", rs -> {
            accounts.put(rs.getString("code"), new Acct(rs.getObject("id", UUID.class), rs.getBoolean("postable"),
                    "ACTIVE".equals(rs.getString("status")), rs.getBoolean("requires_cost_center"), rs.getString("subsidiary_type")));
        });
        Map<String, UUID> costCenters = new HashMap<>();
        Set<String> inactiveCostCenters = new HashSet<>();
        jdbc.query("SELECT id, code, status FROM cost_centers", rs -> {
            costCenters.put(rs.getString("code"), rs.getObject("id", UUID.class));
            if (!"ACTIVE".equals(rs.getString("status"))) {
                inactiveCostCenters.add(rs.getString("code"));
            }
        });
        Map<String, UUID> subsidiaries = new HashMap<>();
        jdbc.query("SELECT id, sl_type, code FROM subsidiary_ledger_accounts WHERE status = 'ACTIVE'", rs -> {
            subsidiaries.put(rs.getString("sl_type") + "|" + rs.getString("code"), rs.getObject("id", UUID.class));
        });

        List<List<String>> rowErrors = new ArrayList<>();
        List<LineRequest> lines = new ArrayList<>();
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        for (MigrationCsv.Row r : rows) {
            List<String> e = new ArrayList<>();
            Map<String, String> v = r.values();
            Acct a = accounts.get(v.get("account_code"));
            UUID cc = null;
            UUID sl = null;
            if (v.get("account_code").isEmpty()) {
                e.add("account_code is required.");
            } else if (a == null) {
                e.add("Account " + v.get("account_code") + " does not exist.");
            } else {
                if (!a.postable()) {
                    e.add("Account " + v.get("account_code") + " is a header account.");
                }
                if (!a.active()) {
                    e.add("Account " + v.get("account_code") + " is inactive.");
                }
                if (a.needsCostCenter() && v.get("cost_center_code").isEmpty()) {
                    e.add("Account " + v.get("account_code") + " requires a cost centre.");
                }
                if (a.slType() != null && v.get("subsidiary_code").isEmpty()) {
                    e.add("Account " + v.get("account_code") + " keeps a " + a.slType().toLowerCase(Locale.ROOT)
                            + " sub-ledger; give the subsidiary_code.");
                } else if (a.slType() == null && !v.get("subsidiary_code").isEmpty()) {
                    e.add("Account " + v.get("account_code") + " does not keep a sub-ledger; leave subsidiary_code empty.");
                } else if (a.slType() != null) {
                    sl = subsidiaries.get(a.slType() + "|" + v.get("subsidiary_code"));
                    if (sl == null) {
                        e.add("No active " + a.slType().toLowerCase(Locale.ROOT) + " subsidiary account " + v.get("subsidiary_code") + ".");
                    }
                }
            }
            if (!v.get("cost_center_code").isEmpty()) {
                cc = costCenters.get(v.get("cost_center_code"));
                if (cc == null) {
                    e.add("Cost centre " + v.get("cost_center_code") + " does not exist.");
                } else if (inactiveCostCenters.contains(v.get("cost_center_code"))) {
                    e.add("Cost centre " + v.get("cost_center_code") + " is inactive.");
                }
            }
            String description = KindRules.text(v.get("description"), "description", e, false, 500);
            BigDecimal d = KindRules.amount(v.get("debit"), "debit", e, true);
            BigDecimal c = KindRules.amount(v.get("credit"), "credit", e, true);
            boolean hasD = d != null && d.signum() > 0;
            boolean hasC = c != null && c.signum() > 0;
            if (hasD == hasC && e.stream().noneMatch(x -> x.startsWith("debit") || x.startsWith("credit"))) {
                e.add("Give either a debit or a credit greater than zero.");
            }
            if (e.isEmpty()) {
                debits = debits.add(hasD ? d : BigDecimal.ZERO);
                credits = credits.add(hasC ? c : BigDecimal.ZERO);
                lines.add(new LineRequest(a.id(), cc, sl, description, hasD ? d : BigDecimal.ZERO.setScale(2),
                        hasC ? c : BigDecimal.ZERO.setScale(2)));
            }
            rowErrors.add(e);
        }
        List<String> batchErrors = new ArrayList<>();
        if (rowErrors.stream().allMatch(List::isEmpty) && debits.compareTo(credits) != 0) {
            batchErrors.add("Debits (" + debits.toPlainString() + ") do not equal credits (" + credits.toPlainString() + ").");
        }
        try {
            AccountingPeriod p = calendar.resolve(cutover, false);
            if (p.getPeriodNumber() != 1) {
                batchErrors.add("The cutover date must fall in the first period of a fiscal year; " + cutover + " is in " + p.getName() + ".");
            }
        } catch (ApiException ex) {
            batchErrors.add(ex.getMessage());
        }
        return new Checked(rowErrors, batchErrors, debits, KindRules.sides(), List.of(), lines);
    }
}
