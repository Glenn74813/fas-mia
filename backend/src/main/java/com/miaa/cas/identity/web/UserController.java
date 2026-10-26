package com.miaa.cas.identity.web;

import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.identity.UserAdminService;
import com.miaa.cas.identity.domain.UserStatus;
import com.miaa.cas.identity.web.IdentityDtos.CreateUserRequest;
import com.miaa.cas.identity.web.IdentityDtos.RoleAssignmentResult;
import com.miaa.cas.identity.web.IdentityDtos.SetRolesRequest;
import com.miaa.cas.identity.web.IdentityDtos.TemporaryPasswordRequest;
import com.miaa.cas.identity.web.IdentityDtos.UpdateUserRequest;
import com.miaa.cas.identity.web.IdentityDtos.UserDetail;
import com.miaa.cas.identity.web.IdentityDtos.UserSummary;
import com.miaa.cas.identity.web.IdentityDtos.VersionRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
@RequestMapping("/api/v1/users")
@Tag(name = "Users", description = "User administration (technical administration; grants no financial authority)")
public class UserController {

    private final UserAdminService service;

    public UserController(UserAdminService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_USER_VIEW')")
    @Operation(summary = "Search users. sort: username|displayName|createdAt|lastLoginAt, prefix '-' for descending")
    public PageResponse<UserSummary> list(@RequestParam(required = false) String q,
            @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size, @RequestParam(required = false) String sort) {
        return service.list(q, status, page, size, sort);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_USER_VIEW')")
    public UserDetail get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_USER_ADMINISTER')")
    @Operation(summary = "Create a user with a temporary password (must be changed at first sign-in)")
    public RoleAssignmentResult create(@Valid @RequestBody CreateUserRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_USER_ADMINISTER')")
    public UserDetail update(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        return service.update(id, request);
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('PERM_USER_ADMINISTER')")
    public UserDetail activate(@PathVariable UUID id, @Valid @RequestBody VersionRequest request) {
        return service.setStatus(id, request.version(), UserStatus.ACTIVE);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('PERM_USER_ADMINISTER')")
    @Operation(summary = "Deactivate a user and revoke all of their sessions")
    public UserDetail deactivate(@PathVariable UUID id, @Valid @RequestBody VersionRequest request) {
        return service.setStatus(id, request.version(), UserStatus.INACTIVE);
    }

    @PostMapping("/{id}/unlock")
    @PreAuthorize("hasAuthority('PERM_USER_ADMINISTER')")
    public UserDetail unlock(@PathVariable UUID id) {
        return service.unlock(id);
    }

    @PutMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('PERM_USER_ADMINISTER')")
    @Operation(summary = "Replace the user's roles (SoD-checked; returns non-blocking warnings)")
    public RoleAssignmentResult setRoles(@PathVariable UUID id, @Valid @RequestBody SetRolesRequest request) {
        return service.setRoles(id, request.version(), request.roleCodes());
    }

    @PostMapping("/{id}/sessions/revoke")
    @PreAuthorize("hasAuthority('PERM_SESSION_REVOKE')")
    public Map<String, Integer> revokeSessions(@PathVariable UUID id) {
        return Map.of("sessionsRevoked", service.revokeSessions(id));
    }

    @PostMapping("/{id}/mfa/reset")
    @PreAuthorize("hasAuthority('PERM_USER_ADMINISTER')")
    @Operation(summary = "Revoke the user's authenticator; they must re-enrol at next sign-in")
    public UserDetail resetMfa(@PathVariable UUID id) {
        return service.resetMfa(id);
    }

    @PostMapping("/{id}/password/temporary")
    @PreAuthorize("hasAuthority('PERM_USER_ADMINISTER')")
    @Operation(summary = "Issue a temporary password; the user must change it at next sign-in")
    public UserDetail temporaryPassword(@PathVariable UUID id, @Valid @RequestBody TemporaryPasswordRequest request) {
        return service.issueTemporaryPassword(id, request.temporaryPassword());
    }
}
