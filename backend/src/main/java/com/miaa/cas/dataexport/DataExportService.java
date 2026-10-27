package com.miaa.cas.dataexport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.miaa.cas.audit.Actor;
import com.miaa.cas.audit.AuditService;
import com.miaa.cas.audit.EventStore;
import com.miaa.cas.common.crypto.AesGcmEncryptor;
import com.miaa.cas.common.crypto.ChunkedFileCipher;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.web.JdbcPaging;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.documents.DocumentStorage;
import com.miaa.cas.notifications.NotificationService;
import com.miaa.cas.security.CurrentActor;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Full data export (TOR §3.6). One holder of {@code DATA_EXPORT} requests an export with a purpose and another approves
 * it. A worker then writes, from one consistent snapshot, every table as CSV ({@code tables/<table>.csv}, PostgreSQL
 * COPY format with a header), the data dictionary ({@code dictionary.json}), every supporting document
 * ({@code documents/<id>/<file name>}), the audit-chain verification and a manifest ({@code manifest.json}, with row
 * counts and SHA-256 per file, also listed in {@code SHA256SUMS}) into one ZIP. The ZIP is encrypted at rest in
 * {@code cas.exports.dir}; only the requester downloads it, while still holding the permission, until it expires.
 *
 * <p>Sign-in secrets (password hashes, authenticator secrets, recovery, e-mail and reset codes, session tokens, passkey
 * challenges), the replay cache and generated report and bank files are left out; the manifest says what and why.
 * Supplier bank account numbers are decrypted into {@code tables/supplier_bank_account_numbers.csv}.
 */
@Service
public class DataExportService {

    public static final String PERMISSION = "DATA_EXPORT";
    private static final Logger log = LoggerFactory.getLogger(DataExportService.class);

    static final Map<String, String> EXCLUDED_TABLES = Map.of(
            "refresh_tokens", "Session tokens (sign-in secrets).",
            "password_reset_tokens", "Password reset tokens (sign-in secrets).",
            "mfa_email_codes", "E-mailed sign-in codes (sign-in secrets).",
            "mfa_recovery_codes", "Recovery code hashes (sign-in secrets).",
            "webauthn_challenges", "Single-use passkey challenges (sign-in secrets).",
            "idempotency_keys", "Short-lived cache of repeated requests, not business data.",
            "document_contents", "Encrypted document storage; the documents themselves are under documents/.");
    static final Map<String, Set<String>> EXCLUDED_COLUMNS = Map.of(
            "users", Set.of("password_hash"),
            "mfa_methods", Set.of("secret_ciphertext", "enrollment_challenge_id"),
            "webauthn_credentials", Set.of("attested_credential_data"),
            "report_jobs", Set.of("content"),
            "payment_batches", Set.of("content"),
            "supplier_bank_accounts", Set.of("account_number_enc"));
    static final String EXCLUDED_COLUMNS_REASON = "Sign-in secrets, encrypted values (bank account numbers are exported decrypted in "
            + "supplier_bank_account_numbers.csv) and generated report or bank files, which can be produced again.";

    public record ExportView(UUID id, String kind, Integer fiscalYear, String purpose, String status, String requestedBy, Instant requestedAt,
            String decidedBy, Instant decidedAt, String decisionNote, Instant startedAt, Instant finishedAt, String fileName,
            Long sizeBytes, String sha256, String error, Instant expiresAt, long version) {
    }

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final TransactionTemplate tx;
    private final TransactionTemplate snapshot;
    private final AuditService audit;
    private final EventStore events;
    private final NotificationService notifications;
    private final DocumentStorage documents;
    private final AesGcmEncryptor encryptor;
    private final ObjectMapper json;
    private final Clock clock;
    private final Path dir;
    private final Duration retention;
    private final int retentionYears;

    public DataExportService(JdbcTemplate jdbc, DataSource dataSource, PlatformTransactionManager txManager, AuditService audit,
            EventStore events, NotificationService notifications, DocumentStorage documents, AesGcmEncryptor encryptor, ObjectMapper json,
            Clock clock, @Value("${cas.exports.dir:${java.io.tmpdir}/cas-exports}") String dir,
            @Value("${cas.exports.retention:P7D}") Duration retention, @Value("${cas.records.retention-years:10}") int retentionYears) {
        this.retentionYears = retentionYears;
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.tx = new TransactionTemplate(txManager);
        this.snapshot = new TransactionTemplate(txManager);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.snapshot.setReadOnly(true);
        this.audit = audit;
        this.events = events;
        this.notifications = notifications;
        this.documents = documents;
        this.encryptor = encryptor;
        this.json = json.copy().enable(SerializationFeature.INDENT_OUTPUT);
        this.clock = clock;
        this.dir = Path.of(dir);
        this.retention = retention;
    }

    // ---------------------------------------------------------------- requests and decisions

    /** A full export, or with {@code fiscalYearId} the archive of that closed fiscal year (TOR §4.5). */
    @Transactional
    public ExportView request(String purpose, UUID fiscalYearId) {
        CurrentActor.requirePermission(PERMISSION);
        String p = purpose == null ? "" : purpose.trim();
        if (p.length() < 10) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "State the purpose of the export in at least 10 characters.");
        }
        String kind = fiscalYearId == null ? "FULL" : "ARCHIVE";
        if (fiscalYearId != null) {
            List<String> status = jdbc.queryForList("SELECT status FROM fiscal_years WHERE id = ?", String.class, fiscalYearId);
            if (status.isEmpty()) {
                throw ApiException.notFound("Fiscal year");
            }
            if (!"CLOSED".equals(status.get(0))) {
                throw new ApiException(ErrorCode.INVALID_STATE, "Only a closed fiscal year can be archived.");
            }
        }
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO data_exports (id, kind, fiscal_year_id, purpose, status, requested_by_id, requested_by, requested_at) "
                + "VALUES (?, ?, ?, ?, 'REQUESTED', ?, ?, ?)", id, kind, fiscalYearId, p, CurrentActor.userId(), CurrentActor.username(),
                Timestamp.from(Instant.now(clock)));
        audit.record("DATA_EXPORT_REQUESTED", "DATA_EXPORT", id, fiscalYearId == null ? Map.of("kind", kind, "purpose", p)
                : Map.of("kind", kind, "purpose", p, "fiscalYearId", fiscalYearId.toString()));
        for (UUID holder : jdbc.queryForList("""
                SELECT DISTINCT u.id FROM users u JOIN user_roles ur ON ur.user_id = u.id JOIN role_permissions rp ON rp.role_id = ur.role_id
                WHERE rp.permission_code = ? AND u.status = 'ACTIVE' AND u.id <> ?""", UUID.class, PERMISSION, CurrentActor.userId())) {
            notifications.notify(holder, "DATA_EXPORT", fiscalYearId == null ? "A full data export needs approval"
                    : "A fiscal-year archive needs approval", CurrentActor.username() + " asked for an export: " + p, "/admin/data-exports");
        }
        return view(id);
    }

    /** Approves (queues) or rejects a requested export; never by the person who asked for it. */
    @Transactional
    public ExportView decide(UUID id, boolean approve, String note, long version) {
        CurrentActor.requirePermission(PERMISSION);
        Map<String, Object> e = row(id, true);
        if (((Number) e.get("version")).longValue() != version) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION, "The export was changed by someone else. Reload and try again.");
        }
        if (!"REQUESTED".equals(e.get("status"))) {
            throw new ApiException(ErrorCode.INVALID_STATE, "The export has already been " + e.get("status").toString().toLowerCase() + ".");
        }
        if (CurrentActor.userId().equals(e.get("requested_by_id"))) {
            throw new ApiException(ErrorCode.SEGREGATION_OF_DUTIES, "Another person must approve your export request.");
        }
        String n = note == null || note.isBlank() ? null : note.trim();
        if (!approve && (n == null || n.length() < 5)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Give a reason for rejecting the export.");
        }
        jdbc.update("UPDATE data_exports SET status = ?, decided_by_id = ?, decided_by = ?, decided_at = ?, decision_note = ?, "
                + "version = version + 1 WHERE id = ?", approve ? "APPROVED" : "REJECTED", CurrentActor.userId(), CurrentActor.username(),
                Timestamp.from(Instant.now(clock)), n, id);
        audit.record(approve ? "DATA_EXPORT_APPROVED" : "DATA_EXPORT_REJECTED", "DATA_EXPORT", id,
                n == null ? Map.of() : Map.of("note", n));
        notifications.notify((UUID) e.get("requested_by_id"), "DATA_EXPORT", approve ? "Your data export was approved"
                : "Your data export was rejected", approve ? "It is being prepared; you will be told when it is ready." : n, "/admin/data-exports");
        return view(id);
    }

    @Transactional(readOnly = true)
    public PageResponse<ExportView> list(int page, int size) {
        CurrentActor.requirePermission(PERMISSION);
        return JdbcPaging.query(jdbc, SELECT + " ORDER BY e.requested_at DESC, e.id", (rs, i) -> map(rs), page, size);
    }

    // ---------------------------------------------------------------- download

    public record Download(String fileName, long size, Path file, String context) {
    }

    /** Checks and audits a download; the caller then streams it with {@link #write}. */
    @Transactional
    public Download prepareDownload(UUID id) {
        CurrentActor.requirePermission(PERMISSION);
        Map<String, Object> e = row(id, false);
        if (!CurrentActor.userId().equals(e.get("requested_by_id"))) {
            throw ApiException.notFound("Data export");
        }
        if (!"DONE".equals(e.get("status"))) {
            throw new ApiException(ErrorCode.INVALID_STATE, "The export is " + e.get("status").toString().toLowerCase() + ".");
        }
        audit.record("DATA_EXPORT_DOWNLOADED", "DATA_EXPORT", id, Map.of("file", e.get("file_name"), "sha256", e.get("sha256")));
        return new Download((String) e.get("file_name"), ((Number) e.get("size_bytes")).longValue(), file(id), context(id));
    }

    public void write(Download d, OutputStream out) throws IOException {
        try (InputStream in = Files.newInputStream(d.file())) {
            ChunkedFileCipher.decrypt(encryptor, d.context(), in, out);
        }
    }

    // ---------------------------------------------------------------- worker

    @Scheduled(fixedDelayString = "${cas.exports.poll-interval:PT30S}")
    public void poll() {
        try {
            processPending();
            expire();
        } catch (RuntimeException e) {
            log.error("Data export worker failed", e);
        }
    }

    /** Builds every approved export; returns how many were processed. */
    public int processPending() {
        int n = 0;
        while (true) {
            Map<String, Object> job = tx.execute(s -> {
                List<Map<String, Object>> rows = jdbc.queryForList("""
                        UPDATE data_exports SET status = 'RUNNING', started_at = ?, version = version + 1
                        WHERE id = (SELECT id FROM data_exports WHERE status = 'APPROVED' ORDER BY requested_at FOR UPDATE SKIP LOCKED LIMIT 1)
                        RETURNING *""", Timestamp.from(Instant.now(clock)));
                return rows.isEmpty() ? null : rows.get(0);
            });
            if (job == null) {
                return n;
            }
            build(job);
            n++;
        }
    }

    void expire() {
        Timestamp now = Timestamp.from(Instant.now(clock));
        for (UUID id : jdbc.queryForList("SELECT id FROM data_exports WHERE status = 'DONE' AND expires_at < ?", UUID.class, now)) {
            try {
                Files.deleteIfExists(file(id));
            } catch (IOException e) {
                log.warn("Could not delete expired export {}: {}", id, e.toString());
                continue;
            }
            tx.executeWithoutResult(s -> {
                jdbc.update("UPDATE data_exports SET status = 'EXPIRED', version = version + 1 WHERE id = ?", id);
                audit.record(Actor.SYSTEM, "DATA_EXPORT_EXPIRED", "DATA_EXPORT", id, Map.of());
            });
        }
    }

    private void build(Map<String, Object> job) {
        UUID id = (UUID) job.get("id");
        Path target = file(id);
        try {
            Files.createDirectories(dir);
            Path partial = target.resolveSibling(id + ".partial");
            MessageDigest zipDigest = sha256();
            long[] size = {0};
            Map<String, Object> manifest = new LinkedHashMap<>();
            try (OutputStream file = Files.newOutputStream(partial);
                    OutputStream sealed = ChunkedFileCipher.encrypting(encryptor, context(id), file);
                    OutputStream counted = new FilterOutputStream(new DigestOutputStream(sealed, zipDigest)) {
                        @Override
                        public void write(byte[] b, int off, int len) throws IOException {
                            out.write(b, off, len);
                            size[0] += len;
                        }

                        @Override
                        public void write(int b) throws IOException {
                            out.write(b);
                            size[0]++;
                        }
                    };
                    ZipOutputStream zip = new ZipOutputStream(counted)) {
                snapshot.executeWithoutResult(s -> {
                    try {
                        writeContents(job, zip, manifest);
                    } catch (IOException | SQLException e) {
                        throw new IllegalStateException(e);
                    }
                });
            }
            Files.move(partial, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            String sha = HexFormat.of().formatHex(zipDigest.digest());
            String fileName = (manifest.get("fiscalYear") != null ? "miaa-cas-archive-FY" + manifest.get("fiscalYear")
                    : "miaa-cas-export-" + Instant.now(clock).toString().substring(0, 10)) + "-" + id.toString().substring(0, 8) + ".zip";
            Instant done = Instant.now(clock);
            tx.executeWithoutResult(s -> {
                jdbc.update("UPDATE data_exports SET status = 'DONE', finished_at = ?, file_name = ?, size_bytes = ?, sha256 = ?, "
                        + "expires_at = ?, version = version + 1 WHERE id = ?", Timestamp.from(done), fileName, size[0], sha,
                        Timestamp.from(done.plus(retention)), id);
                audit.record(Actor.SYSTEM, "DATA_EXPORT_GENERATED", "DATA_EXPORT", id, Map.of("file", fileName, "sha256", sha,
                        "bytes", size[0], "tables", ((List<?>) manifest.get("tables")).size(), "documents", manifest.get("documents")));
                if (job.get("fiscal_year_id") != null) {
                    // The archive's checksum enters the hash-chained audit trail, so a copy can later be proven unaltered.
                    audit.record(Actor.SYSTEM, "FISCAL_YEAR_ARCHIVED", "FISCAL_YEAR", job.get("fiscal_year_id"), Map.of("exportId",
                            id.toString(), "fiscalYear", manifest.get("fiscalYear"), "sha256", sha, "file", fileName));
                }
                notifications.notify((UUID) job.get("requested_by_id"), "DATA_EXPORT", "Your data export is ready",
                        "Download it within " + retention.toDays() + " days.", "/admin/data-exports");
            });
        } catch (IOException | RuntimeException e) {
            log.warn("Data export {} failed", id, e);
            try {
                Files.deleteIfExists(target.resolveSibling(id + ".partial"));
            } catch (IOException ignored) {
                // left for the operator; it is encrypted
            }
            String msg = "The export could not be generated (" + e.getClass().getSimpleName() + ").";
            tx.executeWithoutResult(s -> {
                jdbc.update("UPDATE data_exports SET status = 'FAILED', finished_at = ?, error = ?, version = version + 1 WHERE id = ?",
                        Timestamp.from(Instant.now(clock)), msg, id);
                notifications.notify((UUID) job.get("requested_by_id"), "DATA_EXPORT", "Your data export failed", msg, "/admin/data-exports");
            });
        }
    }

    /** Everything inside the ZIP, read in the caller's snapshot transaction. */
    private void writeContents(Map<String, Object> job, ZipOutputStream zip, Map<String, Object> manifest) throws IOException, SQLException {
        Map<String, String> sums = new LinkedHashMap<>();
        Connection con = DataSourceUtils.getConnection(dataSource);
        var copy = con.unwrap(PGConnection.class).getCopyAPI();

        UUID fiscalYearId = (UUID) job.get("fiscal_year_id");
        Map<String, String> scope = null;
        if (fiscalYearId != null) {
            Map<String, Object> fy = jdbc.queryForMap("SELECT fiscal_year, start_date, end_date FROM fiscal_years WHERE id = ?", fiscalYearId);
            scope = ArchiveScope.filters(fiscalYearId, ((java.sql.Date) fy.get("start_date")).toLocalDate(),
                    ((java.sql.Date) fy.get("end_date")).toLocalDate());
            manifest.put("fiscalYear", fy.get("fiscal_year"));
            manifest.put("periodCovered", fy.get("start_date") + " to " + fy.get("end_date"));
            manifest.put("retainUntil", ((java.sql.Date) fy.get("end_date")).toLocalDate().plusYears(retentionYears).toString());
        }
        List<Map<String, Object>> tables = new ArrayList<>();
        List<Map<String, String>> excluded = new ArrayList<>();
        Map<String, Object> dictionary = new LinkedHashMap<>();
        for (String table : jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' "
                + "AND table_type = 'BASE TABLE' ORDER BY table_name", String.class)) {
            List<Map<String, Object>> cols = jdbc.queryForList("""
                    SELECT c.column_name, c.data_type, c.is_nullable = 'YES' AS nullable,
                           col_description(format('public.%I', c.table_name)::regclass, c.ordinal_position) AS description
                    FROM information_schema.columns c WHERE c.table_schema = 'public' AND c.table_name = ? ORDER BY c.ordinal_position""", table);
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("description", jdbc.queryForObject("SELECT obj_description(format('public.%I', ?::text)::regclass, 'pg_class')",
                    String.class, table));
            t.put("columns", cols);
            dictionary.put(table, t);
            if (EXCLUDED_TABLES.containsKey(table)) {
                excluded.add(Map.of("table", table, "reason", EXCLUDED_TABLES.get(table)));
                continue;
            }
            String where = scope == null ? "TRUE" : scope.get(table);
            if (where == null) {
                continue;
            }
            Set<String> skip = EXCLUDED_COLUMNS.getOrDefault(table, Set.of());
            List<String> select = new ArrayList<>();
            for (Map<String, Object> c : cols) {
                String name = (String) c.get("column_name");
                if (!skip.contains(name)) {
                    // Remaining binary values (passkey credential ids) as base64 text.
                    select.add("bytea".equals(c.get("data_type")) ? "encode(" + quote(name) + ", 'base64') AS " + quote(name) : quote(name));
                }
            }
            String path = "tables/" + table + ".csv";
            long rows = jdbc.queryForObject("SELECT count(*) FROM " + quote(table) + " WHERE " + where, Long.class);
            sums.put(path, entry(zip, path, out -> copy.copyOut("COPY (SELECT " + String.join(", ", select) + " FROM " + quote(table)
                    + " WHERE " + where + ") TO STDOUT WITH (FORMAT csv, HEADER true)", out)));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("table", table);
            m.put("file", path);
            m.put("rows", rows);
            m.put("sha256", sums.get(path));
            if (!skip.isEmpty()) {
                m.put("excludedColumns", skip.stream().sorted().toList());
            }
            tables.add(m);
        }

        // Supplier bank account numbers, decrypted (full exports only; an archive keeps the last four digits).
        String banks = "tables/supplier_bank_account_numbers.csv";
        if (scope == null) {
            sums.put(banks, entry(zip, banks, out -> {
                out.write("supplier_bank_account_id,account_number\n".getBytes(StandardCharsets.UTF_8));
                jdbc.query("SELECT id, account_number_enc FROM supplier_bank_accounts ORDER BY id", (ResultSet rs) -> {
                    UUID bid = rs.getObject(1, UUID.class);
                    String number = new String(encryptor.decrypt(rs.getBytes(2), "supplier-bank-account:" + bid), StandardCharsets.UTF_8);
                    try {
                        out.write((bid + "," + csv(number) + "\n").getBytes(StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
            }));
        }

        // Supporting documents.
        int docs = 0;
        for (Map<String, Object> d : jdbc.queryForList("SELECT id, storage_key, file_name FROM supporting_documents WHERE "
                + (scope == null ? "TRUE" : scope.get("supporting_documents")) + " ORDER BY uploaded_at, id")) {
            String path = "documents/" + d.get("id") + "/" + safeName((String) d.get("file_name"));
            byte[] content = documents.get((String) d.get("storage_key"));
            sums.put(path, entry(zip, path, out -> out.write(content)));
            docs++;
        }

        sums.put("dictionary.json", entry(zip, "dictionary.json", out -> json.writeValue(nonClosing(out), dictionary)));

        manifest.put("exportId", job.get("id"));
        manifest.put("kind", scope == null ? "FULL" : "ARCHIVE");
        manifest.put("purpose", job.get("purpose"));
        manifest.put("requestedBy", job.get("requested_by"));
        manifest.put("approvedBy", job.get("decided_by"));
        manifest.put("generatedAt", Instant.now(clock).toString());
        manifest.put("schemaVersion", jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL "
                + "ORDER BY installed_rank DESC LIMIT 1", String.class));
        manifest.put("format", "Tables are CSV (RFC 4180, UTF-8, header row) from one consistent database snapshot; amounts are exact "
                + "decimals, timestamps are ISO 8601 with offset, empty fields are NULL. dictionary.json describes every column.");
        manifest.put("tables", tables);
        manifest.put("excludedTables", excluded);
        manifest.put("excludedColumnsReason", EXCLUDED_COLUMNS_REASON);
        manifest.put("documents", docs);
        manifest.put("auditChain", List.of(events.verify(EventStore.Table.AUDIT), events.verify(EventStore.Table.SECURITY)));
        sums.put("manifest.json", entry(zip, "manifest.json", out -> json.writeValue(nonClosing(out), manifest)));

        StringBuilder list = new StringBuilder();
        sums.forEach((p, h) -> list.append(h).append("  ").append(p).append('\n'));
        entry(zip, "SHA256SUMS", out -> out.write(list.toString().getBytes(StandardCharsets.UTF_8)));
    }

    // ---------------------------------------------------------------- helpers

    private interface Writer {
        void write(OutputStream out) throws IOException, SQLException;
    }

    /** Writes one ZIP entry and returns the SHA-256 of its content. */
    private static String entry(ZipOutputStream zip, String path, Writer w) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        MessageDigest md = sha256();
        try {
            w.write(new DigestOutputStream(nonClosing(zip), md));
        } catch (SQLException e) {
            throw new IOException(e);
        }
        zip.closeEntry();
        return HexFormat.of().formatHex(md.digest());
    }

    private static OutputStream nonClosing(OutputStream out) {
        return new FilterOutputStream(out) {
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
            }

            @Override
            public void close() throws IOException {
                flush();
            }
        };
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static String csv(String v) {
        return v.matches("[^\",\\r\\n]*") ? v : '"' + v.replace("\"", "\"\"") + '"';
    }

    static String safeName(String name) {
        String n = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        n = n.replaceAll("^\\.+", "_");
        return n.isEmpty() ? "document" : n.length() > 150 ? n.substring(n.length() - 150) : n;
    }

    private Path file(UUID id) {
        return dir.resolve(id + ".cas-export");
    }

    private static String context(UUID id) {
        return "data-export:" + id;
    }

    private Map<String, Object> row(UUID id, boolean lock) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM data_exports WHERE id = ?" + (lock ? " FOR UPDATE" : ""), id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Data export");
        }
        return rows.get(0);
    }

    private static final String SELECT = "SELECT e.*, fy.fiscal_year FROM data_exports e LEFT JOIN fiscal_years fy ON fy.id = e.fiscal_year_id";

    private ExportView view(UUID id) {
        return jdbc.queryForObject(SELECT + " WHERE e.id = ?", (rs, i) -> map(rs), id);
    }

    private static ExportView map(ResultSet rs) throws SQLException {
        long size = rs.getLong("size_bytes");
        int year = rs.getInt("fiscal_year");
        Integer fiscalYear = rs.wasNull() ? null : year;
        return new ExportView(rs.getObject("id", UUID.class), rs.getString("kind"), fiscalYear, rs.getString("purpose"), rs.getString("status"),
                rs.getString("requested_by"), ts(rs, "requested_at"), rs.getString("decided_by"), ts(rs, "decided_at"),
                rs.getString("decision_note"), ts(rs, "started_at"), ts(rs, "finished_at"), rs.getString("file_name"),
                rs.wasNull() ? null : size, rs.getString("sha256"), rs.getString("error"), ts(rs, "expires_at"), rs.getLong("version"));
    }

    private static Instant ts(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }
}
