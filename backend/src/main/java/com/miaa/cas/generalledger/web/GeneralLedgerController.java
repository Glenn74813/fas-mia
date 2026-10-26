package com.miaa.cas.generalledger.web;

import com.miaa.cas.generalledger.LedgerBalanceService;
import com.miaa.cas.generalledger.LedgerInquiryService;
import com.miaa.cas.generalledger.web.LedgerDtos.AccountLedger;
import com.miaa.cas.generalledger.web.LedgerDtos.BalanceDifference;
import com.miaa.cas.generalledger.web.LedgerDtos.EntryDetail;
import com.miaa.cas.generalledger.web.LedgerDtos.RebuildResult;
import com.miaa.cas.generalledger.web.LedgerDtos.SubsidiaryReconciliationRow;
import com.miaa.cas.generalledger.web.LedgerDtos.TrialBalance;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/general-ledger")
@Tag(name = "General ledger", description = "Trial balance, account and subsidiary ledgers, entry drill-down")
public class GeneralLedgerController {

    private final LedgerInquiryService inquiry;
    private final LedgerBalanceService balances;

    public GeneralLedgerController(LedgerInquiryService inquiry, LedgerBalanceService balances) {
        this.inquiry = inquiry;
        this.balances = balances;
    }

    @GetMapping("/trial-balance")
    @PreAuthorize("hasAuthority('PERM_LEDGER_VIEW')")
    @Operation(summary = "Trial balance through a period, computed from posted journal lines")
    public TrialBalance trialBalance(@RequestParam UUID periodId) {
        return inquiry.trialBalance(periodId);
    }

    @GetMapping("/accounts/{accountId}/ledger")
    @PreAuthorize("hasAuthority('PERM_LEDGER_VIEW')")
    @Operation(summary = "Account (or subsidiary) ledger with opening balance and running balance in the normal-balance sign")
    public AccountLedger ledger(@PathVariable UUID accountId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID subsidiaryAccountId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "100") int size) {
        return inquiry.accountLedger(accountId, from, to, subsidiaryAccountId, page, size);
    }

    @GetMapping("/entries/{entryId}")
    @PreAuthorize("hasAuthority('PERM_LEDGER_VIEW')")
    public EntryDetail entry(@PathVariable UUID entryId) {
        return inquiry.entry(entryId);
    }

    @GetMapping("/subsidiary-reconciliation")
    @PreAuthorize("hasAuthority('PERM_LEDGER_VIEW')")
    @Operation(summary = "Control accounts vs the sum of their subsidiary-ledger balances through a period")
    public List<SubsidiaryReconciliationRow> subsidiaryReconciliation(@RequestParam UUID periodId) {
        return inquiry.subsidiaryReconciliation(periodId);
    }

    @GetMapping("/balance-differences")
    @PreAuthorize("hasAuthority('PERM_LEDGER_VIEW')")
    @Operation(summary = "Differences between the ledger balance cache and posted lines (should be empty)")
    public List<BalanceDifference> differences(@RequestParam(required = false) UUID periodId) {
        return balances.differences(periodId);
    }

    @PostMapping("/balances/rebuild")
    @PreAuthorize("hasAuthority('PERM_LEDGER_REBUILD')")
    public RebuildResult rebuild() {
        return balances.rebuild();
    }
}
