package com.miaa.cas.financialreporting.web;

import com.miaa.cas.audit.Actor;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.financialreporting.FinancialStatementService;
import com.miaa.cas.financialreporting.ReportDtos.JobRequest;
import com.miaa.cas.financialreporting.ReportDtos.JobView;
import com.miaa.cas.financialreporting.ReportDtos.StatementResult;
import com.miaa.cas.financialreporting.ReportDtos.TemplateRequest;
import com.miaa.cas.financialreporting.ReportDtos.TemplateView;
import com.miaa.cas.financialreporting.ReportJobService;
import com.miaa.cas.financialreporting.ReportScheduleService;
import com.miaa.cas.financialreporting.StatementTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Financial reporting", description = "Statement templates, financial statements and background report jobs")
public class ReportingController {

    private final StatementTemplateService templates;
    private final FinancialStatementService statements;
    private final ReportJobService jobs;
    private final ReportScheduleService schedules;

    public ReportingController(StatementTemplateService templates, FinancialStatementService statements, ReportJobService jobs,
            ReportScheduleService schedules) {
        this.schedules = schedules;
        this.templates = templates;
        this.statements = statements;
        this.jobs = jobs;
    }

    @GetMapping("/report-templates")
    @PreAuthorize("hasAnyAuthority('PERM_FINANCIAL_REPORT_VIEW', 'PERM_ACCOUNT_ADMINISTER')")
    public List<TemplateView> templates() {
        return templates.list();
    }

    @GetMapping("/report-templates/{id}")
    @PreAuthorize("hasAnyAuthority('PERM_FINANCIAL_REPORT_VIEW', 'PERM_ACCOUNT_ADMINISTER')")
    public TemplateView template(@PathVariable UUID id) {
        return templates.get(id);
    }

    @PostMapping("/report-templates")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_ACCOUNT_ADMINISTER')")
    @Operation(summary = "Define a new version of a statement template (draft until a second administrator activates it)")
    public TemplateView createTemplate(@Valid @RequestBody TemplateRequest request) {
        return templates.create(request, false, Actor.current());
    }

    @PostMapping("/report-templates/{id}/activate")
    @PreAuthorize("hasAuthority('PERM_ACCOUNT_ADMINISTER')")
    public TemplateView activate(@PathVariable UUID id) {
        return templates.activate(id, Actor.current());
    }

    @GetMapping("/financial-statements")
    @PreAuthorize("hasAuthority('PERM_FINANCIAL_REPORT_VIEW')")
    @Operation(summary = "Generate a statement on screen from the active (or a given) template version")
    public StatementResult statement(@RequestParam String template, @RequestParam UUID periodId,
            @RequestParam(defaultValue = "false") boolean comparative) {
        return statements.generate(template, periodId, comparative);
    }

    @PostMapping("/report-jobs")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Queue a report export; each report type requires its own module's permission (checked in the service)")
    public JobView requestJob(@Valid @RequestBody JobRequest request) {
        return jobs.request(request);
    }

    @GetMapping("/report-jobs")
    @PreAuthorize("isAuthenticated()")
    public PageResponse<JobView> myJobs(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return jobs.mine(page, size);
    }

    @GetMapping("/report-jobs/{id}")
    @PreAuthorize("isAuthenticated()")
    public JobView job(@PathVariable UUID id) {
        return jobs.get(id);
    }

    @GetMapping("/report-jobs/{id}/file")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> file(@PathVariable UUID id) {
        ReportJobService.ReportFile f = jobs.download(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(f.fileName()).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(f.contentType()))
                .body(f.content());
    }

    @GetMapping("/report-schedules")
    @PreAuthorize("isAuthenticated()")
    public List<ReportScheduleService.ScheduleView> mySchedules() {
        return schedules.mine();
    }

    @PostMapping("/report-schedules")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    public ReportScheduleService.ScheduleView createSchedule(
            @Valid @RequestBody ReportScheduleService.ScheduleRequest request) {
        return schedules.create(request);
    }

    @DeleteMapping("/report-schedules/{id}")
    @PreAuthorize("isAuthenticated()")
    public ReportScheduleService.ScheduleView deleteSchedule(@PathVariable UUID id) {
        return schedules.delete(id);
    }
}
