package com.miaa.cas.generalledger;

import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.money.Money;
import com.miaa.cas.common.web.JdbcPaging;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.security.CurrentActor;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Open-item clearing (MS §11.1 "manual and automatic clearing", FR-GL-17). On an open-item managed account (clearing,
 * suspense, advance and GR/IR-type accounts), every posted line stays open until it is cleared together with other
 * lines of the same account and subsidiary account that net to zero. Clearing posts nothing and never changes a
 * posted line; it can be reversed with a reason. Automatic clearing proposes groups by reference (the same line
 * description) or by amount (an equal and opposite pair, oldest first) and applies them only when asked.
 */
@Service
public class ClearingService {

    public record OpenItem(UUID lineId, String entryNumber, LocalDate accountingDate, String description, UUID subsidiaryAccountId,
            String subsidiaryCode, BigDecimal debit, BigDecimal credit, long ageDays) {
    }

    public record OpenItemAccount(UUID id, String code, String name, long openItems, BigDecimal openNet) {
    }

    public record Proposal(String key, List<UUID> lineIds, BigDecimal total) {
    }

    public record Clearing(UUID id, String rule, int lineCount, BigDecimal total, String note, String status, String clearedBy,
            Instant clearedAt, String reversedBy, String reversalReason, List<String> entryNumbers) {
    }

    private static final String OPEN = """
            FROM journal_entry_lines l JOIN journal_entries e ON e.id = l.entry_id
            LEFT JOIN subsidiary_ledger_accounts s ON s.id = l.subsidiary_account_id
            WHERE l.account_id = ? AND NOT EXISTS (SELECT 1 FROM gl_clearing_lines c WHERE c.line_id = l.id AND c.active)""";

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Clock clock;

    public ClearingService(JdbcTemplate jdbc, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<OpenItemAccount> accounts() {
        return jdbc.query("""
                SELECT a.id, a.code, a.name,
                       (SELECT count(*) FROM journal_entry_lines l WHERE l.account_id = a.id
                          AND NOT EXISTS (SELECT 1 FROM gl_clearing_lines c WHERE c.line_id = l.id AND c.active)) AS open_items,
                       (SELECT coalesce(sum(l.debit - l.credit), 0) FROM journal_entry_lines l WHERE l.account_id = a.id
                          AND NOT EXISTS (SELECT 1 FROM gl_clearing_lines c WHERE c.line_id = l.id AND c.active)) AS open_net
                FROM chart_of_accounts a WHERE a.open_item_managed ORDER BY a.code""",
                (rs, i) -> new OpenItemAccount(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getLong(4), Money.of(rs.getBigDecimal(5))));
    }

    @Transactional
    public void setManaged(UUID accountId, boolean managed) {
        CurrentActor.requirePermission("ACCOUNT_ADMINISTER");
        Map<String, Object> a = jdbc.queryForList("SELECT code, account_type, postable, open_item_managed FROM chart_of_accounts WHERE id = ? FOR UPDATE",
                accountId).stream().findFirst().orElseThrow(() -> ApiException.notFound("Account"));
        if (managed && (!Boolean.TRUE.equals(a.get("postable")) || !List.of("ASSET", "LIABILITY").contains(a.get("account_type")))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Only postable asset or liability accounts are managed by open item.");
        }
        jdbc.update("UPDATE chart_of_accounts SET open_item_managed = ? WHERE id = ?", managed, accountId);
        audit.record(managed ? "ACCOUNT_OPEN_ITEM_MANAGED" : "ACCOUNT_OPEN_ITEM_UNMANAGED", "ACCOUNT", a.get("code"),
                Map.of("before", a.get("open_item_managed"), "after", managed));
    }

    @Transactional(readOnly = true)
    public PageResponse<OpenItem> openItems(UUID accountId, UUID subsidiaryAccountId, int page, int size) {
        requireManaged(accountId);
        LocalDate today = LocalDate.now(clock);
        return JdbcPaging.query(jdbc, "SELECT l.id, e.entry_number, l.accounting_date, l.description, l.subsidiary_account_id, s.code AS sl_code, "
                + "l.debit, l.credit " + OPEN + " AND (?::uuid IS NULL OR l.subsidiary_account_id = ?::uuid) ORDER BY l.accounting_date, e.entry_number, l.line_no",
                (rs, i) -> new OpenItem(rs.getObject(1, UUID.class), rs.getString(2), rs.getDate(3).toLocalDate(), rs.getString(4),
                        rs.getObject(5, UUID.class), rs.getString(6), rs.getBigDecimal(7), rs.getBigDecimal(8),
                        ChronoUnit.DAYS.between(rs.getDate(3).toLocalDate(), today)), page, size, accountId, subsidiaryAccountId, subsidiaryAccountId);
    }

    /** Clears the chosen lines as one group: same account and subsidiary account, all open, netting to zero. */
    @Transactional
    public Clearing clear(UUID accountId, List<UUID> lineIds, String note) {
        CurrentActor.requirePermission("GL_CLEARING");
        requireManaged(accountId);
        lockAccount(accountId);
        return create(accountId, lineIds, "MANUAL", note);
    }

    /** Proposes (and with {@code apply}, creates) clearings by REFERENCE or AMOUNT over the account's open items. */
    @Transactional
    public List<Proposal> autoClear(UUID accountId, String rule, boolean apply) {
        CurrentActor.requirePermission("GL_CLEARING");
        requireManaged(accountId);
        if (!"REFERENCE".equals(rule) && !"AMOUNT".equals(rule)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The rule must be REFERENCE or AMOUNT.");
        }
        if (apply) {
            lockAccount(accountId);
        }
        List<Map<String, Object>> open = jdbc.queryForList("SELECT l.id, l.subsidiary_account_id, l.description, l.debit, l.credit, l.accounting_date "
                + OPEN + " ORDER BY l.accounting_date, l.id", accountId);
        List<Proposal> proposals = "REFERENCE".equals(rule) ? byReference(open) : byAmount(open);
        if (apply) {
            for (Proposal p : proposals) {
                create(accountId, p.lineIds(), rule, "Automatic clearing by " + rule.toLowerCase(Locale.ROOT) + ": " + p.key());
            }
        }
        return proposals;
    }

    @Transactional
    public Clearing reverse(UUID clearingId, String reason) {
        CurrentActor.requirePermission("GL_CLEARING");
        Map<String, Object> c = jdbc.queryForList("SELECT * FROM gl_clearings WHERE id = ? FOR UPDATE", clearingId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Clearing"));
        if (!"ACTIVE".equals(c.get("status"))) {
            throw new ApiException(ErrorCode.INVALID_STATE, "The clearing is already reversed.");
        }
        if (reason == null || reason.trim().length() < 5) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Give the reason for reversing the clearing (at least 5 characters).");
        }
        jdbc.update("UPDATE gl_clearing_lines SET active = FALSE WHERE clearing_id = ?", clearingId);
        jdbc.update("UPDATE gl_clearings SET status = 'REVERSED', reversed_by = ?, reversed_at = ?, reversal_reason = ? WHERE id = ?",
                CurrentActor.username(), Timestamp.from(Instant.now(clock)), reason.trim(), clearingId);
        audit.record("GL_CLEARING_REVERSED", "GL_CLEARING", clearingId, Map.of("reason", reason.trim()));
        return clearing(clearingId);
    }

    @Transactional(readOnly = true)
    public List<Clearing> history(UUID accountId) {
        return jdbc.queryForList("SELECT id FROM gl_clearings WHERE account_id = ? ORDER BY cleared_at DESC LIMIT 200", UUID.class, accountId)
                .stream().map(this::clearing).toList();
    }

    /** Open items older than {@code days} on any open-item managed account, for the close checklist's exception report. */
    public List<String> agedItems(LocalDate asOf, int days, int limit) {
        return jdbc.query("""
                SELECT a.code, e.entry_number, l.accounting_date, l.debit - l.credit AS net FROM journal_entry_lines l
                JOIN chart_of_accounts a ON a.id = l.account_id JOIN journal_entries e ON e.id = l.entry_id
                WHERE a.open_item_managed AND l.accounting_date < ?
                  AND NOT EXISTS (SELECT 1 FROM gl_clearing_lines c WHERE c.line_id = l.id AND c.active)
                ORDER BY l.accounting_date LIMIT ?""", (rs, i) -> rs.getString(1) + " " + rs.getString(2) + " of " + rs.getDate(3)
                + " (" + Money.of(rs.getBigDecimal(4)).toPlainString() + ")", Date.valueOf(asOf.minusDays(days)), limit);
    }

    // ---------------------------------------------------------------- helpers

    private Clearing create(UUID accountId, List<UUID> lineIds, String rule, String note) {
        List<UUID> ids = lineIds == null ? List.of() : lineIds.stream().distinct().toList();
        if (ids.size() < 2) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Choose at least two items to clear against each other.");
        }
        List<Map<String, Object>> lines = jdbc.queryForList("SELECT l.id, l.subsidiary_account_id, l.debit, l.credit " + OPEN
                + " AND l.id = ANY (?)", accountId, (Object) ids.toArray(UUID[]::new));
        if (lines.size() != ids.size()) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Some of the items are not open items of this account (already cleared, or another account).");
        }
        if (lines.stream().map(l -> l.get("subsidiary_account_id")).distinct().count() > 1) {
            throw new ApiException(ErrorCode.ACCOUNTING_RULE_VIOLATION, "Items of different subsidiary accounts cannot clear each other.");
        }
        BigDecimal debit = lines.stream().map(l -> (BigDecimal) l.get("debit")).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = lines.stream().map(l -> (BigDecimal) l.get("credit")).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (debit.compareTo(credit) != 0) {
            throw new ApiException(ErrorCode.ACCOUNTING_RULE_VIOLATION, "The items do not net to zero: debits " + Money.of(debit).toPlainString()
                    + ", credits " + Money.of(credit).toPlainString() + ".");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO gl_clearings (id, account_id, rule, line_count, total, note, status, cleared_by_id, cleared_by, cleared_at)
                VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?)""", id, accountId, rule, ids.size(), Money.of(debit),
                note == null || note.isBlank() ? null : note.trim(), CurrentActor.userId(), CurrentActor.username(), Timestamp.from(Instant.now(clock)));
        for (UUID line : ids) {
            jdbc.update("INSERT INTO gl_clearing_lines (clearing_id, line_id) VALUES (?, ?)", id, line);
        }
        audit.record("GL_ITEMS_CLEARED", "GL_CLEARING", id, Map.of("account", accountId.toString(), "rule", rule, "lines", ids.size(),
                "total", Money.of(debit).toPlainString()));
        return clearing(id);
    }

    private static List<Proposal> byReference(List<Map<String, Object>> open) {
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> l : open) {
            String ref = l.get("description") == null ? "" : ((String) l.get("description")).trim().toLowerCase(Locale.ROOT);
            if (!ref.isEmpty()) {
                groups.computeIfAbsent(l.get("subsidiary_account_id") + "|" + ref, k -> new ArrayList<>()).add(l);
            }
        }
        List<Proposal> out = new ArrayList<>();
        groups.forEach((key, lines) -> {
            BigDecimal net = lines.stream().map(l -> ((BigDecimal) l.get("debit")).subtract((BigDecimal) l.get("credit"))).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (lines.size() >= 2 && net.signum() == 0) {
                out.add(new Proposal((String) lines.get(0).get("description"), lines.stream().map(l -> (UUID) l.get("id")).toList(),
                        Money.of(lines.stream().map(l -> (BigDecimal) l.get("debit")).reduce(BigDecimal.ZERO, BigDecimal::add))));
            }
        });
        return out;
    }

    private static List<Proposal> byAmount(List<Map<String, Object>> open) {
        Map<String, Deque<Map<String, Object>>> debits = new LinkedHashMap<>();
        for (Map<String, Object> l : open) {
            if (((BigDecimal) l.get("debit")).signum() > 0) {
                debits.computeIfAbsent(l.get("subsidiary_account_id") + "|" + Money.of((BigDecimal) l.get("debit")).toPlainString(),
                        k -> new ArrayDeque<>()).add(l);
            }
        }
        List<Proposal> out = new ArrayList<>();
        for (Map<String, Object> l : open) {
            BigDecimal credit = (BigDecimal) l.get("credit");
            if (credit.signum() > 0) {
                Deque<Map<String, Object>> match = debits.get(l.get("subsidiary_account_id") + "|" + Money.of(credit).toPlainString());
                if (match != null && !match.isEmpty()) {
                    Map<String, Object> d = match.poll();
                    out.add(new Proposal(Money.of(credit).toPlainString(), List.of((UUID) d.get("id"), (UUID) l.get("id")), Money.of(credit)));
                }
            }
        }
        return out;
    }

    private Clearing clearing(UUID id) {
        Map<String, Object> c = jdbc.queryForMap("SELECT * FROM gl_clearings WHERE id = ?", id);
        List<String> entries = jdbc.queryForList("""
                SELECT DISTINCT e.entry_number FROM gl_clearing_lines cl JOIN journal_entry_lines l ON l.id = cl.line_id
                JOIN journal_entries e ON e.id = l.entry_id WHERE cl.clearing_id = ? ORDER BY e.entry_number""", String.class, id);
        return new Clearing(id, (String) c.get("rule"), ((Number) c.get("line_count")).intValue(), (BigDecimal) c.get("total"), (String) c.get("note"),
                (String) c.get("status"), (String) c.get("cleared_by"), ((Timestamp) c.get("cleared_at")).toInstant(), (String) c.get("reversed_by"),
                (String) c.get("reversal_reason"), entries);
    }

    private void requireManaged(UUID accountId) {
        Boolean managed = jdbc.queryForList("SELECT open_item_managed FROM chart_of_accounts WHERE id = ?", Boolean.class, accountId).stream()
                .findFirst().orElseThrow(() -> ApiException.notFound("Account"));
        if (!managed) {
            throw new ApiException(ErrorCode.INVALID_STATE, "The account is not managed by open item.");
        }
    }

    private void lockAccount(UUID accountId) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))", "gl-clearing:" + accountId);
    }
}
