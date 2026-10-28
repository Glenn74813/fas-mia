package com.miaa.cas.generalledger.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** API contracts for the chart of accounts, dimensions, fiscal calendar and ledger inquiries. */
public final class LedgerDtos {

    private LedgerDtos() {
    }

    // ---------------------------------------------------------------- chart of accounts
    public record AccountRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9.-]{0,29}$") String code,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 1000) String description,
            @NotNull String accountType,
            String normalBalance,
            String parentCode,
            boolean postable,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            @Size(max = 30) String fsLineCode,
            boolean requiresCostCenter,
            String subsidiaryType,
            boolean budgetControlled,
            @Size(max = 20) String taxCode) {
    }

    public record AccountUpdateRequest(
            @NotNull Long version,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 1000) String description,
            @NotNull String accountType,
            String normalBalance,
            String parentCode,
            boolean postable,
            LocalDate effectiveTo,
            @Size(max = 30) String fsLineCode,
            boolean requiresCostCenter,
            String subsidiaryType,
            boolean budgetControlled,
            @Size(max = 20) String taxCode) {
    }

    public record AccountResponse(UUID id, String code, String name, String description, String accountType,
            String normalBalance, UUID parentId, String parentCode, boolean postable, String status,
            LocalDate effectiveFrom, LocalDate effectiveTo, String fsLineCode, boolean requiresCostCenter,
            String subsidiaryType, boolean budgetControlled, String taxCode, boolean placeholder, long version,
            /** What the account may be chosen for outside a journal voucher ({@code AccountPurpose}). */
            List<String> purposes) {
    }

    public record ChartSummary(List<AccountResponse> accounts, long activePlaceholderAccounts) {
    }

    public record ImportError(int line, String code, String message) {
    }

    public record ImportResult(int created, boolean placeholder, List<ImportError> errors) {
    }

    // ---------------------------------------------------------------- dimensions
    public record CostCenterRequest(@NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9.-]{0,29}$") String code,
            @NotBlank @Size(max = 200) String name) {
    }

    public record DimensionUpdateRequest(@NotNull Long version, @NotBlank @Size(max = 200) String name,
            @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE") String status) {
    }

    public record CostCenterResponse(UUID id, String code, String name, String status, boolean placeholder, long version) {
    }

    public record SubsidiaryAccountRequest(@NotBlank @Pattern(regexp = "SUPPLIER|CUSTOMER|EMPLOYEE|OTHER") String slType,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9.-]{0,39}$") String code,
            @NotBlank @Size(max = 200) String name) {
    }

    public record SubsidiaryAccountResponse(UUID id, String slType, String code, String name, String status, long version) {
    }

    // ---------------------------------------------------------------- fiscal calendar
    public record FiscalYearRequest(@NotNull @Min(2000) @Max(2200) Integer fiscalYear) {
    }

    public record PeriodResponse(UUID id, UUID fiscalYearId, int fiscalYear, int periodNumber, String name,
            String periodType, LocalDate startDate, LocalDate endDate, String status, long version) {
    }

    public record FiscalYearResponse(UUID id, int fiscalYear, LocalDate startDate, LocalDate endDate, String status,
            Instant closedAt, String closedBy, List<PeriodResponse> periods, long version) {
    }

    public record VersionRequest(@NotNull Long version) {
    }

    public record CloseCheck(String code, String description, String outcome, boolean waivable, String detail) {
    }

    public record CloseChecklist(PeriodResponse period, List<CloseCheck> checks, boolean readyToClose) {
    }

    public record ClosePeriodRequest(@NotNull Long version, List<String> waivedChecks, @Size(max = 1000) String justification) {
    }

    public record ReopenRequest(@NotBlank @Size(min = 10, max = 1000) String reason) {
    }

    public record ReopenDecisionRequest(@NotNull Boolean approve, @Size(max = 1000) String note) {
    }

    public record PeriodChangeResponse(UUID id, UUID periodId, String periodName, String changeType, String fromStatus,
            String toStatus, String requestStatus, String reason, List<String> waivedChecks, String requestedBy,
            Instant requestedAt, String decidedBy, Instant decidedAt, String decisionNote) {
    }

    // ---------------------------------------------------------------- inquiries
    public record TrialBalanceRow(UUID accountId, String code, String name, String accountType, String normalBalance,
            boolean placeholder, BigDecimal openingDebit, BigDecimal openingCredit, BigDecimal periodDebit,
            BigDecimal periodCredit, BigDecimal closingDebit, BigDecimal closingCredit) {
    }

    public record TrialBalance(PeriodResponse period, List<TrialBalanceRow> rows, BigDecimal totalOpeningDebit,
            BigDecimal totalOpeningCredit, BigDecimal totalPeriodDebit, BigDecimal totalPeriodCredit,
            BigDecimal totalClosingDebit, BigDecimal totalClosingCredit, boolean balanced, boolean placeholderAccountsUsed,
            String source) {
    }

    public record LedgerLine(UUID lineId, UUID entryId, String entryNumber, UUID voucherId, LocalDate accountingDate,
            String periodName, String description, String costCenterCode, String subsidiaryCode, BigDecimal debit,
            BigDecimal credit, BigDecimal runningBalance) {
    }

    public record AccountLedger(AccountResponse account, LocalDate from, LocalDate to, BigDecimal openingBalance,
            BigDecimal periodDebit, BigDecimal periodCredit, BigDecimal closingBalance, String balanceSign,
            List<LedgerLine> lines, int page, int size, long totalLines) {
    }

    public record EntryLine(int lineNo, UUID accountId, String accountCode, String accountName, String costCenterCode,
            String subsidiaryCode, String subsidiaryName, String description, BigDecimal debit, BigDecimal credit) {
    }

    public record EntryDetail(UUID id, String entryNumber, UUID voucherId, String voucherType, LocalDate accountingDate,
            String periodName, String description, String sourceType, String sourceReference, BigDecimal totalDebit,
            BigDecimal totalCredit, UUID reversalOfEntryId, String reversalOfEntryNumber, UUID reversedByEntryId,
            String reversedByEntryNumber, String preparedBy, String postedBy, Instant postedAt, List<EntryLine> lines) {
    }

    public record SubsidiaryReconciliationRow(UUID accountId, String code, String name, String subsidiaryType,
            BigDecimal glBalance, BigDecimal subsidiaryTotal, BigDecimal unassigned, boolean reconciled) {
    }

    public record BalanceDifference(UUID accountId, String accountCode, UUID periodId, String periodName,
            BigDecimal storedDebit, BigDecimal storedCredit, BigDecimal entryDebit, BigDecimal entryCredit) {
    }

    public record RebuildResult(int differencesFound, int rowsRebuilt, List<BalanceDifference> differences) {
    }
}
