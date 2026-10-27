package com.miaa.cas.accountspayable.web;

import com.miaa.cas.accountspayable.ApDtos.ActionRequest;
import com.miaa.cas.accountspayable.ApDtos.AdvanceRegister;
import com.miaa.cas.accountspayable.ApDtos.AgingReport;
import com.miaa.cas.accountspayable.ApDtos.ApReconciliation;
import com.miaa.cas.accountspayable.ApDtos.ConfirmPaymentRequest;
import com.miaa.cas.accountspayable.ApDtos.DvRequest;
import com.miaa.cas.accountspayable.ApDtos.DvSummary;
import com.miaa.cas.accountspayable.ApDtos.DvUpdateRequest;
import com.miaa.cas.accountspayable.ApDtos.DvView;
import com.miaa.cas.accountspayable.ApDtos.InvoiceRequest;
import com.miaa.cas.accountspayable.ApDtos.InvoiceSummary;
import com.miaa.cas.accountspayable.ApDtos.InvoiceView;
import com.miaa.cas.accountspayable.ApDtos.PaymentInstructionRequest;
import com.miaa.cas.accountspayable.ApDtos.SupplierRequest;
import com.miaa.cas.accountspayable.ApDtos.SupplierUpdateRequest;
import com.miaa.cas.accountspayable.ApDtos.SupplierView;
import com.miaa.cas.accountspayable.ApDtos.VoidRequest;
import com.miaa.cas.accountspayable.ApDtos.WithholdingSchedule;
import com.miaa.cas.accountspayable.ApInvoiceService;
import com.miaa.cas.accountspayable.ApReportService;
import com.miaa.cas.accountspayable.DisbursementService;
import com.miaa.cas.accountspayable.SupplierService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.idempotency.IdempotencyService;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.documents.DocumentService;
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
@Tag(name = "Accounts payable", description = "Suppliers, supplier invoices, disbursement vouchers and payables reports")
public class PayablesController {

    private final SupplierService suppliers;
    private final ApInvoiceService invoices;
    private final DisbursementService disbursements;
    private final ApReportService reports;
    private final DocumentService documents;
    private final com.miaa.cas.accountspayable.SupplierBankService supplierBanks;
    private final IdempotencyService idempotency;

    public PayablesController(SupplierService suppliers, ApInvoiceService invoices, DisbursementService disbursements,
            ApReportService reports, DocumentService documents, com.miaa.cas.accountspayable.SupplierBankService supplierBanks,
            IdempotencyService idempotency) {
        this.supplierBanks = supplierBanks;
        this.idempotency = idempotency;
        this.suppliers = suppliers;
        this.invoices = invoices;
        this.disbursements = disbursements;
        this.reports = reports;
        this.documents = documents;
    }

    // ---------------------------------------------------------------- suppliers
    @GetMapping("/suppliers")
    @PreAuthorize("hasAnyAuthority('PERM_AP_VIEW', 'PERM_INVENTORY_MANAGE')")
    public PageResponse<SupplierView> suppliers(@RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size, @RequestParam(required = false) String sort) {
        return suppliers.list(q, page, size, sort);
    }

    @GetMapping("/suppliers/{id}")
    @PreAuthorize("hasAuthority('PERM_AP_VIEW')")
    public SupplierView supplier(@PathVariable UUID id) {
        return suppliers.get(id);
    }

    @PostMapping("/suppliers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_SUPPLIER_MANAGE')")
    public SupplierView createSupplier(@Valid @RequestBody SupplierRequest request) {
        return suppliers.create(request);
    }

    @PutMapping("/suppliers/{id}")
    @PreAuthorize("hasAuthority('PERM_SUPPLIER_MANAGE')")
    public SupplierView updateSupplier(@PathVariable UUID id, @Valid @RequestBody SupplierUpdateRequest request) {
        return suppliers.update(id, request);
    }

    @GetMapping("/suppliers/{id}/bank-accounts")
    @PreAuthorize("hasAnyAuthority('PERM_SUPPLIER_MANAGE', 'PERM_SUPPLIER_BANK_VERIFY', 'PERM_AP_VIEW')")
    public List<com.miaa.cas.accountspayable.SupplierBankService.SupplierBankView> supplierBankAccounts(@PathVariable UUID id) {
        return supplierBanks.list(id);
    }

    @PostMapping("/suppliers/{id}/bank-accounts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_SUPPLIER_MANAGE')")
    @Operation(summary = "Enter bank details for ADA payment; they replace any current account and are unusable until verified")
    public com.miaa.cas.accountspayable.SupplierBankService.SupplierBankView addSupplierBankAccount(@PathVariable UUID id,
            @Valid @RequestBody com.miaa.cas.accountspayable.SupplierBankService.BankAccountRequest request) {
        return supplierBanks.add(id, request);
    }

    @PostMapping("/supplier-bank-accounts/{id}/verification")
    @PreAuthorize("hasAuthority('PERM_SUPPLIER_BANK_VERIFY')")
    public com.miaa.cas.accountspayable.SupplierBankService.SupplierBankView verifySupplierBankAccount(@PathVariable UUID id,
            @Valid @RequestBody com.miaa.cas.accountspayable.SupplierBankService.VerifyRequest request) {
        return supplierBanks.verify(id, request.approve(), request.note());
    }

    // ---------------------------------------------------------------- invoices
    @GetMapping("/accounts-payable/invoices")
    @PreAuthorize("hasAuthority('PERM_AP_VIEW')")
    public PageResponse<InvoiceSummary> invoices(@RequestParam(required = false) String status,
            @RequestParam(required = false) UUID supplierId, @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort) {
        return invoices.list(status, supplierId, q, page, size, sort);
    }

    @GetMapping("/accounts-payable/invoices/{id}")
    @PreAuthorize("hasAuthority('PERM_AP_VIEW')")
    public InvoiceView invoice(@PathVariable UUID id) {
        return invoices.get(id);
    }

    @PostMapping("/accounts-payable/invoices")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_AP_INVOICE_PREPARE')")
    @Operation(summary = "Register a supplier invoice (withholding and VAT computed from tax codes)")
    public InvoiceView createInvoice(@Valid @RequestBody InvoiceRequest request) {
        return invoices.create(request);
    }

    @PutMapping("/accounts-payable/invoices/{id}")
    @PreAuthorize("hasAuthority('PERM_AP_INVOICE_PREPARE')")
    public InvoiceView updateInvoice(@PathVariable UUID id, @RequestParam long version, @Valid @RequestBody InvoiceRequest request) {
        return invoices.update(id, version, request);
    }

    @PostMapping("/accounts-payable/invoices/{id}/{action:submit|approve|return|reject|cancel|post}")
    @PreAuthorize("hasAnyAuthority('PERM_AP_INVOICE_PREPARE', 'PERM_AP_INVOICE_APPROVE', 'PERM_JOURNAL_POST')")
    @Operation(summary = "Invoice workflow actions; post recognises the payable in the general ledger")
    public InvoiceView invoiceAction(@PathVariable UUID id, @PathVariable String action, @Valid @RequestBody ActionRequest request) {
        return switch (action) {
            case "submit" -> invoices.submit(id, request.version(), request.comment());
            case "approve" -> invoices.approve(id, request.version(), request.comment());
            case "return" -> invoices.returnOrReject(id, request.version(), request.comment(), false);
            case "reject" -> invoices.returnOrReject(id, request.version(), request.comment(), true);
            case "cancel" -> invoices.cancel(id, request.version(), request.comment());
            default -> invoices.post(id, request.version());
        };
    }

    @PostMapping(value = "/accounts-payable/invoices/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_DOCUMENT_UPLOAD')")
    public DocumentService.DocumentInfo uploadInvoiceDocument(@PathVariable UUID id, @RequestPart("file") MultipartFile file)
            throws IOException {
        return documents.upload(ApInvoiceService.DOCUMENT, id, file.getOriginalFilename(), bytes(file));
    }

    // ---------------------------------------------------------------- disbursement vouchers
    @GetMapping("/disbursement-vouchers")
    @PreAuthorize("hasAuthority('PERM_AP_VIEW')")
    public PageResponse<DvSummary> disbursementVouchers(@RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort) {
        return disbursements.list(status, page, size, sort);
    }

    @GetMapping("/disbursement-vouchers/{id}")
    @PreAuthorize("hasAuthority('PERM_AP_VIEW')")
    public DvView disbursementVoucher(@PathVariable UUID id) {
        return disbursements.get(id);
    }

    @PostMapping("/disbursement-vouchers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_DISBURSEMENT_PREPARE')")
    public DvView createDv(@Valid @RequestBody DvRequest request) {
        return disbursements.create(request.supplierId(), request.particulars(), request.allocations());
    }

    @PutMapping("/disbursement-vouchers/{id}")
    @PreAuthorize("hasAuthority('PERM_DISBURSEMENT_PREPARE')")
    public DvView updateDv(@PathVariable UUID id, @Valid @RequestBody DvUpdateRequest request) {
        return disbursements.update(id, request.version(), request.particulars(), request.allocations());
    }

    @PostMapping("/disbursement-vouchers/{id}/{action:submit|approve|return|reject|cancel|post}")
    @PreAuthorize("hasAnyAuthority('PERM_DISBURSEMENT_PREPARE', 'PERM_DV_BUDGET_CERTIFY', 'PERM_DV_CERTIFY', 'PERM_PAYMENT_APPROVE', 'PERM_JOURNAL_POST')")
    public DvView dvAction(@PathVariable UUID id, @PathVariable String action, @Valid @RequestBody ActionRequest request) {
        return switch (action) {
            case "submit" -> disbursements.submit(id, request.version(), request.comment());
            case "approve" -> disbursements.approve(id, request.version(), request.comment());
            case "return" -> disbursements.returnOrReject(id, request.version(), request.comment(), false);
            case "reject" -> disbursements.returnOrReject(id, request.version(), request.comment(), true);
            case "cancel" -> disbursements.cancel(id, request.version(), request.comment());
            default -> disbursements.post(id, request.version());
        };
    }

    @PostMapping("/disbursement-vouchers/{id}/payment-instruction")
    @PreAuthorize("hasAuthority('PERM_PAYMENT_RELEASE')")
    @Operation(summary = "Issue a check (recorded in the check register) or record an ADA reference. Requires an Idempotency-Key header")
    public Object instruct(@PathVariable UUID id, @Valid @RequestBody PaymentInstructionRequest request, @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return idempotency.once("AP_PAYMENT_INSTRUCT", idempotencyKey, IdempotencyService.hash("AP_PAYMENT_INSTRUCT", id, request), 200,
                () -> disbursements.instruct(id, request));
    }

    @PostMapping("/disbursement-vouchers/{id}/check-approval")
    @PreAuthorize("hasAuthority('PERM_CHECK_APPROVE')")
    @Operation(summary = "Approve the prepared check (a second person); it can then be released")
    public DvView approveCheck(@PathVariable UUID id, @Valid @RequestBody ActionRequest request) {
        return disbursements.approveCheck(id, request.version());
    }

    @PostMapping("/disbursement-vouchers/{id}/payment-void")
    @PreAuthorize("hasAuthority('PERM_PAYMENT_RELEASE')")
    public DvView voidPayment(@PathVariable UUID id, @Valid @RequestBody VoidRequest request) {
        return disbursements.voidPayment(id, request.version(), request.reason());
    }

    @PostMapping("/disbursement-vouchers/{id}/payment-confirmation")
    @PreAuthorize("hasAuthority('PERM_PAYMENT_RELEASE')")
    @Operation(summary = "Confirm the payment was released; Accounting then posts it")
    public DvView confirm(@PathVariable UUID id, @Valid @RequestBody ConfirmPaymentRequest request) {
        return disbursements.confirm(id, request.version(), request.releasedOn());
    }

    @PostMapping(value = "/disbursement-vouchers/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_DOCUMENT_UPLOAD')")
    public DocumentService.DocumentInfo uploadDvDocument(@PathVariable UUID id, @RequestPart("file") MultipartFile file)
            throws IOException {
        return documents.upload(DisbursementService.DOCUMENT, id, file.getOriginalFilename(), bytes(file));
    }

    // ---------------------------------------------------------------- reports
    @GetMapping("/accounts-payable/aging")
    @PreAuthorize("hasAuthority('PERM_AP_VIEW')")
    public AgingReport aging(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return reports.aging(asOf);
    }

    @GetMapping("/accounts-payable/reconciliation")
    @PreAuthorize("hasAuthority('PERM_AP_VIEW') and hasAuthority('PERM_LEDGER_VIEW')")
    public ApReconciliation reconciliation() {
        return reports.reconciliation();
    }

    @GetMapping("/accounts-payable/advances")
    @PreAuthorize("hasAuthority('PERM_AP_VIEW')")
    public AdvanceRegister advances() {
        return reports.advances();
    }

    @GetMapping("/accounts-payable/withholding-schedule")
    @PreAuthorize("hasAuthority('PERM_AP_VIEW')")
    @Operation(summary = "Expanded withholding per supplier and tax code for a quarter (data for BIR Form 2307)")
    public WithholdingSchedule withholding(@RequestParam int year, @RequestParam int quarter) {
        return reports.withholding(year, quarter);
    }

    private static byte[] bytes(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new ApiException(ErrorCode.FILE_REJECTED, "No file was uploaded.");
        }
        return file.getBytes();
    }
}
