package com.miaa.cas.generalledger.web;

import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.generalledger.ClearingService;
import com.miaa.cas.generalledger.ClearingService.Clearing;
import com.miaa.cas.generalledger.ClearingService.OpenItem;
import com.miaa.cas.generalledger.ClearingService.OpenItemAccount;
import com.miaa.cas.generalledger.ClearingService.Proposal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Open-item clearing on open-item managed accounts (FR-GL-17). */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "General ledger")
public class ClearingController {

    public record ManagedRequest(@NotNull Boolean managed) {
    }

    public record ClearRequest(@NotNull @Size(min = 2, max = 500) List<UUID> lineIds, @NotBlank @Size(max = 500) String note) {
    }

    public record AutoClearRequest(@NotBlank String rule, boolean apply) {
    }

    public record ReverseRequest(@NotBlank @Size(max = 500) String reason) {
    }

    private final ClearingService clearing;

    public ClearingController(ClearingService clearing) {
        this.clearing = clearing;
    }

    @GetMapping("/open-items/accounts")
    @PreAuthorize("hasAuthority('PERM_LEDGER_VIEW')")
    public List<OpenItemAccount> accounts() {
        return clearing.accounts();
    }

    @PutMapping("/accounts/{id}/open-item-managed")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_ACCOUNT_ADMINISTER')")
    @Operation(summary = "Manage an asset or liability account by open item, or stop doing so")
    public void setManaged(@PathVariable UUID id, @Valid @RequestBody ManagedRequest r) {
        clearing.setManaged(id, r.managed());
    }

    @GetMapping("/open-items/accounts/{id}/items")
    @PreAuthorize("hasAuthority('PERM_LEDGER_VIEW')")
    public PageResponse<OpenItem> items(@PathVariable UUID id, @RequestParam(required = false) UUID subsidiaryAccountId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return clearing.openItems(id, subsidiaryAccountId, page, size);
    }

    @PostMapping("/open-items/accounts/{id}/clearings")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_GL_CLEARING')")
    @Operation(summary = "Clear open items of one subsidiary account that net to zero")
    public Clearing clear(@PathVariable UUID id, @Valid @RequestBody ClearRequest r) {
        return clearing.clear(id, r.lineIds(), r.note());
    }

    @PostMapping("/open-items/accounts/{id}/auto-clear")
    @PreAuthorize("hasAuthority('PERM_GL_CLEARING')")
    @Operation(summary = "Propose (apply=false) or apply clearings by REFERENCE or AMOUNT")
    public List<Proposal> autoClear(@PathVariable UUID id, @Valid @RequestBody AutoClearRequest r) {
        return clearing.autoClear(id, r.rule(), r.apply());
    }

    @GetMapping("/open-items/accounts/{id}/clearings")
    @PreAuthorize("hasAuthority('PERM_LEDGER_VIEW')")
    public List<Clearing> history(@PathVariable UUID id) {
        return clearing.history(id);
    }

    @PostMapping("/clearings/{id}/reverse")
    @PreAuthorize("hasAuthority('PERM_GL_CLEARING')")
    public Clearing reverse(@PathVariable UUID id, @Valid @RequestBody ReverseRequest r) {
        return clearing.reverse(id, r.reason());
    }
}
