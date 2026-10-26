package com.miaa.cas.accountsreceivable;

import com.miaa.cas.documents.DocumentService.DocumentInfo;
import com.miaa.cas.journal.DecisionLog.Decision;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** API contracts for customers, contracts, billing documents, collections, receivables reports and the portal. */
public final class ArDtos {

    private ArDtos() {
    }

    private static final String TIN = "^[0-9]{3}-?[0-9]{3}-?[0-9]{3}(-?[0-9]{3,5})?$";

    public record CustomerRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9.-]{0,39}$") String code,
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "CONCESSIONAIRE|AIRLINE|GOVERNMENT|OTHER") String customerType,
            @Pattern(regexp = TIN, message = "TIN format 000-000-000[-00000]") String tin,
            @Size(max = 500) String address,
            @Size(max = 254) @jakarta.validation.constraints.Email String email,
            @PositiveOrZero @Digits(integer = 17, fraction = 2) BigDecimal creditLimit,
            @NotNull @Min(0) @Max(365) Integer paymentTermsDays) {
    }

    public record CustomerUpdateRequest(@NotNull Long version, @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "CONCESSIONAIRE|AIRLINE|GOVERNMENT|OTHER") String customerType,
            @Pattern(regexp = TIN, message = "TIN format 000-000-000[-00000]") String tin,
            @Size(max = 500) String address, @Size(max = 254) @jakarta.validation.constraints.Email String email,
            @PositiveOrZero @Digits(integer = 17, fraction = 2) BigDecimal creditLimit,
            @NotNull @Min(0) @Max(365) Integer paymentTermsDays,
            @NotNull @Pattern(regexp = "ACTIVE|INACTIVE") String status) {
    }

    public record CustomerView(UUID id, String code, String name, String customerType, String tin, String address,
            String email, BigDecimal creditLimit, int paymentTermsDays, UUID subsidiaryAccountId, String status,
            BigDecimal openBalance, BigDecimal unappliedCollections, long version) {
    }

    public record ContractRequest(@NotBlank @Size(max = 60) String contractNumber, @NotBlank @Size(max = 500) String description,
            @NotNull LocalDate startDate, LocalDate endDate,
            @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal monthlyAmount,
            @NotNull UUID revenueAccountId, UUID costCenterId, UUID vatCodeId) {
    }

    public record ContractStatusRequest(@NotNull Long version, @NotNull @Pattern(regexp = "ACTIVE|SUSPENDED|ENDED") String status) {
    }

    public record ContractView(UUID id, UUID customerId, String contractNumber, String description, LocalDate startDate,
            LocalDate endDate, BigDecimal monthlyAmount, UUID revenueAccountId, String revenueAccountCode, UUID costCenterId,
            String costCenterCode, UUID vatCodeId, String vatCode, String status, long version) {
    }

    public record PortalUserView(UUID userId, String username, String displayName, String status) {
    }

    public record BillingLineRequest(@NotNull UUID revenueAccountId, UUID costCenterId, @NotBlank @Size(max = 500) String description,
            @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal netAmount) {
    }

    public record BillingRequest(
            @NotNull @Pattern(regexp = "INVOICE|CREDIT_MEMO|DEBIT_MEMO") String kind,
            @NotNull UUID customerId,
            UUID relatedInvoiceId,
            @NotNull LocalDate documentDate,
            LocalDate dueDate,
            @NotBlank @Size(max = 1000) String description,
            UUID vatCodeId,
            @NotNull @Size(min = 1, max = 200) List<@Valid @NotNull BillingLineRequest> lines) {
    }

    public record ActionRequest(@NotNull Long version, @Size(max = 1000) String comment) {
    }

    public record BillingRunRequest(@NotNull @Pattern(regexp = "^[0-9]{4}-(0[1-9]|1[0-2])$") String billingPeriod,
            @NotNull LocalDate documentDate) {
    }

    public record BillingRunResult(String billingPeriod, List<BillingSummary> created, List<String> skipped) {
    }

    public record BillingLineView(int lineNo, UUID revenueAccountId, String accountCode, String accountName, UUID costCenterId,
            String costCenterCode, String description, BigDecimal netAmount) {
    }

    public record BillingSummary(UUID id, String kind, String documentNumber, String customerCode, String customerName,
            LocalDate documentDate, LocalDate dueDate, String billingPeriod, String status, BigDecimal grossAmount,
            BigDecimal settledAmount, BigDecimal openBalance, String preparedBy, Instant updatedAt, long version) {
    }

    public record BillingView(UUID id, String kind, String documentNumber, UUID customerId, String customerCode,
            String customerName, String customerTin, UUID contractId, String contractNumber, String billingPeriod,
            UUID relatedInvoiceId, String relatedInvoiceNumber, LocalDate documentDate, LocalDate dueDate, String periodName,
            String description, String status, String currentStep, UUID vatCodeId, String vatCode, BigDecimal netAmount,
            BigDecimal vatAmount, BigDecimal grossAmount, BigDecimal settledAmount, BigDecimal openBalance, String source,
            String preparedBy, UUID postedEntryId, String postedEntryNumber, List<BillingLineView> lines,
            List<Decision> decisions, List<DocumentInfo> documents, List<String> warnings, List<String> availableActions,
            long version) {
    }

    public record ReceiptAllocationRequest(@NotNull UUID documentId,
            @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal amount) {
    }

    public record ReceiptRequest(@NotNull UUID customerId, @NotNull LocalDate receiptDate,
            @NotNull @Pattern(regexp = "CASH|CHECK|BANK_TRANSFER") String paymentMethod,
            @Pattern(regexp = "^[A-Za-z0-9/ -]{1,60}$") String paymentReference,
            @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal amount,
            @PositiveOrZero @Digits(integer = 17, fraction = 2) BigDecimal taxWithheld,
            @Size(max = 500) String remarks,
            @NotNull @Size(max = 200) List<@Valid @NotNull ReceiptAllocationRequest> allocations) {
    }

    public record ApplyRequest(@NotNull Long version, @NotNull LocalDate accountingDate,
            @NotNull @Size(min = 1, max = 200) List<@Valid @NotNull ReceiptAllocationRequest> allocations) {
    }

    public record CancelRequest(@NotNull Long version, @NotBlank @Size(min = 5, max = 500) String reason) {
    }

    public record AllocationView(UUID documentId, String documentNumber, String kind, BigDecimal amount, boolean appliedLater,
            String postedEntryNumber, Instant createdAt) {
    }

    public record ReceiptSummary(UUID id, String receiptNumber, String customerCode, String customerName, LocalDate receiptDate,
            String paymentMethod, BigDecimal amount, BigDecimal taxWithheld, BigDecimal unappliedAmount, String status,
            String recordedBy, Instant updatedAt, long version) {
    }

    public record ReceiptView(UUID id, String receiptNumber, UUID customerId, String customerCode, String customerName,
            LocalDate receiptDate, String paymentMethod, String paymentReference, BigDecimal amount, BigDecimal taxWithheld,
            BigDecimal allocatedAmount, BigDecimal unappliedAmount, String status, String remarks, String recordedBy,
            String cancelReason, UUID postedEntryId, String postedEntryNumber, List<AllocationView> allocations,
            List<DocumentInfo> documents, List<String> availableActions, long version) {
    }

    public record OpenItem(UUID id, String kind, String documentNumber, LocalDate documentDate, LocalDate dueDate,
            BigDecimal grossAmount, BigDecimal openBalance, BigDecimal pendingCollections, BigDecimal available) {
    }

    public record ArAgingRow(UUID customerId, String customerCode, String customerName, BigDecimal current,
            BigDecimal days1to30, BigDecimal days31to60, BigDecimal days61to90, BigDecimal over90, BigDecimal total) {
    }

    public record ArAgingReport(LocalDate asOf, List<ArAgingRow> rows, ArAgingRow totals) {
    }

    public record StatementLine(LocalDate date, String type, String reference, String description, BigDecimal charges,
            BigDecimal credits, BigDecimal balance) {
    }

    public record Statement(UUID customerId, String customerCode, String customerName, LocalDate from, LocalDate to,
            BigDecimal openingBalance, List<StatementLine> lines, BigDecimal closingBalance, BigDecimal unappliedCollections) {
    }

    public record ControlReconciliation(String key, UUID controlAccountId, String controlAccountCode,
            BigDecimal subledgerBalance, BigDecimal generalLedgerBalance, BigDecimal difference, boolean reconciled) {
    }

    public record ArReconciliation(ControlReconciliation receivables, ControlReconciliation unappliedCollections,
            boolean reconciled) {
    }

    public record PortalAccount(String customerCode, String customerName, String tin, String address, int paymentTermsDays,
            BigDecimal openBalance, BigDecimal overdueBalance, BigDecimal unappliedCollections, LocalDate asOf) {
    }
}
