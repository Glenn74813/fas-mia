package com.miaa.cas.accountsreceivable.web;

import com.miaa.cas.accountsreceivable.ArDtos.ActionRequest;
import com.miaa.cas.accountsreceivable.ArDtos.ApplyRequest;
import com.miaa.cas.accountsreceivable.ArDtos.ArAgingReport;
import com.miaa.cas.accountsreceivable.ArDtos.ArReconciliation;
import com.miaa.cas.accountsreceivable.ArDtos.BillingRequest;
import com.miaa.cas.accountsreceivable.ArDtos.BillingRunRequest;
import com.miaa.cas.accountsreceivable.ArDtos.BillingRunResult;
import com.miaa.cas.accountsreceivable.ArDtos.BillingSummary;
import com.miaa.cas.accountsreceivable.ArDtos.BillingView;
import com.miaa.cas.accountsreceivable.ArDtos.CancelRequest;
import com.miaa.cas.accountsreceivable.ArDtos.ContractRequest;
import com.miaa.cas.accountsreceivable.ArDtos.ContractStatusRequest;
import com.miaa.cas.accountsreceivable.ArDtos.ContractView;
import com.miaa.cas.accountsreceivable.ArDtos.CustomerRequest;
import com.miaa.cas.accountsreceivable.ArDtos.CustomerUpdateRequest;
import com.miaa.cas.accountsreceivable.ArDtos.CustomerView;
import com.miaa.cas.accountsreceivable.ArDtos.OpenItem;
import com.miaa.cas.accountsreceivable.ArDtos.PortalUserView;
import com.miaa.cas.accountsreceivable.ArDtos.ReceiptRequest;
import com.miaa.cas.accountsreceivable.ArDtos.ReceiptSummary;
import com.miaa.cas.accountsreceivable.ArDtos.ReceiptView;
import com.miaa.cas.accountsreceivable.ArDtos.Statement;
import com.miaa.cas.accountsreceivable.ArReportService;
import com.miaa.cas.accountsreceivable.BillingService;
import com.miaa.cas.accountsreceivable.CustomerService;
import com.miaa.cas.accountsreceivable.PortalAccessService;
import com.miaa.cas.accountsreceivable.PortalInquiryService;
import com.miaa.cas.accountsreceivable.ReceiptService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.idempotency.IdempotencyService;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.documents.DocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@Tag(name = "Accounts receivable", description = "Customers, contracts, billing, collections and receivables reports")
public class ReceivablesController {

    private final CustomerService customers;
    private final BillingService billings;
    private final ReceiptService receipts;
    private final ArReportService reports;
    private final DocumentService documents;
    private final PortalAccessService portalAccess;
    private final PortalInquiryService inquiries;
    private final IdempotencyService idempotency;

    public ReceivablesController(CustomerService customers, BillingService billings, ReceiptService receipts,
            ArReportService reports, DocumentService documents, PortalAccessService portalAccess, PortalInquiryService inquiries,
            IdempotencyService idempotency) {
        this.portalAccess = portalAccess;
        this.inquiries = inquiries;
        this.idempotency = idempotency;
        this.customers = customers;
        this.billings = billings;
        this.receipts = receipts;
        this.reports = reports;
        this.documents = documents;
    }

    // ---------------------------------------------------------------- customers
    @GetMapping("/customers")
    @PreAuthorize("hasAnyAuthority('PERM_AR_VIEW', 'PERM_COLLECTION_RECORD')")
    public PageResponse<CustomerView> customers(@RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return customers.list(q, page, size);
    }

    @GetMapping("/customers/{id}")
    @PreAuthorize("hasAnyAuthority('PERM_AR_VIEW', 'PERM_COLLECTION_RECORD')")
    public CustomerView customer(@PathVariable UUID id) {
        return customers.get(id);
    }

    @PostMapping("/customers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_CUSTOMER_MANAGE')")
    public CustomerView createCustomer(@Valid @RequestBody CustomerRequest request) {
        return customers.create(request);
    }

    @PutMapping("/customers/{id}")
    @PreAuthorize("hasAuthority('PERM_CUSTOMER_MANAGE')")
    public CustomerView updateCustomer(@PathVariable UUID id, @Valid @RequestBody CustomerUpdateRequest request) {
        return customers.update(id, request);
    }

    @GetMapping("/customers/{id}/open-items")
    @PreAuthorize("hasAnyAuthority('PERM_AR_VIEW', 'PERM_COLLECTION_RECORD')")
    @Operation(summary = "Posted invoices and debit memos with a balance still to collect")
    public List<OpenItem> openItems(@PathVariable UUID id) {
        return receipts.openItems(id);
    }

    @GetMapping("/customers/{id}/contracts")
    @PreAuthorize("hasAuthority('PERM_AR_VIEW')")
    public List<ContractView> contracts(@PathVariable UUID id) {
        return customers.contracts(id);
    }

    @PostMapping("/customers/{id}/contracts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_CUSTOMER_MANAGE')")
    public ContractView createContract(@PathVariable UUID id, @Valid @RequestBody ContractRequest request) {
        return customers.createContract(id, request);
    }

    @PutMapping("/customer-contracts/{id}/status")
    @PreAuthorize("hasAuthority('PERM_CUSTOMER_MANAGE')")
    public ContractView contractStatus(@PathVariable UUID id, @Valid @RequestBody ContractStatusRequest request) {
        return customers.setContractStatus(id, request.version(), request.status());
    }

    @GetMapping("/customers/{id}/portal-users")
    @PreAuthorize("hasAuthority('PERM_AR_VIEW')")
    public List<PortalUserView> portalUsers(@PathVariable UUID id) {
        return customers.portalUsers(id);
    }

    @PostMapping("/customers/{id}/portal-link-requests")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_CUSTOMER_MANAGE')")
    @Operation(summary = "Request that a concessionaire portal account be linked to this customer (a second person approves)")
    public PortalAccessService.RequestView requestPortalLink(@PathVariable UUID id,
            @Valid @RequestBody PortalAccessService.LinkRequest request) {
        return portalAccess.request(id, request);
    }

    @GetMapping("/portal-link-requests")
    @PreAuthorize("hasAnyAuthority('PERM_CUSTOMER_MANAGE', 'PERM_PORTAL_ACCESS_APPROVE')")
    public PageResponse<PortalAccessService.RequestView> portalLinkRequests(
            @RequestParam(required = false) UUID customerId, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return portalAccess.list(customerId, status, page, size);
    }

    @GetMapping("/portal-link-requests/{id}")
    @PreAuthorize("hasAnyAuthority('PERM_CUSTOMER_MANAGE', 'PERM_PORTAL_ACCESS_APPROVE')")
    public PortalAccessService.RequestView portalLinkRequest(@PathVariable UUID id) {
        return portalAccess.get(id);
    }

    @PostMapping("/portal-link-requests/{id}/decision")
    @PreAuthorize("hasAuthority('PERM_PORTAL_ACCESS_APPROVE')")
    public PortalAccessService.RequestView decidePortalLink(@PathVariable UUID id,
            @Valid @RequestBody PortalAccessService.Decision request) {
        return portalAccess.decide(id, request.approve(), request.note());
    }

    @PostMapping("/portal-link-requests/{id}/cancel")
    @PreAuthorize("hasAuthority('PERM_CUSTOMER_MANAGE')")
    public PortalAccessService.RequestView cancelPortalLink(@PathVariable UUID id) {
        return portalAccess.cancel(id);
    }

    @PostMapping(value = "/portal-link-requests/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_DOCUMENT_UPLOAD')")
    public DocumentService.DocumentInfo uploadPortalLinkEvidence(@PathVariable UUID id, @RequestPart("file") MultipartFile file)
            throws IOException {
        return documents.upload(PortalAccessService.OWNER, id, file.getOriginalFilename(), bytes(file));
    }

    @GetMapping("/portal-inquiries")
    @PreAuthorize("hasAuthority('PERM_AR_VIEW')")
    public PageResponse<PortalInquiryService.InquiryView> portalInquiries(@RequestParam(required = false) String status,
            @RequestParam(required = false) UUID customerId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return inquiries.list(status, customerId, page, size);
    }

    @GetMapping("/portal-inquiries/{id}")
    @PreAuthorize("hasAuthority('PERM_AR_VIEW')")
    public PortalInquiryService.InquiryView portalInquiry(@PathVariable UUID id) {
        return inquiries.get(id);
    }

    public record InquiryResponse(@NotNull Long version, @NotBlank String response) {
    }

    @PostMapping("/portal-inquiries/{id}/response")
    @PreAuthorize("hasAuthority('PERM_PORTAL_INQUIRY_RESPOND')")
    public PortalInquiryService.InquiryView respondToInquiry(@PathVariable UUID id, @Valid @RequestBody InquiryResponse request) {
        return inquiries.respond(id, request.version(), request.response());
    }

    @PostMapping("/portal-inquiries/{id}/close")
    @PreAuthorize("hasAuthority('PERM_PORTAL_INQUIRY_RESPOND')")
    public PortalInquiryService.InquiryView closeInquiry(@PathVariable UUID id, @Valid @RequestBody VersionOnly request) {
        return inquiries.close(id, request.version());
    }

    public record VersionOnly(@NotNull Long version) {
    }

    @DeleteMapping("/customers/{id}/portal-users/{userId}")
    @PreAuthorize("hasAuthority('PERM_CUSTOMER_MANAGE')")
    public List<PortalUserView> unlinkPortalUser(@PathVariable UUID id, @PathVariable UUID userId) {
        return customers.unlinkPortalUser(id, userId);
    }

    // ---------------------------------------------------------------- billing documents
    @GetMapping("/accounts-receivable/billings")
    @PreAuthorize("hasAuthority('PERM_AR_VIEW')")
    public PageResponse<BillingSummary> billings(@RequestParam(required = false) String kind,
            @RequestParam(required = false) String status, @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size, @RequestParam(required = false) String sort) {
        return billings.list(kind, status, customerId, q, page, size, sort);
    }

    @GetMapping("/accounts-receivable/billings/{id}")
    @PreAuthorize("hasAuthority('PERM_AR_VIEW')")
    public BillingView billing(@PathVariable UUID id) {
        return billings.get(id);
    }

    @PostMapping("/accounts-receivable/billings")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_AR_BILLING_PREPARE')")
    @Operation(summary = "Prepare a billing invoice, credit memo or debit memo (output VAT computed from the tax code)")
    public BillingView createBilling(@Valid @RequestBody BillingRequest request) {
        return billings.create(request);
    }

    @PutMapping("/accounts-receivable/billings/{id}")
    @PreAuthorize("hasAuthority('PERM_AR_BILLING_PREPARE')")
    public BillingView updateBilling(@PathVariable UUID id, @RequestParam long version, @Valid @RequestBody BillingRequest request) {
        return billings.update(id, version, request);
    }

    @PostMapping("/accounts-receivable/billing-runs")
    @PreAuthorize("hasAuthority('PERM_AR_BILLING_PREPARE')")
    @Operation(summary = "Generate draft invoices for active contracts for a month; re-running the month creates nothing new")
    public BillingRunResult billingRun(@Valid @RequestBody BillingRunRequest request) {
        return billings.runContractBilling(request.billingPeriod(), request.documentDate());
    }

    @PostMapping("/accounts-receivable/billings/{id}/{action:submit|approve|return|reject|cancel|post}")
    @PreAuthorize("hasAnyAuthority('PERM_AR_BILLING_PREPARE', 'PERM_AR_BILLING_APPROVE', 'PERM_JOURNAL_POST')")
    @Operation(summary = "Billing workflow actions; post recognises the receivable in the general ledger")
    public BillingView billingAction(@PathVariable UUID id, @PathVariable String action, @Valid @RequestBody ActionRequest request) {
        return switch (action) {
            case "submit" -> billings.submit(id, request.version(), request.comment());
            case "approve" -> billings.approve(id, request.version(), request.comment());
            case "return" -> billings.returnOrReject(id, request.version(), request.comment(), false);
            case "reject" -> billings.returnOrReject(id, request.version(), request.comment(), true);
            case "cancel" -> billings.cancel(id, request.version(), request.comment());
            default -> billings.post(id, request.version());
        };
    }

    @PostMapping(value = "/accounts-receivable/billings/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_DOCUMENT_UPLOAD')")
    public DocumentService.DocumentInfo uploadBillingDocument(@PathVariable UUID id, @RequestPart("file") MultipartFile file)
            throws IOException {
        return documents.upload(BillingService.OWNER, id, file.getOriginalFilename(), bytes(file));
    }

    // ---------------------------------------------------------------- receipts
    @GetMapping("/receipts")
    @PreAuthorize("hasAnyAuthority('PERM_AR_VIEW', 'PERM_COLLECTION_RECORD')")
    public PageResponse<ReceiptSummary> receipts(@RequestParam(required = false) String status,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort) {
        return receipts.list(status, customerId, from, to, page, size, sort);
    }

    @GetMapping("/receipts/{id}")
    @PreAuthorize("hasAnyAuthority('PERM_AR_VIEW', 'PERM_COLLECTION_RECORD')")
    public ReceiptView receipt(@PathVariable UUID id) {
        return receipts.get(id);
    }

    @PostMapping("/receipts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_COLLECTION_RECORD')")
    @Operation(summary = "Record a collection and its application to open invoices (numbered immediately, gap-free). "
            + "Requires an Idempotency-Key header")
    public Object recordReceipt(@Valid @RequestBody ReceiptRequest request, @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return idempotency.once("AR_RECEIPT_RECORD", idempotencyKey, IdempotencyService.hash("AR_RECEIPT_RECORD", request), 201,
                () -> receipts.record(request));
    }

    @PostMapping("/receipts/{id}/post")
    @PreAuthorize("hasAuthority('PERM_JOURNAL_POST')")
    public ReceiptView postReceipt(@PathVariable UUID id, @Valid @RequestBody ActionRequest request) {
        return receipts.post(id, request.version());
    }

    @PostMapping("/receipts/{id}/cancel")
    @PreAuthorize("hasAnyAuthority('PERM_COLLECTION_RECORD', 'PERM_JOURNAL_POST')")
    public ReceiptView cancelReceipt(@PathVariable UUID id, @Valid @RequestBody CancelRequest request) {
        return receipts.cancel(id, request.version(), request.reason());
    }

    @PostMapping("/receipts/{id}/applications")
    @PreAuthorize("hasAuthority('PERM_JOURNAL_POST')")
    @Operation(summary = "Apply a posted receipt's unapplied amount to open invoices. Requires an Idempotency-Key header")
    public Object applyReceipt(@PathVariable UUID id, @Valid @RequestBody ApplyRequest request, @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return idempotency.once("AR_RECEIPT_APPLY", idempotencyKey, IdempotencyService.hash("AR_RECEIPT_APPLY", id, request), 200,
                () -> receipts.applyUnapplied(id, request.version(), request.accountingDate(), request.allocations()));
    }

    @PostMapping(value = "/receipts/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_DOCUMENT_UPLOAD')")
    public DocumentService.DocumentInfo uploadReceiptDocument(@PathVariable UUID id, @RequestPart("file") MultipartFile file)
            throws IOException {
        return documents.upload(ReceiptService.OWNER, id, file.getOriginalFilename(), bytes(file));
    }

    // ---------------------------------------------------------------- reports
    @GetMapping("/accounts-receivable/aging")
    @PreAuthorize("hasAuthority('PERM_AR_VIEW')")
    public ArAgingReport aging(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(required = false) UUID customerId) {
        return reports.aging(asOf, customerId);
    }

    @GetMapping("/customers/{id}/statement")
    @PreAuthorize("hasAuthority('PERM_AR_VIEW')")
    public Statement statement(@PathVariable UUID id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.statement(id, from, to);
    }

    @GetMapping("/accounts-receivable/reconciliation")
    @PreAuthorize("hasAuthority('PERM_AR_VIEW') and hasAuthority('PERM_LEDGER_VIEW')")
    public ArReconciliation reconciliation() {
        return reports.reconciliation();
    }

    private static byte[] bytes(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new ApiException(ErrorCode.FILE_REJECTED, "No file was uploaded.");
        }
        return file.getBytes();
    }
}
