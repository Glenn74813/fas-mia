package com.miaa.cas.tickets.web;

import com.miaa.cas.audit.Actor;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.tickets.SlaPolicyService;
import com.miaa.cas.tickets.SlaPolicyService.CalendarView;
import com.miaa.cas.tickets.SlaPolicyService.HolidayRequest;
import com.miaa.cas.tickets.SlaPolicyService.PolicyRequest;
import com.miaa.cas.tickets.SlaPolicyService.PolicyView;
import com.miaa.cas.tickets.TicketService;
import com.miaa.cas.tickets.TicketService.ActionRequest;
import com.miaa.cas.tickets.TicketService.Category;
import com.miaa.cas.tickets.TicketService.TicketRequest;
import com.miaa.cas.tickets.TicketService.TicketSummary;
import com.miaa.cas.tickets.TicketService.TicketView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Support tickets", description = "Incidents, service requests, change requests and enhancements with SLA timers")
public class TicketsController {

    public record CategoryRequest(@Size(max = 30) String code, @NotBlank @Size(max = 120) String name, int sortOrder, String status) {
    }

    private final TicketService tickets;
    private final SlaPolicyService policies;
    private final JdbcTemplate jdbc;

    public TicketsController(TicketService tickets, SlaPolicyService policies, JdbcTemplate jdbc) {
        this.tickets = tickets;
        this.policies = policies;
        this.jdbc = jdbc;
    }

    @GetMapping("/tickets")
    @PreAuthorize("hasAnyAuthority('PERM_TICKET_CREATE', 'PERM_TICKET_VIEW_ALL', 'PERM_TICKET_WORK')")
    public PageResponse<TicketSummary> list(@RequestParam(defaultValue = "false") boolean all, @RequestParam(required = false) String status,
            @RequestParam(required = false) String type, @RequestParam(required = false) String severity,
            @RequestParam(required = false) UUID categoryId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return tickets.list(all, status, type, severity, categoryId, page, size);
    }

    @PostMapping(value = "/tickets/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('PERM_TICKET_CREATE', 'PERM_TICKET_WORK')")
    @Operation(summary = "Attach a screenshot or document (PDF, image, spreadsheet) to an open ticket")
    public TicketView attach(@PathVariable UUID id, @RequestPart("file") MultipartFile file) throws IOException {
        return tickets.attach(id, file.getOriginalFilename(), file.getBytes());
    }

    @GetMapping("/ticket-categories")
    @PreAuthorize("hasAnyAuthority('PERM_TICKET_CREATE', 'PERM_TICKET_VIEW_ALL', 'PERM_TICKET_WORK', 'PERM_TICKET_ADMIN')")
    public List<Category> categories() {
        return tickets.categories();
    }

    @PostMapping("/ticket-categories")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_TICKET_ADMIN')")
    public Category createCategory(@Valid @RequestBody CategoryRequest r) {
        return tickets.saveCategory(null, r.code(), r.name(), r.sortOrder(), "ACTIVE");
    }

    @PutMapping("/ticket-categories/{id}")
    @PreAuthorize("hasAuthority('PERM_TICKET_ADMIN')")
    public Category updateCategory(@PathVariable UUID id, @Valid @RequestBody CategoryRequest r) {
        return tickets.saveCategory(id, r.code(), r.name(), r.sortOrder(), r.status());
    }

    @GetMapping("/tickets/{id}")
    @PreAuthorize("hasAnyAuthority('PERM_TICKET_CREATE', 'PERM_TICKET_VIEW_ALL', 'PERM_TICKET_WORK')")
    public TicketView get(@PathVariable UUID id) {
        return tickets.get(id);
    }

    @PostMapping("/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_TICKET_CREATE')")
    public TicketView create(@Valid @RequestBody TicketRequest request) {
        return tickets.create(request);
    }

    @PostMapping("/tickets/{id}/actions")
    @PreAuthorize("hasAnyAuthority('PERM_TICKET_CREATE', 'PERM_TICKET_WORK', 'PERM_TICKET_APPROVE')")
    public TicketView act(@PathVariable UUID id, @Valid @RequestBody ActionRequest request) {
        return tickets.act(id, request);
    }

    /** SLA compliance by type and priority for tickets created in the range. */
    @GetMapping("/tickets/sla-summary")
    @PreAuthorize("hasAnyAuthority('PERM_TICKET_VIEW_ALL', 'PERM_TICKET_WORK')")
    public List<Map<String, Object>> slaSummary(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
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

    @GetMapping("/sla-policies")
    @PreAuthorize("hasAnyAuthority('PERM_TICKET_ADMIN', 'PERM_TICKET_WORK', 'PERM_TICKET_VIEW_ALL')")
    public List<PolicyView> policies() {
        return policies.policies();
    }

    @PostMapping("/sla-policies")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_TICKET_ADMIN')")
    public PolicyView createPolicy(@Valid @RequestBody PolicyRequest request) {
        return policies.create(request, false, Actor.current());
    }

    @GetMapping("/business-calendar")
    @PreAuthorize("hasAnyAuthority('PERM_TICKET_ADMIN', 'PERM_TICKET_WORK', 'PERM_TICKET_VIEW_ALL')")
    public CalendarView calendar() {
        return policies.calendar();
    }

    @PostMapping("/business-calendar/holidays")
    @PreAuthorize("hasAuthority('PERM_TICKET_ADMIN')")
    public CalendarView addHoliday(@Valid @RequestBody HolidayRequest request) {
        return policies.addHoliday(request, Actor.current());
    }

    @DeleteMapping("/business-calendar/holidays/{date}")
    @PreAuthorize("hasAuthority('PERM_TICKET_ADMIN')")
    public CalendarView removeHoliday(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return policies.removeHoliday(date, Actor.current());
    }
}
