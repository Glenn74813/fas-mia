package com.miaa.cas.migration.web;

import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.documents.DocumentService;
import com.miaa.cas.migration.MigrationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
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

/** Opening-balance migration batches (Phase 5a). */
@RestController
@RequestMapping("/api/v1/migration")
@Tag(name = "Migration")
public class MigrationController {

    public record VersionRequest(@NotNull Long version) {
    }

    public record CancelRequest(@NotNull Long version, @NotBlank String reason) {
    }

    private final MigrationService migration;
    private final DocumentService documents;

    public MigrationController(MigrationService migration, DocumentService documents) {
        this.migration = migration;
        this.documents = documents;
    }

    @GetMapping("/batches")
    @PreAuthorize("hasAnyAuthority('PERM_MIGRATION_PREPARE', 'PERM_MIGRATION_APPROVE')")
    public PageResponse<MigrationService.BatchSummary> list(@RequestParam(required = false) String kind,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return migration.list(kind, page, size);
    }

    @GetMapping("/batches/{id}")
    @PreAuthorize("hasAnyAuthority('PERM_MIGRATION_PREPARE', 'PERM_MIGRATION_APPROVE')")
    public MigrationService.BatchDetail get(@PathVariable UUID id) {
        return migration.get(id);
    }

    @PostMapping(value = "/batches", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_MIGRATION_PREPARE')")
    public MigrationService.BatchDetail upload(@RequestParam MigrationService.Kind kind, @RequestParam String description,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate cutoverDate,
            @RequestParam(required = false) String openingEntryNumber, @RequestPart("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new ApiException(ErrorCode.FILE_REJECTED, "No file was uploaded.");
        }
        return migration.upload(kind, description, cutoverDate, openingEntryNumber, file.getOriginalFilename(), file.getBytes());
    }

    @PostMapping("/batches/{id}/apply")
    @PreAuthorize("hasAnyAuthority('PERM_MIGRATION_PREPARE', 'PERM_MIGRATION_APPROVE')")
    public MigrationService.BatchDetail apply(@PathVariable UUID id, @Valid @RequestBody VersionRequest request) {
        return migration.apply(id, request.version());
    }

    @PostMapping("/batches/{id}/cancel")
    @PreAuthorize("hasAnyAuthority('PERM_MIGRATION_PREPARE', 'PERM_MIGRATION_APPROVE')")
    public MigrationService.BatchDetail cancel(@PathVariable UUID id, @Valid @RequestBody CancelRequest request) {
        return migration.cancel(id, request.version(), request.reason());
    }

    @PostMapping(value = "/batches/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_MIGRATION_PREPARE')")
    public DocumentService.DocumentInfo attach(@PathVariable UUID id, @RequestPart("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new ApiException(ErrorCode.FILE_REJECTED, "No file was uploaded.");
        }
        return documents.upload(MigrationService.OWNER, id, file.getOriginalFilename(), file.getBytes());
    }

    /** The empty CAS template for a batch kind: the header row and a comment describing each column. */
    @GetMapping("/templates/{kind}")
    @PreAuthorize("hasAuthority('PERM_MIGRATION_PREPARE')")
    public ResponseEntity<byte[]> template(@PathVariable MigrationService.Kind kind) {
        String body = MigrationTemplates.text(kind);
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + kind.name().toLowerCase() + "-template.csv\"")
                .body(body.getBytes(StandardCharsets.UTF_8));
    }
}
