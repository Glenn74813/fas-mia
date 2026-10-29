package com.miaa.cas.fixedassets;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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

/** API contracts for asset categories, AUC projects, the asset register, depreciation, disposals and PPE reports. */
public final class FaDtos {

    private FaDtos() {
    }

    public record CategoryRequest(@NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9.-]{0,29}$") String code,
            @NotBlank @Size(max = 200) String name, @NotNull UUID assetAccountId, @NotNull UUID accumulatedAccountId,
            @NotNull UUID expenseAccountId, @NotNull @Min(1) @Max(1200) Integer defaultUsefulLifeMonths,
            @DecimalMin("0") @DecimalMax("1") @Digits(integer = 1, fraction = 4) BigDecimal defaultResidualRate,
            UUID accumulatedImpairmentAccountId) {
    }

    public record ImpairmentAccountRequest(@NotNull UUID accountId) {
    }

    public record CategoryView(UUID id, String code, String name, UUID assetAccountId, String assetAccountCode,
            UUID accumulatedAccountId, String accumulatedAccountCode, UUID expenseAccountId, String expenseAccountCode,
            int defaultUsefulLifeMonths, BigDecimal defaultResidualRate, String status, boolean placeholder,
            UUID accumulatedImpairmentAccountId, String accumulatedImpairmentAccountCode) {
    }

    public record ProjectRequest(@NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9.-]{0,39}$") String code,
            @NotBlank @Size(max = 200) String name, @NotNull UUID aucAccountId, UUID costCenterId) {
    }

    public record ProjectView(UUID id, String code, String name, UUID aucAccountId, String aucAccountCode,
            UUID subsidiaryAccountId, String subsidiaryCode, UUID costCenterId, String status, BigDecimal costIncurred,
            BigDecimal capitalized, BigDecimal balance, long version) {
    }

    public record AssetRequest(@NotNull UUID categoryId, @NotBlank @Size(max = 500) String description,
            @Size(max = 100) String serialNumber, @Size(max = 200) String location, @Size(max = 200) String custodian,
            UUID costCenterId, @NotNull @Pattern(regexp = "INVOICE|AUC|OTHER") String acquisitionSource,
            UUID supplierInvoiceId, UUID aucProjectId, UUID offsetAccountId, @Size(max = 1000) String justification,
            @NotNull LocalDate acquisitionDate, @NotNull LocalDate inServiceDate,
            @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal cost,
            @PositiveOrZero @Digits(integer = 17, fraction = 2) BigDecimal residualValue,
            @Min(1) @Max(1200) Integer usefulLifeMonths) {
    }

    public record VersionRequest(@NotNull Long version) {
    }

    public record CancelRequest(@NotNull Long version, @NotBlank @Size(min = 5, max = 500) String reason) {
    }

    public record AssetSummary(UUID id, String assetNumber, String description, String categoryCode, String location,
            String custodian, LocalDate inServiceDate, BigDecimal cost, BigDecimal accumulatedDepreciation,
            BigDecimal netBookValue, String status, long version) {
    }

    public record DepreciationHistory(String periodName, LocalDate periodEnd, BigDecimal amount, String runStatus,
            String entryNumber) {
    }

    public record AssetView(UUID id, String assetNumber, String description, String serialNumber, String location,
            String custodian, UUID categoryId, String categoryCode, String categoryName, UUID costCenterId,
            String costCenterCode, String acquisitionSource, UUID supplierInvoiceId, String supplierInvoiceNumber,
            UUID aucProjectId, String aucProjectCode, String offsetAccountCode, String justification,
            LocalDate acquisitionDate, LocalDate inServiceDate, BigDecimal cost, BigDecimal residualValue,
            int usefulLifeMonths, BigDecimal monthlyDepreciation, BigDecimal accumulatedDepreciation,
            BigDecimal netBookValue, BigDecimal grossCarryingAmount, BigDecimal accumulatedImpairment, BigDecimal revaluationSurplus,
            String status, String preparedBy, String activatedBy, Instant activatedAt,
            UUID acquisitionEntryId, String acquisitionEntryNumber, List<DepreciationHistory> depreciation,
            DisposalView disposal, List<RemeasurementView> remeasurements, List<String> availableActions, long version) {
    }

    public record RunRequest(@NotNull UUID periodId) {
    }

    public record RunLine(UUID assetId, String assetNumber, String description, String categoryCode, BigDecimal amount,
            BigDecimal accumulatedAfter, BigDecimal netBookValueAfter) {
    }

    public record RunView(UUID id, UUID periodId, String periodName, LocalDate periodEnd, String status,
            BigDecimal totalAmount, int assetCount, String preparedBy, UUID postedEntryId, String postedEntryNumber,
            List<RunLine> lines, List<String> availableActions, long version) {
    }

    public record RunSummary(UUID id, String periodName, LocalDate periodEnd, String status, BigDecimal totalAmount,
            int assetCount, String preparedBy, Instant updatedAt) {
    }

    public record DisposalRequest(@NotNull UUID assetId, @NotNull LocalDate disposalDate,
            @NotNull @Pattern(regexp = "SALE|SCRAP|DONATION|TRANSFER|LOSS") String method,
            @PositiveOrZero @Digits(integer = 17, fraction = 2) BigDecimal proceeds, UUID proceedsAccountId,
            @NotBlank @Size(min = 5, max = 1000) String reason) {
    }

    public record DisposalView(UUID id, UUID assetId, String assetNumber, String assetDescription, LocalDate disposalDate,
            String method, BigDecimal proceeds, String proceedsAccountCode, String reason, String status, BigDecimal cost,
            BigDecimal accumulated, BigDecimal accumulatedImpairment, BigDecimal netBookValue, BigDecimal gainLoss, String preparedBy,
            UUID postedEntryId, String postedEntryNumber, List<String> availableActions, long version) {
    }

    /**
     * An impairment (PAS 36) at the recoverable amount, or a revaluation (PAS 16) at fair value, both given as the new
     * carrying amount. {@code remainingLifeMonths} reassesses the remaining useful life; null keeps the current schedule.
     */
    public record RemeasurementRequest(@NotNull UUID assetId, @NotNull @Pattern(regexp = "IMPAIRMENT|REVALUATION") String kind,
            @NotNull LocalDate remeasuredOn, @NotNull @PositiveOrZero @Digits(integer = 17, fraction = 2) BigDecimal newCarryingAmount,
            @Min(0) @Max(1200) Integer remainingLifeMonths, @Size(max = 200) String basisReference,
            @NotBlank @Size(min = 5, max = 1000) String reason) {
    }

    /** Amounts are as posted, or for a draft what posting it now would record. */
    public record RemeasurementView(UUID id, UUID assetId, String assetNumber, String assetDescription, String kind,
            LocalDate remeasuredOn, BigDecimal carryingBefore, BigDecimal newCarryingAmount, Integer remainingLifeMonths,
            String basisReference, String reason, String status, BigDecimal costChange, BigDecimal depreciationEliminated,
            BigDecimal impairmentChange, BigDecimal surplusChange, BigDecimal lossInProfit, BigDecimal reversalInProfit,
            Integer basisRemainingMonths, String preparedBy, UUID postedEntryId, String postedEntryNumber,
            List<String> availableActions, long version) {
    }

    /**
     * Roll-forward per category. Cost is the gross carrying amount: revaluations change it. Revaluations also eliminate
     * accumulated depreciation and impairment against it (elimination method).
     */
    public record ScheduleRow(String categoryCode, String categoryName, BigDecimal openingCost, BigDecimal additions,
            BigDecimal revaluations, BigDecimal disposalsCost, BigDecimal closingCost, BigDecimal openingAccumulated,
            BigDecimal depreciation, BigDecimal eliminatedAccumulated, BigDecimal disposalsAccumulated, BigDecimal closingAccumulated,
            BigDecimal openingImpairment, BigDecimal impairment, BigDecimal disposalsImpairment, BigDecimal closingImpairment,
            BigDecimal netBookValue) {
    }

    public record PpeSchedule(LocalDate from, LocalDate to, List<ScheduleRow> rows, ScheduleRow totals, String note) {
    }

    public record ReconciliationRow(String accountCode, String accountName, String side, BigDecimal register,
            BigDecimal generalLedger, BigDecimal difference) {
    }

    public record FaReconciliation(List<ReconciliationRow> rows, boolean reconciled) {
    }
}
