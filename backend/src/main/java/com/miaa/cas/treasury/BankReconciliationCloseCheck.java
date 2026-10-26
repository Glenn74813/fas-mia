package com.miaa.cas.treasury;

import com.miaa.cas.generalledger.PeriodCloseContributor;
import com.miaa.cas.generalledger.PeriodCloseService;
import com.miaa.cas.generalledger.domain.AccountingPeriod;
import com.miaa.cas.generalledger.web.LedgerDtos.CloseCheck;
import java.sql.Date;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Close checklist: every active bank account with ledger activity has an approved reconciliation through the period end. */
@Component
public class BankReconciliationCloseCheck implements PeriodCloseContributor {

    private final JdbcTemplate jdbc;

    public BankReconciliationCloseCheck(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<CloseCheck> checks(AccountingPeriod period) {
        Date end = Date.valueOf(period.getEndDate());
        List<String> missing = jdbc.queryForList("""
                SELECT b.code FROM bank_accounts b
                WHERE b.status = 'ACTIVE'
                  AND EXISTS (SELECT 1 FROM journal_entry_lines l WHERE l.account_id = b.gl_account_id AND l.accounting_date <= ?)
                  AND NOT EXISTS (SELECT 1 FROM bank_reconciliations r WHERE r.bank_account_id = b.id AND r.status = 'APPROVED'
                                  AND r.as_of_date >= ?)
                ORDER BY b.code""", String.class, end, end);
        return List.of(new CloseCheck("BANK_RECONCILIATIONS", "Bank reconciliations approved through the period end",
                missing.isEmpty() ? PeriodCloseService.PASS : PeriodCloseService.FAIL, true, missing.isEmpty() ? ""
                        : "Not reconciled through " + period.getEndDate() + ": " + String.join(", ", missing.stream().limit(10).toList())
                                + (missing.size() > 10 ? " and " + (missing.size() - 10) + " more" : "")));
    }
}
