package com.miaa.cas.tickets;

import com.miaa.cas.audit.Actor;
import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SLA policies per contract phase, ticket type and priority, and the business calendar. No SLA values
 * are built in: the warranty, maintenance and Annex G commitments are configured once confirmed (C-15).
 */
@Service
public class SlaPolicyService {

    public record PolicyRequest(@NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,29}$") String contractPhase,
            @NotNull @Pattern(regexp = "INCIDENT|SERVICE_REQUEST|CHANGE_REQUEST|ENHANCEMENT") String ticketType,
            @NotNull @Pattern(regexp = "P1|P2|P3|P4") String priority, @NotNull @Min(1) @Max(525600) Integer responseMinutes,
            @NotNull @Min(1) @Max(5256000) Integer resolutionMinutes, @NotNull Boolean businessHours) {
    }

    public record PolicyView(UUID id, String contractPhase, String ticketType, String priority, int responseMinutes, int resolutionMinutes,
            boolean businessHours, boolean placeholder, String status, String createdBy, Instant createdAt) {
    }

    public record HolidayRequest(@NotNull LocalDate date, @NotBlank @Size(max = 120) String name) {
    }

    public record Holiday(LocalDate date, String name) {
    }

    public record CalendarView(String zone, LocalTime start, LocalTime end, List<DayOfWeek> workdays, String contractPhase, List<Holiday> holidays) {
    }

    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ZoneId zone;
    private final LocalTime start;
    private final LocalTime end;
    private final Set<DayOfWeek> workdays;
    private final String contractPhase;

    public SlaPolicyService(AuditService audit, JdbcTemplate jdbc, Clock clock,
            @Value("${cas.tickets.business-hours.zone:Asia/Manila}") String zone,
            @Value("${cas.tickets.business-hours.start:08:00}") String start, @Value("${cas.tickets.business-hours.end:17:00}") String end,
            @Value("${cas.tickets.business-hours.days:MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY}") String days,
            @Value("${cas.tickets.contract-phase:WARRANTY}") String contractPhase) {
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
        this.zone = ZoneId.of(zone);
        this.start = LocalTime.parse(start);
        this.end = LocalTime.parse(end);
        this.workdays = EnumSet.noneOf(DayOfWeek.class);
        Arrays.stream(days.split(",")).map(String::trim).map(DayOfWeek::valueOf).forEach(this.workdays::add);
        this.contractPhase = contractPhase;
    }

    public String contractPhase() {
        return contractPhase;
    }

    /** A clock that knows the holidays as they stand now. */
    public BusinessClock clock() {
        Set<LocalDate> holidays = new HashSet<>(jdbc.queryForList("SELECT holiday_date FROM business_holidays", LocalDate.class));
        return new BusinessClock(zone, start, end, workdays, holidays::contains);
    }


    /** SLA compliance by type and priority for tickets created between the two dates (inclusive). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> slaSummary(LocalDate from, LocalDate to) {
        return jdbc.queryForList("""
                SELECT ticket_type AS "ticketType", priority, count(*) AS total,
                       count(*) FILTER (WHERE sla_policy_id IS NULL) AS "noSla",
                       count(*) FILTER (WHERE first_response_at IS NOT NULL AND NOT response_breached) AS "respondedInTime",
                       count(*) FILTER (WHERE response_breached) AS "responseBreached",
                       count(*) FILTER (WHERE status IN ('RESOLVED', 'CLOSED') AND NOT resolution_breached) AS "resolvedInTime",
                       count(*) FILTER (WHERE resolution_breached) AS "resolutionBreached",
                       count(*) FILTER (WHERE status NOT IN ('RESOLVED', 'CLOSED', 'REJECTED', 'CANCELLED')) AS "open"
                FROM tickets WHERE created_at >= ? AND created_at < ? GROUP BY ticket_type, priority ORDER BY ticket_type, priority""",
                Date.valueOf(from), Date.valueOf(to.plusDays(1)));
    }
    @Transactional(readOnly = true)
    public List<PolicyView> policies() {
        return jdbc.query("SELECT * FROM sla_policies ORDER BY status, contract_phase, ticket_type, priority", (rs, i) -> new PolicyView(
                rs.getObject("id", UUID.class), rs.getString("contract_phase"), rs.getString("ticket_type"), rs.getString("priority"),
                rs.getInt("response_minutes"), rs.getInt("resolution_minutes"), rs.getBoolean("business_hours"), rs.getBoolean("placeholder"),
                rs.getString("status"), rs.getString("created_by"), rs.getTimestamp("created_at").toInstant()));
    }

    /** Adds a policy; it replaces the active one for the same phase, type and priority (kept as RETIRED). */
    @Transactional
    public PolicyView create(PolicyRequest req, boolean placeholder, Actor actor) {
        if (req.resolutionMinutes() < req.responseMinutes()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Resolution time cannot be shorter than response time.");
        }
        jdbc.update("UPDATE sla_policies SET status = 'RETIRED' WHERE contract_phase = ? AND ticket_type = ? AND priority = ? AND status = 'ACTIVE'",
                req.contractPhase(), req.ticketType(), req.priority());
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sla_policies (id, contract_phase, ticket_type, priority, response_minutes, resolution_minutes, business_hours,
                    placeholder, status, created_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)""", id, req.contractPhase(),
                req.ticketType(), req.priority(), req.responseMinutes(), req.resolutionMinutes(), req.businessHours(), placeholder, actor.username(),
                Timestamp.from(Instant.now(clock)));
        audit.record(actor, "SLA_POLICY_SET", "SLA_POLICY", req.contractPhase() + "/" + req.ticketType() + "/" + req.priority(), Map.of(
                "responseMinutes", req.responseMinutes(), "resolutionMinutes", req.resolutionMinutes(), "businessHours", req.businessHours()));
        return policies().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    /** The active policy for a new or re-prioritised ticket, or null when none is configured. */
    Map<String, Object> activePolicy(String phase, String type, String priority) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM sla_policies WHERE contract_phase = ? AND ticket_type = ? AND priority = ? "
                + "AND status = 'ACTIVE'", phase, type, priority);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Transactional(readOnly = true)
    public CalendarView calendar() {
        return new CalendarView(zone.getId(), start, end, workdays.stream().sorted().toList(), contractPhase, jdbc.query(
                "SELECT holiday_date, name FROM business_holidays ORDER BY holiday_date", (rs, i) -> new Holiday(rs.getDate(1).toLocalDate(),
                        rs.getString(2))));
    }

    @Transactional
    public CalendarView addHoliday(HolidayRequest req, Actor actor) {
        jdbc.update("INSERT INTO business_holidays (holiday_date, name, created_by, created_at) VALUES (?, ?, ?, ?) ON CONFLICT (holiday_date) "
                + "DO UPDATE SET name = EXCLUDED.name", Date.valueOf(req.date()), req.name().trim(), actor.username(), Timestamp.from(Instant.now(clock)));
        audit.record(actor, "HOLIDAY_SET", "BUSINESS_CALENDAR", req.date().toString(), Map.of("name", req.name().trim()));
        return calendar();
    }

    @Transactional
    public CalendarView removeHoliday(LocalDate date, Actor actor) {
        if (jdbc.update("DELETE FROM business_holidays WHERE holiday_date = ?", Date.valueOf(date)) == 0) {
            throw ApiException.notFound("Holiday");
        }
        audit.record(actor, "HOLIDAY_REMOVED", "BUSINESS_CALENDAR", date.toString(), Map.of());
        return calendar();
    }
}
