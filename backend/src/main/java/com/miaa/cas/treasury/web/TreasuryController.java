package com.miaa.cas.treasury.web;

import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.idempotency.IdempotencyService;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.documents.DocumentService;
import com.miaa.cas.treasury.BankReconciliationService;
import com.miaa.cas.treasury.DepositService;
import com.miaa.cas.treasury.StatementImportService;
import com.miaa.cas.treasury.TreasuryDtos.ActionRequest;
import com.miaa.cas.treasury.TreasuryDtos.AutoMatchResult;
import com.miaa.cas.treasury.TreasuryDtos.CancelRequest;
import com.miaa.cas.treasury.TreasuryDtos.CashPosition;
import com.miaa.cas.treasury.TreasuryDtos.CheckRow;
import com.miaa.cas.treasury.TreasuryDtos.DailyCollectionReport;
import com.miaa.cas.treasury.TreasuryDtos.DepositRequest;
import com.miaa.cas.treasury.TreasuryDtos.DepositSummary;
import com.miaa.cas.treasury.TreasuryDtos.DepositView;
import com.miaa.cas.treasury.TreasuryDtos.ManualMatchRequest;
import com.miaa.cas.treasury.TreasuryDtos.ReconciliationView;
import com.miaa.cas.treasury.TreasuryDtos.StatementSummary;
import com.miaa.cas.treasury.TreasuryDtos.StatementView;
import com.miaa.cas.treasury.TreasuryDtos.UndepositedReceipt;
import com.miaa.cas.treasury.TreasuryDtos.VersionRequest;
import com.miaa.cas.treasury.TreasuryReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
@Tag(name = "Treasury", description = "Deposits, daily collection report, cash position, check register, bank statements and reconciliation")
public class TreasuryController {

    private final DepositService deposits;
    private final TreasuryReportService reports;
    private final StatementImportService statements;
    private final BankReconciliationService reconciliations;
    private final DocumentService documents;
    private final com.miaa.cas.treasury.PaymentFileService paymentFiles;
    private final IdempotencyService idempotency;

    public record PaymentFileRequest(@jakarta.validation.constraints.NotNull UUID bankAccountId,
            @jakarta.validation.constraints.NotNull LocalDate paymentDate,
            @jakarta.validation.constraints.NotEmpty @jakarta.validation.constraints.Size(max = 500) List<UUID> dvIds) {
    }

    public TreasuryController(DepositService deposits, TreasuryReportService reports, StatementImportService statements,
            BankReconciliationService reconciliations, DocumentService documents, com.miaa.cas.treasury.PaymentFileService paymentFiles,
            IdempotencyService idempotency) {
        this.paymentFiles = paymentFiles;
        this.idempotency = idempotency;
        this.deposits = deposits;
        this.reports = reports;
        this.statements = statements;
        this.reconciliations = reconciliations;
        this.documents = documents;
    }

    // ---------------------------------------------------------------- deposits
    @GetMapping("/treasury/undeposited-receipts")
    @PreAuthorize("hasAnyAuthority('PERM_TREASURY_VIEW', 'PERM_DEPOSIT_PREPARE')")
    public List<UndepositedReceipt> undeposited() {
        return deposits.undeposited();
    }

    @GetMapping("/deposits")
    @PreAuthorize("hasAuthority('PERM_TREASURY_VIEW')")
    public PageResponse<DepositSummary> deposits(@RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return deposits.list(status, page, size);
    }

    @GetMapping("/deposits/{id}")
    @PreAuthorize("hasAuthority('PERM_TREASURY_VIEW')")
    public DepositView deposit(@PathVariable UUID id) {
        return deposits.get(id);
    }

    @PostMapping("/deposits")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_DEPOSIT_PREPARE')")
    @Operation(summary = "Prepare a deposit of posted collections to a bank account")
    public DepositView createDeposit(@Valid @RequestBody DepositRequest request) {
        return deposits.create(request);
    }

    @PostMapping("/deposits/{id}/post")
    @PreAuthorize("hasAuthority('PERM_JOURNAL_POST')")
    public DepositView postDeposit(@PathVariable UUID id, @Valid @RequestBody VersionRequest request) {
        return deposits.post(id, request.version());
    }

    @PostMapping("/deposits/{id}/cancel")
    @PreAuthorize("hasAnyAuthority('PERM_DEPOSIT_PREPARE', 'PERM_JOURNAL_POST')")
    public DepositView cancelDeposit(@PathVariable UUID id, @Valid @RequestBody CancelRequest request) {
        return deposits.cancel(id, request.version(), request.reason());
    }

    @PostMapping(value = "/deposits/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_DOCUMENT_UPLOAD')")
    public DocumentService.DocumentInfo uploadDepositDocument(@PathVariable UUID id, @RequestPart("file") MultipartFile file)
            throws IOException {
        return documents.upload(DepositService.OWNER, id, file.getOriginalFilename(), bytes(file));
    }

    // ---------------------------------------------------------------- reports
    @GetMapping("/treasury/daily-collection-report")
    @PreAuthorize("hasAnyAuthority('PERM_TREASURY_VIEW', 'PERM_COLLECTION_RECORD')")
    public DailyCollectionReport dailyCollections(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String collector) {
        return reports.dailyCollections(date, collector);
    }

    @GetMapping("/treasury/cash-position")
    @PreAuthorize("hasAuthority('PERM_TREASURY_VIEW')")
    public CashPosition cashPosition(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return reports.cashPosition(asOf);
    }

    @GetMapping("/treasury/checks")
    @PreAuthorize("hasAnyAuthority('PERM_TREASURY_VIEW', 'PERM_AP_VIEW')")
    public List<CheckRow> checks(@RequestParam(required = false) UUID bankAccountId, @RequestParam(required = false) String status,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return reports.checks(bankAccountId, status, asOf);
    }

    // ---------------------------------------------------------------- statements
    @PostMapping(value = "/bank-statements", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_BANK_STATEMENT_IMPORT')")
    @Operation(summary = "Import a bank statement (CSV, MT940 or CAMT053); it must balance and not overlap earlier statements")
    public StatementView importStatement(@RequestParam UUID bankAccountId, @RequestParam String format,
            @RequestParam(required = false) BigDecimal openingBalance, @RequestParam(required = false) BigDecimal closingBalance,
            @RequestPart("file") MultipartFile file) throws IOException {
        return statements.importStatement(bankAccountId, format, file.getOriginalFilename(), bytes(file), openingBalance,
                closingBalance);
    }

    @GetMapping("/bank-statements")
    @PreAuthorize("hasAuthority('PERM_TREASURY_VIEW')")
    public PageResponse<StatementSummary> statements(@RequestParam(required = false) UUID bankAccountId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return statements.list(bankAccountId, page, size);
    }

    @GetMapping("/bank-statements/{id}")
    @PreAuthorize("hasAuthority('PERM_TREASURY_VIEW')")
    public StatementView statement(@PathVariable UUID id) {
        return statements.get(id);
    }

    // ---------------------------------------------------------------- reconciliation
    @PostMapping("/bank-statements/{id}/reconciliation")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_BANK_RECONCILIATION_PREPARE')")
    public ReconciliationView startReconciliation(@PathVariable UUID id) {
        return reconciliations.start(id);
    }

    @GetMapping("/bank-reconciliations")
    @PreAuthorize("hasAuthority('PERM_TREASURY_VIEW')")
    public PageResponse<Map<String, Object>> reconciliations(@RequestParam(required = false) UUID bankAccountId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return reconciliations.list(bankAccountId, page, size);
    }

    @GetMapping("/bank-reconciliations/{id}")
    @PreAuthorize("hasAuthority('PERM_TREASURY_VIEW')")
    public ReconciliationView reconciliation(@PathVariable UUID id) {
        return reconciliations.get(id);
    }

    @PostMapping("/bank-reconciliations/{id}/auto-match")
    @PreAuthorize("hasAuthority('PERM_BANK_RECONCILIATION_PREPARE')")
    public AutoMatchResult autoMatch(@PathVariable UUID id, @Valid @RequestBody VersionRequest request) {
        return reconciliations.autoMatch(id, request.version());
    }

    @PostMapping("/bank-reconciliations/{id}/matches")
    @PreAuthorize("hasAuthority('PERM_BANK_RECONCILIATION_PREPARE')")
    public ReconciliationView match(@PathVariable UUID id, @Valid @RequestBody ManualMatchRequest request) {
        return reconciliations.match(id, request.version(), request.statementLineIds(), request.journalLineIds());
    }

    @DeleteMapping("/bank-reconciliations/{id}/matches/{matchId}")
    @PreAuthorize("hasAuthority('PERM_BANK_RECONCILIATION_PREPARE')")
    public ReconciliationView unmatch(@PathVariable UUID id, @PathVariable UUID matchId, @RequestParam long version) {
        return reconciliations.unmatch(id, version, matchId);
    }

    @PostMapping("/bank-reconciliations/{id}/{action:submit|approve|return}")
    @PreAuthorize("hasAnyAuthority('PERM_BANK_RECONCILIATION_PREPARE', 'PERM_BANK_RECONCILIATION_APPROVE')")
    public ReconciliationView reconciliationAction(@PathVariable UUID id, @PathVariable String action,
            @Valid @RequestBody ActionRequest request) {
        return switch (action) {
            case "submit" -> reconciliations.submit(id, request.version(), request.comment());
            case "approve" -> reconciliations.approve(id, request.version(), request.comment());
            default -> reconciliations.returnToPreparer(id, request.version(), request.comment());
        };
    }

    // ---------------------------------------------------------------- payment files
    @GetMapping("/payment-files/eligible")
    @PreAuthorize("hasAuthority('PERM_PAYMENT_RELEASE')")
    public List<com.miaa.cas.treasury.PaymentFileService.EligibleDv> eligibleForPaymentFile() {
        return paymentFiles.eligible();
    }

    @GetMapping("/payment-files")
    @PreAuthorize("hasAnyAuthority('PERM_PAYMENT_RELEASE', 'PERM_TREASURY_VIEW')")
    public PageResponse<com.miaa.cas.treasury.PaymentFileService.BatchView> paymentFiles(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return paymentFiles.list(page, size);
    }

    @PostMapping("/payment-files")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_PAYMENT_RELEASE')")
    @Operation(summary = "Issue ADA instructions for approved vouchers and produce one payment file (CAS generic layout, C-11). "
            + "Requires an Idempotency-Key header")
    public Object createPaymentFile(@Valid @RequestBody PaymentFileRequest request, @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return idempotency.once("TR_PAYMENT_FILE", idempotencyKey, IdempotencyService.hash("TR_PAYMENT_FILE", request), 201,
                () -> paymentFiles.create(new com.miaa.cas.treasury.PaymentFileService.BatchRequest(request.bankAccountId(),
                        request.paymentDate(), request.dvIds())));
    }

    @GetMapping("/payment-files/{id}/file")
    @PreAuthorize("hasAuthority('PERM_PAYMENT_RELEASE')")
    public org.springframework.http.ResponseEntity<byte[]> paymentFile(@PathVariable UUID id) {
        var f = paymentFiles.download(id);
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        org.springframework.http.ContentDisposition.attachment().filename(f.fileName()).build().toString())
                .header("X-Content-Type-Options", "nosniff").header("Cache-Control", "no-store")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8")).body(f.content());
    }

    private static byte[] bytes(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new ApiException(ErrorCode.FILE_REJECTED, "No file was uploaded.");
        }
        return file.getBytes();
    }
}
