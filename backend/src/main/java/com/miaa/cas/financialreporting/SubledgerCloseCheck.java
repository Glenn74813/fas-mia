package com.miaa.cas.financialreporting;

import com.miaa.cas.accountspayable.ApReportService;
import com.miaa.cas.accountsreceivable.ArReportService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.fixedassets.FaReportService;
import com.miaa.cas.generalledger.PeriodCloseContributor;
import com.miaa.cas.generalledger.PeriodCloseService;
import com.miaa.cas.generalledger.domain.AccountingPeriod;
import com.miaa.cas.generalledger.web.LedgerDtos.CloseCheck;
import com.miaa.cas.inventoryaccounting.InventoryReportService;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Close checklist: the AP, AR, fixed-asset and inventory sub-ledgers agree with their control accounts. The module
 * reconciliations compare current balances, not balances as of the period end, so the check is meaningful when the
 * period is closed promptly; a difference can be waived with a justification like any other check.
 */
@Component
public class SubledgerCloseCheck implements PeriodCloseContributor {

    private final ApReportService ap;
    private final ArReportService ar;
    private final FaReportService fa;
    private final InventoryReportService inventory;

    private final TransactionTemplate tx;

    public SubledgerCloseCheck(ApReportService ap, ArReportService ar, FaReportService fa, InventoryReportService inventory,
            PlatformTransactionManager txManager) {
        // Each reconciliation runs in its own read-only transaction, so one that fails (an unmapped control account)
        // does not mark the caller's period-close transaction rollback-only.
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.tx.setReadOnly(true);
        this.ap = ap;
        this.ar = ar;
        this.fa = fa;
        this.inventory = inventory;
    }

    @Override
    public List<CloseCheck> checks(AccountingPeriod period) {
        List<CloseCheck> out = new ArrayList<>();
        out.add(check("AP_RECONCILED", "Payables agree with the AP control account", () -> ap.reconciliation().reconciled()));
        out.add(check("AR_RECONCILED", "Receivables and unapplied collections agree with their control accounts", () -> ar.reconciliation().reconciled()));
        out.add(check("FA_RECONCILED", "The asset register agrees with the asset and accumulated-depreciation accounts",
                () -> fa.reconciliation().reconciled()));
        out.add(check("INVENTORY_RECONCILED", "Stock and GR/IR agree with their accounts", () -> inventory.reconciliation().reconciled()));
        return out;
    }

    private CloseCheck check(String code, String description, BooleanSupplier reconciled) {
        try {
            boolean ok = Boolean.TRUE.equals(tx.execute(st -> reconciled.getAsBoolean()));
            return new CloseCheck(code, description, ok ? PeriodCloseService.PASS : PeriodCloseService.FAIL, true,
                    ok ? "" : "A difference exists today; see the module's reconciliation report");
        } catch (ApiException e) {
            // Typically a control account that is not mapped yet.
            return new CloseCheck(code, description, PeriodCloseService.NOT_AVAILABLE, true, e.getMessage());
        }
    }
}
