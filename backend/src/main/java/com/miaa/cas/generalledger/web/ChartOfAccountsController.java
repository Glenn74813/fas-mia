package com.miaa.cas.generalledger.web;

import com.miaa.cas.audit.Actor;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.generalledger.AccountService;
import com.miaa.cas.generalledger.DimensionService;
import com.miaa.cas.generalledger.web.LedgerDtos.AccountRequest;
import com.miaa.cas.generalledger.web.LedgerDtos.AccountResponse;
import com.miaa.cas.generalledger.web.LedgerDtos.AccountUpdateRequest;
import com.miaa.cas.generalledger.web.LedgerDtos.ChartSummary;
import com.miaa.cas.generalledger.web.LedgerDtos.CostCenterRequest;
import com.miaa.cas.generalledger.web.LedgerDtos.CostCenterResponse;
import com.miaa.cas.generalledger.web.LedgerDtos.DimensionUpdateRequest;
import com.miaa.cas.generalledger.web.LedgerDtos.ImportResult;
import com.miaa.cas.generalledger.web.LedgerDtos.SubsidiaryAccountRequest;
import com.miaa.cas.generalledger.web.LedgerDtos.SubsidiaryAccountResponse;
import com.miaa.cas.generalledger.web.LedgerDtos.VersionRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Chart of accounts", description = "Accounts, cost centres and subsidiary-ledger accounts")
public class ChartOfAccountsController {

    private final AccountService accounts;
    private final DimensionService dimensions;

    public ChartOfAccountsController(AccountService accounts, DimensionService dimensions) {
        this.accounts = accounts;
        this.dimensions = dimensions;
    }

    @GetMapping("/accounts")
    @PreAuthorize("hasAuthority('PERM_LEDGER_VIEW') or hasAuthority('PERM_ACCOUNT_ADMINISTER')")
    @Operation(summary = "Full chart of accounts, plus the count of active placeholder accounts")
    public ChartSummary chart() {
        return accounts.chart();
    }

    @GetMapping("/accounts/{id}")
    @PreAuthorize("hasAuthority('PERM_LEDGER_VIEW') or hasAuthority('PERM_ACCOUNT_ADMINISTER')")
    public AccountResponse get(@PathVariable UUID id) {
        return accounts.get(id);
    }

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_ACCOUNT_ADMINISTER')")
    public AccountResponse create(@Valid @RequestBody AccountRequest request) {
        return accounts.create(request);
    }

    @PutMapping("/accounts/{id}")
    @PreAuthorize("hasAuthority('PERM_ACCOUNT_ADMINISTER')")
    public AccountResponse update(@PathVariable UUID id, @Valid @RequestBody AccountUpdateRequest request) {
        return accounts.update(id, request);
    }

    @PostMapping("/accounts/{id}/activate")
    @PreAuthorize("hasAuthority('PERM_ACCOUNT_ADMINISTER')")
    public AccountResponse activate(@PathVariable UUID id, @Valid @RequestBody VersionRequest request) {
        return accounts.setStatus(id, request.version(), true);
    }

    @PostMapping("/accounts/{id}/deactivate")
    @PreAuthorize("hasAuthority('PERM_ACCOUNT_ADMINISTER')")
    public AccountResponse deactivate(@PathVariable UUID id, @Valid @RequestBody VersionRequest request) {
        return accounts.setStatus(id, request.version(), false);
    }

    @PostMapping(value = "/accounts/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_ACCOUNT_ADMINISTER')")
    @Operation(summary = "All-or-nothing CSV import. placeholder=true marks every imported account as a placeholder")
    public ImportResult importCsv(@RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "false") boolean placeholder) throws IOException {
        if (file.isEmpty() || file.getSize() > 2_000_000) {
            throw new ApiException(ErrorCode.FILE_REJECTED, "Provide a non-empty CSV file of at most 2 MB.");
        }
        return accounts.importCsv(file.getInputStream(), placeholder, Actor.current());
    }

    @GetMapping("/cost-centers")
    @PreAuthorize("hasAuthority('PERM_LEDGER_VIEW') or hasAuthority('PERM_ACCOUNT_ADMINISTER')")
    public List<CostCenterResponse> costCenters() {
        return dimensions.costCenters();
    }

    @PostMapping("/cost-centers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_ACCOUNT_ADMINISTER')")
    public CostCenterResponse createCostCenter(@Valid @RequestBody CostCenterRequest request) {
        return dimensions.createCostCenter(request, false, Actor.current());
    }

    @PutMapping("/cost-centers/{id}")
    @PreAuthorize("hasAuthority('PERM_ACCOUNT_ADMINISTER')")
    public CostCenterResponse updateCostCenter(@PathVariable UUID id, @Valid @RequestBody DimensionUpdateRequest request) {
        return dimensions.updateCostCenter(id, request);
    }

    @GetMapping("/subsidiary-accounts")
    @PreAuthorize("hasAuthority('PERM_LEDGER_VIEW') or hasAuthority('PERM_ACCOUNT_ADMINISTER')")
    public PageResponse<SubsidiaryAccountResponse> subsidiaryAccounts(@RequestParam(required = false) String slType,
            @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return dimensions.subsidiaryAccounts(slType, q, page, size);
    }

    @GetMapping("/subsidiary-accounts/{id}")
    @PreAuthorize("hasAuthority('PERM_LEDGER_VIEW') or hasAuthority('PERM_ACCOUNT_ADMINISTER')")
    public SubsidiaryAccountResponse subsidiaryAccount(@PathVariable UUID id) {
        return dimensions.subsidiaryAccount(id);
    }

    @PostMapping("/subsidiary-accounts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_ACCOUNT_ADMINISTER')")
    public SubsidiaryAccountResponse createSubsidiaryAccount(@Valid @RequestBody SubsidiaryAccountRequest request) {
        return dimensions.createSubsidiaryAccount(request);
    }

    @PutMapping("/subsidiary-accounts/{id}")
    @PreAuthorize("hasAuthority('PERM_ACCOUNT_ADMINISTER')")
    public SubsidiaryAccountResponse updateSubsidiaryAccount(@PathVariable UUID id,
            @Valid @RequestBody DimensionUpdateRequest request) {
        return dimensions.updateSubsidiaryAccount(id, request);
    }
}
