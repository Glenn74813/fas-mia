package com.miaa.cas.journal.web;

import com.miaa.cas.journal.AllocationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

@RestController
@RequestMapping("/api/v1/allocation-cycles")
@Tag(name = "Overhead allocation", description = "Allocation cycles run into draft journal vouchers (TOR §3.5.1, Annex B.7, B.8)")
public class AllocationController {

    public record ReceiverRequest(@NotNull UUID costCenterId, @NotNull BigDecimal weight) {
    }

    public record RuleRequest(@Size(max = 300) String description, @NotNull UUID senderAccountId, @NotNull UUID senderCostCenterId,
            UUID receiverAccountId, @NotBlank String keyType, @NotEmpty @Size(max = 200) List<@Valid @NotNull ReceiverRequest> receivers) {
    }

    public record CycleRequest(@Size(max = 30) String code, @NotBlank @Size(max = 200) String name, @Size(max = 1000) String description,
            @NotEmpty @Size(max = 100) List<@Valid @NotNull RuleRequest> rules, Long version) {
    }

    public record StatusRequest(@NotBlank String status, long version) {
    }

    public record RunRequest(@NotNull UUID periodId) {
    }

    private final AllocationService allocations;

    public AllocationController(AllocationService allocations) {
        this.allocations = allocations;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('PERM_LEDGER_VIEW', 'PERM_ALLOCATION_MANAGE')")
    public List<AllocationService.Cycle> list() {
        return allocations.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_ALLOCATION_MANAGE')")
    public AllocationService.Cycle create(@Valid @RequestBody CycleRequest r) {
        return allocations.create(r.code(), r.name(), r.description(), rules(r));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_ALLOCATION_MANAGE')")
    @Operation(summary = "Replace a cycle's name, description and rules")
    public AllocationService.Cycle update(@PathVariable UUID id, @Valid @RequestBody CycleRequest r) {
        return allocations.update(id, r.version() == null ? -1 : r.version(), r.name(), r.description(), rules(r));
    }

    @PostMapping("/{id}/status")
    @PreAuthorize("hasAuthority('PERM_ALLOCATION_MANAGE')")
    public AllocationService.Cycle status(@PathVariable UUID id, @Valid @RequestBody StatusRequest r) {
        return allocations.setStatus(id, r.version(), r.status());
    }

    @GetMapping("/{id}/preview")
    @PreAuthorize("hasAnyAuthority('PERM_LEDGER_VIEW', 'PERM_ALLOCATION_MANAGE')")
    @Operation(summary = "The voucher lines a run would create for the period, without creating anything")
    public AllocationService.Preview preview(@PathVariable UUID id, @RequestParam UUID periodId) {
        return allocations.preview(id, periodId);
    }

    @PostMapping("/{id}/runs")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_ALLOCATION_MANAGE') and hasAuthority('PERM_JOURNAL_CREATE')")
    @Operation(summary = "Run the cycle for a period: creates a draft journal voucher")
    public AllocationService.Run run(@PathVariable UUID id, @Valid @RequestBody RunRequest r) {
        return allocations.run(id, r.periodId());
    }

    @GetMapping("/{id}/runs")
    @PreAuthorize("hasAnyAuthority('PERM_LEDGER_VIEW', 'PERM_ALLOCATION_MANAGE')")
    public List<AllocationService.Run> runs(@PathVariable UUID id) {
        return allocations.runs(id);
    }

    private static List<AllocationService.RuleInput> rules(CycleRequest r) {
        return r.rules().stream().map(x -> {
            Map<UUID, BigDecimal> receivers = new LinkedHashMap<>();
            x.receivers().forEach(rc -> receivers.merge(rc.costCenterId(), rc.weight(), BigDecimal::add));
            return new AllocationService.RuleInput(x.description(), x.senderAccountId(), x.senderCostCenterId(), x.receiverAccountId(),
                    x.keyType(), receivers);
        }).toList();
    }
}
