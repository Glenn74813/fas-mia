package com.miaa.cas.closing.web;

import com.miaa.cas.closing.CloseCalendarService;
import com.miaa.cas.closing.CloseCalendarService.Checklist;
import com.miaa.cas.closing.CloseCalendarService.ScheduleItem;
import com.miaa.cas.closing.CloseCalendarService.Task;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Day-end, month-end, quarter-end and year-end checklists, the closing schedule and its task templates. */
@RestController
@RequestMapping("/api/v1/close")
@Tag(name = "Closing")
public class CloseCalendarController {

    public record CompleteRequest(@NotBlank @Size(max = 80) String scopeKey, @NotBlank String outcome, @NotBlank @Size(max = 1000) String note) {
    }

    public record TaskRequest(Long version, @NotBlank @Pattern(regexp = "[A-Za-z0-9-]{2,30}") String code, @NotBlank @Size(max = 200) String name,
            @NotBlank String frequency, @Min(0) @Max(90) int dueOffsetDays, @NotBlank String responsiblePermission,
            @Size(max = 1000) String instructions, int sortOrder, String status) {
    }

    private final CloseCalendarService close;

    public CloseCalendarController(CloseCalendarService close) {
        this.close = close;
    }

    @GetMapping("/checklists")
    @PreAuthorize("hasAuthority('PERM_LEDGER_VIEW')")
    @Operation(summary = "A checklist: scope is a date (DAY_END), period id (MONTH_END), '<fiscalYearId>:Q<n>' (QUARTER_END) or fiscal year id")
    public Checklist checklist(@RequestParam String frequency, @RequestParam String scope) {
        return close.checklist(frequency, scope);
    }

    @PostMapping("/tasks/{id}/complete")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Tick off a close task for a scope with a note; requires the task's responsible permission")
    public Checklist complete(@PathVariable UUID id, @Valid @RequestBody CompleteRequest r) {
        return close.complete(id, r.scopeKey(), r.outcome(), r.note());
    }

    @GetMapping("/schedule")
    @PreAuthorize("hasAuthority('PERM_LEDGER_VIEW')")
    public List<ScheduleItem> schedule(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return close.schedule(from, to);
    }

    @GetMapping("/tasks")
    @PreAuthorize("hasAuthority('PERM_LEDGER_VIEW')")
    public List<Task> tasks() {
        return close.tasks();
    }

    @PostMapping("/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_CLOSE_TASK_MANAGE')")
    public Task create(@Valid @RequestBody TaskRequest r) {
        return close.saveTask(null, null, r.code(), r.name(), r.frequency(), r.dueOffsetDays(), r.responsiblePermission(), r.instructions(),
                r.sortOrder(), "ACTIVE");
    }

    @PutMapping("/tasks/{id}")
    @PreAuthorize("hasAuthority('PERM_CLOSE_TASK_MANAGE')")
    public Task update(@PathVariable UUID id, @Valid @RequestBody TaskRequest r) {
        if (r.version() == null || (!"ACTIVE".equals(r.status()) && !"INACTIVE".equals(r.status()))) {
            throw new com.miaa.cas.common.error.ApiException(com.miaa.cas.common.error.ErrorCode.VALIDATION_FAILED,
                    "Give the version and a status of ACTIVE or INACTIVE.");
        }
        return close.saveTask(id, r.version(), r.code(), r.name(), r.frequency(), r.dueOffsetDays(), r.responsiblePermission(), r.instructions(),
                r.sortOrder(), r.status());
    }
}
