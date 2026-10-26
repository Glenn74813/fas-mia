package com.miaa.cas.financialreporting;

import jakarta.validation.Valid;
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
import java.util.Map;
import java.util.UUID;

/** API contracts for statement templates, financial statements and report jobs. */
public final class ReportDtos {

    private ReportDtos() {
    }

    /**
     * A template line. OPENING, CLOSING and MOVEMENT lines belong to movement statements (SCF, SCE); a MOVEMENT line's
     * counterpartCodes classify the change by the statement line codes of the other lines of each entry.
     */
    public record TemplateLineRequest(@NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._-]{0,39}$") String code,
            @NotBlank @Size(max = 200) String label,
            @NotNull @Pattern(regexp = "HEADER|LINE|SURPLUS|TOTAL|OPENING|CLOSING|MOVEMENT") String type,
            @Size(max = 50) List<@NotBlank @Size(max = 30) String> fsLineCodes, @Size(max = 50) List<@NotBlank String> totalOf,
            @NotNull @Min(-1) @Max(1) Integer sign, @Min(0) @Max(4) Integer indent,
            @Size(max = 50) List<@NotBlank @Size(max = 30) String> counterpartCodes) {

        public TemplateLineRequest(String code, String label, String type, List<String> fsLineCodes, List<String> totalOf, Integer sign,
                Integer indent) {
            this(code, label, type, fsLineCodes, totalOf, sign, indent, List.of());
        }
    }

    public record TemplateRequest(@NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._-]{0,39}$") String code,
            @NotBlank @Size(max = 200) String name, @NotNull @Pattern(regexp = "SFP|SFPERF|SCF|SCE") String statementType,
            @Size(max = 81) String balanceCheck, @NotNull @Size(min = 1, max = 300) List<@Valid @NotNull TemplateLineRequest> lines) {
    }

    public record TemplateLine(int lineNo, String code, String label, String type, List<String> fsLineCodes, List<String> totalOf,
            int sign, int indent, List<String> counterpartCodes) {
    }

    /** Part of a movement that no MOVEMENT line classifies (debit positive), so it cannot vanish from a statement. */
    public record UnclassifiedMovement(List<String> fsLineCodes, String counterpartCode, BigDecimal amount) {
    }

    public record TemplateView(UUID id, String code, int version, String name, String statementType, String status,
            boolean placeholder, String balanceCheck, String createdBy, Instant createdAt, String activatedBy,
            Instant activatedAt, List<TemplateLine> lines, List<String> availableActions) {
    }

    public record StatementLineResult(String code, String label, String type, int indent, BigDecimal current,
            BigDecimal comparative) {
    }

    public record UnmappedAccount(String accountCode, String accountName, String fsLineCode, BigDecimal balance) {
    }

    public record StatementResult(UUID templateId, String templateCode, int templateVersion, String title, String statementType,
            String periodName, LocalDate periodEnd, String comparativePeriodName, List<StatementLineResult> lines,
            Boolean balanced, List<UnmappedAccount> unmapped, List<UnclassifiedMovement> unclassified, boolean placeholderData,
            List<String> notes) {
    }

    public record JobRequest(
            @NotNull @Pattern(regexp = "FINANCIAL_STATEMENT|TRIAL_BALANCE|BUDGET_VS_ACTUAL|AP_AGING|AR_AGING|PPE_SCHEDULE|INVENTORY_STOCK|AUDIT_TRAIL"
                    + "|CASH_DISBURSEMENT_JOURNAL|COLLECTION_SUMMARY|GENERAL_LEDGER|SUBSIDIARY_LEDGER|DAILY_COLLECTION_REPORT"
                    + "|BANK_RECONCILIATION_STATEMENT|WITHHOLDING_SCHEDULE")
            String reportType,
            @NotNull @Pattern(regexp = "CSV|XLSX|PDF") String format, @NotNull Map<String, String> parameters) {
    }

    public record JobView(UUID id, String reportType, String format, Map<String, Object> parameters, String status,
            String requestedBy, Instant requestedAt, Instant startedAt, Instant finishedAt, String error, String fileName,
            Long sizeBytes, String sha256, Instant expiresAt) {
    }
}
