package com.miaa.cas.journal.web;

import com.miaa.cas.documents.DocumentService.DocumentInfo;
import com.miaa.cas.journal.DecisionLog.Decision;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** API contracts for journal vouchers. Amounts are decimal strings with at most two decimal places. */
public final class JournalDtos {

    private JournalDtos() {
    }

    public record LineRequest(
            @NotNull UUID accountId,
            UUID costCenterId,
            UUID subsidiaryAccountId,
            @Size(max = 500) String description,
            @NotNull @PositiveOrZero @Digits(integer = 17, fraction = 2) BigDecimal debit,
            @NotNull @PositiveOrZero @Digits(integer = 17, fraction = 2) BigDecimal credit) {
    }

    public record VoucherRequest(
            @NotNull @Pattern(regexp = "STANDARD|ADJUSTING|OPENING") String voucherType,
            @NotNull LocalDate accountingDate,
            boolean adjustmentPeriod,
            @NotBlank @Size(max = 1000) String description,
            @Size(max = 200) String supportingReference,
            @NotNull @Size(max = 500) List<@Valid @NotNull LineRequest> lines) {
    }

    public record UpdateVoucherRequest(
            @NotNull Long version,
            @NotNull @Pattern(regexp = "STANDARD|ADJUSTING|OPENING") String voucherType,
            @NotNull LocalDate accountingDate,
            boolean adjustmentPeriod,
            @NotBlank @Size(max = 1000) String description,
            @Size(max = 200) String supportingReference,
            @NotNull @Size(max = 500) List<@Valid @NotNull LineRequest> lines) {
    }

    public record ActionRequest(@NotNull Long version, @Size(max = 1000) String comment) {
    }

    public record ReversalRequest(@NotNull LocalDate accountingDate, boolean adjustmentPeriod,
            @NotBlank @Size(min = 10, max = 1000) String reason) {
    }

    public record ClosingVoucherRequest(@NotNull UUID surplusAccountId) {
    }

    public record RecurringRequest(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 1000) String description,
            @NotNull @Pattern(regexp = "MONTHLY|QUARTERLY|ANNUALLY") String frequency,
            @NotNull @Min(1) @Max(28) Integer dayOfMonth,
            @NotNull LocalDate firstRunDate,
            LocalDate endDate,
            @NotNull @Size(min = 2, max = 200) List<@Valid @NotNull LineRequest> lines) {
    }

    public record RecurringResponse(UUID id, String name, String description, String frequency, int dayOfMonth,
            LocalDate nextRunDate, LocalDate endDate, String status, String owner, BigDecimal total, long version) {
    }

    public record RunDueResult(int generated, List<String> skipped) {
    }

    public record LineResponse(UUID id, int lineNo, UUID accountId, String accountCode, String accountName,
            boolean placeholderAccount, UUID costCenterId, String costCenterCode, UUID subsidiaryAccountId,
            String subsidiaryCode, String subsidiaryName, String description, BigDecimal debit, BigDecimal credit) {
    }

    public record StepState(int order, String code, String permission, String state, String actor, Instant decidedAt) {
    }

    public record VoucherSummary(UUID id, String voucherNumber, String voucherType, String status, LocalDate accountingDate,
            String periodName, String description, BigDecimal totalDebit, BigDecimal totalCredit, String preparedBy,
            String currentStep, Instant updatedAt, long version) {
    }

    public record VoucherDetail(UUID id, String voucherNumber, String voucherType, String status, LocalDate accountingDate,
            UUID periodId, String periodName, String periodStatus, String description, String supportingReference,
            String sourceType, String sourceReference, String currencyCode, BigDecimal totalDebit, BigDecimal totalCredit,
            boolean balanced, String preparedBy, UUID preparedById, Instant submittedAt, UUID postedEntryId,
            UUID reversalOfEntryId, String reversalOfEntryNumber, boolean requiresAttachment, List<LineResponse> lines,
            List<StepState> workflow, List<Decision> decisions, List<DocumentInfo> documents, List<String> availableActions,
            Instant createdAt, Instant updatedAt, long version) {
    }
}
