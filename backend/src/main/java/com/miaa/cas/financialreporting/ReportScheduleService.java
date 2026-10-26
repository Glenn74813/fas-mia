package com.miaa.cas.financialreporting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaa.cas.audit.Actor;
import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.notifications.NotificationService;
import com.miaa.cas.security.CurrentActor;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Recurring report runs (MS §11.8). A schedule queues an ordinary report job for its owner at the chosen time (Manila
 * time). Parameters may use relative tokens, resolved when the job is queued:
 * {today}, {yesterday}, {monthStart}, {prevMonthStart}, {prevMonthEnd}, {year}, {quarter}, {prevQuarter},
 * {prevQuarterYear}, {currentPeriod} and {previousPeriod} (accounting period ids).
 * At every run the owner must be active and still hold the report's permission; otherwise the schedule is suspended.
 */
@Service
public class ReportScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ReportScheduleService.class);
    static final ZoneId MANILA = ZoneId.of("Asia/Manila");
    private static final java.util.regex.Pattern TOKEN = java.util.regex.Pattern.compile("\\{([A-Za-z]+)}");

    public record ScheduleRequest(@NotBlank @Size(max = 200) String name, @NotBlank String reportType,
            @NotNull @Pattern(regexp = "CSV|XLSX|PDF") String format, @NotNull Map<String, String> parameters,
            @NotNull @Pattern(regexp = "DAILY|WEEKLY|MONTHLY") String frequency, @NotNull @Min(0) @Max(23) Integer runHour,
            @Min(1) @Max(7) Integer dayOfWeek, @Min(1) @Max(28) Integer dayOfMonth) {
    }

    public record ScheduleView(UUID id, String name, String reportType, String format, Map<String, String> parameters, String frequency,
            int runHour, Integer dayOfWeek, Integer dayOfMonth, String status, String statusReason, Instant nextRunAt, Instant lastRunAt,
            UUID lastJobId, long version) {
    }

    private final ReportJobService jobs;
    private final AuditService audit;
    private final NotificationService notifications;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ReportScheduleService(ReportJobService jobs, AuditService audit, NotificationService notifications, ObjectMapper json, JdbcTemplate jdbc, TransactionTemplate tx,
            Clock clock) {
        this.jobs = jobs;
        this.audit = audit;
        this.notifications = notifications;
        this.json = json;
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ScheduleView> mine() {
        return jdbc.queryForList("SELECT id FROM report_schedules WHERE owner_id = ? AND status <> 'DELETED' ORDER BY name", UUID.class,
                CurrentActor.userId()).stream().map(this::view).toList();
    }

    @Transactional
    public ScheduleView create(ScheduleRequest req) {
        CurrentActor.requirePermission(ManagementReportService.permissionFor(req.reportType(), req.parameters()));
        if ("WEEKLY".equals(req.frequency()) && req.dayOfWeek() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Choose the day of the week (1 = Monday … 7 = Sunday).");
        }
        if ("MONTHLY".equals(req.frequency()) && req.dayOfMonth() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Choose the day of the month (1 to 28).");
        }
        // The parameters must make a valid report today, with the tokens resolved.
        jobs.validateParameters(req.reportType(), resolve(req.parameters(), LocalDate.now(clock.withZone(MANILA))));
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        jdbc.update("""
                INSERT INTO report_schedules (id, owner_id, owner, name, report_type, format, parameters, frequency, run_hour, day_of_week,
                    day_of_month, status, next_run_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, 'ACTIVE', ?, ?)""",
                id, CurrentActor.userId(), CurrentActor.username(), req.name().trim(), req.reportType(), req.format(), write(req.parameters()),
                req.frequency(), req.runHour(), req.dayOfWeek(), req.dayOfMonth(),
                Timestamp.from(nextRun(req.frequency(), req.runHour(), req.dayOfWeek(), req.dayOfMonth(), now)), Timestamp.from(now));
        audit.record("REPORT_SCHEDULE_CREATED", "REPORT_SCHEDULE", id, Map.of("type", req.reportType(), "frequency", req.frequency()));
        return view(id);
    }

    @Transactional
    public ScheduleView delete(UUID id) {
        Map<String, Object> s = owned(id);
        jdbc.update("UPDATE report_schedules SET status = 'DELETED', version = version + 1 WHERE id = ?", id);
        audit.record("REPORT_SCHEDULE_DELETED", "REPORT_SCHEDULE", id, Map.of("type", s.get("report_type")));
        return view(id);
    }

    /** Queues the jobs of every due schedule; each schedule in its own transaction, so one failure never blocks the rest. */
    @Scheduled(fixedDelayString = "${cas.reports.schedule-interval:PT1M}")
    public void runDue() {
        List<UUID> due = jdbc.queryForList("SELECT id FROM report_schedules WHERE status = 'ACTIVE' AND next_run_at <= ? ORDER BY next_run_at",
                UUID.class, Timestamp.from(Instant.now(clock)));
        for (UUID id : due) {
            try {
                tx.executeWithoutResult(t -> runOne(id));
            } catch (RuntimeException e) {
                log.error("Report schedule {} failed", id, e);
            }
        }
    }

    void runOne(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM report_schedules WHERE id = ? AND status = 'ACTIVE' "
                + "AND next_run_at <= ? FOR UPDATE SKIP LOCKED", id, Timestamp.from(Instant.now(clock)));
        if (rows.isEmpty()) {
            return;
        }
        Map<String, Object> s = rows.get(0);
        Instant now = Instant.now(clock);
        UUID owner = (UUID) s.get("owner_id");
        String type = (String) s.get("report_type");
        Map<String, String> params = resolve(read((String) jdbc.queryForObject("SELECT parameters::text FROM report_schedules WHERE id = ?",
                String.class, id)), LocalDate.now(clock.withZone(MANILA)));
        Instant next = nextRun((String) s.get("frequency"), ((Number) s.get("run_hour")).intValue(), (Integer) intOrNull(s.get("day_of_week")),
                (Integer) intOrNull(s.get("day_of_month")), now);
        String problem = ownerProblem(owner, ManagementReportService.permissionFor(type, params));
        if (problem == null) {
            try {
                jobs.validateParameters(type, params);
            } catch (ApiException e) {
                problem = "The parameters are no longer valid: " + e.getMessage();
            }
        }
        Actor actor = new Actor(owner, (String) s.get("owner"));
        if (problem != null) {
            jdbc.update("UPDATE report_schedules SET status = 'SUSPENDED', status_reason = ?, version = version + 1 WHERE id = ?", problem, id);
            notifications.notify(owner, "REPORT_SCHEDULE_SUSPENDED", "Your scheduled report \"" + s.get("name") + "\" was suspended", problem,
                    "/reports/jobs");
            audit.record(actor, "REPORT_SCHEDULE_SUSPENDED", "REPORT_SCHEDULE", id, Map.of("reason", problem));
            return;
        }
        UUID job = jobs.enqueue(type, (String) s.get("format"), params, owner, (String) s.get("owner"));
        jdbc.update("UPDATE report_schedules SET last_run_at = ?, last_job_id = ?, next_run_at = ?, version = version + 1 WHERE id = ?",
                Timestamp.from(now), job, Timestamp.from(next), id);
        audit.record(actor, "REPORT_REQUESTED", "REPORT_JOB", job, Map.of("type", type, "format", s.get("format"), "schedule", id.toString()));
    }

    /** Null when the owner may still run the report; otherwise the reason the schedule stops. */
    private String ownerProblem(UUID owner, String permission) {
        Map<String, Object> u = jdbc.queryForMap("SELECT status, access_expires_at FROM users WHERE id = ?", owner);
        Timestamp expires = (Timestamp) u.get("access_expires_at");
        if (!"ACTIVE".equals(u.get("status")) || (expires != null && expires.toInstant().isBefore(Instant.now(clock)))) {
            return "The owner's account is no longer active.";
        }
        boolean holds = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM user_roles ur JOIN role_permissions rp ON rp.role_id = ur.role_id
                               WHERE ur.user_id = ? AND rp.permission_code = ?)""", Boolean.class, owner, permission));
        return holds ? null : "The owner no longer holds " + permission + ".";
    }

    /** The next run strictly after {@code after}, in Manila time. */
    static Instant nextRun(String frequency, int hour, Integer dayOfWeek, Integer dayOfMonth, Instant after) {
        ZonedDateTime from = after.atZone(MANILA);
        LocalDate d = from.toLocalDate();
        for (int i = 0; i < 400; i++) {
            LocalDate c = d.plusDays(i);
            boolean matches = switch (frequency) {
                case "DAILY" -> true;
                case "WEEKLY" -> c.getDayOfWeek() == DayOfWeek.of(dayOfWeek);
                default -> c.getDayOfMonth() == dayOfMonth;
            };
            ZonedDateTime at = LocalDateTime.of(c, java.time.LocalTime.of(hour, 0)).atZone(MANILA);
            if (matches && at.toInstant().isAfter(after)) {
                return at.toInstant();
            }
        }
        throw new IllegalStateException("No next run for " + frequency);
    }

    /** Replaces relative tokens in parameter values with dates, quarters or period ids as of {@code today}. */
    Map<String, String> resolve(Map<String, String> params, LocalDate today) {
        Map<String, String> out = new HashMap<>();
        LocalDate prevMonth = today.minusMonths(1);
        int quarter = (today.getMonthValue() - 1) / 3 + 1;
        int prevQuarter = quarter == 1 ? 4 : quarter - 1;
        Map<String, String> v = new HashMap<>(Map.of(
                "today", today.toString(),
                "yesterday", today.minusDays(1).toString(),
                "monthStart", today.withDayOfMonth(1).toString(),
                "prevMonthStart", prevMonth.withDayOfMonth(1).toString(),
                "prevMonthEnd", prevMonth.with(TemporalAdjusters.lastDayOfMonth()).toString(),
                "year", String.valueOf(today.getYear()),
                "quarter", String.valueOf(quarter),
                "prevQuarter", String.valueOf(prevQuarter),
                "prevQuarterYear", String.valueOf(quarter == 1 ? today.getYear() - 1 : today.getYear())));
        for (Map.Entry<String, String> e : params.entrySet()) {
            String value = e.getValue() == null ? null : e.getValue();
            if (value != null) {
                Matcher m = TOKEN.matcher(value);
                StringBuilder sb = new StringBuilder();
                while (m.find()) {
                    String key = m.group(1);
                    String replacement = switch (key) {
                        case "currentPeriod" -> period(today);
                        case "previousPeriod" -> period(prevMonth.with(TemporalAdjusters.lastDayOfMonth()));
                        default -> v.get(key);
                    };
                    if (replacement == null) {
                        throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown token {" + key + "} in parameters." + e.getKey() + ".");
                    }
                    m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                }
                m.appendTail(sb);
                value = sb.toString();
            }
            out.put(e.getKey(), value);
        }
        return out;
    }

    private String period(LocalDate d) {
        List<UUID> ids = jdbc.queryForList("SELECT id FROM accounting_periods WHERE period_number <= 12 AND ? BETWEEN start_date AND end_date",
                UUID.class, java.sql.Date.valueOf(d));
        if (ids.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "No accounting period covers " + d + ".");
        }
        return ids.get(0).toString();
    }

    private Map<String, Object> owned(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM report_schedules WHERE id = ? AND owner_id = ? AND status <> 'DELETED'",
                id, CurrentActor.userId());
        if (rows.isEmpty()) {
            throw ApiException.notFound("Report schedule");
        }
        return rows.get(0);
    }

    private ScheduleView view(UUID id) {
        Map<String, Object> r = jdbc.queryForMap("SELECT *, parameters::text AS p FROM report_schedules WHERE id = ?", id);
        Timestamp last = (Timestamp) r.get("last_run_at");
        return new ScheduleView(id, (String) r.get("name"), (String) r.get("report_type"), (String) r.get("format"), read((String) r.get("p")),
                (String) r.get("frequency"), ((Number) r.get("run_hour")).intValue(), intOrNull(r.get("day_of_week")),
                intOrNull(r.get("day_of_month")), (String) r.get("status"), (String) r.get("status_reason"),
                ((Timestamp) r.get("next_run_at")).toInstant(), last == null ? null : last.toInstant(), (UUID) r.get("last_job_id"),
                ((Number) r.get("version")).longValue());
    }

    private static Integer intOrNull(Object o) {
        return o == null ? null : ((Number) o).intValue();
    }

    private String write(Map<String, String> m) {
        try {
            return json.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, String> read(String s) {
        try {
            return json.readValue(s, new TypeReference<Map<String, String>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
