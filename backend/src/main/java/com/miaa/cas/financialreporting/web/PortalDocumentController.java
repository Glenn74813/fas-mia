package com.miaa.cas.financialreporting.web;

import com.miaa.cas.financialreporting.PortalDocumentService;
import com.miaa.cas.financialreporting.PortalDocumentService.PortalPdf;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** PDF copies for concessionaires: their own posted billings and statement of account only. */
@RestController
@RequestMapping("/api/v1/portal")
@Tag(name = "Concessionaire portal")
@PreAuthorize("hasAuthority('PERM_PORTAL_SELF_VIEW')")
public class PortalDocumentController {

    private final PortalDocumentService documents;

    public PortalDocumentController(PortalDocumentService documents) {
        this.documents = documents;
    }

    @GetMapping("/billings/{id}/pdf")
    public ResponseEntity<byte[]> billing(@PathVariable UUID id) {
        return pdf(documents.billing(id));
    }

    @GetMapping("/statement/pdf")
    public ResponseEntity<byte[]> statement(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return pdf(documents.statement(from, to));
    }

    private static ResponseEntity<byte[]> pdf(PortalPdf f) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(f.fileName()).build().toString())
                .header("X-Content-Type-Options", "nosniff").header(HttpHeaders.CACHE_CONTROL, "no-store").body(f.content());
    }
}
