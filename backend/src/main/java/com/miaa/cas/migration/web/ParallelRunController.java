package com.miaa.cas.migration.web;

import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.migration.ParallelRunService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Parallel-run comparison of a legacy trial balance with CAS (Phase 5e). */
@RestController
@RequestMapping("/api/v1/parallel-runs")
@Tag(name = "Migration")
public class ParallelRunController {

    public record VersionRequest(@NotNull Long version) {
    }

    public record ExplainRequest(@NotBlank String explanation) {
    }

    public record NoteRequest(@NotNull Long version, @NotBlank String note) {
    }

    public record CancelRequest(@NotNull Long version, @NotBlank String reason) {
    }

    private final ParallelRunService runs;

    public ParallelRunController(ParallelRunService runs) {
        this.runs = runs;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('PERM_MIGRATION_PREPARE', 'PERM_MIGRATION_APPROVE')")
    public PageResponse<ParallelRunService.RunSummary> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return runs.list(page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('PERM_MIGRATION_PREPARE', 'PERM_MIGRATION_APPROVE')")
    public ParallelRunService.RunDetail get(@PathVariable UUID id) {
        return runs.get(id);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_MIGRATION_PREPARE')")
    public ParallelRunService.RunDetail upload(@RequestParam UUID periodId, @RequestParam String basis, @RequestParam String legacySystem,
            @RequestPart("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new ApiException(ErrorCode.FILE_REJECTED, "No file was uploaded.");
        }
        return runs.upload(periodId, basis, legacySystem, file.getOriginalFilename(), file.getBytes());
    }

    @PostMapping("/{id}/recompare")
    @PreAuthorize("hasAuthority('PERM_MIGRATION_PREPARE')")
    public ParallelRunService.RunDetail recompare(@PathVariable UUID id, @Valid @RequestBody VersionRequest request) {
        return runs.recompare(id, request.version());
    }

    @PostMapping("/{id}/lines/{lineId}/explanation")
    @PreAuthorize("hasAnyAuthority('PERM_MIGRATION_PREPARE', 'PERM_MIGRATION_APPROVE')")
    public ParallelRunService.RunDetail explain(@PathVariable UUID id, @PathVariable UUID lineId, @Valid @RequestBody ExplainRequest request) {
        return runs.explain(id, lineId, request.explanation());
    }

    @PostMapping("/{id}/sign-off")
    @PreAuthorize("hasAuthority('PERM_MIGRATION_APPROVE')")
    public ParallelRunService.RunDetail signOff(@PathVariable UUID id, @Valid @RequestBody NoteRequest request) {
        return runs.signOff(id, request.version(), request.note());
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyAuthority('PERM_MIGRATION_PREPARE', 'PERM_MIGRATION_APPROVE')")
    public ParallelRunService.RunDetail cancel(@PathVariable UUID id, @Valid @RequestBody CancelRequest request) {
        return runs.cancel(id, request.version(), request.reason());
    }

    @GetMapping("/template")
    @PreAuthorize("hasAuthority('PERM_MIGRATION_PREPARE')")
    public ResponseEntity<byte[]> template() {
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"legacy-trial-balance-template.csv\"")
                .body(ParallelRunService.template().getBytes(StandardCharsets.UTF_8));
    }
}
