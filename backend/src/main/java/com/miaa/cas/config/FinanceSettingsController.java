package com.miaa.cas.config;

import com.miaa.cas.audit.Actor;
import com.miaa.cas.generalledger.AccountMappingService;
import com.miaa.cas.tax.TaxCodeService;
import com.miaa.cas.treasury.BankAccountService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Finance configuration: control-account mappings, tax codes and bank accounts. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Finance settings")
public class FinanceSettingsController {

    public record MappingRequest(@NotNull UUID accountId) {
    }

    private final AccountMappingService mappings;
    private final TaxCodeService taxCodes;
    private final BankAccountService banks;

    public FinanceSettingsController(AccountMappingService mappings, TaxCodeService taxCodes, BankAccountService banks) {
        this.mappings = mappings;
        this.taxCodes = taxCodes;
        this.banks = banks;
    }

    @GetMapping("/account-mappings")
    @PreAuthorize("hasAuthority('PERM_LEDGER_VIEW') or hasAuthority('PERM_ACCOUNT_ADMINISTER')")
    public List<AccountMappingService.Mapping> mappings() {
        return mappings.list();
    }

    @PutMapping("/account-mappings/{key}")
    @PreAuthorize("hasAuthority('PERM_ACCOUNT_ADMINISTER')")
    public AccountMappingService.Mapping setMapping(@PathVariable String key, @Valid @RequestBody MappingRequest request) {
        return mappings.set(key, request.accountId(), Actor.current());
    }

    @GetMapping("/tax-codes")
    @PreAuthorize("hasAnyAuthority('PERM_AP_VIEW', 'PERM_AR_VIEW', 'PERM_LEDGER_VIEW', 'PERM_ACCOUNT_ADMINISTER')")
    public List<TaxCodeService.TaxCode> taxCodes() {
        return taxCodes.list();
    }

    @PostMapping("/tax-codes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_ACCOUNT_ADMINISTER')")
    public TaxCodeService.TaxCode createTaxCode(@Valid @RequestBody TaxCodeService.TaxCodeRequest request) {
        return taxCodes.create(request, false, Actor.current());
    }

    @GetMapping("/bank-accounts")
    @PreAuthorize("hasAnyAuthority('PERM_AP_VIEW', 'PERM_LEDGER_VIEW', 'PERM_BANK_ACCOUNT_MANAGE', 'PERM_PAYMENT_RELEASE', 'PERM_TREASURY_VIEW', 'PERM_DEPOSIT_PREPARE')")
    public List<BankAccountService.BankAccount> bankAccounts() {
        return banks.list();
    }

    @PostMapping("/bank-accounts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_BANK_ACCOUNT_MANAGE')")
    public BankAccountService.BankAccount createBankAccount(@Valid @RequestBody BankAccountService.BankAccountRequest request) {
        return banks.create(request, Actor.current());
    }
}
