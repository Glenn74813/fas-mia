package com.miaa.cas.closing;

import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.financialreporting.SubledgerCloseCheck;
import com.miaa.cas.generalledger.FiscalCalendarService;
import com.miaa.cas.generalledger.PeriodCloseService;
import com.miaa.cas.generalledger.domain.AccountingPeriod;
import com.miaa.cas.generalledger.web.LedgerDtos.CloseCheck;
import com.miaa.cas.security.CurrentActor;
import com.miaa.cas.treasury.BankReconciliationCloseCheck;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Close checklists and the closing schedule (MS §5.4). A checklist covers one day, accounting period, quarter or
 * fiscal year. It combines automatic reconciliation and exception checks (computed live, with the exceptions listed)
 * and the manual close tasks of the close calendar, each ticked off with a note by a holder of the task's permission.
 * The month-end checks are the period-close checks. The schedule lists when each task falls due.
 */
@Service
public class CloseCalendarService {

    public static final List<String> FREQUENCIES = List.of("DAY_END", "MONTH_END", "QUARTER_END", "YEAR_END");

    public record Check(String code, String description, String outcome, String detail, List<String> exceptions) {
    }

    public record TaskItem(UUID taskId, String code, String name, String instructions, String responsiblePermission, LocalDate dueDate,
            boolean overdue, String status, String note, String completedBy, Instant completedAt, boolean canComplete, boolean placeholder) {
    }

    public record Checklist(String frequency, String scopeKey, String label, LocalDate scopeEnd, List<Check> checks, List<TaskItem> tasks) {
    }

    public record Task(UUID id, String code, String name, String frequency, int dueOffsetDays, String responsiblePermission, String instructions,
            int sortOrder, String status, boolean placeholder, long version) {
    }

    public record ScheduleItem(UUID taskId, String taskName, String frequency, String scopeKey, String scopeLabel, LocalDate dueDate,
            String status, boolean overdue) {
    }

    /** Where a scope (one day, period, quarter or year) ends and how it is shown. */
    record Scope(String frequency, String key, String label, LocalDate end) {
    }

    private static final Pattern QUARTER_KEY = Pattern.compile("([0-9a-f-]{36}):Q([1-4])");

    private final JdbcTemplate jdbc;
    private final PeriodCloseService periodClose;
    private final FiscalCalendarService calendar;
    private final BankReconciliationCloseCheck bankCheck;
    private final SubledgerCloseCheck subledgerCheck;
    private final AuditService audit;
    private final Clock clock;

    public CloseCalendarService(JdbcTemplate jdbc, PeriodCloseService periodClose, FiscalCalendarService calendar,
            BankReconciliationCloseCheck bankCheck, SubledgerCloseCheck subledgerCheck, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.periodClose = periodClose;
        this.calendar = calendar;
        this.bankCheck = bankCheck;
        this.subledgerCheck = subledgerCheck;
        this.audit = audit;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- checklists

    @Transactional(readOnly = true)
    public Checklist checklist(String frequency, String scopeKey) {
        Scope scope = scope(frequency, scopeKey);
        List<Check> checks = switch (frequency) {
            case "DAY_END" -> dayEnd(scope.end());
            case "MONTH_END" -> periodClose.checklist(UUID.fromString(scope.key())).checks().stream().map(CloseCalendarService::of).toList();
            case "QUARTER_END" -> quarterEnd(scope);
            default -> yearEnd(UUID.fromString(scope.key()));
        };
        return new Checklist(frequency, scope.key(), scope.label(), scope.end(), checks, tasks(scope));
    }

    @Transactional
    public Checklist complete(UUID taskId, String scopeKey, String outcome, String note) {
        Task t = task(taskId);
        CurrentActor.requirePermission(t.responsiblePermission());
        if (!"ACTIVE".equals(t.status())) {
            throw new ApiException(ErrorCode.INVALID_STATE, "The task is no longer active.");
        }
        if (!"DONE".equals(outcome) && !"NOT_APPLICABLE".equals(outcome)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The outcome must be DONE or NOT_APPLICABLE.");
        }
        if (note == null || note.trim().length() < 3) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Record what was done or why it does not apply (at least 3 characters).");
        }
        Scope scope = scope(t.frequency(), scopeKey);
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM close_task_completions WHERE task_id = ? AND scope_key = ?)",
                Boolean.class, taskId, scope.key()))) {
            throw new ApiException(ErrorCode.INVALID_STATE, "This task is already completed for " + scope.label() + ".");
        }
        jdbc.update("""
                INSERT INTO close_task_completions (id, task_id, scope_key, outcome, note, completed_by_id, completed_by, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", UUID.randomUUID(), taskId, scope.key(), outcome, note.trim(), CurrentActor.userId(),
                CurrentActor.username(), Timestamp.from(Instant.now(clock)));
        audit.record("CLOSE_TASK_COMPLETED", "CLOSE_TASK", t.code(), Map.of("scope", scope.label(), "outcome", outcome, "note", note.trim()));
        return checklist(t.frequency(), scope.key());
    }

    // ---------------------------------------------------------------- schedule

    /** Every task occurrence falling due between the dates, with its status. Days are Monday to Friday. */
    @Transactional(readOnly = true)
    public List<ScheduleItem> schedule(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from) || from.plusDays(92).isBefore(to)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Choose a range of at most three months.");
        }
        LocalDate today = LocalDate.now(clock);
        List<ScheduleItem> out = new ArrayList<>();
        for (Task t : tasks().stream().filter(x -> "ACTIVE".equals(x.status())).toList()) {
            for (Scope s : scopesDueBetween(t, from, to)) {
                String status = completion(t.id(), s.key());
                LocalDate due = s.end().plusDays(t.dueOffsetDays());
                out.add(new ScheduleItem(t.id(), t.name(), t.frequency(), s.key(), s.label(), due, status, "OPEN".equals(status) && due.isBefore(today)));
            }
        }
        out.sort((a, b) -> a.dueDate().compareTo(b.dueDate()));
        return out;
    }

    /** Open tasks due today or in the last week that the caller can complete, for the inbox. */
    public List<ScheduleItem> dueForCurrentUser() {
        LocalDate today = LocalDate.now(clock);
        Map<UUID, String> permission = new java.util.HashMap<>();
        tasks().forEach(t -> permission.put(t.id(), t.responsiblePermission()));
        return schedule(today.minusDays(7), today).stream().filter(i -> "OPEN".equals(i.status()))
                .filter(i -> CurrentActor.hasPermission(permission.get(i.taskId()))).toList();
    }

    // ---------------------------------------------------------------- task templates

    @Transactional(readOnly = true)
    public List<Task> tasks() {
        return jdbc.query("SELECT * FROM close_tasks ORDER BY frequency, sort_order, code", (rs, i) -> new Task(rs.getObject("id", UUID.class),
                rs.getString("code"), rs.getString("name"), rs.getString("frequency"), rs.getInt("due_offset_days"),
                rs.getString("responsible_permission"), rs.getString("instructions"), rs.getInt("sort_order"), rs.getString("status"),
                rs.getBoolean("placeholder"), rs.getLong("version")));
    }

    @Transactional
    public Task saveTask(UUID id, Long version, String code, String name, String frequency, int offset, String permission, String instructions,
            int sortOrder, String status) {
        CurrentActor.requirePermission("CLOSE_TASK_MANAGE");
        if (!FREQUENCIES.contains(frequency)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown frequency.");
        }
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM permissions WHERE code = ?)", Boolean.class, permission))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown permission " + permission + ".");
        }
        Timestamp now = Timestamp.from(Instant.now(clock));
        if (id == null) {
            if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM close_tasks WHERE code = ?)", Boolean.class,
                    code.trim().toUpperCase()))) {
                throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "A close task with code " + code.trim().toUpperCase() + " already exists.");
            }
            id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO close_tasks (id, code, name, frequency, due_offset_days, responsible_permission, instructions, sort_order, status,
                        updated_by, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)""", id, code.trim().toUpperCase(), name.trim(), frequency,
                    offset, permission, instructions, sortOrder, CurrentActor.username(), now);
            audit.record("CLOSE_TASK_CREATED", "CLOSE_TASK", code.trim().toUpperCase(), Map.of("frequency", frequency, "offset", offset,
                    "permission", permission));
        } else {
            int n = jdbc.update("""
                    UPDATE close_tasks SET name = ?, frequency = ?, due_offset_days = ?, responsible_permission = ?, instructions = ?, sort_order = ?,
                        status = ?, placeholder = FALSE, version = version + 1, updated_by = ?, updated_at = ? WHERE id = ? AND version = ?""",
                    name.trim(), frequency, offset, permission, instructions, sortOrder, status, CurrentActor.username(), now, id, version);
            if (n == 0) {
                throw ApiException.staleVersion();
            }
            audit.record("CLOSE_TASK_UPDATED", "CLOSE_TASK", id, Map.of("frequency", frequency, "offset", offset, "permission", permission,
                    "status", status));
        }
        return task(id);
    }

    // ---------------------------------------------------------------- automatic checks

    private List<Check> dayEnd(LocalDate day) {
        List<Check> out = new ArrayList<>();
        out.add(exceptions("COLLECTIONS_DEPOSITED", "Posted collections up to this day are deposited", jdbc.queryForList("""
                SELECT r.receipt_number || ' of ' || r.receipt_date || ' (' || r.amount || ')' FROM receipts r
                WHERE r.status = 'POSTED' AND r.receipt_date <= ?
                  AND NOT EXISTS (SELECT 1 FROM deposit_items i JOIN deposits d ON d.id = i.deposit_id
                                  WHERE i.receipt_id = r.id AND i.live AND d.status <> 'CANCELLED')
                ORDER BY r.receipt_date LIMIT 51""", String.class, Date.valueOf(day))));
        out.add(exceptions("COLLECTIONS_POSTED", "Collections recorded up to this day are posted", jdbc.queryForList("""
                SELECT receipt_number || ' of ' || receipt_date FROM receipts WHERE status = 'RECORDED' AND receipt_date <= ?
                ORDER BY receipt_date LIMIT 51""", String.class, Date.valueOf(day))));
        out.add(exceptions("PAYMENTS_POSTED", "Payments released up to this day are posted", jdbc.queryForList("""
                SELECT dv_number || ' paid ' || payment_date FROM disbursement_vouchers WHERE status = 'PAID' AND payment_date <= ?
                ORDER BY payment_date LIMIT 51""", String.class, Date.valueOf(day))));
        out.add(exceptions("INTEGRATIONS_HEALTHY", "No integration transmission is dead or rejected", jdbc.queryForList("""
                SELECT channel || ' ' || document_type || ' ' || status FROM integration_transmissions WHERE status IN ('DEAD', 'REJECTED')
                ORDER BY created_at LIMIT 51""", String.class)));
        return out;
    }

    private List<Check> quarterEnd(Scope scope) {
        var m = QUARTER_KEY.matcher(scope.key());
        m.matches();
        UUID fy = UUID.fromString(m.group(1));
        int q = Integer.parseInt(m.group(2));
        List<Check> out = new ArrayList<>();
        out.add(exceptions("QUARTER_PERIODS_CLOSED", "The quarter's three months are closed", jdbc.queryForList("""
                SELECT name || ' is ' || lower(status) FROM accounting_periods WHERE fiscal_year_id = ? AND period_type = 'REGULAR'
                  AND period_number BETWEEN ? AND ? AND status <> 'CLOSED' ORDER BY period_number""", String.class, fy, q * 3 - 2, q * 3)));
        AccountingPeriod last = lastPeriodOfQuarter(fy, q);
        bankCheck.checks(last).forEach(c -> out.add(of(c)));
        int year = jdbc.queryForObject("SELECT fiscal_year FROM fiscal_years WHERE id = ?", Integer.class, fy);
        boolean schedule = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM report_jobs WHERE report_type = 'WITHHOLDING_SCHEDULE' AND status IN ('DONE', 'EXPIRED')
                  AND parameters->>'year' = ? AND parameters->>'quarter' = ?)""", Boolean.class, String.valueOf(year), String.valueOf(q)));
        out.add(new Check("WITHHOLDING_SCHEDULE", "The quarter's expanded withholding schedule has been produced", schedule ? PeriodCloseService.PASS
                : PeriodCloseService.FAIL, schedule ? "" : "Request it from My reports (Expanded withholding schedule).", List.of()));
        return out;
    }

    private List<Check> yearEnd(UUID fy) {
        List<Check> out = new ArrayList<>();
        out.add(exceptions("YEAR_PERIODS_CLOSED", "All twelve months are closed", jdbc.queryForList("""
                SELECT name || ' is ' || lower(status) FROM accounting_periods WHERE fiscal_year_id = ? AND period_type = 'REGULAR' AND status <> 'CLOSED'
                ORDER BY period_number""", String.class, fy)));
        boolean closing = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM journal_entries WHERE fiscal_year_id = ? "
                + "AND voucher_type = 'CLOSING')", Boolean.class, fy));
        out.add(new Check("CLOSING_ENTRY_POSTED", "The year-end closing entry is posted", closing ? PeriodCloseService.PASS : PeriodCloseService.FAIL,
                closing ? "" : "Run the year-end close from the fiscal calendar.", List.of()));
        String status = jdbc.queryForObject("SELECT status FROM fiscal_years WHERE id = ?", String.class, fy);
        out.add(new Check("FISCAL_YEAR_CLOSED", "The fiscal year is closed", "CLOSED".equals(status) ? PeriodCloseService.PASS : PeriodCloseService.FAIL,
                "Status: " + status.toLowerCase(), List.of()));
        AccountingPeriod last = lastPeriodOfQuarter(fy, 4);
        subledgerCheck.checks(last).forEach(c -> out.add(of(c)));
        return out;
    }

    private static Check exceptions(String code, String description, List<String> items) {
        boolean more = items.size() > 50;
        return new Check(code, description, items.isEmpty() ? PeriodCloseService.PASS : PeriodCloseService.FAIL,
                items.isEmpty() ? "" : (more ? "More than 50" : String.valueOf(items.size())) + " exception(s)", more ? items.subList(0, 50) : items);
    }

    private static Check of(CloseCheck c) {
        return new Check(c.code(), c.description(), c.outcome(), c.detail(), List.of());
    }

    // ---------------------------------------------------------------- scopes and tasks

    private List<TaskItem> tasks(Scope scope) {
        LocalDate today = LocalDate.now(clock);
        List<TaskItem> out = new ArrayList<>();
        for (Task t : tasks()) {
            if (!t.frequency().equals(scope.frequency()) || !"ACTIVE".equals(t.status())) {
                continue;
            }
            Map<String, Object> c = jdbc.queryForList("SELECT * FROM close_task_completions WHERE task_id = ? AND scope_key = ?", t.id(), scope.key())
                    .stream().findFirst().orElse(null);
            LocalDate due = scope.end().plusDays(t.dueOffsetDays());
            out.add(new TaskItem(t.id(), t.code(), t.name(), t.instructions(), t.responsiblePermission(), due, c == null && due.isBefore(today),
                    c == null ? "OPEN" : (String) c.get("outcome"), c == null ? null : (String) c.get("note"),
                    c == null ? null : (String) c.get("completed_by"), c == null ? null : ((Timestamp) c.get("completed_at")).toInstant(),
                    c == null && CurrentActor.hasPermission(t.responsiblePermission()), t.placeholder()));
        }
        return out;
    }

    Scope scope(String frequency, String key) {
        try {
            return switch (frequency) {
                case "DAY_END" -> {
                    LocalDate d = LocalDate.parse(key);
                    yield new Scope(frequency, d.toString(), "Day " + d, d);
                }
                case "MONTH_END" -> {
                    AccountingPeriod p = calendar.period(UUID.fromString(key));
                    yield new Scope(frequency, p.getId().toString(), p.getName(), p.getEndDate());
                }
                case "QUARTER_END" -> {
                    var m = QUARTER_KEY.matcher(key);
                    if (!m.matches()) {
                        throw new IllegalArgumentException();
                    }
                    UUID fy = UUID.fromString(m.group(1));
                    int q = Integer.parseInt(m.group(2));
                    AccountingPeriod last = lastPeriodOfQuarter(fy, q);
                    yield new Scope(frequency, fy + ":Q" + q, "Q" + q + " " + jdbc.queryForObject("SELECT fiscal_year FROM fiscal_years WHERE id = ?", Integer.class, fy), last.getEndDate());
                }
                case "YEAR_END" -> {
                    UUID fy = UUID.fromString(key);
                    Map<String, Object> y = jdbc.queryForMap("SELECT fiscal_year, end_date FROM fiscal_years WHERE id = ?", fy);
                    yield new Scope(frequency, fy.toString(), "Fiscal year " + y.get("fiscal_year"), lastRegularEnd(fy));
                }
                default -> throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown frequency " + frequency + ".");
            };
        } catch (IllegalArgumentException | org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown scope " + key + " for " + frequency + ".");
        }
    }

    private List<Scope> scopesDueBetween(Task t, LocalDate from, LocalDate to) {
        LocalDate a = from.minusDays(t.dueOffsetDays());
        LocalDate b = to.minusDays(t.dueOffsetDays());
        List<Scope> out = new ArrayList<>();
        switch (t.frequency()) {
            case "DAY_END" -> {
                for (LocalDate d = a; !d.isAfter(b); d = d.plusDays(1)) {
                    if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                        out.add(new Scope("DAY_END", d.toString(), "Day " + d, d));
                    }
                }
            }
            case "MONTH_END" -> jdbc.query("SELECT id, name, end_date FROM accounting_periods WHERE period_type = 'REGULAR' AND end_date BETWEEN ? AND ? "
                    + "ORDER BY end_date", rs -> {
                        out.add(new Scope("MONTH_END", rs.getString(1), rs.getString(2), rs.getDate(3).toLocalDate()));
                    }, Date.valueOf(a), Date.valueOf(b));
            case "QUARTER_END" -> jdbc.query("""
                    SELECT p.fiscal_year_id, p.period_number / 3 AS q, f.fiscal_year, p.end_date FROM accounting_periods p
                    JOIN fiscal_years f ON f.id = p.fiscal_year_id
                    WHERE p.period_type = 'REGULAR' AND p.period_number IN (3, 6, 9, 12) AND p.end_date BETWEEN ? AND ? ORDER BY p.end_date""", rs -> {
                        out.add(new Scope("QUARTER_END", rs.getString(1) + ":Q" + rs.getInt(2), "Q" + rs.getInt(2) + " " + rs.getInt(3),
                                rs.getDate(4).toLocalDate()));
                    }, Date.valueOf(a), Date.valueOf(b));
            default -> jdbc.query("""
                    SELECT f.id, f.fiscal_year, p.end_date FROM fiscal_years f JOIN accounting_periods p ON p.fiscal_year_id = f.id
                    WHERE p.period_type = 'REGULAR' AND p.period_number = 12 AND p.end_date BETWEEN ? AND ? ORDER BY p.end_date""", rs -> {
                        out.add(new Scope("YEAR_END", rs.getString(1), "Fiscal year " + rs.getInt(2), rs.getDate(3).toLocalDate()));
                    }, Date.valueOf(a), Date.valueOf(b));
        }
        return out;
    }

    private AccountingPeriod lastPeriodOfQuarter(UUID fy, int q) {
        UUID id = jdbc.queryForObject("SELECT id FROM accounting_periods WHERE fiscal_year_id = ? AND period_type = 'REGULAR' AND period_number = ?",
                UUID.class, fy, q * 3);
        return calendar.period(id);
    }

    private LocalDate lastRegularEnd(UUID fy) {
        return jdbc.queryForObject("SELECT max(end_date) FROM accounting_periods WHERE fiscal_year_id = ? AND period_type = 'REGULAR'", Date.class, fy)
                .toLocalDate();
    }

    private String completion(UUID taskId, String scopeKey) {
        return jdbc.queryForList("SELECT outcome FROM close_task_completions WHERE task_id = ? AND scope_key = ?", String.class, taskId, scopeKey)
                .stream().findFirst().orElse("OPEN");
    }

    private Task task(UUID id) {
        return tasks().stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow(() -> ApiException.notFound("Close task"));
    }
}
