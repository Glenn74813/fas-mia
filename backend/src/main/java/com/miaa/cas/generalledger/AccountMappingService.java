package com.miaa.cas.generalledger;

import com.miaa.cas.audit.Actor;
import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.generalledger.domain.Account;
import com.miaa.cas.generalledger.domain.AccountRepository;
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
 * Control accounts used by automatic sub-ledger postings, configured by mapping key so MIAA's approved
 * chart can be wired in without code changes.
 */
@Service
public class AccountMappingService {

    /** Keys the system understands, with what each account is used for. */
    public static final Map<String, String> KEYS = Map.ofEntries(
            Map.entry("AP_TRADE_PAYABLE", "Accounts payable control account (supplier sub-ledger)"),
            Map.entry("INPUT_VAT", "Input VAT recognised on supplier invoices"),
            Map.entry("AR_TRADE_RECEIVABLE", "Accounts receivable control account (customer sub-ledger)"),
            Map.entry("OUTPUT_VAT", "Output VAT on billings"),
            Map.entry("UNAPPLIED_COLLECTIONS", "Collections received but not yet applied to invoices"),
            Map.entry("CASH_ON_HAND", "Undeposited collections held by collecting officers"),
            Map.entry("CREDITABLE_WHT", "Tax withheld by customers on their payments (creditable withholding tax)"),
            Map.entry("FA_DISPOSAL_GAIN", "Gain on disposal of property, plant and equipment"),
            Map.entry("FA_DISPOSAL_LOSS", "Loss on disposal of property, plant and equipment"),
            Map.entry("FA_IMPAIRMENT_LOSS", "Impairment loss and revaluation decreases on property, plant and equipment"),
            Map.entry("FA_IMPAIRMENT_REVERSAL", "Reversal of impairment loss (revaluation increase reversing an earlier decrease)"),
            Map.entry("FA_REVALUATION_SURPLUS", "Revaluation surplus on property, plant and equipment"),
            Map.entry("INVENTORY_GRIR", "Goods received not yet invoiced (GR/IR clearing)"),
            Map.entry("INVENTORY_VARIANCE", "Inventory count gains and losses"),
            Map.entry("INVENTORY_REVALUATION", "Inventory price adjustments (restating an item's average cost)"),
            Map.entry("SUPPLIER_ADVANCES", "Advances to suppliers and contractors (supplier sub-ledger)"));

    /** {@code purpose}: the {@link AccountPurpose} the mapped account must fit, so the screen offers only those. */
    public record Mapping(String key, String description, UUID accountId, String accountCode, String accountName, String purpose) {
    }

    private final JdbcTemplate jdbc;
    private final AccountRepository accounts;
    private final AuditService audit;
    private final Clock clock;

    public AccountMappingService(JdbcTemplate jdbc, AccountRepository accounts, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Mapping> list() {
        Map<String, UUID> current = new java.util.HashMap<>();
        jdbc.query("SELECT mapping_key, account_id FROM gl_account_mappings",
                rs -> { current.put(rs.getString(1), rs.getObject(2, UUID.class)); });
        return KEYS.keySet().stream().sorted().map(k -> {
            UUID id = current.get(k);
            Account a = id == null ? null : accounts.findById(id).orElse(null);
            return new Mapping(k, KEYS.get(k), id, a == null ? null : a.getCode(), a == null ? null : a.getName(), purposeOf(k));
        }).toList();
    }

    @Transactional
    public Mapping set(String key, UUID accountId, Actor actor) {
        if (!KEYS.containsKey(key)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown mapping key " + key);
        }
        Account a = accounts.findById(accountId).orElseThrow(() -> ApiException.notFound("Account"));
        if (!a.isPostable() || !"ACTIVE".equals(a.getStatus())) {
            throw AccountService.rule("Mapped accounts must be active and postable.");
        }
        AccountPurpose purpose = AccountPurpose.MAPPINGS.get(key);
        if (purpose != null && !purpose.fits(a)) {
            throw AccountService.rule(key + ": account " + a.getCode() + " cannot be used here; it must be " + purpose.requirement() + ".");
        }
        jdbc.update("""
                INSERT INTO gl_account_mappings (mapping_key, account_id, description, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT (mapping_key) DO UPDATE SET account_id = EXCLUDED.account_id,
                updated_at = EXCLUDED.updated_at, updated_by = EXCLUDED.updated_by""",
                key, accountId, KEYS.get(key), Timestamp.from(Instant.now(clock)), actor.username());
        audit.record(actor, "ACCOUNT_MAPPING_SET", "ACCOUNT_MAPPING", key, Map.of("account", a.getCode()));
        return new Mapping(key, KEYS.get(key), accountId, a.getCode(), a.getName(), purposeOf(key));
    }

    private static String purposeOf(String key) {
        return AccountPurpose.MAPPINGS.getOrDefault(key, AccountPurpose.JOURNAL_LINE).name();
    }

    /** The mapped account, or an accounting-rule error telling the user what to configure. */
    public UUID require(String key) {
        List<UUID> ids = jdbc.queryForList("SELECT account_id FROM gl_account_mappings WHERE mapping_key = ?", UUID.class, key);
        if (ids.isEmpty()) {
            throw AccountService.rule("The control account '" + key + "' is not configured (General ledger > Account mappings).");
        }
        return ids.get(0);
    }
}
