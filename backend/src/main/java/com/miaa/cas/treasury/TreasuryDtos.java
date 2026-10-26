package com.miaa.cas.treasury;

import com.miaa.cas.documents.DocumentService.DocumentInfo;
import com.miaa.cas.journal.DecisionLog.Decision;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** API contracts for deposits, treasury reports, bank statements and bank reconciliation. */
public final class TreasuryDtos {

    private TreasuryDtos() {
    }

    // ---------------------------------------------------------------- deposits

    public record DepositRequest(@NotNull UUID bankAccountId, @NotNull LocalDate depositDate,
            @Pattern(regexp = "^[A-Za-z0-9/ -]{1,60}$") String bankReference,
            @NotEmpty @Size(max = 500) List<@NotNull UUID> receiptIds) {
    }

    public record VersionRequest(@NotNull Long version) {
    }

    public record CancelRequest(@NotNull Long version, @NotBlank @Size(min = 5, max = 500) String reason) {
    }

    public record UndepositedReceipt(UUID id, String receiptNumber, String customerName, LocalDate receiptDate,
            String paymentMethod, String paymentReference, String recordedBy, BigDecimal amount) {
    }

    public record DepositItem(UUID receiptId, String receiptNumber, String customerName, LocalDate receiptDate,
            String paymentMethod, String paymentReference, BigDecimal amount) {
    }

    public record DepositSummary(UUID id, String depositNumber, String bankAccountCode, LocalDate depositDate,
            String bankReference, BigDecimal amount, int items, String status, String preparedBy, Instant updatedAt,
            long version) {
    }

    public record DepositView(UUID id, String depositNumber, UUID bankAccountId, String bankAccountCode, String bankName,
            LocalDate depositDate, String bankReference, BigDecimal amount, String status, String preparedBy,
            String cancelReason, UUID postedEntryId, String postedEntryNumber, List<DepositItem> items,
            List<DocumentInfo> documents, List<String> availableActions, long version) {
    }

    // ---------------------------------------------------------------- reports

    public record DcrLine(String receiptNumber, String customerName, String paymentMethod, String paymentReference,
            BigDecimal amount, BigDecimal taxWithheld, String status, String recordedBy, String depositNumber,
            String cancelReason) {
    }

    public record MethodTotal(String paymentMethod, int count, BigDecimal amount) {
    }

    public record DailyCollectionReport(LocalDate date, String collector, String firstNumber, String lastNumber,
            List<DcrLine> lines, List<MethodTotal> totals, BigDecimal totalCollected, BigDecimal totalTaxWithheld,
            BigDecimal undeposited, int cancelled, String note) {
    }

    public record BankPosition(UUID bankAccountId, String code, String bankName, String last4, String glAccountCode,
            BigDecimal bookBalance, LocalDate lastReconciledStatementEnd) {
    }

    public record CashPosition(LocalDate asOf, BigDecimal cashOnHandBook, BigDecimal undepositedCollections,
            List<BankPosition> banks, BigDecimal totalBank) {
    }

    public record CheckRow(UUID id, String bankAccountCode, String checkNumber, String dvNumber, String payee,
            BigDecimal amount, LocalDate issueDate, String status, String voidReason, long ageDays) {
    }

    // ---------------------------------------------------------------- statements

    public record StatementSummary(UUID id, UUID bankAccountId, String bankAccountCode, String format,
            String statementReference, LocalDate periodStart, LocalDate periodEnd, BigDecimal openingBalance,
            BigDecimal closingBalance, int lineCount, int unmatchedLines, String fileName, Instant importedAt,
            String importedBy, UUID reconciliationId, String reconciliationStatus) {
    }

    public record StatementLineView(UUID id, int lineNo, LocalDate valueDate, LocalDate bookingDate, BigDecimal amount,
            String description, String bankReference, UUID matchId) {
    }

    public record StatementView(StatementSummary summary, List<StatementLineView> lines, List<String> warnings) {
    }

    // ---------------------------------------------------------------- reconciliation

    public record LedgerItem(UUID journalLineId, String entryNumber, LocalDate accountingDate, String description,
            String reference, BigDecimal amount) {
    }

    public record MatchView(UUID id, String method, String rule, String createdBy, Instant createdAt,
            List<StatementLineView> statementLines, List<LedgerItem> ledgerLines, boolean locked) {
    }

    public record ReconciliationFigures(BigDecimal bankBalance, BigDecimal depositsInTransit, BigDecimal outstandingChecks,
            BigDecimal adjustedBank, BigDecimal bookBalance, BigDecimal unrecordedCredits, BigDecimal unrecordedDebits,
            BigDecimal adjustedBook, BigDecimal difference) {
    }

    public record ReconciliationView(UUID id, UUID bankAccountId, String bankAccountCode, UUID statementId,
            LocalDate periodStart, LocalDate asOfDate, String status, String currentStep, ReconciliationFigures figures,
            List<StatementLineView> unmatchedStatementLines, List<LedgerItem> unmatchedLedgerLines, List<MatchView> matches,
            List<String> warnings, String preparedBy, String approvedBy, Instant approvedAt, List<Decision> decisions,
            List<String> availableActions, long version) {
    }

    public record ManualMatchRequest(@NotNull Long version, @NotEmpty @Size(max = 50) List<@NotNull UUID> statementLineIds,
            @NotEmpty @Size(max = 50) List<@NotNull UUID> journalLineIds) {
    }

    public record ActionRequest(@NotNull Long version, @Size(max = 1000) String comment) {
    }

    public record AutoMatchResult(int matched, ReconciliationView reconciliation) {
    }
}
