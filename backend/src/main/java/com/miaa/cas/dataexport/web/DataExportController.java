package com.miaa.cas.dataexport.web;

import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.dataexport.DataExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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
@RequestMapping("/api/v1/data-exports")
@Tag(name = "Data export", description = "Full exports of the system's data for AVTECH (TOR §3.6), requested and approved by two people")
public class DataExportController {

    public record ExportRequest(@NotBlank @Size(max = 500) String purpose, UUID fiscalYearId) {
    }

    public record DecisionRequest(@NotNull Boolean approve, @Size(max = 500) String note, long version) {
    }

    private final DataExportService exports;

    public DataExportController(DataExportService exports) {
        this.exports = exports;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_DATA_EXPORT')")
    public PageResponse<DataExportService.ExportView> list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return exports.list(page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_DATA_EXPORT')")
    @Operation(summary = "Ask for a full export, or the archive of a closed fiscal year, with its purpose; another holder of DATA_EXPORT must approve it")
    public DataExportService.ExportView request(@Valid @RequestBody ExportRequest request) {
        return exports.request(request.purpose(), request.fiscalYearId());
    }

    @PostMapping("/{id}/decision")
    @PreAuthorize("hasAuthority('PERM_DATA_EXPORT')")
    @Operation(summary = "Approve or reject another person's export request")
    public DataExportService.ExportView decide(@PathVariable UUID id, @Valid @RequestBody DecisionRequest request) {
        return exports.decide(id, request.approve(), request.note(), request.version());
    }

    @GetMapping("/{id}/download")
    @PreAuthorize("hasAuthority('PERM_DATA_EXPORT')")
    @Operation(summary = "Download a finished export (requester only); the ZIP is decrypted as it is sent")
    public void download(@PathVariable UUID id, HttpServletResponse response) throws IOException {
        DataExportService.Download d = exports.prepareDownload(id);
        response.setContentType("application/zip");
        response.setContentLengthLong(d.size());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(d.fileName()).build().toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        exports.write(d, response.getOutputStream());
    }
}
