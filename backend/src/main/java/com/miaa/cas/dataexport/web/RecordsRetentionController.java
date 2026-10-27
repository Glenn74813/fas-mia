package com.miaa.cas.dataexport.web;

import com.miaa.cas.dataexport.RecordsRetentionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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

@RestController
@RequestMapping("/api/v1/records-retention")
@Tag(name = "Records retention", description = "Retention schedule per fiscal year, fiscal-year archives and legal holds (TOR §4.5)")
public class RecordsRetentionController {

    public record HoldRequest(UUID fiscalYearId, @NotBlank @Size(max = 500) String reason, @Size(max = 100) String reference) {
    }

    public record ReleaseRequest(@NotBlank @Size(max = 500) String note, long version) {
    }

    private final RecordsRetentionService retention;

    public RecordsRetentionController(RecordsRetentionService retention) {
        this.retention = retention;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('PERM_RECORDS_RETENTION_MANAGE', 'PERM_DATA_EXPORT', 'PERM_AUDIT_LOG_VIEW')")
    @Operation(summary = "Each fiscal year's retention date, archives and holds")
    public RecordsRetentionService.Schedule schedule() {
        return retention.schedule();
    }

    @PostMapping("/holds")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_RECORDS_RETENTION_MANAGE')")
    @Operation(summary = "Place a legal hold on a fiscal year, or on every record when no year is given")
    public RecordsRetentionService.Hold place(@Valid @RequestBody HoldRequest request) {
        return retention.place(request.fiscalYearId(), request.reason(), request.reference());
    }

    @PostMapping("/holds/{id}/release")
    @PreAuthorize("hasAuthority('PERM_RECORDS_RETENTION_MANAGE')")
    public RecordsRetentionService.Hold release(@PathVariable UUID id, @Valid @RequestBody ReleaseRequest request) {
        return retention.release(id, request.note(), request.version());
    }
}
