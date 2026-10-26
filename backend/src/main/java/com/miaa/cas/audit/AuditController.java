package com.miaa.cas.audit;

import com.miaa.cas.common.web.PageRequests;
import com.miaa.cas.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Audit", description = "Read-only access to the append-only audit and security event trails")
public class AuditController {

    private final EventStore store;
    private final AuditService audit;

    public AuditController(EventStore store, AuditService audit) {
        this.store = store;
        this.audit = audit;
    }

    @GetMapping("/audit-events")
    @PreAuthorize("hasAuthority('PERM_AUDIT_LOG_VIEW')")
    @Operation(summary = "Search audit events (newest first)")
    public PageResponse<EventRecord> auditEvents(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) UUID actorUserId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return search(EventStore.Table.AUDIT, new EventQuery(from, to, actorUserId, action, resourceType, resourceId),
                page, size);
    }

    @GetMapping("/security-events")
    @PreAuthorize("hasAuthority('PERM_SECURITY_EVENT_VIEW')")
    @Operation(summary = "Search authentication and authorisation events (newest first)")
    public PageResponse<EventRecord> securityEvents(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) UUID actorUserId,
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return search(EventStore.Table.SECURITY, new EventQuery(from, to, actorUserId, action, null, null), page, size);
    }

    @PostMapping("/audit-events/verification")
    @PreAuthorize("hasAuthority('PERM_AUDIT_LOG_VIEW')")
    @Operation(summary = "Verify the hash chains of the audit and security event trails")
    @Transactional
    public Map<String, EventStore.ChainVerification> verify() {
        EventStore.ChainVerification auditResult = store.verify(EventStore.Table.AUDIT);
        EventStore.ChainVerification securityResult = store.verify(EventStore.Table.SECURITY);
        audit.record("AUDIT_CHAIN_VERIFIED", "AUDIT_TRAIL", null, Map.of(
                "auditIntact", auditResult.intact(), "auditRows", auditResult.rowsChecked(),
                "securityIntact", securityResult.intact(), "securityRows", securityResult.rowsChecked()));
        return Map.of("audit", auditResult, "security", securityResult);
    }

    private PageResponse<EventRecord> search(EventStore.Table table, EventQuery q, int page, int size) {
        PageRequests.of(page, size, null, Map.of("sequence", "id"), "sequence");
        long total = store.count(table, q);
        return new PageResponse<>(store.search(table, q, page, size), page, size, total,
                (int) ((total + size - 1) / size));
    }
}
