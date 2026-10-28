package com.miaa.cas.treasury;

import com.miaa.cas.audit.Actor;
import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.generalledger.AccountPurpose;
import com.miaa.cas.common.error.ErrorCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Bank accounts used for disbursements and collections, each tied to a cash-in-bank GL account. */
@Service
public class BankAccountService {

    public record BankAccount(UUID id, String code, String bankName, String accountName, String accountNumberLast4,
            UUID glAccountId, String glAccountCode, String status) {
    }

    public record BankAccountRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9.-]{0,29}$") String code,
            @NotBlank @Size(max = 200) String bankName,
            @NotBlank @Size(max = 200) String accountName,
            @NotBlank @Pattern(regexp = "^[0-9]{4}$", message = "enter only the last four digits") String accountNumberLast4,
            @NotNull UUID glAccountId) {
    }

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Clock clock;

    public BankAccountService(JdbcTemplate jdbc, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<BankAccount> list() {
        return jdbc.query("SELECT b.*, a.code AS gl_code FROM bank_accounts b JOIN chart_of_accounts a ON a.id = b.gl_account_id "
                + "ORDER BY b.code", (rs, i) -> new BankAccount(rs.getObject("id", UUID.class), rs.getString("code"),
                rs.getString("bank_name"), rs.getString("account_name"), rs.getString("account_number_last4"),
                rs.getObject("gl_account_id", UUID.class), rs.getString("gl_code"), rs.getString("status")));
    }

    @Transactional(readOnly = true)
    public BankAccount requireActive(UUID id) {
        return list().stream().filter(b -> b.id().equals(id) && "ACTIVE".equals(b.status())).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.ACCOUNTING_RULE_VIOLATION, "The bank account is not active.",
                        Map.of("violations", List.of("Choose an active bank account."))));
    }

    @Transactional
    public BankAccount create(BankAccountRequest req, Actor actor) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM bank_accounts WHERE code = ?)", Boolean.class, req.code());
        if (Boolean.TRUE.equals(exists)) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "Bank account code already exists.");
        }
        AccountPurpose.require(jdbc, req.glAccountId(), AccountPurpose.CASH, "Cash GL account");
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO bank_accounts (id, code, bank_name, account_name, account_number_last4, gl_account_id, status, "
                + "created_at, created_by) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)", id, req.code(), req.bankName(),
                req.accountName(), req.accountNumberLast4(), req.glAccountId(), Timestamp.from(Instant.now(clock)),
                actor.username());
        audit.record(actor, "BANK_ACCOUNT_CREATED", "BANK_ACCOUNT", req.code(), Map.of("bank", req.bankName(),
                "last4", req.accountNumberLast4()));
        return requireActive(id);
    }
}
