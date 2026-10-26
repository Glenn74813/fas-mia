package com.miaa.cas.accountsreceivable.web;

import com.miaa.cas.accountsreceivable.ArDtos.BillingSummary;
import com.miaa.cas.accountsreceivable.ArDtos.PortalAccount;
import com.miaa.cas.accountsreceivable.ArDtos.ReceiptSummary;
import com.miaa.cas.accountsreceivable.ArDtos.Statement;
import com.miaa.cas.accountsreceivable.PortalInquiryService;
import com.miaa.cas.accountsreceivable.PortalService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.documents.DocumentService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

/** Concessionaire self-service: the caller's own account only, resolved server-side. */
@RestController
@RequestMapping("/api/v1/portal")
@Tag(name = "Concessionaire portal")
@PreAuthorize("hasAuthority('PERM_PORTAL_SELF_VIEW')")
public class PortalController {

    private final PortalService portal;
    private final PortalInquiryService inquiries;
    private final DocumentService documents;

    public PortalController(PortalService portal, PortalInquiryService inquiries, DocumentService documents) {
        this.portal = portal;
        this.inquiries = inquiries;
        this.documents = documents;
    }

    @GetMapping("/account")
    public PortalAccount account() {
        return portal.account();
    }

    @GetMapping("/billings")
    public PageResponse<BillingSummary> billings(@RequestParam(required = false) String kind,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return portal.billings(kind, page, size);
    }

    @GetMapping("/billings/{id}")
    public PortalService.PortalBilling billing(@PathVariable UUID id) {
        return portal.billing(id);
    }

    @GetMapping("/receipts")
    public PageResponse<ReceiptSummary> receipts(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return portal.receipts(page, size);
    }

    @GetMapping("/statement")
    public Statement statement(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return portal.statement(from, to);
    }

    @GetMapping("/inquiries")
    public PageResponse<PortalInquiryService.InquiryView> inquiries(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return inquiries.mine(page, size);
    }

    @PostMapping("/inquiries")
    @ResponseStatus(HttpStatus.CREATED)
    public PortalInquiryService.InquiryView submitInquiry(@Valid @RequestBody PortalInquiryService.InquiryRequest request) {
        return inquiries.submit(request);
    }

    @GetMapping("/inquiries/{id}")
    public PortalInquiryService.InquiryView inquiry(@PathVariable UUID id) {
        return inquiries.get(id);
    }

    @PostMapping(value = "/inquiries/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentService.DocumentInfo attachToInquiry(@PathVariable UUID id, @RequestPart("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new ApiException(ErrorCode.FILE_REJECTED, "No file was uploaded.");
        }
        return documents.upload(PortalInquiryService.OWNER, id, file.getOriginalFilename(), file.getBytes());
    }
}
