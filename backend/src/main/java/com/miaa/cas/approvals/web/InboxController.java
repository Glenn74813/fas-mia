package com.miaa.cas.approvals.web;

import com.miaa.cas.approvals.ApprovalInboxService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The approvals inbox: documents across modules waiting for a step the caller may take. */
@RestController
@RequestMapping("/api/v1/inbox")
@Tag(name = "Approvals inbox")
public class InboxController {

    private final ApprovalInboxService inbox;

    public InboxController(ApprovalInboxService inbox) {
        this.inbox = inbox;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated() and !hasAuthority('PERM_PORTAL_SELF_VIEW')")
    @Operation(summary = "Documents waiting for the caller, oldest first; the caller's own documents are never included. "
            + "q filters by number, description, counterparty or preparer")
    public ApprovalInboxService.Inbox mine(@RequestParam(required = false) String q) {
        return inbox.mine(q);
    }
}
