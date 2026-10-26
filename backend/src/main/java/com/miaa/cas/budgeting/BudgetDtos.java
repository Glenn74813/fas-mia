package com.miaa.cas.budgeting;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class BudgetDtos {

    private BudgetDtos() {
    }

    public record CreateBudgetRequest(@NotNull UUID fiscalYearId, @NotBlank @Size(max = 200) String name) {
    }

    public record AllocationRequest(@NotNull UUID accountId, UUID costCenterId,
            @NotNull @PositiveOrZero @Digits(integer = 17, fraction = 2) BigDecimal amount) {
    }

    public record SetAllocationsRequest(@NotNull Long version,
            @NotNull @Size(max = 2000) List<@Valid @NotNull AllocationRequest> allocations) {
    }

    public record ActionRequest(@NotNull Long version, @Size(max = 1000) String comment) {
    }

    public record AdjustmentRequest(UUID fromAllocationId, @NotNull UUID toAllocationId,
            @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal amount,
            @NotBlank @Size(min = 10, max = 1000) String reason) {
    }

    public record DecisionRequest(@NotNull Boolean approve) {
    }

    public record AllocationLine(UUID id, UUID accountId, String accountCode, String accountName, UUID costCenterId,
            String costCenterCode, BigDecimal allotment, BigDecimal reserved, BigDecimal actual, BigDecimal available,
            BigDecimal utilizationPercent) {
    }

    public record BudgetView(UUID id, UUID fiscalYearId, int fiscalYear, String name, String status, String currentStep,
            String preparedBy, Instant approvedAt, String approvedBy, List<AllocationLine> allocations,
            BigDecimal totalAllotment, BigDecimal totalReserved, BigDecimal totalActual, BigDecimal totalAvailable,
            List<String> availableActions, long version) {
    }

    public record AdjustmentView(UUID id, UUID fromAllocationId, String fromLabel, UUID toAllocationId, String toLabel,
            BigDecimal amount, String reason, String status, String requestedBy, Instant requestedAt, String decidedBy,
            Instant decidedAt) {
    }

    /** One line of expenditure to be checked against the budget. */
    public record Demand(UUID accountId, UUID costCenterId, BigDecimal amount) {
    }
}
