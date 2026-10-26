package com.miaa.cas.documents;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/documents")
@Tag(name = "Documents", description = "Download and removal of supporting documents")
public class DocumentController {

    private final DocumentService documents;

    public DocumentController(DocumentService documents) {
        this.documents = documents;
    }

    @GetMapping("/{id}/content")
    @PreAuthorize("hasAuthority('PERM_DOCUMENT_VIEW')")
    @Operation(summary = "Download a document (always as an attachment; access is audited)")
    public ResponseEntity<byte[]> download(@PathVariable UUID id) {
        DocumentService.Download d = documents.download(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(d.document().getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(d.document().getFileName(), java.nio.charset.StandardCharsets.UTF_8)
                                .build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(d.content());
    }

    @PostMapping("/{id}/remove")
    @PreAuthorize("hasAuthority('PERM_DOCUMENT_UPLOAD')")
    public DocumentService.DocumentInfo remove(@PathVariable UUID id) {
        return documents.remove(id);
    }
}
