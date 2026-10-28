package com.miaa.cas.journal;

import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.money.Money;
import com.miaa.cas.generalledger.AccountPurpose;
import com.miaa.cas.generalledger.FiscalCalendarService;
import com.miaa.cas.generalledger.domain.AccountingPeriod;
import com.miaa.cas.journal.web.JournalDtos.LineRequest;
import com.miaa.cas.journal.web.JournalDtos.VoucherRequest;
import com.miaa.cas.security.CurrentActor;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Overhead allocation cycles (TOR §3.5.1, Annex B.7, B.8). Each rule takes the period's posted balance of a sender
 * account in a sender cost centre and spreads it over receiving cost centres in proportion to their weights (fixed
 * percentages that add up to 100, or statistical quantities). A run creates one draft journal voucher dated the period's
 * last day: the sender is credited and the receivers debited (on the rule's receiver account, or the same account).
 * Cents left over by rounding go to the receiver with the largest weight. A cycle runs once per period unless the
 * earlier voucher was cancelled or rejected. The cycles and keys are MIAA's (C-10).
 */
@Service
public class AllocationService {

    public static final String MANAGE = "ALLOCATION_MANAGE";

    public record Receiver(UUID costCenterId, String costCenterCode, BigDecimal weight) {
    }

    public record Rule(UUID id, int seq, String description, UUID senderAccountId, String senderAccountCode, UUID senderCostCenterId,
            String senderCostCenterCode, UUID receiverAccountId, String receiverAccountCode, String keyType, List<Receiver> receivers) {
    }

    public record Cycle(UUID id, String code, String name, String description, String status, List<Rule> rules, String updatedBy,
            Instant updatedAt, long version) {
    }

    public record RuleInput(String description, UUID senderAccountId, UUID senderCostCenterId, UUID receiverAccountId, String keyType,
            Map<UUID, BigDecimal> receivers) {
    }

    public record PreviewLine(int seq, String accountCode, String costCenterCode, String description, BigDecimal debit, BigDecimal credit) {
    }

    public record Preview(UUID cycleId, UUID periodId, String periodName, BigDecimal total, List<PreviewLine> lines, List<String> notes) {
    }

    public record Run(UUID id, UUID periodId, String periodName, UUID voucherId, String voucherNumber, String voucherStatus, BigDecimal total,
            String runBy, Instant runAt) {
    }

    private final JdbcTemplate jdbc;
    private final FiscalCalendarService calendar;
    private final JournalVoucherService vouchers;
    private final AuditService audit;
    private final Clock clock;

    public AllocationService(JdbcTemplate jdbc, FiscalCalendarService calendar, JournalVoucherService vouchers, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.calendar = calendar;
        this.vouchers = vouchers;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Cycle> list() {
        return jdbc.queryForList("SELECT id FROM allocation_cycles ORDER BY status, code", UUID.class).stream().map(this::get).toList();
    }

    @Transactional
    public Cycle create(String code, String name, String description, List<RuleInput> rules) {
        CurrentActor.requirePermission(MANAGE);
        String c = code == null ? "" : code.trim().toUpperCase();
        if (!c.matches("[A-Z0-9][A-Z0-9_-]{1,29}")) {
            throw bad("The code is 2 to 30 letters, digits, dashes or underscores.");
        }
        if (jdbc.queryForObject("SELECT count(*) FROM allocation_cycles WHERE code = ?", Long.class, c) > 0) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "A cycle " + c + " already exists.");
        }
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now(clock));
        jdbc.update("INSERT INTO allocation_cycles (id, code, name, description, status, created_by, created_at, updated_by, updated_at) "
                + "VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?)", id, c, name.trim(), blank(description), CurrentActor.username(), now,
                CurrentActor.username(), now);
        writeRules(id, rules);
        audit.record("ALLOCATION_CYCLE_CREATED", "ALLOCATION_CYCLE", id, Map.of("code", c, "rules", rules.size()));
        return get(id);
    }

    /** Replaces the cycle's rules (and name and description); earlier runs keep their vouchers. */
    @Transactional
    public Cycle update(UUID id, long version, String name, String description, List<RuleInput> rules) {
        CurrentActor.requirePermission(MANAGE);
        lock(id, version);
        jdbc.update("DELETE FROM allocation_rules WHERE cycle_id = ?", id);
        writeRules(id, rules);
        jdbc.update("UPDATE allocation_cycles SET name = ?, description = ?, updated_by = ?, updated_at = ?, version = version + 1 WHERE id = ?",
                name.trim(), blank(description), CurrentActor.username(), Timestamp.from(Instant.now(clock)), id);
        audit.record("ALLOCATION_CYCLE_UPDATED", "ALLOCATION_CYCLE", id, Map.of("rules", rules.size()));
        return get(id);
    }

    @Transactional
    public Cycle setStatus(UUID id, long version, String status) {
        CurrentActor.requirePermission(MANAGE);
        if (!Set.of("ACTIVE", "INACTIVE").contains(status)) {
            throw bad("status must be ACTIVE or INACTIVE.");
        }
        lock(id, version);
        jdbc.update("UPDATE allocation_cycles SET status = ?, updated_by = ?, updated_at = ?, version = version + 1 WHERE id = ?", status,
                CurrentActor.username(), Timestamp.from(Instant.now(clock)), id);
        audit.record("ALLOCATION_CYCLE_" + status, "ALLOCATION_CYCLE", id, Map.of());
        return get(id);
    }

    /** What a run would post for the period, without creating anything. */
    @Transactional(readOnly = true)
    public Preview preview(UUID id, UUID periodId) {
        AccountingPeriod period = calendar.period(periodId);
        Cycle cycle = get(id);
        List<PreviewLine> lines = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Rule r : cycle.rules()) {
            BigDecimal balance = Money.of(jdbc.queryForObject("""
                    SELECT coalesce(sum(debit - credit), 0) FROM journal_entry_lines
                    WHERE account_id = ? AND cost_center_id = ? AND period_id = ?""", BigDecimal.class, r.senderAccountId(),
                    r.senderCostCenterId(), periodId));
            if (balance.signum() == 0) {
                notes.add("Rule " + r.seq() + ": " + r.senderAccountCode() + " / " + r.senderCostCenterCode() + " has nothing to allocate in "
                        + period.getName() + ".");
                continue;
            }
            String text = "Allocation " + cycle.code() + " rule " + r.seq() + (r.description() == null ? "" : ": " + r.description());
            lines.add(line(r.seq(), r.senderAccountCode(), r.senderCostCenterCode(), text, balance.negate()));
            BigDecimal weights = r.receivers().stream().map(Receiver::weight).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal given = BigDecimal.ZERO;
            Receiver largest = r.receivers().stream().max((a, b) -> a.weight().compareTo(b.weight())).orElseThrow();
            Map<Receiver, BigDecimal> shares = new LinkedHashMap<>();
            for (Receiver x : r.receivers()) {
                BigDecimal share = balance.multiply(x.weight()).divide(weights, 2, RoundingMode.HALF_UP);
                shares.put(x, share);
                given = given.add(share);
            }
            shares.merge(largest, balance.subtract(given), BigDecimal::add);
            String account = r.receiverAccountCode() == null ? r.senderAccountCode() : r.receiverAccountCode();
            shares.forEach((x, share) -> {
                if (share.signum() != 0) {
                    lines.add(line(r.seq(), account, x.costCenterCode(), text, share));
                }
            });
            total = total.add(balance.abs());
        }
        return new Preview(id, periodId, period.getName(), Money.of(total), lines, notes);
    }

    /** Creates the draft journal voucher for the period. */
    @Transactional
    public Run run(UUID id, UUID periodId) {
        CurrentActor.requirePermission(MANAGE);
        CurrentActor.requirePermission("JOURNAL_CREATE");
        Cycle cycle = get(id);
        jdbc.queryForObject("SELECT id FROM allocation_cycles WHERE id = ? FOR UPDATE", UUID.class, id);
        if (!"ACTIVE".equals(cycle.status())) {
            throw new ApiException(ErrorCode.INVALID_STATE, "The cycle is inactive.");
        }
        List<String> earlier = jdbc.queryForList("""
                SELECT v.voucher_number FROM allocation_runs r JOIN journal_vouchers v ON v.id = r.voucher_id
                WHERE r.cycle_id = ? AND r.period_id = ? AND v.status NOT IN ('CANCELLED', 'REJECTED')""", String.class, id, periodId);
        if (!earlier.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_STATE, "The cycle has already run for this period (voucher "
                    + (earlier.get(0) == null ? "in draft" : earlier.get(0)) + "); cancel that voucher to run it again.");
        }
        Preview p = preview(id, periodId);
        if (p.lines().isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_STATE, "There is nothing to allocate in " + p.periodName() + ".");
        }
        AccountingPeriod period = calendar.period(periodId);
        List<LineRequest> lines = p.lines().stream().map(l -> new LineRequest(account(l.accountCode()), costCenter(l.costCenterCode()), null,
                l.description().length() > 500 ? l.description().substring(0, 500) : l.description(), l.debit(), l.credit())).toList();
        UUID voucher = vouchers.create(new VoucherRequest("STANDARD", period.getEndDate(), false, "Overhead allocation " + cycle.code() + ", "
                + period.getName(), "ALLOC-" + cycle.code(), lines)).id();
        UUID runId = UUID.randomUUID();
        jdbc.update("INSERT INTO allocation_runs (id, cycle_id, period_id, voucher_id, total, run_by, run_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                runId, id, periodId, voucher, p.total(), CurrentActor.username(), Timestamp.from(Instant.now(clock)));
        audit.record("ALLOCATION_RUN", "ALLOCATION_CYCLE", id, Map.of("period", p.periodName(), "voucherId", voucher.toString(), "total", p.total()));
        return runs(id).stream().filter(r -> r.id().equals(runId)).findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<Run> runs(UUID id) {
        return jdbc.query("""
                SELECT r.id, r.period_id, p.name, r.voucher_id, v.voucher_number, v.status, r.total, r.run_by, r.run_at
                FROM allocation_runs r JOIN accounting_periods p ON p.id = r.period_id JOIN journal_vouchers v ON v.id = r.voucher_id
                WHERE r.cycle_id = ? ORDER BY r.run_at DESC""", (rs, i) -> new Run(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), rs.getObject(4, UUID.class), rs.getString(5), rs.getString(6), rs.getBigDecimal(7), rs.getString(8),
                rs.getTimestamp(9).toInstant()), id);
    }

    @Transactional(readOnly = true)
    public Cycle get(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM allocation_cycles WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Allocation cycle");
        }
        Map<String, Object> c = rows.get(0);
        List<Rule> rules = jdbc.query("""
                SELECT r.id, r.seq, r.description, r.sender_account_id, sa.code AS sa, r.sender_cost_center_id, sc.code AS sc,
                       r.receiver_account_id, ra.code AS ra, r.key_type
                FROM allocation_rules r JOIN chart_of_accounts sa ON sa.id = r.sender_account_id JOIN cost_centers sc ON sc.id = r.sender_cost_center_id
                LEFT JOIN chart_of_accounts ra ON ra.id = r.receiver_account_id WHERE r.cycle_id = ? ORDER BY r.seq""", (rs, i) -> {
                    UUID rid = rs.getObject("id", UUID.class);
                    List<Receiver> receivers = jdbc.query("SELECT x.cost_center_id, cc.code, x.weight FROM allocation_receivers x "
                            + "JOIN cost_centers cc ON cc.id = x.cost_center_id WHERE x.rule_id = ? ORDER BY cc.code",
                            (r2, j) -> new Receiver(r2.getObject(1, UUID.class), r2.getString(2), r2.getBigDecimal(3).stripTrailingZeros()), rid);
                    return new Rule(rid, rs.getInt("seq"), rs.getString("description"), rs.getObject("sender_account_id", UUID.class), rs.getString("sa"),
                            rs.getObject("sender_cost_center_id", UUID.class), rs.getString("sc"), rs.getObject("receiver_account_id", UUID.class),
                            rs.getString("ra"), rs.getString("key_type"), receivers);
                }, id);
        return new Cycle(id, (String) c.get("code"), (String) c.get("name"), (String) c.get("description"), (String) c.get("status"), rules,
                (String) c.get("updated_by"), ((Timestamp) c.get("updated_at")).toInstant(), ((Number) c.get("version")).longValue());
    }

    private void writeRules(UUID cycleId, List<RuleInput> rules) {
        if (rules == null || rules.isEmpty()) {
            throw bad("A cycle needs at least one rule.");
        }
        int seq = 1;
        for (RuleInput r : rules) {
            String at = "Rule " + seq + ": ";
            requireExpense(r.senderAccountId(), at + "sender account");
            if (r.receiverAccountId() != null) {
                requireExpense(r.receiverAccountId(), at + "receiver account");
            }
            requireCostCenter(r.senderCostCenterId(), at + "sender cost centre");
            if (!Set.of("PERCENT", "QUANTITY").contains(r.keyType())) {
                throw bad(at + "the key is PERCENT or QUANTITY.");
            }
            if (r.receivers() == null || r.receivers().isEmpty()) {
                throw bad(at + "name at least one receiving cost centre.");
            }
            BigDecimal sum = BigDecimal.ZERO;
            for (Map.Entry<UUID, BigDecimal> x : r.receivers().entrySet()) {
                requireCostCenter(x.getKey(), at + "receiver");
                if (x.getKey().equals(r.senderCostCenterId())) {
                    throw bad(at + "the sender cost centre cannot also receive.");
                }
                if (x.getValue() == null || x.getValue().signum() <= 0) {
                    throw bad(at + "every receiver needs a weight above zero.");
                }
                sum = sum.add(x.getValue());
            }
            if ("PERCENT".equals(r.keyType()) && sum.compareTo(BigDecimal.valueOf(100)) != 0) {
                throw bad(at + "the percentages add up to " + sum.stripTrailingZeros().toPlainString() + ", not 100.");
            }
            UUID ruleId = UUID.randomUUID();
            jdbc.update("INSERT INTO allocation_rules (id, cycle_id, seq, description, sender_account_id, sender_cost_center_id, receiver_account_id, "
                    + "key_type) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", ruleId, cycleId, seq, blank(r.description()), r.senderAccountId(),
                    r.senderCostCenterId(), r.receiverAccountId(), r.keyType());
            for (Map.Entry<UUID, BigDecimal> x : r.receivers().entrySet()) {
                jdbc.update("INSERT INTO allocation_receivers (rule_id, cost_center_id, weight) VALUES (?, ?, ?)", ruleId, x.getKey(), x.getValue());
            }
            seq++;
        }
    }

    private void requireExpense(UUID account, String what) {
        if (account == null) {
            throw bad(what + " is required.");
        }
        AccountPurpose.require(jdbc, account, AccountPurpose.ALLOCATION, what);
    }

    private void requireCostCenter(UUID cc, String what) {
        if (cc == null || jdbc.queryForObject("SELECT count(*) FROM cost_centers WHERE id = ?", Long.class, cc) == 0) {
            throw bad(what + " must be an existing cost centre.");
        }
    }

    private void lock(UUID id, long version) {
        List<Long> v = jdbc.queryForList("SELECT version FROM allocation_cycles WHERE id = ? FOR UPDATE", Long.class, id);
        if (v.isEmpty()) {
            throw ApiException.notFound("Allocation cycle");
        }
        if (v.get(0) != version) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION, "The cycle was changed by someone else. Reload and try again.");
        }
    }

    private UUID account(String code) {
        return jdbc.queryForObject("SELECT id FROM chart_of_accounts WHERE code = ?", UUID.class, code);
    }

    private UUID costCenter(String code) {
        return jdbc.queryForObject("SELECT id FROM cost_centers WHERE code = ?", UUID.class, code);
    }

    /** A signed amount as a debit (positive) or credit (negative) line. */
    private static PreviewLine line(int seq, String account, String cc, String text, BigDecimal amount) {
        return new PreviewLine(seq, account, cc, text, amount.signum() > 0 ? Money.of(amount) : Money.of(BigDecimal.ZERO),
                amount.signum() < 0 ? Money.of(amount.negate()) : Money.of(BigDecimal.ZERO));
    }

    private static ApiException bad(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message);
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
