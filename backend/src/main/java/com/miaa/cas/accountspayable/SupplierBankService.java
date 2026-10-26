package com.miaa.cas.accountspayable;

import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.crypto.AesGcmEncryptor;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.security.CurrentActor;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Supplier bank accounts for electronic (ADA) payment. Account numbers are stored encrypted and shown
 * only as their last four digits; the full number appears only in a generated payment file. A new or
 * changed account cannot be paid until a second person verifies it, and entering a replacement
 * immediately withdraws the previous account, so changed bank details always stop payments until checked.
 */
@Service
public class SupplierBankService {

    public record BankAccountRequest(@NotBlank @Size(max = 200) String bankName, @Size(max = 200) String branch,
            @NotBlank @Size(max = 200) String accountName,
            @NotBlank @Pattern(regexp = "^[0-9 -]{6,30}$", message = "digits only (spaces or hyphens allowed)") String accountNumber) {
    }

    public record VerifyRequest(@NotNull Boolean approve, @NotBlank @Size(min = 5, max = 500) String note) {
    }

    public record SupplierBankView(UUID id, UUID supplierId, String bankName, String branch, String accountName, String accountNumberMasked,
            String status, String enteredBy, Instant enteredAt, String verifiedBy, Instant verifiedAt, String verificationNote,
            List<String> availableActions) {
    }

    /** Bank details as needed in a payment file (decrypted). Never returned by the API. */
    public record PayeeAccount(UUID id, String bankName, String branch, String accountName, String accountNumber) {
    }

    private final AesGcmEncryptor encryptor;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public SupplierBankService(AesGcmEncryptor encryptor, AuditService audit, JdbcTemplate jdbc, Clock clock) {
        this.encryptor = encryptor;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<SupplierBankView> list(UUID supplierId) {
        return jdbc.queryForList("SELECT id FROM supplier_bank_accounts WHERE supplier_id = ? ORDER BY entered_at DESC", UUID.class, supplierId)
                .stream().map(this::view).toList();
    }

    @Transactional
    public SupplierBankView add(UUID supplierId, BankAccountRequest req) {
        CurrentActor.requirePermission("SUPPLIER_MANAGE");
        String code = jdbc.queryForList("SELECT code FROM suppliers WHERE id = ? FOR UPDATE", String.class, supplierId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Supplier"));
        String digits = req.accountNumber().replaceAll("[ -]", "");
        if (digits.length() < 6 || digits.length() > 20) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The account number must have 6 to 20 digits.");
        }
        Timestamp now = Timestamp.from(Instant.now(clock));
        int withdrawn = jdbc.update("UPDATE supplier_bank_accounts SET status = 'INACTIVE', version = version + 1 WHERE supplier_id = ? "
                + "AND status IN ('PENDING', 'VERIFIED')", supplierId);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO supplier_bank_accounts (id, supplier_id, bank_name, branch, account_name, account_number_enc, account_number_last4,
                    status, entered_by_id, entered_by, entered_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?)""", id, supplierId,
                req.bankName().trim(), blank(req.branch()), req.accountName().trim(), encryptor.encrypt(digits.getBytes(StandardCharsets.UTF_8),
                        aad(id)), digits.substring(digits.length() - 4), CurrentActor.userId(), CurrentActor.username(), now);
        audit.record("SUPPLIER_BANK_ACCOUNT_ENTERED", "SUPPLIER", code, Map.of("bank", req.bankName().trim(),
                "last4", digits.substring(digits.length() - 4), "previousWithdrawn", withdrawn));
        return view(id);
    }

    @Transactional
    public SupplierBankView verify(UUID id, boolean approve, String note) {
        CurrentActor.requirePermission("SUPPLIER_BANK_VERIFY");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT b.*, s.code FROM supplier_bank_accounts b JOIN suppliers s ON s.id = b.supplier_id "
                + "WHERE b.id = ? FOR UPDATE OF b", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Supplier bank account");
        }
        Map<String, Object> b = rows.get(0);
        if (!"PENDING".equals(b.get("status"))) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Only pending bank details can be verified.");
        }
        if (CurrentActor.userId().equals(b.get("entered_by_id"))) {
            throw new ApiException(ErrorCode.SEGREGATION_OF_DUTIES, "You entered these bank details; another person must verify them.");
        }
        jdbc.update("UPDATE supplier_bank_accounts SET status = ?, verified_by = ?, verified_at = ?, verification_note = ?, version = version + 1 "
                + "WHERE id = ?", approve ? "VERIFIED" : "REJECTED", CurrentActor.username(), Timestamp.from(Instant.now(clock)), note.trim(), id);
        audit.record(approve ? "SUPPLIER_BANK_ACCOUNT_VERIFIED" : "SUPPLIER_BANK_ACCOUNT_REJECTED", "SUPPLIER", b.get("code"),
                Map.of("last4", b.get("account_number_last4"), "note", note.trim()));
        return view(id);
    }

    /** The verified account of a supplier, decrypted, for building a payment file; null when none is verified. */
    public PayeeAccount verifiedAccount(UUID supplierId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM supplier_bank_accounts WHERE supplier_id = ? AND status = 'VERIFIED'",
                supplierId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> b = rows.get(0);
        UUID id = (UUID) b.get("id");
        String number = new String(encryptor.decrypt((byte[]) b.get("account_number_enc"), aad(id)), StandardCharsets.UTF_8);
        return new PayeeAccount(id, (String) b.get("bank_name"), (String) b.get("branch"), (String) b.get("account_name"), number);
    }

    private SupplierBankView view(UUID id) {
        Map<String, Object> b = jdbc.queryForMap("SELECT * FROM supplier_bank_accounts WHERE id = ?", id);
        boolean pending = "PENDING".equals(b.get("status"));
        List<String> actions = pending && CurrentActor.hasPermission("SUPPLIER_BANK_VERIFY") && !CurrentActor.userId().equals(b.get("entered_by_id"))
                ? List.of("VERIFY", "REJECT") : List.of();
        Timestamp va = (Timestamp) b.get("verified_at");
        return new SupplierBankView(id, (UUID) b.get("supplier_id"), (String) b.get("bank_name"), (String) b.get("branch"),
                (String) b.get("account_name"), "****" + b.get("account_number_last4"), (String) b.get("status"), (String) b.get("entered_by"),
                ((Timestamp) b.get("entered_at")).toInstant(), (String) b.get("verified_by"), va == null ? null : va.toInstant(),
                (String) b.get("verification_note"), actions);
    }

    private static String aad(UUID id) {
        return "supplier-bank-account:" + id;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
