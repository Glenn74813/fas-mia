package com.miaa.cas.financialreporting;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaa.cas.audit.Actor;
import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.crypto.ContentCipher;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.web.JdbcPaging;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.financialreporting.ReportDtos.JobRequest;
import com.miaa.cas.financialreporting.ReportDtos.JobView;
import com.miaa.cas.financialreporting.export.ReportExporters;
import com.miaa.cas.financialreporting.export.ReportTable;
import com.miaa.cas.generalledger.FiscalCalendarService;
import com.miaa.cas.generalledger.LedgerInquiryService;
import com.miaa.cas.generalledger.web.LedgerDtos.TrialBalance;
import com.miaa.cas.generalledger.web.LedgerDtos.TrialBalanceRow;
import com.miaa.cas.notifications.NotificationService;
import com.miaa.cas.notifications.OutboxService;
import com.miaa.cas.security.CurrentActor;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Background report generation. Requests are queued; a worker claims one job at a time with
 * {@code FOR UPDATE SKIP LOCKED} (safe with several application instances), renders it, and stores the
 * file with its SHA-256 until the retention period ends. Only the requester can download it; every
 * request and download is audited.
 */
@Service
public class ReportJobService {

    private static final Logger log = LoggerFactory.getLogger(ReportJobService.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Manila"));

    public record ReportFile(String fileName, String contentType, byte[] content) {
    }

    private final FinancialStatementService statements;
    private final ManagementReportService management;
    private final StatementTemplateService templates;
    private final LedgerInquiryService ledger;
    private final FiscalCalendarService calendar;
    private final AuditService audit;
    private final ContentCipher cipher;
    private final NotificationService notifications;
    private final OutboxService outbox;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;
    private final Duration retention;

    public ReportJobService(FinancialStatementService statements, ManagementReportService management, StatementTemplateService templates, LedgerInquiryService ledger,
            FiscalCalendarService calendar, AuditService audit, ContentCipher cipher, NotificationService notifications, JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper json, Clock clock,
            @Value("${cas.reports.retention:P30D}") Duration retention, OutboxService outbox) {
        this.outbox = outbox;
        this.statements = statements;
        this.management = management;
        this.templates = templates;
        this.ledger = ledger;
        this.calendar = calendar;
        this.audit = audit;
        this.cipher = cipher;
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
        this.retention = retention;
    }

    @Transactional
    public JobView request(JobRequest req) {
        Map<String, String> p = req.parameters();
        CurrentActor.requirePermission(ManagementReportService.permissionFor(req.reportType(), p));
        validateParameters(req.reportType(), p);
        UUID id = enqueue(req.reportType(), req.format(), p, CurrentActor.userId(), CurrentActor.username());
        audit.record("REPORT_REQUESTED", "REPORT_JOB", id, Map.of("type", req.reportType(), "format", req.format()));
        return view(id);
    }

    /** Fails fast on missing or malformed parameters (also used when a schedule is created and when it runs). */
    void validateParameters(String reportType, Map<String, String> p) {
        if ("FINANCIAL_STATEMENT".equals(reportType) || "TRIAL_BALANCE".equals(reportType)) {
            String periodId = p.get("periodId");
            if (periodId == null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "parameters.periodId is required.");
            }
            calendar.period(UUID.fromString(periodId));
            if ("FINANCIAL_STATEMENT".equals(reportType)) {
                if (p.get("template") == null) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "parameters.template is required.");
                }
                templates.resolve(p.get("template"));
            }
        } else {
            management.validate(reportType, p);
        }
    }

    /** Queues a job for a user; the caller has checked the permission and the parameters. */
    UUID enqueue(String reportType, String format, Map<String, String> p, UUID userId, String username) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO report_jobs (id, report_type, format, parameters, status, requested_by_id, requested_by, requested_at) "
                    + "VALUES (?, ?, ?, ?::jsonb, 'QUEUED', ?, ?, ?)", id, reportType, format, json.writeValueAsString(p), userId, username,
                    Timestamp.from(Instant.now(clock)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return id;
    }

    @Transactional(readOnly = true)
    public PageResponse<JobView> mine(int page, int size) {
        return JdbcPaging.ids(jdbc, "SELECT id FROM report_jobs WHERE requested_by_id = ? ORDER BY requested_at DESC, id", page, size,
                CurrentActor.userId()).map(this::view);
    }

    @Transactional(readOnly = true)
    public JobView get(UUID id) {
        JobView v = view(id);
        requireOwner(id);
        return v;
    }

    @Transactional
    public ReportFile download(UUID id) {
        requireOwner(id);
        Map<String, Object> r = jdbc.queryForMap("SELECT report_type, parameters::text AS parameters, status, file_name, content_type, "
                + "content, content_encryption, sha256 FROM report_jobs WHERE id = ?", id);
        try {
            // The requester must still hold the permission the report needs (it may have been revoked since).
            CurrentActor.requirePermission(ManagementReportService.permissionFor((String) r.get("report_type"),
                    json.readValue((String) r.get("parameters"), new TypeReference<Map<String, String>>() { })));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        if (!"DONE".equals(r.get("status"))) {
            throw new ApiException(ErrorCode.INVALID_STATE, "The report is " + r.get("status").toString().toLowerCase() + ".");
        }
        audit.record("REPORT_DOWNLOADED", "REPORT_JOB", id, Map.of("file", r.get("file_name"), "sha256", r.get("sha256")));
        return new ReportFile((String) r.get("file_name"), (String) r.get("content_type"),
                cipher.open((byte[]) r.get("content"), (String) r.get("content_encryption"), "report-job", id.toString()));
    }

    /** Processes queued jobs until none is left; returns how many were processed. */
    public int processPending() {
        int n = 0;
        while (processOne()) {
            n++;
        }
        return n;
    }

    @Scheduled(fixedDelayString = "${cas.reports.poll-interval:PT5S}")
    public void poll() {
        try {
            processPending();
            tx.executeWithoutResult(s -> jdbc.update("UPDATE report_jobs SET status = 'EXPIRED', content = NULL WHERE status = 'DONE' "
                    + "AND expires_at < ?", Timestamp.from(Instant.now(clock))));
        } catch (RuntimeException e) {
            log.error("Report worker failed", e);
        }
    }

    private boolean processOne() {
        Map<String, Object> job = tx.execute(s -> {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    UPDATE report_jobs SET status = 'RUNNING', started_at = ?
                    WHERE id = (SELECT id FROM report_jobs WHERE status = 'QUEUED' ORDER BY requested_at FOR UPDATE SKIP LOCKED LIMIT 1)
                    RETURNING id, report_type, format, parameters::text AS parameters, requested_by, requested_by_id, schedule_id""",
                    Timestamp.from(Instant.now(clock)));
            return rows.isEmpty() ? null : rows.get(0);
        });
        if (job == null) {
            return false;
        }
        UUID id = (UUID) job.get("id");
        try {
            Map<String, String> p = json.readValue((String) job.get("parameters"), new TypeReference<>() { });
            String format = (String) job.get("format");
            String by = (String) job.get("requested_by");
            String at = STAMP.format(Instant.now(clock));
            ReportTable table;
            String base;
            if ("FINANCIAL_STATEMENT".equals(job.get("report_type"))) {
                var s = statements.generate(p.get("template"), UUID.fromString(p.get("periodId")), "true".equals(p.get("comparative")));
                table = FinancialStatementService.toTable(s, by, at);
                base = s.templateCode() + "_" + s.periodName().replace(' ', '_');
            } else if ("TRIAL_BALANCE".equals(job.get("report_type"))) {
                TrialBalance tb = ledger.trialBalance(UUID.fromString(p.get("periodId")));
                table = trialBalanceTable(tb, by, at);
                base = "trial_balance_" + tb.period().name().replace(' ', '_');
            } else {
                ManagementReportService.Rendered rendered = management.render((String) job.get("report_type"), p, by, at);
                table = rendered.table();
                base = rendered.baseName();
            }
            byte[] content = ReportExporters.export(table, format);
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
            String fileName = base.replaceAll("[^A-Za-z0-9._-]", "_") + "." + format.toLowerCase();
            Instant done = Instant.now(clock);
            byte[] sealed = cipher.seal(content, "report-job", id.toString());
            tx.executeWithoutResult(s -> jdbc.update("UPDATE report_jobs SET status = 'DONE', finished_at = ?, file_name = ?, content_type = ?, "
                    + "content = ?, content_encryption = ?, sha256 = ?, expires_at = ? WHERE id = ?", Timestamp.from(done), fileName,
                    ReportExporters.contentType(format), sealed, ContentCipher.SCHEME, sha, Timestamp.from(done.plus(retention)), id));
            tx.executeWithoutResult(s -> {
                audit.record(Actor.SYSTEM, "REPORT_GENERATED", "REPORT_JOB", id, Map.of("file", fileName, "sha256", sha, "bytes", content.length));
                notifications.notify((UUID) job.get("requested_by_id"), "REPORT_READY", "Your report " + fileName + " is ready", null,
                        "/reports/jobs");
                emailScheduleOwner(job, "scheduled-report-ready", Map.of("fileName", fileName, "retentionDays", retention.toDays()));
            });
        } catch (Exception e) {
            log.warn("Report job {} failed: {}", id, e.toString());
            String msg = e instanceof ApiException a ? a.getMessage() : "The report could not be generated (" + e.getClass().getSimpleName() + ").";
            tx.executeWithoutResult(s -> {
                jdbc.update("UPDATE report_jobs SET status = 'FAILED', finished_at = ?, error = ? WHERE id = ?",
                        Timestamp.from(Instant.now(clock)), msg.length() > 1000 ? msg.substring(0, 1000) : msg, id);
                notifications.notify((UUID) job.get("requested_by_id"), "REPORT_FAILED", "Your " + job.get("report_type").toString()
                        .toLowerCase().replace('_', ' ') + " report could not be generated", msg, "/reports/jobs");
                emailScheduleOwner(job, "scheduled-report-failed", Map.of("error", msg));
            });
        }
        return true;
    }

    /**
     * A job queued by a schedule whose owner asked for it: e-mails the owner (still active, with an address) through the
     * outbox, in the same transaction as the job's result. The message links to My reports and never carries the report.
     */
    private void emailScheduleOwner(Map<String, Object> job, String template, Map<String, Object> extra) {
        UUID scheduleId = (UUID) job.get("schedule_id");
        if (scheduleId == null) {
            return;
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT s.name, s.report_type, u.email FROM report_schedules s JOIN users u ON u.id = s.owner_id
                WHERE s.id = ? AND s.email_when_ready AND s.status <> 'DELETED' AND u.status = 'ACTIVE'
                  AND u.email IS NOT NULL AND u.email <> ''""", scheduleId);
        if (rows.isEmpty()) {
            return;
        }
        Map<String, Object> r = rows.get(0);
        Map<String, Object> payload = new java.util.HashMap<>(extra);
        payload.put("scheduleName", r.get("name"));
        payload.put("reportType", r.get("report_type").toString().toLowerCase().replace('_', ' '));
        outbox.enqueueEmail((String) r.get("email"), template, payload);
        audit.record(Actor.SYSTEM, "REPORT_EMAIL_QUEUED", "REPORT_JOB", job.get("id"), Map.of("schedule", scheduleId.toString(),
                "template", template));
    }

    static ReportTable trialBalanceTable(TrialBalance tb, String by, String at) {
        List<ReportTable.Row> rows = new ArrayList<>();
        for (TrialBalanceRow r : tb.rows()) {
            rows.add(new ReportTable.Row(r.code() + " " + r.name(), 0, false, List.of(r.openingDebit(), r.openingCredit(), r.periodDebit(),
                    r.periodCredit(), r.closingDebit(), r.closingCredit())));
        }
        rows.add(new ReportTable.Row("Total", 0, true, List.of(tb.totalOpeningDebit(), tb.totalOpeningCredit(), tb.totalPeriodDebit(),
                tb.totalPeriodCredit(), tb.totalClosingDebit(), tb.totalClosingCredit())));
        List<String> notes = new ArrayList<>();
        notes.add(tb.balanced() ? "The trial balance is balanced." : "THE TRIAL BALANCE IS NOT BALANCED.");
        if (tb.placeholderAccountsUsed()) {
            notes.add("PLACEHOLDER: placeholder accounts are in use; this is not an official trial balance.");
        }
        notes.add("Generated " + at + " by " + by + ".");
        return new ReportTable("Trial balance", List.of("Manila International Airport Authority", "Period " + tb.period().name()),
                "Account", List.of("Opening debit", "Opening credit", "Period debit", "Period credit", "Closing debit", "Closing credit"), rows, notes);
    }

    private void requireOwner(UUID id) {
        List<UUID> owner = jdbc.queryForList("SELECT requested_by_id FROM report_jobs WHERE id = ?", UUID.class, id);
        if (owner.isEmpty() || !owner.get(0).equals(CurrentActor.userId())) {
            throw ApiException.notFound("Report");
        }
    }

    private JobView view(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, report_type, format, parameters::text AS parameters, status, requested_by, "
                + "requested_at, started_at, finished_at, error, file_name, "
                + "octet_length(content) - CASE WHEN content_encryption = 'NONE' THEN 0 ELSE " + ContentCipher.OVERHEAD + " END AS size, "
                + "sha256, expires_at FROM report_jobs "
                + "WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Report");
        }
        Map<String, Object> r = rows.get(0);
        Map<String, Object> params;
        try {
            params = json.readValue((String) r.get("parameters"), new TypeReference<>() { });
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            params = Map.of();
        }
        return new JobView(id, (String) r.get("report_type"), (String) r.get("format"), params, (String) r.get("status"),
                (String) r.get("requested_by"), ts(r.get("requested_at")), ts(r.get("started_at")), ts(r.get("finished_at")),
                (String) r.get("error"), (String) r.get("file_name"), r.get("size") == null ? null : ((Number) r.get("size")).longValue(),
                (String) r.get("sha256"), ts(r.get("expires_at")));
    }

    private static Instant ts(Object o) {
        return o == null ? null : ((Timestamp) o).toInstant();
    }
}
