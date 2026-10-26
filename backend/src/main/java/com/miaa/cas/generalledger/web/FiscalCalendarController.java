package com.miaa.cas.generalledger.web;

import com.miaa.cas.audit.Actor;
import com.miaa.cas.generalledger.FiscalCalendarService;
import com.miaa.cas.generalledger.PeriodCloseService;
import com.miaa.cas.generalledger.web.LedgerDtos.CloseChecklist;
import com.miaa.cas.generalledger.web.LedgerDtos.ClosePeriodRequest;
import com.miaa.cas.generalledger.web.LedgerDtos.FiscalYearRequest;
import com.miaa.cas.generalledger.web.LedgerDtos.FiscalYearResponse;
import com.miaa.cas.generalledger.web.LedgerDtos.PeriodChangeResponse;
import com.miaa.cas.generalledger.web.LedgerDtos.PeriodResponse;
import com.miaa.cas.generalledger.web.LedgerDtos.ReopenDecisionRequest;
import com.miaa.cas.generalledger.web.LedgerDtos.ReopenRequest;
import com.miaa.cas.generalledger.web.LedgerDtos.VersionRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Fiscal calendar", description = "Fiscal years, accounting periods, close and two-person reopen")
public class FiscalCalendarController {

    private final FiscalCalendarService calendar;
    private final PeriodCloseService close;

    public FiscalCalendarController(FiscalCalendarService calendar, PeriodCloseService close) {
        this.calendar = calendar;
        this.close = close;
    }

    @GetMapping("/fiscal-years")
    @PreAuthorize("hasAuthority('PERM_LEDGER_VIEW')")
    public List<FiscalYearResponse> fiscalYears() {
        return calendar.list();
    }

    @PostMapping("/fiscal-years")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_PERIOD_OPEN')")
    @Operation(summary = "Create a calendar fiscal year with 12 monthly periods and adjustment period 13 (all FUTURE)")
    public FiscalYearResponse createFiscalYear(@Valid @RequestBody FiscalYearRequest request) {
        return calendar.create(request.fiscalYear(), Actor.current());
    }

    @PostMapping("/fiscal-years/{id}/close")
    @PreAuthorize("hasAuthority('PERM_PERIOD_CLOSE')")
    @Operation(summary = "Close a fiscal year once all periods are closed and nominal accounts are zero")
    public FiscalYearResponse closeFiscalYear(@PathVariable UUID id, @Valid @RequestBody VersionRequest request) {
        return calendar.closeFiscalYear(id, request.version(), Actor.current());
    }

    @PostMapping("/accounting-periods/{id}/open")
    @PreAuthorize("hasAuthority('PERM_PERIOD_OPEN')")
    public PeriodResponse open(@PathVariable UUID id, @Valid @RequestBody VersionRequest request) {
        return calendar.openPeriod(id, request.version(), Actor.current());
    }

    @PostMapping("/accounting-periods/{id}/soft-close")
    @PreAuthorize("hasAuthority('PERM_PERIOD_CLOSE')")
    @Operation(summary = "Soft-close: only ADJUSTING and CLOSING vouchers may still post")
    public PeriodResponse softClose(@PathVariable UUID id, @Valid @RequestBody VersionRequest request) {
        return calendar.softClose(id, request.version(), Actor.current());
    }

    @GetMapping("/accounting-periods/{id}/close-checklist")
    @PreAuthorize("hasAuthority('PERM_LEDGER_VIEW')")
    public CloseChecklist checklist(@PathVariable UUID id) {
        return close.checklist(id);
    }

    @PostMapping("/accounting-periods/{id}/close")
    @PreAuthorize("hasAuthority('PERM_PERIOD_CLOSE')")
    @Operation(summary = "Close a period. Failing waivable checks may be waived with a justification")
    public PeriodResponse closePeriod(@PathVariable UUID id, @Valid @RequestBody ClosePeriodRequest request) {
        return close.close(id, request.version(), request.waivedChecks(), request.justification(), Actor.current());
    }

    @GetMapping("/accounting-periods/{id}/history")
    @PreAuthorize("hasAuthority('PERM_LEDGER_VIEW')")
    public List<PeriodChangeResponse> history(@PathVariable UUID id) {
        return close.history(id);
    }

    @PostMapping("/accounting-periods/{id}/reopen-requests")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_PERIOD_CLOSE')")
    @Operation(summary = "Request reopening of a closed period; another user with PERIOD_REOPEN must decide")
    public PeriodChangeResponse requestReopen(@PathVariable UUID id, @Valid @RequestBody ReopenRequest request) {
        return close.requestReopen(id, request.reason(), Actor.current());
    }

    @GetMapping("/period-reopen-requests")
    @PreAuthorize("hasAuthority('PERM_PERIOD_REOPEN') or hasAuthority('PERM_PERIOD_CLOSE')")
    public List<PeriodChangeResponse> pendingReopens() {
        return close.pendingReopens();
    }

    @PostMapping("/period-reopen-requests/{id}/decision")
    @PreAuthorize("hasAuthority('PERM_PERIOD_REOPEN')")
    public PeriodChangeResponse decide(@PathVariable UUID id, @Valid @RequestBody ReopenDecisionRequest request) {
        return close.decideReopen(id, request.approve(), request.note(), Actor.current());
    }
}
