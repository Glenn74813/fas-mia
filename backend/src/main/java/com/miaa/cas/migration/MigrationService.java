package com.miaa.cas.migration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.web.JdbcPaging;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.documents.DocumentOwnerPolicy;
import com.miaa.cas.documents.DocumentService;
import com.miaa.cas.documents.DocumentService.DocumentInfo;
import com.miaa.cas.journal.JournalVoucherService;
import com.miaa.cas.journal.web.JournalDtos.VoucherRequest;
import com.miaa.cas.migration.KindRules.Checked;
import com.miaa.cas.migration.KindRules.Entry;
import com.miaa.cas.migration.KindRules.Side;
import com.miaa.cas.notifications.NotificationService;
import com.miaa.cas.security.CurrentActor;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opening-balance migration (Phase 5). Each batch is one CAS-template CSV, validated row by row and kept with
 * its file and SHA-256. A GL opening trial balance becomes a draft OPENING journal voucher prepared by the
 * uploader, which then follows the journal workflow. Every other kind (open invoices, unapplied collections,
 * supplier advances, the fixed-asset register, stock on hand) belongs to a posted opening entry: it posts
 * nothing itself, records that entry as its posted entry, must agree with it line by line, and is applied by
 * a second person in one transaction. The rules of each kind live in a {@link KindRules}.
 */
@Service
public class MigrationService implements DocumentOwnerPolicy {

    public static final String OWNER = "MIGRATION_BATCH";
    static final int MAX_ROWS = 50_000;
    private static final int SHOWN_ROWS = 200;

    public enum Kind {
        GL_OPENING(List.of("account_code", "cost_center_code", "subsidiary_code", "description", "debit", "credit")),
        AR_OPEN_ITEMS(List.of("customer_code", "document_number", "document_date", "due_date", "description", "outstanding_amount")),
        AP_OPEN_ITEMS(List.of("supplier_code", "invoice_number", "invoice_date", "due_date", "description", "outstanding_amount")),
        AR_UNAPPLIED(List.of("customer_code", "receipt_number", "receipt_date", "description", "unapplied_amount")),
        AP_ADVANCES(List.of("supplier_code", "advance_reference", "advance_date", "description", "outstanding_amount")),
        FA_REGISTER(List.of("asset_number", "category_code", "description", "serial_number", "location", "custodian", "cost_center_code",
                "acquisition_date", "in_service_date", "cost", "residual_value", "useful_life_months", "remaining_life_months",
                "accumulated_depreciation")),
        INVENTORY_STOCK(List.of("item_code", "warehouse_code", "quantity", "unit_cost"));

        private final List<String> columns;

        Kind(List<String> columns) {
            this.columns = columns;
        }

        public List<String> columns() {
            return columns;
        }
    }

    public record BatchSummary(UUID id, String kind, String status, String description, String fileName, String fileSha256,
            int rowCount, int errorCount, BigDecimal totalAmount, LocalDate cutoverDate, String openingEntryNumber,
            UUID journalVoucherId, String journalVoucherNumber, String journalVoucherStatus, String uploadedBy, Instant uploadedAt,
            String appliedBy, Instant appliedAt, String cancelReason, long version) {
    }

    public record RowView(int rowNo, Map<String, String> data, List<String> errors) {
    }

    /** One reconciliation line: a party (customer or supplier) or an account. */
    public record PartyBalance(String partyCode, String partyName, BigDecimal ledger, BigDecimal batch, BigDecimal difference) {
    }

    public record Reconciliation(String controlAccountCode, BigDecimal ledgerTotal, BigDecimal batchTotal, int partiesMatched,
            List<PartyBalance> differences, boolean reconciled) {
    }

    public record BatchDetail(BatchSummary summary, List<String> columns, List<String> batchErrors, List<RowView> errorRows,
            List<RowView> firstRows, Reconciliation reconciliation, List<DocumentInfo> documents, List<String> availableActions) {
    }

    private final Map<Kind, KindRules> rules = new EnumMap<>(Kind.class);
    private final JournalVoucherService journals;
    private final DocumentService documents;
    private final AuditService audit;
    private final NotificationService notifications;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public MigrationService(List<KindRules> rules, JournalVoucherService journals, DocumentService documents, AuditService audit, NotificationService notifications,
            ObjectMapper json, JdbcTemplate jdbc, Clock clock) {
        rules.forEach(r -> this.rules.put(r.kind(), r));
        if (this.rules.size() != Kind.values().length) {
            throw new IllegalStateException("Every migration kind needs its rules: " + this.rules.keySet());
        }
        this.journals = journals;
        this.documents = documents;
        this.audit = audit;
        this.notifications = notifications;
        this.json = json;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public PageResponse<BatchSummary> list(String kind, int page, int size) {
        requireViewer();
        String k = kind == null || kind.isBlank() ? null : kind.trim().toUpperCase(Locale.ROOT);
        return JdbcPaging.ids(jdbc, "SELECT id FROM migration_batches WHERE (?::text IS NULL OR kind = ?::text) ORDER BY uploaded_at DESC, id",
                page, size, k, k).map(this::summary);
    }

    @Transactional(readOnly = true)
    public BatchDetail get(UUID id) {
        requireViewer();
        return detail(id);
    }

    // ------------------------------------------------------------------ commands

    @Transactional
    public BatchDetail upload(Kind kind, String description, LocalDate cutoverDate, String openingEntryNumber, String fileName,
            byte[] content) {
        CurrentActor.requirePermission("MIGRATION_PREPARE");
        if (content == null || content.length == 0) {
            throw new ApiException(ErrorCode.FILE_REJECTED, "No file was uploaded.");
        }
        if (description == null || description.isBlank() || description.trim().length() > 500) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Describe the batch (up to 500 characters).");
        }
        String sha = sha256(content);
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM migration_batches WHERE kind = ? AND file_sha256 = ? "
                + "AND status = 'APPLIED')", Boolean.class, kind.name(), sha))) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "This file has already been applied.");
        }
        Entry entry = null;
        LocalDate cutover;
        if (kind == Kind.GL_OPENING) {
            if (cutoverDate == null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Enter the cutover date of the opening balances.");
            }
            cutover = cutoverDate;
        } else {
            entry = openingEntry(openingEntryNumber);
            requireNoAppliedBatch(kind, entry);
            cutover = entry.date();
        }
        List<MigrationCsv.Row> rows = MigrationCsv.read(content, kind.columns(), MAX_ROWS);
        Checked checked = rules.get(kind).check(rows, cutover);

        UUID id = UUID.randomUUID();
        String name = fileName == null ? "upload.csv" : fileName;
        jdbc.update("""
                INSERT INTO migration_batches (id, kind, status, description, file_name, file_sha256, row_count, error_count, total_amount,
                    batch_errors, cutover_date, opening_entry_id, uploaded_by_id, uploaded_by, uploaded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)""", id, kind.name(),
                checked.errorCount() == 0 ? "VALIDATED" : "INVALID", description.trim(), name, sha, rows.size(), checked.errorCount(),
                checked.total(), write(checked.batchErrors()), Date.valueOf(cutover), entry == null ? null : entry.id(),
                CurrentActor.userId(), CurrentActor.username(), Timestamp.from(Instant.now(clock)));
        List<Object[]> params = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            params.add(new Object[] {id, rows.get(i).rowNo(), write(rows.get(i).values()), write(checked.rowErrors().get(i))});
        }
        jdbc.batchUpdate("INSERT INTO migration_batch_rows (batch_id, row_no, data, errors) VALUES (?, ?, ?::jsonb, ?::jsonb)", params);
        documents.upload(OWNER, id, name, content);
        audit.record("MIGRATION_BATCH_UPLOADED", OWNER, id, Map.of("kind", kind.name(), "sha256", sha, "rows", rows.size(),
                "errors", checked.errorCount(), "total", checked.total().toPlainString()));
        return detail(id);
    }

    /**
     * GL batch: creates the draft opening voucher (by the uploader, who becomes its preparer). Any other batch:
     * a second person applies it, provided every row is still valid and every line agrees with the opening entry.
     */
    @Transactional
    public BatchDetail apply(UUID id, long version) {
        Map<String, Object> b = lock(id, version);
        if (!"VALIDATED".equals(b.get("status"))) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Only a validated batch can be applied.");
        }
        Kind kind = Kind.valueOf((String) b.get("kind"));
        KindRules rule = rules.get(kind);
        boolean uploader = CurrentActor.userId().equals(b.get("uploaded_by_id"));
        List<MigrationCsv.Row> rows = storedRows(id);
        LocalDate cutover = ((Date) b.get("cutover_date")).toLocalDate();
        Timestamp now = Timestamp.from(Instant.now(clock));
        if (kind == Kind.GL_OPENING) {
            CurrentActor.requirePermission("MIGRATION_PREPARE");
            CurrentActor.requirePermission("JOURNAL_CREATE");
            if (!uploader) {
                throw new ApiException(ErrorCode.ACCESS_DENIED, "Only the uploader can create the opening voucher; they become its preparer.");
            }
            Checked checked = rule.check(rows, cutover);
            requireValid(checked);
            var voucher = journals.create(new VoucherRequest("OPENING", cutover, false, (String) b.get("description"),
                    truncate("Migration batch " + b.get("file_name"), 200), checked.lines()));
            var source = sourceFile(id, (String) b.get("file_sha256"));
            documents.upload("JOURNAL_VOUCHER", voucher.id(), source.document().getFileName(), source.content());
            jdbc.update("UPDATE migration_batches SET status = 'APPLIED', journal_voucher_id = ?, applied_by_id = ?, applied_by = ?, "
                    + "applied_at = ?, version = version + 1 WHERE id = ?", voucher.id(), CurrentActor.userId(), CurrentActor.username(), now, id);
            audit.record("MIGRATION_OPENING_VOUCHER_CREATED", OWNER, id, Map.of("voucherId", voucher.id().toString(),
                    "lines", checked.lines().size(), "total", checked.total().toPlainString()));
            return detail(id);
        }
        CurrentActor.requirePermission("MIGRATION_APPROVE");
        if (uploader) {
            throw new ApiException(ErrorCode.SEGREGATION_OF_DUTIES, "You uploaded this batch; another person must apply it.");
        }
        Entry entry = entry((UUID) b.get("opening_entry_id"));
        requireNoAppliedBatch(kind, entry);
        Checked checked = rule.check(rows, cutover);
        requireValid(checked);
        Reconciliation rec = reconcile(rule, entry, checked);
        if (!rec.reconciled()) {
            throw new ApiException(ErrorCode.ACCOUNTING_RULE_VIOLATION, "The batch does not agree with the opening entry on "
                    + rec.differences().size() + " line" + (rec.differences().size() == 1 ? "" : "s") + ".",
                    Map.of("differences", rec.differences()));
        }
        rule.apply(new KindRules.Context(id, (String) b.get("description"), entry, (UUID) b.get("uploaded_by_id"),
                (String) b.get("uploaded_by"), now), checked);
        jdbc.update("UPDATE migration_batches SET status = 'APPLIED', applied_by_id = ?, applied_by = ?, applied_at = ?, "
                + "version = version + 1 WHERE id = ?", CurrentActor.userId(), CurrentActor.username(), now, id);
        notifications.notify((UUID) b.get("uploaded_by_id"), "REQUEST_APPROVED", "Your migration batch (" + kind.name() + ") was applied by "
                + CurrentActor.username(), (String) b.get("description"), "/migration/" + id);
        audit.record("MIGRATION_BATCH_APPLIED", OWNER, id, Map.of("kind", kind.name(), "items", checked.items().size(),
                "total", checked.total().toPlainString(), "openingEntry", entry.number(), "uploadedBy", b.get("uploaded_by")));
        return detail(id);
    }

    @Transactional
    public BatchDetail cancel(UUID id, long version, String reason) {
        Map<String, Object> b = lock(id, version);
        if (!"VALIDATED".equals(b.get("status")) && !"INVALID".equals(b.get("status"))) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Only a batch that has not been applied can be cancelled.");
        }
        boolean uploader = CurrentActor.userId().equals(b.get("uploaded_by_id"));
        if (!uploader && !CurrentActor.hasPermission("MIGRATION_APPROVE")) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Only the uploader or an approver can cancel this batch.");
        }
        if (reason == null || reason.trim().length() < 5 || reason.trim().length() > 500) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Give a reason (5 to 500 characters).");
        }
        jdbc.update("UPDATE migration_batches SET status = 'CANCELLED', cancel_reason = ?, version = version + 1 WHERE id = ?",
                reason.trim(), id);
        audit.record("MIGRATION_BATCH_CANCELLED", OWNER, id, Map.of("reason", reason.trim()));
        return detail(id);
    }

    // ------------------------------------------------------------------ document policy

    @Override
    public String ownerType() {
        return OWNER;
    }

    @Override
    public void assertCanModify(UUID ownerId) {
        Map<String, Object> b = row(ownerId);
        CurrentActor.requirePermission("MIGRATION_PREPARE");
        String status = (String) b.get("status");
        if (!CurrentActor.userId().equals(b.get("uploaded_by_id")) || "APPLIED".equals(status) || "CANCELLED".equals(status)) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Only the uploader can attach files while the batch is open.");
        }
    }

    @Override
    public void assertCanView(UUID ownerId) {
        row(ownerId);
        requireViewer();
    }

    // ------------------------------------------------------------------ reconciliation

    /** Batch against opening entry, line by line; lines that are zero on both sides are left out. */
    private Reconciliation reconcile(KindRules rule, Entry entry, Checked checked) {
        Map<String, Side> ledger = rule.ledger(entry);
        Set<String> keys = new LinkedHashSet<>(ledger.keySet());
        keys.addAll(checked.batch().keySet());
        List<PartyBalance> all = new ArrayList<>();
        BigDecimal zero = BigDecimal.ZERO.setScale(2);
        for (String k : keys) {
            Side g = ledger.get(k);
            Side s = checked.batch().get(k);
            BigDecimal gl = g == null ? zero : g.amount().setScale(2);
            BigDecimal bt = s == null ? zero : s.amount().setScale(2);
            if (gl.signum() == 0 && bt.signum() == 0) {
                continue;
            }
            Side label = s != null ? s : g;
            all.add(new PartyBalance(label.code(), label.name(), gl, bt, bt.subtract(gl)));
        }
        all.sort(Comparator.comparing(PartyBalance::partyCode).thenComparing(PartyBalance::partyName));
        BigDecimal ledgerTotal = all.stream().map(PartyBalance::ledger).reduce(zero, BigDecimal::add);
        BigDecimal batchTotal = all.stream().map(PartyBalance::batch).reduce(zero, BigDecimal::add);
        List<PartyBalance> diffs = all.stream().filter(p -> p.difference().signum() != 0).toList();
        return new Reconciliation(rule.basis(), ledgerTotal, batchTotal, all.size() - diffs.size(), diffs.stream().limit(500).toList(),
                diffs.isEmpty() && !all.isEmpty());
    }

    // ------------------------------------------------------------------ helpers

    private static void requireViewer() {
        if (!CurrentActor.hasPermission("MIGRATION_PREPARE") && !CurrentActor.hasPermission("MIGRATION_APPROVE")) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "You cannot view migration batches.");
        }
    }

    private static void requireValid(Checked checked) {
        if (checked.errorCount() > 0) {
            List<String> problems = new ArrayList<>(checked.batchErrors());
            for (int i = 0; i < checked.rowErrors().size() && problems.size() < 50; i++) {
                for (String e : checked.rowErrors().get(i)) {
                    problems.add("Row " + (i + 1) + ": " + e);
                }
            }
            throw new ApiException(ErrorCode.ACCOUNTING_RULE_VIOLATION, "The batch is no longer valid against current CAS data; "
                    + "cancel it and upload a corrected file.", Map.of("violations", problems));
        }
    }

    private Entry openingEntry(String number) {
        if (number == null || number.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Enter the number of the posted opening entry these items belong to.");
        }
        List<UUID> ids = jdbc.queryForList("SELECT id FROM journal_entries WHERE entry_number = ? AND voucher_type = 'OPENING'", UUID.class,
                number.trim());
        if (ids.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "No posted opening entry " + number.trim() + " exists.");
        }
        return entry(ids.get(0));
    }

    private Entry entry(UUID id) {
        Map<String, Object> e = jdbc.queryForMap("SELECT id, entry_number, accounting_date, period_id FROM journal_entries WHERE id = ?", id);
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM journal_entries WHERE reversal_of_entry_id = ?)",
                Boolean.class, id))) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Opening entry " + e.get("entry_number") + " has been reversed.");
        }
        return new Entry(id, (String) e.get("entry_number"), ((Date) e.get("accounting_date")).toLocalDate(), (UUID) e.get("period_id"));
    }

    private void requireNoAppliedBatch(Kind kind, Entry entry) {
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM migration_batches WHERE kind = ? AND opening_entry_id = ? "
                + "AND status = 'APPLIED')", Boolean.class, kind.name(), entry.id()))) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "A batch of this type has already been applied to opening entry "
                    + entry.number() + ".");
        }
    }

    private Map<String, Object> row(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM migration_batches WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Migration batch");
        }
        return rows.get(0);
    }

    private Map<String, Object> lock(UUID id, long version) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM migration_batches WHERE id = ? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Migration batch");
        }
        if (((Number) rows.get(0).get("version")).longValue() != version) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION, "The batch was changed by someone else; reload it.");
        }
        return rows.get(0);
    }

    private List<MigrationCsv.Row> storedRows(UUID id) {
        return jdbc.query("SELECT row_no, data::text AS data FROM migration_batch_rows WHERE batch_id = ? ORDER BY row_no",
                (rs, i) -> new MigrationCsv.Row(rs.getInt("row_no"), read(rs.getString("data"), new TypeReference<LinkedHashMap<String, String>>() {
                })), id);
    }

    private DocumentService.Download sourceFile(UUID batchId, String sha) {
        DocumentInfo doc = documents.list(OWNER, batchId).stream().filter(d -> d.sha256() != null && java.security.MessageDigest.isEqual(
                sha.getBytes(java.nio.charset.StandardCharsets.US_ASCII), d.sha256().getBytes(java.nio.charset.StandardCharsets.US_ASCII))).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_STATE, "The batch's source file is missing."));
        return documents.download(doc.id());
    }

    private BatchSummary summary(UUID id) {
        Map<String, Object> r = jdbc.queryForMap("""
                SELECT b.*, e.entry_number, v.voucher_number, v.status AS voucher_status FROM migration_batches b
                LEFT JOIN journal_entries e ON e.id = b.opening_entry_id LEFT JOIN journal_vouchers v ON v.id = b.journal_voucher_id
                WHERE b.id = ?""", id);
        Timestamp applied = (Timestamp) r.get("applied_at");
        return new BatchSummary(id, (String) r.get("kind"), (String) r.get("status"), (String) r.get("description"),
                (String) r.get("file_name"), (String) r.get("file_sha256"), ((Number) r.get("row_count")).intValue(),
                ((Number) r.get("error_count")).intValue(), (BigDecimal) r.get("total_amount"), ((Date) r.get("cutover_date")).toLocalDate(),
                (String) r.get("entry_number"), (UUID) r.get("journal_voucher_id"), (String) r.get("voucher_number"),
                (String) r.get("voucher_status"), (String) r.get("uploaded_by"), ((Timestamp) r.get("uploaded_at")).toInstant(),
                (String) r.get("applied_by"), applied == null ? null : applied.toInstant(), (String) r.get("cancel_reason"),
                ((Number) r.get("version")).longValue());
    }

    private BatchDetail detail(UUID id) {
        BatchSummary s = summary(id);
        Kind kind = Kind.valueOf(s.kind());
        Map<String, Object> b = row(id);
        List<String> batchErrors = read(jdbc.queryForObject("SELECT batch_errors::text FROM migration_batches WHERE id = ?", String.class, id),
                new TypeReference<List<String>>() {
                });
        List<RowView> errorRows = rowViews("SELECT row_no, data::text AS data, errors::text AS errors FROM migration_batch_rows "
                + "WHERE batch_id = ? AND errors <> '[]'::jsonb ORDER BY row_no LIMIT " + SHOWN_ROWS, id);
        List<RowView> firstRows = rowViews("SELECT row_no, data::text AS data, errors::text AS errors FROM migration_batch_rows "
                + "WHERE batch_id = ? ORDER BY row_no LIMIT 20", id);
        Reconciliation rec = null;
        // Shown while the batch is open; once applied, the module's own reconciliation reports take over.
        if (kind != Kind.GL_OPENING && s.errorCount() == 0 && "VALIDATED".equals(s.status())) {
            KindRules rule = rules.get(kind);
            Checked checked = rule.check(storedRows(id), s.cutoverDate());
            if (checked.errorCount() == 0) {
                rec = reconcile(rule, entry((UUID) b.get("opening_entry_id")), checked);
            }
        }
        boolean uploader = CurrentActor.userId().equals(b.get("uploaded_by_id"));
        boolean open = "VALIDATED".equals(s.status()) || "INVALID".equals(s.status());
        List<String> actions = new ArrayList<>();
        if ("VALIDATED".equals(s.status())) {
            if (kind == Kind.GL_OPENING && uploader && CurrentActor.hasPermission("JOURNAL_CREATE")) {
                actions.add("CREATE_VOUCHER");
            }
            if (kind != Kind.GL_OPENING && !uploader && CurrentActor.hasPermission("MIGRATION_APPROVE") && rec != null && rec.reconciled()) {
                actions.add("APPLY");
            }
        }
        if (open && (uploader || CurrentActor.hasPermission("MIGRATION_APPROVE"))) {
            actions.add("CANCEL");
        }
        if (open && uploader) {
            actions.add("UPLOAD");
        }
        return new BatchDetail(s, kind.columns(), batchErrors, errorRows, firstRows, rec, documents.list(OWNER, id), actions);
    }

    private List<RowView> rowViews(String sql, UUID id) {
        return jdbc.query(sql, (rs, i) -> new RowView(rs.getInt("row_no"),
                read(rs.getString("data"), new TypeReference<LinkedHashMap<String, String>>() {
                }), read(rs.getString("errors"), new TypeReference<List<String>>() {
                })), id);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
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
}
