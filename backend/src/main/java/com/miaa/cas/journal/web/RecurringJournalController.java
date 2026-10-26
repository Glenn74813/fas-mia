package com.miaa.cas.journal.web;

import com.miaa.cas.audit.Actor;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.generalledger.web.LedgerDtos.VersionRequest;
import com.miaa.cas.journal.RecurringJournalService;
import com.miaa.cas.journal.web.JournalDtos.RecurringRequest;
import com.miaa.cas.journal.web.JournalDtos.RecurringResponse;
import com.miaa.cas.journal.web.JournalDtos.RunDueResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/recurring-journals")
@Tag(name = "Recurring journals")
public class RecurringJournalController {

    private final RecurringJournalService service;
    private final Clock clock;

    public RecurringJournalController(RecurringJournalService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_LEDGER_VIEW') or hasAuthority('PERM_RECURRING_JOURNAL_MANAGE')")
    public PageResponse<RecurringResponse> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return service.list(page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_RECURRING_JOURNAL_MANAGE') and hasAuthority('PERM_JOURNAL_CREATE')")
    public RecurringResponse create(@Valid @RequestBody RecurringRequest request) {
        return service.create(request);
    }

    @PostMapping("/{id}/pause")
    @PreAuthorize("hasAuthority('PERM_RECURRING_JOURNAL_MANAGE')")
    public RecurringResponse pause(@PathVariable UUID id, @Valid @RequestBody VersionRequest request) {
        return service.setStatus(id, request.version(), false);
    }

    @PostMapping("/{id}/resume")
    @PreAuthorize("hasAuthority('PERM_RECURRING_JOURNAL_MANAGE')")
    public RecurringResponse resume(@PathVariable UUID id, @Valid @RequestBody VersionRequest request) {
        return service.setStatus(id, request.version(), true);
    }

    @PostMapping("/run-due")
    @PreAuthorize("hasAuthority('PERM_RECURRING_JOURNAL_MANAGE')")
    @Operation(summary = "Generate draft vouchers for all due templates now (also runs daily on schedule)")
    public RunDueResult runDue() {
        return service.runDue(LocalDate.now(clock.withZone(ZoneId.of("Asia/Manila"))), Actor.current());
    }
}
