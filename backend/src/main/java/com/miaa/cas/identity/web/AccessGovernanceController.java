package com.miaa.cas.identity.web;

import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.identity.AccessReviewService;
import com.miaa.cas.identity.RoleGrantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Access governance (MS §9.3): financial role grant approvals (C-19) and periodic access reviews. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Access governance")
public class AccessGovernanceController {

    public record DecisionRequest(@NotNull Long version, @Size(max = 1000) String note) {
    }

    public record VersionRequest(@NotNull Long version) {
    }

    public record CycleRequest(@NotBlank @Size(max = 200) String name, @NotNull LocalDate dueDate) {
    }

    public record ReviewRequest(@NotNull Long version, @NotBlank String outcome, @Size(max = 1000) String notes) {
    }

    private final RoleGrantService grants;
    private final AccessReviewService reviews;

    public AccessGovernanceController(RoleGrantService grants, AccessReviewService reviews) {
        this.grants = grants;
        this.reviews = reviews;
    }

    // ---------------------------------------------------------------- role grants

    @GetMapping("/role-grants")
    @PreAuthorize("hasAnyAuthority('PERM_ACCESS_GRANT_APPROVE', 'PERM_USER_ADMINISTER', 'PERM_AUDIT_LOG_VIEW')")
    public PageResponse<RoleGrantService.GrantView> grants(@RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return grants.list(status, page, size);
    }

    @GetMapping("/role-grants/{id}")
    @PreAuthorize("hasAnyAuthority('PERM_ACCESS_GRANT_APPROVE', 'PERM_USER_ADMINISTER', 'PERM_AUDIT_LOG_VIEW')")
    public RoleGrantService.GrantView grant(@PathVariable UUID id) {
        return grants.get(id);
    }

    @PostMapping("/role-grants/{id}/approve")
    @PreAuthorize("hasAuthority('PERM_ACCESS_GRANT_APPROVE')")
    @Operation(summary = "Approve a grant of financial roles; never by the requester or the user concerned")
    public RoleGrantService.GrantView approve(@PathVariable UUID id, @Valid @RequestBody DecisionRequest r) {
        return grants.approve(id, r.version(), r.note());
    }

    @PostMapping("/role-grants/{id}/reject")
    @PreAuthorize("hasAuthority('PERM_ACCESS_GRANT_APPROVE')")
    public RoleGrantService.GrantView reject(@PathVariable UUID id, @Valid @RequestBody DecisionRequest r) {
        return grants.reject(id, r.version(), r.note());
    }

    @PostMapping("/role-grants/{id}/cancel")
    @PreAuthorize("hasAnyAuthority('PERM_USER_ADMINISTER')")
    public RoleGrantService.GrantView cancel(@PathVariable UUID id, @Valid @RequestBody VersionRequest r) {
        return grants.cancel(id, r.version());
    }

    // ---------------------------------------------------------------- access reviews

    @GetMapping("/access-review-cycles")
    @PreAuthorize("hasAnyAuthority('PERM_ACCESS_REVIEW_MANAGE', 'PERM_ACCESS_REVIEW_PERFORM', 'PERM_USER_ADMINISTER')")
    public List<AccessReviewService.CycleSummary> cycles() {
        return reviews.cycles();
    }

    @PostMapping("/access-review-cycles")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_ACCESS_REVIEW_MANAGE')")
    @Operation(summary = "Start a review of every active internal user's roles")
    public AccessReviewService.CycleSummary start(@Valid @RequestBody CycleRequest r) {
        return reviews.start(r.name(), r.dueDate());
    }

    @GetMapping("/access-review-cycles/{id}")
    @PreAuthorize("hasAnyAuthority('PERM_ACCESS_REVIEW_MANAGE', 'PERM_ACCESS_REVIEW_PERFORM', 'PERM_USER_ADMINISTER')")
    public AccessReviewService.CycleDetail cycle(@PathVariable UUID id) {
        return reviews.cycle(id);
    }

    @PostMapping("/access-review-cycles/{id}/close")
    @PreAuthorize("hasAuthority('PERM_ACCESS_REVIEW_MANAGE')")
    public AccessReviewService.CycleSummary close(@PathVariable UUID id, @Valid @RequestBody VersionRequest r) {
        return reviews.close(id, r.version());
    }

    @PostMapping("/access-reviews/{id}/review")
    @PreAuthorize("hasAuthority('PERM_ACCESS_REVIEW_PERFORM')")
    @Operation(summary = "Record RETAIN, MODIFY or REVOKE for one user's access; never for your own")
    public AccessReviewService.ReviewItem review(@PathVariable UUID id, @Valid @RequestBody ReviewRequest r) {
        return reviews.review(id, r.version(), r.outcome().trim().toUpperCase(), r.notes());
    }

    @PostMapping("/access-reviews/{id}/action")
    @PreAuthorize("hasAuthority('PERM_USER_ADMINISTER')")
    @Operation(summary = "Record that a MODIFY or REVOKE outcome has been applied")
    public AccessReviewService.ReviewItem action(@PathVariable UUID id, @Valid @RequestBody DecisionRequest r) {
        return reviews.recordAction(id, r.version(), r.note());
    }
}
