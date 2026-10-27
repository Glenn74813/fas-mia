package com.miaa.cas.financialreporting.web;

import com.miaa.cas.financialreporting.adhoc.AdHocReportService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ad-hoc-reports")
@Tag(name = "Ad hoc reports", description = "Reports built by accounting staff over approved datasets (TOR Annex B.5)")
public class AdHocReportController {

    private static final String USERS = "hasAnyAuthority('PERM_LEDGER_VIEW', 'PERM_AP_VIEW', 'PERM_AR_VIEW')";

    public record RunRequest(@NotBlank String dataset, @NotNull AdHocReportService.Definition definition) {
    }

    public record SaveRequest(@NotBlank @Size(max = 120) String name, @NotBlank String dataset, @NotNull AdHocReportService.Definition definition,
            boolean shared, Long version) {
    }

    private final AdHocReportService reports;

    public AdHocReportController(AdHocReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/datasets")
    @PreAuthorize(USERS)
    @Operation(summary = "The datasets and columns the caller may report on")
    public List<AdHocReportService.DatasetInfo> datasets() {
        return reports.datasets();
    }

    @PostMapping("/preview")
    @PreAuthorize(USERS)
    @Operation(summary = "Run a definition and return its first 200 rows")
    public AdHocReportService.Result preview(@Valid @RequestBody RunRequest r) {
        return reports.preview(r.dataset(), r.definition());
    }

    @GetMapping
    @PreAuthorize(USERS)
    @Operation(summary = "Saved reports: the caller's own and those shared with them")
    public List<AdHocReportService.SavedView> saved() {
        return reports.saved();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(USERS)
    public AdHocReportService.SavedView create(@Valid @RequestBody SaveRequest r) {
        return reports.save(null, null, r.name(), r.dataset(), r.definition(), r.shared());
    }

    @PutMapping("/{id}")
    @PreAuthorize(USERS)
    public AdHocReportService.SavedView update(@PathVariable UUID id, @Valid @RequestBody SaveRequest r) {
        return reports.save(id, r.version(), r.name(), r.dataset(), r.definition(), r.shared());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(USERS)
    public void delete(@PathVariable UUID id) {
        reports.delete(id);
    }
}
