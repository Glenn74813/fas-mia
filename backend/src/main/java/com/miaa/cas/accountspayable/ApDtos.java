package com.miaa.cas.accountspayable;

import com.miaa.cas.documents.DocumentService.DocumentInfo;
import com.miaa.cas.journal.DecisionLog.Decision;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** API contracts for suppliers, supplier invoices, disbursement vouchers and payables reports. */
public final class ApDtos {

    private ApDtos() {
    }

    public record SupplierRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9.-]{0,39}$") String code,
            @NotBlank @Size(max = 200) String name,
            @Pattern(regexp = "^[0-9]{3}-?[0-9]{3}-?[0-9]{3}(-?[0-9]{3,5})?$", message = "TIN format 000-000-000[-00000]") String tin,
            @Size(max = 500) String address,
            boolean vatRegistered,
            UUID defaultEwtCodeId) {
    }

    public record SupplierUpdateRequest(@NotNull Long version, @NotBlank @Size(max = 200) String name,
            @Pattern(regexp = "^[0-9]{3}-?[0-9]{3}-?[0-9]{3}(-?[0-9]{3,5})?$", message = "TIN format 000-000-000[-00000]") String tin,
            @Size(max = 500) String address, boolean vatRegistered, UUID defaultEwtCodeId,
            @NotNull @Pattern(regexp = "ACTIVE|INACTIVE") String status) {
    }

    public record SupplierView(UUID id, String code, String name, String tin, String address, boolean vatRegistered,
            UUID defaultEwtCodeId, String defaultEwtCode, UUID subsidiaryAccountId, String status, BigDecimal openBalance,
            long version) {
    }

    public record InvoiceLineRequest(@NotNull UUID accountId, UUID costCenterId, @Size(max = 500) String description,
            @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal netAmount, UUID ewtCodeId) {
    }

    public record InvoiceRequest(
            @NotNull UUID supplierId,
            @NotBlank @Size(max = 60) String invoiceNumber,
            @NotNull LocalDate invoiceDate,
            @NotNull LocalDate dueDate,
            @NotNull LocalDate accountingDate,
            @NotBlank @Size(max = 1000) String description,
            UUID vatCodeId,
            @NotNull @Size(min = 1, max = 200) List<@Valid @NotNull InvoiceLineRequest> lines) {
    }

    public record ActionRequest(@NotNull Long version, @Size(max = 1000) String comment) {
    }

    public record InvoiceLineView(int lineNo, UUID accountId, String accountCode, String accountName, UUID costCenterId,
            String costCenterCode, String description, BigDecimal netAmount, UUID ewtCodeId, String ewtCode,
            BigDecimal ewtAmount) {
    }

    public record InvoiceSummary(UUID id, String supplierCode, String supplierName, String invoiceNumber,
            LocalDate invoiceDate, LocalDate dueDate, String status, BigDecimal payableAmount, BigDecimal paidAmount,
            BigDecimal openBalance, String preparedBy, Instant updatedAt, long version) {
    }

    public record InvoiceView(UUID id, UUID supplierId, String supplierCode, String supplierName, String supplierTin,
            String invoiceNumber, LocalDate invoiceDate, LocalDate dueDate, LocalDate accountingDate, String periodName,
            String description, String status, String currentStep, UUID vatCodeId, String vatCode, BigDecimal netAmount,
            BigDecimal vatAmount, BigDecimal ewtAmount, BigDecimal payableAmount, BigDecimal paidAmount,
            BigDecimal openBalance, String preparedBy, UUID postedEntryId, String postedEntryNumber,
            List<InvoiceLineView> lines, List<Decision> decisions, List<DocumentInfo> documents,
            List<String> possibleDuplicates, List<String> availableActions, long version) {
    }

    public record AllocationRequest(@NotNull UUID invoiceId, @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal amount) {
    }

    public record DvRequest(@NotNull UUID supplierId, @NotBlank @Size(max = 1000) String particulars,
            @NotNull @Size(min = 1, max = 200) List<@Valid @NotNull AllocationRequest> allocations) {
    }

    public record DvUpdateRequest(@NotNull Long version, @NotBlank @Size(max = 1000) String particulars,
            @NotNull @Size(min = 1, max = 200) List<@Valid @NotNull AllocationRequest> allocations) {
    }

    public record PaymentInstructionRequest(@NotNull Long version, @NotNull @Pattern(regexp = "CHECK|ADA") String mode,
            @NotNull UUID bankAccountId, @NotBlank @Pattern(regexp = "^[A-Za-z0-9-]{1,60}$") String reference,
            @NotNull LocalDate paymentDate) {
    }

    public record ConfirmPaymentRequest(@NotNull Long version, @NotNull LocalDate releasedOn) {
    }

    public record VoidRequest(@NotNull Long version, @NotBlank @Size(min = 5, max = 500) String reason) {
    }

    public record DvLineView(UUID invoiceId, String invoiceNumber, LocalDate dueDate, BigDecimal invoicePayable,
            BigDecimal amount) {
    }

    public record DvSummary(UUID id, String dvNumber, String supplierName, String particulars, String status,
            String currentStep, BigDecimal amount, String preparedBy, Instant updatedAt, long version) {
    }

    public record DvView(UUID id, String dvNumber, UUID supplierId, String supplierCode, String supplierName,
            String particulars, String status, String currentStep, BigDecimal amount, String paymentMode,
            UUID bankAccountId, String bankAccountCode, String paymentReference, LocalDate paymentDate, String instructedBy,
            Instant instructedAt, String confirmedBy, Instant confirmedAt, String preparedBy, UUID postedEntryId,
            String postedEntryNumber, List<DvLineView> lines, List<Decision> decisions, List<DocumentInfo> documents,
            List<String> availableActions, long version) {
    }

    public record AgingRow(UUID supplierId, String supplierCode, String supplierName, BigDecimal current,
            BigDecimal days1to30, BigDecimal days31to60, BigDecimal days61to90, BigDecimal over90, BigDecimal total) {
    }

    public record AgingReport(LocalDate asOf, List<AgingRow> rows, AgingRow totals) {
    }

    public record ApReconciliation(UUID controlAccountId, String controlAccountCode, BigDecimal subledgerOpenBalance,
            BigDecimal generalLedgerBalance, BigDecimal difference, boolean reconciled) {
    }

    public record AdvanceRow(UUID id, String supplierCode, String supplierName, String reference, LocalDate advanceDate,
            String description, BigDecimal amount, BigDecimal outstandingAmount, String status, String openingEntryNumber) {
    }

    /** Supplier advances carried over at cutover, with the register against the mapped advances account (if mapped). */
    public record AdvanceRegister(List<AdvanceRow> rows, BigDecimal totalOutstanding, ApReconciliation reconciliation, String note) {
    }

    public record WithholdingRow(String supplierTin, String supplierName, String taxCode, BigDecimal month1Base,
            BigDecimal month2Base, BigDecimal month3Base, BigDecimal totalBase, BigDecimal taxWithheld) {
    }

    public record WithholdingSchedule(int year, int quarter, String note, List<WithholdingRow> rows) {
    }
}
