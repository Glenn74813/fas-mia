package com.miaa.cas.generalledger;

import com.miaa.cas.audit.Actor;
import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.generalledger.domain.Account;
import com.miaa.cas.generalledger.domain.AccountRepository;
import com.miaa.cas.generalledger.domain.AccountType;
import com.miaa.cas.generalledger.domain.NormalBalance;
import com.miaa.cas.generalledger.web.LedgerDtos.AccountRequest;
import com.miaa.cas.generalledger.web.LedgerDtos.AccountResponse;
import com.miaa.cas.generalledger.web.LedgerDtos.AccountUpdateRequest;
import com.miaa.cas.generalledger.web.LedgerDtos.ChartSummary;
import com.miaa.cas.generalledger.web.LedgerDtos.ImportError;
import com.miaa.cas.generalledger.web.LedgerDtos.ImportResult;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chart-of-accounts maintenance. Structural rules: parents are non-postable headers of the same type,
 * the hierarchy has no cycles, and an account's type, normal balance or postability cannot change once
 * it has been used on a voucher or posted entry.
 */
@Service
public class AccountService {

    static final Set<String> SUBSIDIARY_TYPES = Set.of("SUPPLIER", "CUSTOMER", "EMPLOYEE", "OTHER");
    private static final ZoneId MANILA = ZoneId.of("Asia/Manila");

    private final AccountRepository accounts;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Clock clock;

    public AccountService(AccountRepository accounts, JdbcTemplate jdbc, AuditService audit, Clock clock) {
        this.accounts = accounts;
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ChartSummary chart() {
        List<Account> all = accounts.findAllByOrderByCodeAsc();
        Map<UUID, String> codes = all.stream().collect(Collectors.toMap(Account::getId, Account::getCode));
        return new ChartSummary(all.stream().map(a -> toResponse(a, codes)).toList(),
                accounts.countByPlaceholderTrueAndStatus("ACTIVE"));
    }

    @Transactional(readOnly = true)
    public AccountResponse get(UUID id) {
        Account a = load(id);
        return toResponse(a, a.getParentId() == null ? Map.of() : Map.of(a.getParentId(), load(a.getParentId()).getCode()));
    }

    @Transactional
    public AccountResponse create(AccountRequest req) {
        Account account = build(req, false, Actor.current());
        audit.record("ACCOUNT_CREATED", "ACCOUNT", account.getCode(), Map.of("name", account.getName(),
                "type", account.getAccountType().name(), "postable", account.isPostable()));
        return get(account.getId());
    }

    @Transactional
    public AccountResponse update(UUID id, AccountUpdateRequest req) {
        Account account = load(id);
        if (account.getVersion() != req.version()) {
            throw ApiException.staleVersion();
        }
        Map<String, Object> before = snapshot(account);
        AccountType type = parseType(req.accountType());
        NormalBalance normal = req.normalBalance() == null ? type.defaultNormalBalance() : parseNormal(req.normalBalance());
        UUID parentId = resolveParent(req.parentCode(), type);
        boolean structureChanged = type != account.getAccountType() || normal != account.getNormalBalance()
                || req.postable() != account.isPostable();
        if (structureChanged && isUsed(id)) {
            throw rule("Type, normal balance and postability cannot change after the account has been used. "
                    + "Create a new account and reclassify through a journal entry.");
        }
        if (req.postable() && accounts.existsByParentId(id)) {
            throw rule("An account with child accounts cannot be postable.");
        }
        if (parentId != null && createsCycle(id, parentId)) {
            throw rule("The selected parent would create a cycle in the hierarchy.");
        }
        validateSubsidiaryType(req.subsidiaryType());
        account.setStructure(type, normal, parentId, req.postable());
        account.setAttributes(req.name().trim(), req.description(), req.fsLineCode(), req.requiresCostCenter(),
                blankToNull(req.subsidiaryType()), req.budgetControlled(), req.taxCode(), req.effectiveTo());
        account.touch(Instant.now(clock), Actor.current().username());
        accounts.flush();
        audit.record("ACCOUNT_UPDATED", "ACCOUNT", account.getCode(), Map.of("before", before, "after", snapshot(account)));
        return get(id);
    }

    @Transactional
    public AccountResponse setStatus(UUID id, long version, boolean active) {
        Account account = load(id);
        if (account.getVersion() != version) {
            throw ApiException.staleVersion();
        }
        if (!active) {
            Long pending = jdbc.queryForObject("""
                    SELECT count(*) FROM journal_voucher_lines l JOIN journal_vouchers v ON v.id = l.voucher_id
                    WHERE l.account_id = ? AND v.status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'RETURNED')""", Long.class, id);
            if (pending != null && pending > 0) {
                throw rule("The account is used on " + pending + " unposted voucher line(s); resolve them first.");
            }
        }
        account.setStatus(active ? "ACTIVE" : "INACTIVE");
        account.touch(Instant.now(clock), Actor.current().username());
        accounts.flush();
        audit.record(active ? "ACCOUNT_ACTIVATED" : "ACCOUNT_DEACTIVATED", "ACCOUNT", account.getCode(), Map.of());
        return get(id);
    }

    /**
     * Imports accounts from CSV (header: code,name,type,normalBalance,parentCode,postable,fsLineCode,
     * requiresCostCenter,subsidiaryType,budgetControlled). All-or-nothing: any row error rolls back the
     * whole file and every error is reported. Lines starting with '#' are comments.
     */
    @Transactional
    public ImportResult importCsv(InputStream csv, boolean placeholder, Actor actor) {
        List<String[]> rows = new ArrayList<>();
        List<Integer> lineNumbers = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(csv, StandardCharsets.UTF_8))) {
            String line;
            int n = 0;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                n++;
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                if (header) {
                    header = false;
                    if (!line.trim().toLowerCase().startsWith("code,name,type")) {
                        throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unexpected CSV header: " + line);
                    }
                    continue;
                }
                rows.add(line.split(",", -1));
                lineNumbers.add(n);
            }
        } catch (IOException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The file could not be read.");
        }
        List<ImportError> errors = new ArrayList<>();
        Map<String, Integer> pending = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            String[] r = rows.get(i);
            if (r.length != 10) {
                errors.add(new ImportError(lineNumbers.get(i), r.length > 0 ? r[0] : "", "Expected 10 columns, found " + r.length));
            } else if (pending.put(r[0].trim(), i) != null || accounts.existsByCode(r[0].trim())) {
                errors.add(new ImportError(lineNumbers.get(i), r[0], "Duplicate account code"));
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The import was rejected; no accounts were created.",
                    Map.of("errors", errors));
        }
        // Create parents before children: repeat passes until no further progress.
        int created = 0;
        boolean progress = true;
        while (!pending.isEmpty() && progress) {
            progress = false;
            for (var it = pending.entrySet().iterator(); it.hasNext();) {
                var e = it.next();
                String[] r = rows.get(e.getValue());
                String parentCode = r[4].trim();
                if (!parentCode.isEmpty() && pending.containsKey(parentCode)) {
                    continue;
                }
                try {
                    build(new AccountRequest(r[0].trim(), r[1].trim(), null, r[2].trim(), blankToNull(r[3]),
                            blankToNull(parentCode), Boolean.parseBoolean(r[5].trim()), null, null, blankToNull(r[6]),
                            Boolean.parseBoolean(r[7].trim()), blankToNull(r[8]), Boolean.parseBoolean(r[9].trim()), null),
                            placeholder, actor);
                    created++;
                } catch (ApiException ex) {
                    errors.add(new ImportError(lineNumbers.get(e.getValue()), r[0], ex.getMessage()));
                }
                it.remove();
                progress = true;
            }
        }
        pending.forEach((code, idx) -> errors.add(new ImportError(lineNumbers.get(idx), code, "Parent account not found")));
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The import was rejected; no accounts were created.",
                    Map.of("errors", errors));
        }
        accounts.flush();
        audit.record(actor, "CHART_OF_ACCOUNTS_IMPORTED", "CHART_OF_ACCOUNTS", null,
                Map.of("created", created, "placeholder", placeholder));
        return new ImportResult(created, placeholder, List.of());
    }

    private Account build(AccountRequest req, boolean placeholder, Actor actor) {
        if (accounts.existsByCode(req.code())) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "Account code " + req.code() + " already exists.");
        }
        AccountType type = parseType(req.accountType());
        NormalBalance normal = req.normalBalance() == null ? type.defaultNormalBalance() : parseNormal(req.normalBalance());
        UUID parentId = resolveParent(req.parentCode(), type);
        validateSubsidiaryType(req.subsidiaryType());
        if (req.subsidiaryType() != null && !req.postable()) {
            throw rule("Only postable accounts can require a subsidiary ledger.");
        }
        Instant now = Instant.now(clock);
        LocalDate from = req.effectiveFrom() == null ? LocalDate.of(2000, 1, 1) : req.effectiveFrom();
        Account a = new Account(UUID.randomUUID(), req.code(), req.name().trim(), type, normal, parentId, req.postable(), from,
                placeholder, now, actor.username());
        a.setAttributes(req.name().trim(), req.description(), blankToNull(req.fsLineCode()), req.requiresCostCenter(),
                blankToNull(req.subsidiaryType()), req.budgetControlled(), req.taxCode(), req.effectiveTo());
        return accounts.saveAndFlush(a);
    }

    private UUID resolveParent(String parentCode, AccountType type) {
        if (parentCode == null || parentCode.isBlank()) {
            return null;
        }
        Account parent = accounts.findByCode(parentCode.trim())
                .orElseThrow(() -> rule("Parent account " + parentCode + " does not exist."));
        if (parent.isPostable()) {
            throw rule("Parent account " + parentCode + " is postable; parents must be header accounts.");
        }
        if (parent.getAccountType() != type) {
            throw rule("Parent account " + parentCode + " is of type " + parent.getAccountType() + ", not " + type + ".");
        }
        return parent.getId();
    }

    private boolean createsCycle(UUID id, UUID parentId) {
        Map<UUID, UUID> parents = accounts.findAll().stream().filter(a -> a.getParentId() != null)
                .collect(Collectors.toMap(Account::getId, Account::getParentId));
        UUID cursor = parentId;
        for (int guard = 0; cursor != null && guard < 1000; guard++) {
            if (cursor.equals(id)) {
                return true;
            }
            cursor = parents.get(cursor);
        }
        return false;
    }

    private boolean isUsed(UUID id) {
        Boolean used = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM journal_voucher_lines WHERE account_id = ?) "
                + "OR EXISTS (SELECT 1 FROM journal_entry_lines WHERE account_id = ?)", Boolean.class, id, id);
        return Boolean.TRUE.equals(used);
    }

    private static void validateSubsidiaryType(String t) {
        if (t != null && !t.isBlank() && !SUBSIDIARY_TYPES.contains(t)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unsupported subsidiary type " + t);
        }
    }

    private static AccountType parseType(String t) {
        try {
            return AccountType.valueOf(t.trim().toUpperCase());
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unsupported account type '" + t + "'.");
        }
    }

    private static NormalBalance parseNormal(String n) {
        try {
            return NormalBalance.valueOf(n.trim().toUpperCase());
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unsupported normal balance '" + n + "'.");
        }
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static Map<String, Object> snapshot(Account a) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", a.getName());
        m.put("type", a.getAccountType().name());
        m.put("normalBalance", a.getNormalBalance().name());
        m.put("parentId", String.valueOf(a.getParentId()));
        m.put("postable", a.isPostable());
        m.put("fsLineCode", String.valueOf(a.getFsLineCode()));
        m.put("requiresCostCenter", a.isRequiresCostCenter());
        m.put("subsidiaryType", String.valueOf(a.getSubsidiaryType()));
        m.put("effectiveTo", String.valueOf(a.getEffectiveTo()));
        return m;
    }

    Account load(UUID id) {
        return accounts.findById(id).orElseThrow(() -> ApiException.notFound("Account"));
    }

    static ApiException rule(String message) {
        return new ApiException(ErrorCode.ACCOUNTING_RULE_VIOLATION, message, Map.of("violations", List.of(message)));
    }

    static AccountResponse toResponse(Account a, Map<UUID, String> codes) {
        return new AccountResponse(a.getId(), a.getCode(), a.getName(), a.getDescription(), a.getAccountType().name(),
                a.getNormalBalance().name(), a.getParentId(), a.getParentId() == null ? null : codes.get(a.getParentId()),
                a.isPostable(), a.getStatus(), a.getEffectiveFrom(), a.getEffectiveTo(), a.getFsLineCode(),
                a.isRequiresCostCenter(), a.getSubsidiaryType(), a.isBudgetControlled(), a.getTaxCode(), a.isPlaceholder(),
                a.getVersion());
    }

    static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(MANILA));
    }
}
