package com.miaa.cas.identity.web;

import com.miaa.cas.identity.RoleGrantService;
import com.miaa.cas.identity.SodPolicy;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** API contracts for user and role administration. Only listed fields are bindable (no mass assignment). */
public final class IdentityDtos {

    private IdentityDtos() {
    }

    public record CreateUserRequest(
            @NotBlank @Pattern(regexp = "^[a-z0-9._-]{3,64}$", message = "3-64 lower-case letters, digits, '.', '_' or '-'")
            String username,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 200) String displayName,
            @NotNull String userType,
            @NotBlank @Size(max = 128) String temporaryPassword,
            @NotNull @Size(max = 20) Set<@Pattern(regexp = "^[A-Z][A-Z0-9_]{2,63}$") String> roleCodes,
            Instant accessExpiresAt) {
    }

    public record UpdateUserRequest(
            @NotNull Long version,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 200) String displayName,
            Instant accessExpiresAt) {
    }

    public record VersionRequest(@NotNull Long version) {
    }

    public record SetRolesRequest(@NotNull Long version,
            @NotNull @Size(max = 20) Set<@Pattern(regexp = "^[A-Z][A-Z0-9_]{2,63}$") String> roleCodes) {
    }

    public record TemporaryPasswordRequest(@NotBlank @Size(max = 128) String temporaryPassword) {
    }

    public record UserSummary(UUID id, String username, String displayName, String email, String userType,
            String status, boolean locked, Instant lastLoginAt, long version) {
    }

    public record UserDetail(UUID id, String username, String displayName, String email, String userType,
            String status, boolean locked, Instant lockedUntil, boolean mustChangePassword, boolean mfaEnrolled,
            Instant accessExpiresAt, Instant lastLoginAt, Instant passwordChangedAt, Instant createdAt, String createdBy,
            Set<String> roles, Set<String> effectivePermissions, int activeSessions, long version) {
    }

    /** {@code pendingGrant} is set when financial roles wait for a second person's approval instead of applying (C-19). */
    public record RoleAssignmentResult(UserDetail user, List<SodPolicy.Conflict> warnings, RoleGrantService.GrantView pendingGrant) {
    }

    public record CreateRoleRequest(
            @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{2,63}$") String code,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 500) String description,
            @NotNull @Size(max = 100) Set<@Pattern(regexp = "^[A-Z][A-Z0-9_]{2,63}$") String> permissionCodes) {
    }

    public record UpdateRoleRequest(@NotNull Long version, @NotBlank @Size(max = 120) String name,
            @Size(max = 500) String description) {
    }

    public record SetPermissionsRequest(@NotNull Long version,
            @NotNull @Size(max = 100) Set<@Pattern(regexp = "^[A-Z][A-Z0-9_]{2,63}$") String> permissionCodes) {
    }

    public record RoleResponse(UUID id, String code, String name, String description, boolean systemRole,
            Set<String> permissionCodes, long userCount, long version) {
    }

    public record RoleChangeResult(RoleResponse role, List<SodPolicy.Conflict> warnings) {
    }
}
