package com.miaa.cas.identity;

import com.miaa.cas.audit.Actor;
import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.identity.domain.Role;
import com.miaa.cas.identity.domain.RoleRepository;
import com.miaa.cas.identity.web.IdentityDtos.CreateRoleRequest;
import com.miaa.cas.identity.web.IdentityDtos.RoleChangeResult;
import com.miaa.cas.identity.web.IdentityDtos.RoleResponse;
import com.miaa.cas.identity.web.IdentityDtos.UpdateRoleRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Role maintenance. Permission changes are evaluated against SoD rules for every current holder of
 * the role, and an administrator cannot change the permissions of a role they hold themselves.
 */
@Service
public class RoleAdminService {

    private final RoleRepository roles;
    private final RoleAssignments assignments;
    private final PermissionCatalog catalog;
    private final AuditService audit;
    private final Clock clock;

    public RoleAdminService(RoleRepository roles, RoleAssignments assignments, PermissionCatalog catalog,
            AuditService audit, Clock clock) {
        this.roles = roles;
        this.assignments = assignments;
        this.catalog = catalog;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<RoleResponse> list() {
        return roles.findAllByOrderByCodeAsc().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public RoleResponse get(String code) {
        return toResponse(load(code));
    }

    @Transactional
    public RoleChangeResult create(CreateRoleRequest req) {
        if (roles.existsByCode(req.code())) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "Role code is already in use.");
        }
        validateCodes(req.permissionCodes());
        List<SodPolicy.Conflict> conflicts = SodPolicy.evaluate(req.permissionCodes(), catalog.sodRules());
        if (!SodPolicy.blocking(conflicts).isEmpty()) {
            throw sodException(SodPolicy.blocking(conflicts), List.of());
        }
        Role role = roles.saveAndFlush(new Role(UUID.randomUUID(), req.code(), req.name().trim(), req.description(),
                req.permissionCodes(), Instant.now(clock), Actor.current().username()));
        audit.record("ROLE_CREATED", "ROLE", role.getCode(), Map.of("permissions", new TreeSet<>(req.permissionCodes())));
        return new RoleChangeResult(toResponse(role), conflicts);
    }

    @Transactional
    public RoleResponse update(String code, UpdateRoleRequest req) {
        Role role = loadVersioned(code, req.version());
        Map<String, Object> before = Map.of("name", role.getName(), "description", String.valueOf(role.getDescription()));
        role.update(req.name().trim(), req.description(), Instant.now(clock), Actor.current().username());
        roles.flush();
        audit.record("ROLE_UPDATED", "ROLE", code, Map.of("before", before, "after",
                Map.of("name", role.getName(), "description", String.valueOf(role.getDescription()))));
        return toResponse(role);
    }

    @Transactional
    public RoleChangeResult setPermissions(String code, long version, Set<String> permissionCodes) {
        Role role = loadVersioned(code, version);
        Actor actor = Actor.current();
        if (actor.userId() != null && assignments.roleCodes(actor.userId()).contains(code)) {
            throw new ApiException(ErrorCode.SELF_MODIFICATION_FORBIDDEN,
                    "You cannot change the permissions of a role you hold. Ask another administrator.");
        }
        validateCodes(permissionCodes);
        List<SodPolicy.Rule> rules = catalog.sodRules();
        Set<SodPolicy.Conflict> warnings = new LinkedHashSet<>(SodPolicy.evaluate(permissionCodes, rules));
        List<SodPolicy.Conflict> blocking = new ArrayList<>(SodPolicy.blocking(List.copyOf(warnings)));
        List<String> affectedUsers = new ArrayList<>();
        for (UUID userId : assignments.usersWithRole(role.getId())) {
            List<SodPolicy.Conflict> userConflicts = SodPolicy.evaluate(
                    assignments.effectivePermissionsWithRoleReplaced(userId, role.getId(), permissionCodes), rules);
            warnings.addAll(userConflicts);
            List<SodPolicy.Conflict> userBlocking = SodPolicy.blocking(userConflicts);
            if (!userBlocking.isEmpty()) {
                affectedUsers.add(userId.toString());
                userBlocking.stream().filter(c -> !blocking.contains(c)).forEach(blocking::add);
            }
        }
        if (!blocking.isEmpty()) {
            throw sodException(blocking, affectedUsers);
        }
        Set<String> before = new TreeSet<>(role.getPermissionCodes());
        role.replacePermissions(permissionCodes, Instant.now(clock), actor.username());
        roles.flush();
        audit.record("ROLE_PERMISSIONS_CHANGED", "ROLE", code, Map.of("before", before,
                "after", new TreeSet<>(permissionCodes)));
        return new RoleChangeResult(toResponse(role), List.copyOf(warnings));
    }

    private void validateCodes(Set<String> codes) {
        List<String> unknown = catalog.unknownCodes(List.copyOf(codes));
        if (!unknown.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown permission(s): " + String.join(", ", unknown));
        }
    }

    private static ApiException sodException(List<SodPolicy.Conflict> blocking, List<String> affectedUsers) {
        return new ApiException(ErrorCode.SOD_CONFLICT, "The permission set violates segregation-of-duties rules.",
                Map.of("conflicts", blocking, "affectedUserIds", affectedUsers));
    }

    private Role load(String code) {
        return roles.findByCode(code).orElseThrow(() -> ApiException.notFound("Role"));
    }

    private Role loadVersioned(String code, long version) {
        Role role = load(code);
        if (role.getVersion() != version) {
            throw ApiException.staleVersion();
        }
        return role;
    }

    private RoleResponse toResponse(Role r) {
        return new RoleResponse(r.getId(), r.getCode(), r.getName(), r.getDescription(), r.isSystemRole(),
                new TreeSet<>(r.getPermissionCodes()), assignments.countUsersWithRole(r.getId()), r.getVersion());
    }
}
