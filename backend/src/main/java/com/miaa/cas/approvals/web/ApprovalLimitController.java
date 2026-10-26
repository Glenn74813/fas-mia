package com.miaa.cas.approvals.web;

import com.miaa.cas.approvals.ApprovalLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Per-role approval limits (C-24): read by limit managers, auditors and role administrators; set by limit managers. */
@RestController
@RequestMapping("/api/v1/approval-limits")
@Tag(name = "Approval limits")
public class ApprovalLimitController {

    public record LimitRequest(@NotNull @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal maxAmount) {
    }

    private final ApprovalLimitService limits;

    public ApprovalLimitController(ApprovalLimitService limits) {
        this.limits = limits;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('PERM_APPROVAL_LIMIT_MANAGE', 'PERM_AUDIT_LOG_VIEW', 'PERM_ROLE_VIEW')")
    public Map<String, Object> list() {
        return Map.of("limits", limits.list(), "documentTypes", ApprovalLimitService.DOCUMENT_TYPES);
    }

    @PutMapping("/{roleCode}/{documentType}")
    @PreAuthorize("hasAuthority('PERM_APPROVAL_LIMIT_MANAGE')")
    @Operation(summary = "Set the most a holder of the role may approve on one document of the type")
    public ApprovalLimitService.Limit set(@PathVariable String roleCode, @PathVariable String documentType,
            @Valid @RequestBody LimitRequest request) {
        return limits.set(roleCode, documentType, request.maxAmount());
    }

    @DeleteMapping("/{roleCode}/{documentType}")
    @PreAuthorize("hasAuthority('PERM_APPROVAL_LIMIT_MANAGE')")
    public ResponseEntity<Void> remove(@PathVariable String roleCode, @PathVariable String documentType) {
        limits.remove(roleCode, documentType);
        return ResponseEntity.noContent().build();
    }
}
