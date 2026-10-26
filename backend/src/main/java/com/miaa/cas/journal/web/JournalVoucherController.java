package com.miaa.cas.journal.web;

import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.web.ApiDeprecation;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.documents.DocumentService;
import com.miaa.cas.journal.DecisionLog;
import com.miaa.cas.journal.JournalVoucherService;
import com.miaa.cas.journal.PostingService;
import com.miaa.cas.journal.VoucherQueryService;
import com.miaa.cas.journal.web.JournalDtos.ActionRequest;
import com.miaa.cas.journal.web.JournalDtos.ClosingVoucherRequest;
import com.miaa.cas.journal.web.JournalDtos.ReversalRequest;
import com.miaa.cas.journal.web.JournalDtos.UpdateVoucherRequest;
import com.miaa.cas.journal.web.JournalDtos.VoucherDetail;
import com.miaa.cas.journal.web.JournalDtos.VoucherRequest;
import com.miaa.cas.journal.web.JournalDtos.VoucherSummary;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Journal vouchers", description = "JEV preparation, approval workflow, posting, reversal and year-end closing")
public class JournalVoucherController {

    private final JournalVoucherService commands;
    private final PostingService posting;
    private final VoucherQueryService queries;
    private final DocumentService documents;

    public JournalVoucherController(JournalVoucherService commands, PostingService posting, VoucherQueryService queries,
            DocumentService documents) {
        this.commands = commands;
        this.posting = posting;
        this.queries = queries;
        this.documents = documents;
    }

    @GetMapping("/journal-vouchers")
    @PreAuthorize("hasAuthority('PERM_LEDGER_VIEW') or hasAuthority('PERM_JOURNAL_CREATE')")
    @Operation(summary = "Search vouchers. sort: accountingDate|updatedAt|voucherNumber|total, '-' prefix for descending")
    public PageResponse<VoucherSummary> list(@RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String q, @RequestParam(defaultValue = "false") boolean mine,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort) {
        return queries.list(status, type, from, to, q, mine, page, size, sort);
    }

    @GetMapping("/journal-vouchers/approval-queue")
    @PreAuthorize("hasAnyAuthority('PERM_JOURNAL_REVIEW', 'PERM_JOURNAL_APPROVE', 'PERM_JOURNAL_POST')")
    @Operation(summary = "Deprecated: use GET /api/v1/inbox. Vouchers awaiting an action the current user may take")
    @Deprecated(since = "2026-10-23")
    @ApiDeprecation(since = "2026-10-23", sunset = "2027-04-30", successor = "/api/v1/inbox")
    public List<VoucherSummary> queue() {
        return queries.awaitingMyAction();
    }

    @GetMapping("/journal-vouchers/{id}")
    @PreAuthorize("hasAuthority('PERM_LEDGER_VIEW')")
    public VoucherDetail get(@PathVariable UUID id) {
        return queries.detail(id);
    }

    @PostMapping("/journal-vouchers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_JOURNAL_CREATE')")
    public VoucherDetail create(@Valid @RequestBody VoucherRequest request) {
        return commands.create(request);
    }

    @PutMapping("/journal-vouchers/{id}")
    @PreAuthorize("hasAuthority('PERM_JOURNAL_CREATE')")
    public VoucherDetail update(@PathVariable UUID id, @Valid @RequestBody UpdateVoucherRequest request) {
        return commands.update(id, request);
    }

    @PostMapping("/journal-vouchers/{id}/submit")
    @PreAuthorize("hasAuthority('PERM_JOURNAL_SUBMIT')")
    public VoucherDetail submit(@PathVariable UUID id, @Valid @RequestBody ActionRequest request) {
        return commands.submit(id, request.version(), request.comment());
    }

    @PostMapping("/journal-vouchers/{id}/approve")
    @PreAuthorize("hasAnyAuthority('PERM_JOURNAL_REVIEW', 'PERM_JOURNAL_APPROVE')")
    @Operation(summary = "Complete the current review/approval step (permission depends on the step)")
    public VoucherDetail approve(@PathVariable UUID id, @Valid @RequestBody ActionRequest request) {
        return commands.approveStep(id, request.version(), request.comment());
    }

    @PostMapping("/journal-vouchers/{id}/return")
    @PreAuthorize("hasAnyAuthority('PERM_JOURNAL_REVIEW', 'PERM_JOURNAL_APPROVE', 'PERM_JOURNAL_POST')")
    @Operation(summary = "Return to the preparer for correction (comment required)")
    public VoucherDetail returnVoucher(@PathVariable UUID id, @Valid @RequestBody ActionRequest request) {
        return commands.returnOrReject(id, request.version(), request.comment(), false);
    }

    @PostMapping("/journal-vouchers/{id}/reject")
    @PreAuthorize("hasAnyAuthority('PERM_JOURNAL_REVIEW', 'PERM_JOURNAL_APPROVE', 'PERM_JOURNAL_POST')")
    @Operation(summary = "Reject permanently (comment required)")
    public VoucherDetail reject(@PathVariable UUID id, @Valid @RequestBody ActionRequest request) {
        return commands.returnOrReject(id, request.version(), request.comment(), true);
    }

    @PostMapping("/journal-vouchers/{id}/cancel")
    @PreAuthorize("hasAuthority('PERM_JOURNAL_CREATE')")
    public VoucherDetail cancel(@PathVariable UUID id, @Valid @RequestBody ActionRequest request) {
        return commands.cancel(id, request.version(), request.comment());
    }

    @PostMapping("/journal-vouchers/{id}/post")
    @PreAuthorize("hasAuthority('PERM_JOURNAL_POST')")
    @Operation(summary = "Post an approved voucher to the general ledger. Requires an Idempotency-Key header")
    public Object post(@PathVariable UUID id, @Valid @RequestBody ActionRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return posting.post(id, request.version(), idempotencyKey);
    }

    @GetMapping("/journal-vouchers/{id}/documents")
    @PreAuthorize("hasAuthority('PERM_LEDGER_VIEW')")
    public List<DocumentService.DocumentInfo> documents(@PathVariable UUID id) {
        return documents.list(DecisionLog.JOURNAL_VOUCHER, id);
    }

    @PostMapping(value = "/journal-vouchers/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_DOCUMENT_UPLOAD')")
    @Operation(summary = "Attach a supporting document (pdf, png, jpg, csv, xlsx, docx; max 10 MB)")
    public DocumentService.DocumentInfo upload(@PathVariable UUID id, @RequestPart("file") MultipartFile file)
            throws IOException {
        if (file == null || file.isEmpty()) {
            throw new ApiException(ErrorCode.FILE_REJECTED, "No file was uploaded.");
        }
        return documents.upload(DecisionLog.JOURNAL_VOUCHER, id, file.getOriginalFilename(), file.getBytes());
    }

    @PostMapping("/journal-entries/{entryId}/reversal")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_JOURNAL_REVERSE')")
    @Operation(summary = "Create and submit a reversal voucher for a posted entry (the original is never changed)")
    public VoucherDetail reverse(@PathVariable UUID entryId, @Valid @RequestBody ReversalRequest request) {
        return commands.createReversal(entryId, request.accountingDate(), request.adjustmentPeriod(), request.reason());
    }

    @PostMapping("/fiscal-years/{id}/closing-voucher")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_PERIOD_CLOSE')")
    @Operation(summary = "Generate and submit the year-end closing voucher into the adjustment period")
    public VoucherDetail closingVoucher(@PathVariable UUID id, @Valid @RequestBody ClosingVoucherRequest request) {
        return commands.createClosingVoucher(id, request.surplusAccountId());
    }
}
