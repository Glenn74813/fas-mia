package com.miaa.cas.fixedassets.web;

import com.miaa.cas.audit.Actor;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.fixedassets.AssetSetupService;
import com.miaa.cas.fixedassets.DepreciationService;
import com.miaa.cas.fixedassets.DisposalService;
import com.miaa.cas.fixedassets.FaDtos.AssetRequest;
import com.miaa.cas.fixedassets.FaDtos.AssetSummary;
import com.miaa.cas.fixedassets.FaDtos.AssetView;
import com.miaa.cas.fixedassets.FaDtos.CancelRequest;
import com.miaa.cas.fixedassets.FaDtos.CategoryRequest;
import com.miaa.cas.fixedassets.FaDtos.CategoryView;
import com.miaa.cas.fixedassets.FaDtos.DisposalRequest;
import com.miaa.cas.fixedassets.FaDtos.DisposalView;
import com.miaa.cas.fixedassets.FaDtos.FaReconciliation;
import com.miaa.cas.fixedassets.FaDtos.PpeSchedule;
import com.miaa.cas.fixedassets.FaDtos.ProjectRequest;
import com.miaa.cas.fixedassets.FaDtos.ProjectView;
import com.miaa.cas.fixedassets.FaDtos.RunRequest;
import com.miaa.cas.fixedassets.FaDtos.RunSummary;
import com.miaa.cas.fixedassets.FaDtos.RunView;
import com.miaa.cas.fixedassets.FaDtos.VersionRequest;
import com.miaa.cas.fixedassets.FaReportService;
import com.miaa.cas.fixedassets.FixedAssetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Fixed assets", description = "Asset categories, AUC, register, depreciation, disposals and PPE reports")
public class FixedAssetsController {

    private final AssetSetupService setup;
    private final FixedAssetService assets;
    private final DepreciationService depreciation;
    private final DisposalService disposals;
    private final FaReportService reports;

    public FixedAssetsController(AssetSetupService setup, FixedAssetService assets, DepreciationService depreciation,
            DisposalService disposals, FaReportService reports) {
        this.setup = setup;
        this.assets = assets;
        this.depreciation = depreciation;
        this.disposals = disposals;
        this.reports = reports;
    }

    // ---------------------------------------------------------------- setup
    @GetMapping("/asset-categories")
    @PreAuthorize("hasAuthority('PERM_FA_VIEW')")
    public List<CategoryView> categories() {
        return setup.categories();
    }

    @PostMapping("/asset-categories")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_FA_MANAGE')")
    public CategoryView createCategory(@Valid @RequestBody CategoryRequest request) {
        return setup.createCategory(request, false, Actor.current());
    }

    @GetMapping("/auc-projects")
    @PreAuthorize("hasAuthority('PERM_FA_VIEW')")
    public List<ProjectView> projects() {
        return setup.projects();
    }

    @PostMapping("/auc-projects")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_FA_MANAGE')")
    @Operation(summary = "Open an asset-under-construction project (creates its subsidiary account on the AUC account)")
    public ProjectView createProject(@Valid @RequestBody ProjectRequest request) {
        return setup.createProject(request, Actor.current());
    }

    @PostMapping("/auc-projects/{id}/close")
    @PreAuthorize("hasAuthority('PERM_FA_MANAGE')")
    public ProjectView closeProject(@PathVariable UUID id, @Valid @RequestBody VersionRequest request) {
        return setup.closeProject(id, request.version(), Actor.current());
    }

    // ---------------------------------------------------------------- register
    @GetMapping("/fixed-assets")
    @PreAuthorize("hasAuthority('PERM_FA_VIEW')")
    public PageResponse<AssetSummary> assets(@RequestParam(required = false) String status, @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return assets.list(status, categoryId, q, page, size);
    }

    @GetMapping("/fixed-assets/{id}")
    @PreAuthorize("hasAuthority('PERM_FA_VIEW')")
    public AssetView asset(@PathVariable UUID id) {
        return assets.get(id);
    }

    @PostMapping("/fixed-assets")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_FA_MANAGE')")
    @Operation(summary = "Register an asset (draft) from a posted invoice, an AUC project, or another justified source")
    public AssetView createAsset(@Valid @RequestBody AssetRequest request) {
        return assets.create(request);
    }

    @PostMapping("/fixed-assets/{id}/activate")
    @PreAuthorize("hasAuthority('PERM_JOURNAL_POST')")
    public AssetView activate(@PathVariable UUID id, @Valid @RequestBody VersionRequest request) {
        return assets.activate(id, request.version());
    }

    @PostMapping("/fixed-assets/{id}/cancel")
    @PreAuthorize("hasAuthority('PERM_FA_MANAGE')")
    public AssetView cancelAsset(@PathVariable UUID id, @Valid @RequestBody CancelRequest request) {
        return assets.cancel(id, request.version(), request.reason());
    }

    // ---------------------------------------------------------------- depreciation
    @GetMapping("/depreciation-runs")
    @PreAuthorize("hasAuthority('PERM_FA_VIEW')")
    public PageResponse<RunSummary> runs(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return depreciation.list(page, size);
    }

    @GetMapping("/depreciation-runs/{id}")
    @PreAuthorize("hasAuthority('PERM_FA_VIEW')")
    public RunView run(@PathVariable UUID id) {
        return depreciation.get(id);
    }

    @PostMapping("/depreciation-runs")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_FA_MANAGE')")
    @Operation(summary = "Compute the depreciation due for a period (draft); missed months are caught up")
    public RunView prepareRun(@Valid @RequestBody RunRequest request) {
        return depreciation.prepare(request.periodId());
    }

    @PostMapping("/depreciation-runs/{id}/{action:post|cancel}")
    @PreAuthorize("hasAnyAuthority('PERM_FA_MANAGE', 'PERM_JOURNAL_POST')")
    public RunView runAction(@PathVariable UUID id, @PathVariable String action, @Valid @RequestBody VersionRequest request) {
        return "post".equals(action) ? depreciation.post(id, request.version()) : depreciation.cancel(id, request.version());
    }

    // ---------------------------------------------------------------- disposals
    @GetMapping("/asset-disposals")
    @PreAuthorize("hasAuthority('PERM_FA_VIEW')")
    public PageResponse<DisposalView> disposals(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return disposals.list(page, size);
    }

    @GetMapping("/asset-disposals/{id}")
    @PreAuthorize("hasAuthority('PERM_FA_VIEW')")
    public DisposalView disposal(@PathVariable UUID id) {
        return disposals.get(id);
    }

    @PostMapping("/asset-disposals")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_FA_MANAGE')")
    public DisposalView prepareDisposal(@Valid @RequestBody DisposalRequest request) {
        return disposals.prepare(request);
    }

    @PostMapping("/asset-disposals/{id}/{action:post|cancel}")
    @PreAuthorize("hasAnyAuthority('PERM_FA_MANAGE', 'PERM_JOURNAL_POST')")
    public DisposalView disposalAction(@PathVariable UUID id, @PathVariable String action, @Valid @RequestBody VersionRequest request) {
        return "post".equals(action) ? disposals.post(id, request.version()) : disposals.cancel(id, request.version());
    }

    // ---------------------------------------------------------------- reports
    @GetMapping("/fixed-assets/reports/ppe-schedule")
    @PreAuthorize("hasAuthority('PERM_FA_VIEW')")
    public PpeSchedule schedule(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.schedule(from, to);
    }

    @GetMapping("/fixed-assets/reports/reconciliation")
    @PreAuthorize("hasAuthority('PERM_FA_VIEW') and hasAuthority('PERM_LEDGER_VIEW')")
    public FaReconciliation reconciliation() {
        return reports.reconciliation();
    }
}
