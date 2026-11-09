package com.miaa.cas.integrations.payroll;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PayrollDtos {

    private PayrollDtos() {
    }

    /** How one General Payroll column is booked. */
    public record ColumnMapping(String columnKey, String label, String treatment, UUID accountId, String accountCode, String accountName,
            String subsidiaryType, long version, String updatedBy, Instant updatedAt) {
    }

    /** Adds a column (no version) or changes one (its current version). */
    public record ColumnMappingRequest(@NotBlank @Size(max = 100) String label,
            @NotBlank @Pattern(regexp = "DEBIT|CREDIT|IGNORE|EMPLOYEE_NUMBER|EMPLOYEE_NAME") String treatment, UUID accountId, Long version) {
    }

    public record ColumnTotal(String label, String treatment, String accountCode, BigDecimal total) {
    }

    /** One line of the voucher the import would create; per employee on an account with an employee subsidiary ledger. */
    public record PreviewLine(String accountCode, String accountName, String side, BigDecimal amount, String employeeNumber,
            String description) {
    }

    /**
     * What a General Payroll file books. Nothing is created while {@code problems} is not empty; {@code alreadyImported}
     * names the voucher the same file created before.
     */
    public record Preview(String fileName, int employees, List<ColumnTotal> columns, List<PreviewLine> lines, BigDecimal totalDebit,
            BigDecimal totalCredit, List<String> problems, String alreadyImported) {
    }

    public record ImportResult(UUID journalVoucherId, String description, int employees, BigDecimal total) {
    }

    public record ImportView(UUID id, String reference, String fileName, int employees, BigDecimal total, UUID journalVoucherId,
            String voucherNumber, String voucherStatus, String importedBy, Instant importedAt) {
    }
}
