package com.miaa.cas.fixedassets.web;

import com.miaa.cas.fixedassets.AssetTransferService;
import com.miaa.cas.fixedassets.AssetTransferService.TransferView;
import com.miaa.cas.fixedassets.DepreciationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Asset transfers between units (cost centre, location, custodian) and the depreciation run frequency. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Fixed assets")
public class AssetTransferController {

    public record TransferRequest(@NotNull LocalDate transferDate, UUID toCostCenterId, @Size(max = 200) String toLocation,
            @Size(max = 200) String toCustodian, @NotBlank @Size(max = 1000) String reason) {
    }

    public record VersionRequest(@NotNull Long version) {
    }

    private final AssetTransferService transfers;
    private final DepreciationService depreciation;

    public AssetTransferController(AssetTransferService transfers, DepreciationService depreciation) {
        this.transfers = transfers;
        this.depreciation = depreciation;
    }

    @GetMapping("/fixed-assets/{id}/transfers")
    @PreAuthorize("hasAuthority('PERM_FA_VIEW')")
    public List<TransferView> forAsset(@PathVariable UUID id) {
        return transfers.forAsset(id);
    }

    @PostMapping("/fixed-assets/{id}/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_FA_MANAGE')")
    @Operation(summary = "Prepare a transfer to another cost centre, location or custodian; a different person posts it")
    public TransferView prepare(@PathVariable UUID id, @Valid @RequestBody TransferRequest r) {
        return transfers.prepare(id, r.transferDate(), r.toCostCenterId(), r.toLocation(), r.toCustodian(), r.reason());
    }

    @PostMapping("/asset-transfers/{id}/post")
    @PreAuthorize("hasAuthority('PERM_JOURNAL_POST')")
    public TransferView post(@PathVariable UUID id, @Valid @RequestBody VersionRequest r) {
        return transfers.post(id, r.version());
    }

    @PostMapping("/asset-transfers/{id}/cancel")
    @PreAuthorize("hasAnyAuthority('PERM_FA_MANAGE', 'PERM_JOURNAL_POST')")
    public TransferView cancel(@PathVariable UUID id, @Valid @RequestBody VersionRequest r) {
        return transfers.cancel(id, r.version());
    }

    @GetMapping("/depreciation-settings")
    @PreAuthorize("hasAuthority('PERM_FA_VIEW')")
    public Map<String, String> settings() {
        return Map.of("frequency", depreciation.frequency());
    }
}
