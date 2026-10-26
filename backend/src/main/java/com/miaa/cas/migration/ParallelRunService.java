package com.miaa.cas.migration;

import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.web.JdbcPaging;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.documents.DocumentOwnerPolicy;
import com.miaa.cas.documents.DocumentService;
import com.miaa.cas.documents.DocumentService.DocumentInfo;
import com.miaa.cas.generalledger.LedgerInquiryService;
import com.miaa.cas.generalledger.web.LedgerDtos.TrialBalanceRow;
import com.miaa.cas.notifications.NotificationService;
import com.miaa.cas.security.CurrentActor;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Parallel-run comparison (Phase 5e). A legacy trial balance for one CAS period, mapped to CAS account codes
 * (many legacy accounts may map to one CAS account), is compared with CAS's trial balance on closing balances or on
 * the period's movements. CAS's figures are a snapshot taken at upload (and again on "recompare"), so later postings
 * never change a comparison silently. Every line that does not match needs a written explanation; a second person
 * signs the comparison off only when none is left unexplained (MS §18).
 */
@Service
public class ParallelRunService implements DocumentOwnerPolicy {

    public static final String OWNER = "PARALLEL_RUN";
    static final List<String> COLUMNS = List.of("legacy_account_code", "legacy_account_name", "cas_account_code", "debit", "credit");

    public record RunSummary(UUID id, UUID periodId, String periodName, String basis, String legacySystem, String fileName,
            String fileSha256, String status, BigDecimal legacyTotal, BigDecimal casTotal, Instant comparedAt, Map<String, Integer> lineCounts,
            int unexplained, String uploadedBy, Instant uploadedAt, String signedOffBy, Instant signedOffAt, String signOffNote,
            String cancelReason, long version) {
    }

    public record Line(UUID id, String lineType, UUID accountId, String accountCode, String accountName, String legacyCodes,
            BigDecimal legacyAmount, BigDecimal casAmount, BigDecimal difference, String explanation, String explainedBy, Instant explainedAt) {
    }

    public record RunDetail(RunSummary summary, List<Line> lines, List<DocumentInfo> documents, List<String> availableActions) {
    }

    /** Legacy figures for one CAS account (or one unmapped legacy account when accountId is null). */
    private record Legacy(UUID accountId, String codes, BigDecimal amount) {
    }

    private record Explained(String text, String by, Timestamp at) {
    }

    private final LedgerInquiryService ledger;
    private final DocumentService documents;
    private final AuditService audit;
    private final NotificationService notifications;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ParallelRunService(LedgerInquiryService ledger, DocumentService documents, AuditService audit, NotificationService notifications, JdbcTemplate jdbc, Clock clock) {
        this.ledger = ledger;
        this.documents = documents;
        this.audit = audit;
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public PageResponse<RunSummary> list(int page, int size) {
        requireViewer();
        return JdbcPaging.ids(jdbc, "SELECT id FROM parallel_runs ORDER BY uploaded_at DESC, id", page, size).map(this::summary);
    }

    @Transactional(readOnly = true)
    public RunDetail get(UUID id) {
        requireViewer();
        return detail(id);
    }

    // ------------------------------------------------------------------ commands

    @Transactional
    public RunDetail upload(UUID periodId, String basis, String legacySystem, String fileName, byte[] content) {
        CurrentActor.requirePermission("MIGRATION_PREPARE");
        if (!"CLOSING".equals(basis) && !"PERIOD".equals(basis)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Compare on CLOSING balances or PERIOD movements.");
        }
        if (legacySystem == null || legacySystem.isBlank() || legacySystem.trim().length() > 100) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Name the legacy system (up to 100 characters).");
        }
        if (content == null || content.length == 0) {
            throw new ApiException(ErrorCode.FILE_REJECTED, "No file was uploaded.");
        }
        List<Legacy> legacy = parse(MigrationCsv.read(content, COLUMNS, MigrationService.MAX_ROWS));
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now(clock));
        String name = fileName == null ? "legacy-trial-balance.csv" : fileName;
        jdbc.update("""
                INSERT INTO parallel_runs (id, period_id, basis, legacy_system, file_name, file_sha256, status, legacy_total, cas_total,
                    compared_at, uploaded_by_id, uploaded_by, uploaded_at) VALUES (?, ?, ?, ?, ?, ?, 'OPEN', 0, 0, ?, ?, ?, ?)""",
                id, periodId, basis, legacySystem.trim(), name, sha256(content), now, CurrentActor.userId(), CurrentActor.username(), now);
        compare(id, periodId, basis, legacy, Map.of());
        documents.upload(OWNER, id, name, content);
        RunSummary s = summary(id);
        audit.record("PARALLEL_RUN_UPLOADED", OWNER, id, Map.of("period", s.periodName(), "basis", basis, "legacySystem", s.legacySystem(),
                "lines", s.lineCounts(), "sha256", s.fileSha256()));
        return detail(id);
    }

    /** Takes CAS's figures again (after corrections were posted); explanations stay only where the difference is unchanged. */
    @Transactional
    public RunDetail recompare(UUID id, long version) {
        CurrentActor.requirePermission("MIGRATION_PREPARE");
        Map<String, Object> r = lockOpen(id, version);
        List<Legacy> legacy = new ArrayList<>();
        Map<String, Explained> kept = new HashMap<>();
        for (Map<String, Object> l : jdbc.queryForList("SELECT * FROM parallel_run_lines WHERE run_id = ?", id)) {
            if (l.get("legacy_codes") != null) {
                legacy.add(new Legacy((UUID) l.get("account_id"), (String) l.get("legacy_codes"), (BigDecimal) l.get("legacy_amount")));
            }
            if (l.get("explanation") != null) {
                kept.put(key((UUID) l.get("account_id"), (String) l.get("legacy_codes")) + "|" + ((BigDecimal) l.get("difference")).toPlainString(),
                        new Explained((String) l.get("explanation"), (String) l.get("explained_by"), (Timestamp) l.get("explained_at")));
            }
        }
        jdbc.update("DELETE FROM parallel_run_lines WHERE run_id = ?", id);
        compare(id, (UUID) r.get("period_id"), (String) r.get("basis"), legacy, kept);
        jdbc.update("UPDATE parallel_runs SET compared_at = ?, version = version + 1 WHERE id = ?", Timestamp.from(Instant.now(clock)), id);
        audit.record("PARALLEL_RUN_RECOMPARED", OWNER, id, Map.of("lines", summary(id).lineCounts()));
        return detail(id);
    }

    @Transactional
    public RunDetail explain(UUID id, UUID lineId, String explanation) {
        if (!CurrentActor.hasPermission("MIGRATION_PREPARE") && !CurrentActor.hasPermission("MIGRATION_APPROVE")) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "You cannot explain parallel-run differences.");
        }
        Map<String, Object> r = row(id);
        if (!"OPEN".equals(r.get("status"))) {
            throw new ApiException(ErrorCode.INVALID_STATE, "The comparison is no longer open.");
        }
        if (explanation == null || explanation.trim().length() < 10 || explanation.trim().length() > 1000) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Explain the difference (10 to 1000 characters).");
        }
        int n = jdbc.update("UPDATE parallel_run_lines SET explanation = ?, explained_by = ?, explained_at = ? WHERE id = ? AND run_id = ? "
                + "AND line_type <> 'MATCHED'", explanation.trim(), CurrentActor.username(), Timestamp.from(Instant.now(clock)), lineId, id);
        if (n == 0) {
            throw ApiException.notFound("Difference");
        }
        audit.record("PARALLEL_RUN_DIFFERENCE_EXPLAINED", OWNER, id, Map.of("line", lineId.toString(), "explanation", explanation.trim()));
        return detail(id);
    }

    @Transactional
    public RunDetail signOff(UUID id, long version, String note) {
        CurrentActor.requirePermission("MIGRATION_APPROVE");
        Map<String, Object> r = lockOpen(id, version);
        if (CurrentActor.userId().equals(r.get("uploaded_by_id"))) {
            throw new ApiException(ErrorCode.SEGREGATION_OF_DUTIES, "You uploaded this comparison; another person must sign it off.");
        }
        int unexplained = unexplained(id);
        if (unexplained > 0) {
            throw new ApiException(ErrorCode.ACCOUNTING_RULE_VIOLATION, unexplained + " difference" + (unexplained == 1 ? " is" : "s are")
                    + " not explained yet.");
        }
        if (note == null || note.trim().length() < 5 || note.trim().length() > 1000) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Add a sign-off note (5 to 1000 characters).");
        }
        jdbc.update("UPDATE parallel_runs SET status = 'SIGNED_OFF', signed_off_by_id = ?, signed_off_by = ?, signed_off_at = ?, "
                + "sign_off_note = ?, version = version + 1 WHERE id = ?", CurrentActor.userId(), CurrentActor.username(),
                Timestamp.from(Instant.now(clock)), note.trim(), id);
        notifications.notify((UUID) r.get("uploaded_by_id"), "REQUEST_APPROVED", "Your parallel-run comparison was signed off by "
                + CurrentActor.username(), note.trim(), "/migration/parallel-runs/" + id);
        audit.record("PARALLEL_RUN_SIGNED_OFF", OWNER, id, Map.of("note", note.trim(), "uploadedBy", r.get("uploaded_by")));
        return detail(id);
    }

    @Transactional
    public RunDetail cancel(UUID id, long version, String reason) {
        Map<String, Object> r = lockOpen(id, version);
        if (!CurrentActor.userId().equals(r.get("uploaded_by_id")) && !CurrentActor.hasPermission("MIGRATION_APPROVE")) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Only the uploader or an approver can cancel this comparison.");
        }
        if (reason == null || reason.trim().length() < 5 || reason.trim().length() > 500) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Give a reason (5 to 500 characters).");
        }
        jdbc.update("UPDATE parallel_runs SET status = 'CANCELLED', cancel_reason = ?, version = version + 1 WHERE id = ?", reason.trim(), id);
        audit.record("PARALLEL_RUN_CANCELLED", OWNER, id, Map.of("reason", reason.trim()));
        return detail(id);
    }

    // ------------------------------------------------------------------ document policy

    @Override
    public String ownerType() {
        return OWNER;
    }

    @Override
    public void assertCanModify(UUID ownerId) {
        Map<String, Object> r = row(ownerId);
        CurrentActor.requirePermission("MIGRATION_PREPARE");
        if (!"OPEN".equals(r.get("status"))) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Files can only be attached while the comparison is open.");
        }
    }

    @Override
    public void assertCanView(UUID ownerId) {
        row(ownerId);
        requireViewer();
    }

    // ------------------------------------------------------------------ comparison

    private List<Legacy> parse(List<MigrationCsv.Row> rows) {
        Map<String, UUID> accounts = new HashMap<>();
        jdbc.query("SELECT id, code FROM chart_of_accounts WHERE postable", rs -> {
            accounts.put(rs.getString("code"), rs.getObject("id", UUID.class));
        });
        List<String> errors = new ArrayList<>();
        Map<UUID, BigDecimal> byAccount = new LinkedHashMap<>();
        Map<UUID, List<String>> codes = new HashMap<>();
        List<Legacy> unmapped = new ArrayList<>();
        for (MigrationCsv.Row r : rows) {
            List<String> e = new ArrayList<>();
            Map<String, String> v = r.values();
            String legacyCode = KindRules.text(v.get("legacy_account_code"), "legacy_account_code", e, true, 60);
            BigDecimal d = KindRules.amount(v.get("debit"), "debit", e, true);
            BigDecimal c = KindRules.amount(v.get("credit"), "credit", e, true);
            UUID account = null;
            if (!v.get("cas_account_code").isEmpty()) {
                account = accounts.get(v.get("cas_account_code"));
                if (account == null) {
                    e.add("CAS account " + v.get("cas_account_code") + " does not exist or is a header account.");
                }
            }
            if (!e.isEmpty()) {
                e.forEach(x -> errors.add("Line " + r.rowNo() + ": " + x));
                continue;
            }
            BigDecimal amount = (d == null ? BigDecimal.ZERO : d).subtract(c == null ? BigDecimal.ZERO : c).setScale(2);
            String label = legacyCode + (v.get("legacy_account_name").isEmpty() ? "" : " " + v.get("legacy_account_name"));
            if (account == null) {
                unmapped.add(new Legacy(null, truncate(label, 2000), amount));
            } else {
                byAccount.merge(account, amount, BigDecimal::add);
                codes.computeIfAbsent(account, k -> new ArrayList<>()).add(legacyCode);
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The file was rejected; correct it and upload again.",
                    Map.of("violations", errors.stream().limit(100).toList()));
        }
        List<Legacy> out = new ArrayList<>();
        byAccount.forEach((a, amt) -> out.add(new Legacy(a, truncate(String.join(", ", codes.get(a)), 2000), amt)));
        out.addAll(unmapped);
        return out;
    }

    private void compare(UUID runId, UUID periodId, String basis, List<Legacy> legacy, Map<String, Explained> kept) {
        Map<UUID, TrialBalanceRow> cas = new LinkedHashMap<>();
        TrialBalanceRow unclosed = null;
        for (TrialBalanceRow row : ledger.trialBalance(periodId).rows()) {
            if (row.accountId() == null) {
                unclosed = row; // prior years' result awaiting year-end closing; it has no account of its own
            } else {
                cas.put(row.accountId(), row);
            }
        }
        Map<UUID, Legacy> mapped = new HashMap<>();
        List<Object[]> lines = new ArrayList<>();
        BigDecimal legacyTotal = BigDecimal.ZERO;
        BigDecimal casTotal = BigDecimal.ZERO;
        for (Legacy l : legacy) {
            legacyTotal = legacyTotal.add(l.amount());
            if (l.accountId() == null) {
                lines.add(line(runId, "UNMAPPED", null, null, null, l.codes(), l.amount(), BigDecimal.ZERO.setScale(2), kept));
            } else {
                mapped.put(l.accountId(), l);
            }
        }
        Map<UUID, String[]> names = new HashMap<>();
        jdbc.query("SELECT id, code, name FROM chart_of_accounts", rs -> {
            names.put(rs.getObject("id", UUID.class), new String[] {rs.getString("code"), rs.getString("name")});
        });
        List<UUID> accountIds = new ArrayList<>(cas.keySet());
        mapped.keySet().stream().filter(a -> !cas.containsKey(a)).forEach(accountIds::add);
        accountIds.sort(Comparator.comparing(a -> names.get(a)[0]));
        for (UUID a : accountIds) {
            TrialBalanceRow row = cas.get(a);
            BigDecimal casAmount = row == null ? BigDecimal.ZERO.setScale(2) : amount(row, basis);
            casTotal = casTotal.add(casAmount);
            Legacy l = mapped.get(a);
            if (l == null && casAmount.signum() == 0) {
                continue;
            }
            BigDecimal legacyAmount = l == null ? BigDecimal.ZERO.setScale(2) : l.amount();
            String type = l == null ? "CAS_ONLY" : legacyAmount.compareTo(casAmount) == 0 ? "MATCHED"
                    : casAmount.signum() == 0 ? "LEGACY_ONLY" : "DIFFERENT";
            lines.add(line(runId, type, a, names.get(a)[0], names.get(a)[1], l == null ? null : l.codes(), legacyAmount, casAmount, kept));
        }
        if (unclosed != null && amount(unclosed, basis).signum() != 0) {
            BigDecimal casAmount = amount(unclosed, basis);
            casTotal = casTotal.add(casAmount);
            lines.add(line(runId, "CAS_ONLY", null, unclosed.code(), unclosed.name(), null, BigDecimal.ZERO.setScale(2), casAmount, kept));
        }
        jdbc.batchUpdate("""
                INSERT INTO parallel_run_lines (id, run_id, line_type, account_id, account_code, account_name, legacy_codes, legacy_amount,
                    cas_amount, difference, explanation, explained_by, explained_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", lines);
        jdbc.update("UPDATE parallel_runs SET legacy_total = ?, cas_total = ? WHERE id = ?", legacyTotal, casTotal, runId);
    }

    private static BigDecimal amount(TrialBalanceRow row, String basis) {
        return ("CLOSING".equals(basis) ? row.closingDebit().subtract(row.closingCredit())
                : row.periodDebit().subtract(row.periodCredit())).setScale(2);
    }

    private static Object[] line(UUID runId, String type, UUID account, String code, String name, String codes, BigDecimal legacy,
            BigDecimal cas, Map<String, Explained> kept) {
        BigDecimal diff = legacy.subtract(cas);
        Explained e = "MATCHED".equals(type) ? null : kept.get(key(account, codes) + "|" + diff.toPlainString());
        return new Object[] {UUID.randomUUID(), runId, type, account, code, name, codes, legacy, cas, diff, e == null ? null : e.text(),
            e == null ? null : e.by(), e == null ? null : e.at()};
    }

    private static String key(UUID account, String codes) {
        return account != null ? account.toString() : codes == null ? "unclosed" : "unmapped:" + codes;
    }

    // ------------------------------------------------------------------ helpers

    private static void requireViewer() {
        if (!CurrentActor.hasPermission("MIGRATION_PREPARE") && !CurrentActor.hasPermission("MIGRATION_APPROVE")) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "You cannot view parallel-run comparisons.");
        }
    }

    private int unexplained(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM parallel_run_lines WHERE run_id = ? AND line_type <> 'MATCHED' AND explanation IS NULL",
                Integer.class, id);
    }

    private Map<String, Object> row(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM parallel_runs WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Parallel-run comparison");
        }
        return rows.get(0);
    }

    private Map<String, Object> lockOpen(UUID id, long version) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM parallel_runs WHERE id = ? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Parallel-run comparison");
        }
        Map<String, Object> r = rows.get(0);
        if (((Number) r.get("version")).longValue() != version) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION, "The comparison was changed by someone else; reload it.");
        }
        if (!"OPEN".equals(r.get("status"))) {
            throw new ApiException(ErrorCode.INVALID_STATE, "The comparison is no longer open.");
        }
        return r;
    }

    private RunSummary summary(UUID id) {
        Map<String, Object> r = jdbc.queryForMap("SELECT r.*, p.name AS period_name FROM parallel_runs r JOIN accounting_periods p ON p.id = r.period_id "
                + "WHERE r.id = ?", id);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String t : List.of("MATCHED", "DIFFERENT", "CAS_ONLY", "LEGACY_ONLY", "UNMAPPED")) {
            counts.put(t, 0);
        }
        jdbc.query("SELECT line_type, count(*) FROM parallel_run_lines WHERE run_id = ? GROUP BY 1", rs -> {
            counts.put(rs.getString(1), rs.getInt(2));
        }, id);
        Timestamp signed = (Timestamp) r.get("signed_off_at");
        return new RunSummary(id, (UUID) r.get("period_id"), (String) r.get("period_name"), (String) r.get("basis"),
                (String) r.get("legacy_system"), (String) r.get("file_name"), (String) r.get("file_sha256"), (String) r.get("status"),
                (BigDecimal) r.get("legacy_total"), (BigDecimal) r.get("cas_total"), ((Timestamp) r.get("compared_at")).toInstant(), counts,
                unexplained(id), (String) r.get("uploaded_by"), ((Timestamp) r.get("uploaded_at")).toInstant(), (String) r.get("signed_off_by"),
                signed == null ? null : signed.toInstant(), (String) r.get("sign_off_note"), (String) r.get("cancel_reason"),
                ((Number) r.get("version")).longValue());
    }

    private RunDetail detail(UUID id) {
        RunSummary s = summary(id);
        List<Line> lines = jdbc.query("""
                SELECT * FROM parallel_run_lines WHERE run_id = ?
                ORDER BY CASE line_type WHEN 'MATCHED' THEN 1 ELSE 0 END, account_code NULLS FIRST, legacy_codes""", (rs, i) -> {
            Timestamp at = rs.getTimestamp("explained_at");
            return new Line(rs.getObject("id", UUID.class), rs.getString("line_type"), rs.getObject("account_id", UUID.class),
                    rs.getString("account_code"), rs.getString("account_name"), rs.getString("legacy_codes"), rs.getBigDecimal("legacy_amount"),
                    rs.getBigDecimal("cas_amount"), rs.getBigDecimal("difference"), rs.getString("explanation"), rs.getString("explained_by"),
                    at == null ? null : at.toInstant());
        }, id);
        boolean open = "OPEN".equals(s.status());
        boolean uploader = Objects.equals(CurrentActor.userId(), row(id).get("uploaded_by_id"));
        List<String> actions = new ArrayList<>();
        if (open && (CurrentActor.hasPermission("MIGRATION_PREPARE") || CurrentActor.hasPermission("MIGRATION_APPROVE"))) {
            actions.add("EXPLAIN");
        }
        if (open && CurrentActor.hasPermission("MIGRATION_PREPARE")) {
            actions.add("RECOMPARE");
        }
        if (open && !uploader && CurrentActor.hasPermission("MIGRATION_APPROVE") && s.unexplained() == 0) {
            actions.add("SIGN_OFF");
        }
        if (open && (uploader || CurrentActor.hasPermission("MIGRATION_APPROVE"))) {
            actions.add("CANCEL");
        }
        return new RunDetail(s, lines, documents.list(OWNER, id), actions);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String template() {
        return """
                # CAS template: legacy trial balance for one CAS period, for the parallel-run comparison.
                # One row per legacy account. debit / credit: the closing balance (or the period's movement, if you compare movements),
                # positive, two decimals, no thousands separators. cas_account_code maps the legacy account to a postable CAS account;
                # several legacy accounts may map to one CAS account. Leave it empty for accounts with no CAS counterpart: they are
                # listed as unmapped and must be explained like any difference.
                """ + String.join(",", COLUMNS) + "\n";
    }
}
