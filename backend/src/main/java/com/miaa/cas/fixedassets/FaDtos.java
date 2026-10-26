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
            @DecimalMin("0") @DecimalMax("1") @Digits(integer = 1, fraction = 4) BigDecimal defaultResidualRate) {
    }

    public record CategoryView(UUID id, String code, String name, UUID assetAccountId, String assetAccountCode,
            UUID accumulatedAccountId, String accumulatedAccountCode, UUID expenseAccountId, String expenseAccountCode,
            int defaultUsefulLifeMonths, BigDecimal defaultResidualRate, String status, boolean placeholder) {
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
            BigDecimal netBookValue, String status, String preparedBy, String activatedBy, Instant activatedAt,
            UUID acquisitionEntryId, String acquisitionEntryNumber, List<DepreciationHistory> depreciation,
            DisposalView disposal, List<String> availableActions, long version) {
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
            BigDecimal accumulated, BigDecimal netBookValue, BigDecimal gainLoss, String preparedBy, UUID postedEntryId,
            String postedEntryNumber, List<String> availableActions, long version) {
    }

    public record ScheduleRow(String categoryCode, String categoryName, BigDecimal openingCost, BigDecimal additions,
            BigDecimal disposalsCost, BigDecimal closingCost, BigDecimal openingAccumulated, BigDecimal depreciation,
            BigDecimal disposalsAccumulated, BigDecimal closingAccumulated, BigDecimal netBookValue) {
    }

    public record PpeSchedule(LocalDate from, LocalDate to, List<ScheduleRow> rows, ScheduleRow totals, String note) {
    }

    public record ReconciliationRow(String accountCode, String accountName, String side, BigDecimal register,
            BigDecimal generalLedger, BigDecimal difference) {
    }

    public record FaReconciliation(List<ReconciliationRow> rows, boolean reconciled) {
    }
}
