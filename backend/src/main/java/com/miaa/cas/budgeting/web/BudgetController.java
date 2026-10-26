package com.miaa.cas.budgeting.web;

import com.miaa.cas.budgeting.BudgetDtos.ActionRequest;
import com.miaa.cas.budgeting.BudgetDtos.AdjustmentRequest;
import com.miaa.cas.budgeting.BudgetDtos.AdjustmentView;
import com.miaa.cas.budgeting.BudgetDtos.BudgetView;
import com.miaa.cas.budgeting.BudgetDtos.CreateBudgetRequest;
import com.miaa.cas.budgeting.BudgetDtos.DecisionRequest;
import com.miaa.cas.budgeting.BudgetDtos.SetAllocationsRequest;
import com.miaa.cas.budgeting.BudgetService;
import com.miaa.cas.common.web.PageResponse;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/budgets")
@Tag(name = "Budgets", description = "Corporate Operating Budget, utilisation and adjustments")
public class BudgetController {

    private final BudgetService budgets;

    public BudgetController(BudgetService budgets) {
        this.budgets = budgets;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('PERM_BUDGET_VIEW', 'PERM_BUDGET_PREPARE', 'PERM_BUDGET_APPROVE')")
    public PageResponse<BudgetView> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return budgets.list(page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('PERM_BUDGET_VIEW', 'PERM_BUDGET_PREPARE', 'PERM_BUDGET_APPROVE')")
    @Operation(summary = "Budget with per-allocation allotment, reservations, actuals, availability and utilisation")
    public BudgetView get(@PathVariable UUID id) {
        return budgets.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_BUDGET_PREPARE')")
    public BudgetView create(@Valid @RequestBody CreateBudgetRequest request) {
        return budgets.create(request.fiscalYearId(), request.name());
    }

    @PutMapping("/{id}/allocations")
    @PreAuthorize("hasAuthority('PERM_BUDGET_PREPARE')")
    public BudgetView allocations(@PathVariable UUID id, @Valid @RequestBody SetAllocationsRequest request) {
        return budgets.setAllocations(id, request.version(), request.allocations());
    }

    @PostMapping("/{id}/{action:submit|approve|return}")
    @PreAuthorize("hasAnyAuthority('PERM_BUDGET_PREPARE', 'PERM_BUDGET_APPROVE')")
    public BudgetView action(@PathVariable UUID id, @PathVariable String action, @Valid @RequestBody ActionRequest request) {
        return switch (action) {
            case "submit" -> budgets.submit(id, request.version(), request.comment());
            case "approve" -> budgets.approve(id, request.version(), request.comment());
            default -> budgets.returnBudget(id, request.version(), request.comment());
        };
    }

    @GetMapping("/{id}/adjustments")
    @PreAuthorize("hasAnyAuthority('PERM_BUDGET_VIEW', 'PERM_BUDGET_PREPARE', 'PERM_BUDGET_APPROVE')")
    public List<AdjustmentView> adjustments(@PathVariable UUID id) {
        return budgets.adjustments(id);
    }

    @PostMapping("/{id}/adjustments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_BUDGET_PREPARE')")
    @Operation(summary = "Request a realignment (from -> to) or a supplemental increase (no 'from')")
    public AdjustmentView requestAdjustment(@PathVariable UUID id, @Valid @RequestBody AdjustmentRequest request) {
        return budgets.requestAdjustment(id, request);
    }

    @PostMapping("/adjustments/{adjustmentId}/decision")
    @PreAuthorize("hasAuthority('PERM_BUDGET_APPROVE')")
    public AdjustmentView decide(@PathVariable UUID adjustmentId, @Valid @RequestBody DecisionRequest request) {
        return budgets.decideAdjustment(adjustmentId, request.approve());
    }
}
