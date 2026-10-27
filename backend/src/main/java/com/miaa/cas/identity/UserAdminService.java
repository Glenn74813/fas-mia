package com.miaa.cas.identity;

import com.miaa.cas.audit.Actor;
import com.miaa.cas.audit.AuditService;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.web.PageRequests;
import com.miaa.cas.common.web.PageResponse;
import com.miaa.cas.identity.domain.MfaMethod;
import com.miaa.cas.identity.domain.MfaMethodRepository;
import com.miaa.cas.identity.domain.Role;
import com.miaa.cas.identity.domain.RoleRepository;
import com.miaa.cas.identity.domain.User;
import com.miaa.cas.identity.domain.UserRepository;
import com.miaa.cas.identity.domain.UserStatus;
import com.miaa.cas.identity.domain.UserType;
import com.miaa.cas.identity.web.IdentityDtos.CreateUserRequest;
import com.miaa.cas.identity.web.IdentityDtos.RoleAssignmentResult;
import com.miaa.cas.identity.web.IdentityDtos.UpdateUserRequest;
import com.miaa.cas.identity.web.IdentityDtos.UserDetail;
import com.miaa.cas.identity.web.IdentityDtos.UserSummary;
import com.miaa.cas.security.mfa.RecoveryCodeService;
import com.miaa.cas.security.password.PasswordPolicy;
import com.miaa.cas.security.session.SessionService;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User administration with least-privilege safeguards: SoD evaluation on every role change, no
 * self-service elevation through the admin API, and an audit event (with before/after values) for
 * every change.
 */
@Service
public class UserAdminService {

    private static final Map<String, String> SORTS = Map.of(
            "username", "username", "displayName", "displayName", "createdAt", "createdAt", "lastLoginAt", "lastLoginAt");

    private final UserRepository users;
    private final RoleRepository roles;
    private final RoleAssignments assignments;
    private final PermissionCatalog catalog;
    private final MfaMethodRepository mfaMethods;
    private final SessionService sessions;
    private final PasswordPolicy passwordPolicy;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final Clock clock;
    private final RoleGrantService grants;
    private final RecoveryCodeService recoveryCodes;

    public UserAdminService(UserRepository users, RoleRepository roles, RoleAssignments assignments,
            PermissionCatalog catalog, MfaMethodRepository mfaMethods, SessionService sessions,
            PasswordPolicy passwordPolicy, PasswordEncoder encoder, AuditService audit, Clock clock, RoleGrantService grants,
            RecoveryCodeService recoveryCodes) {
        this.grants = grants;
        this.recoveryCodes = recoveryCodes;
        this.users = users;
        this.roles = roles;
        this.assignments = assignments;
        this.catalog = catalog;
        this.mfaMethods = mfaMethods;
        this.sessions = sessions;
        this.passwordPolicy = passwordPolicy;
        this.encoder = encoder;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<UserSummary> list(String q, String status, int page, int size, String sort) {
        Pageable pageable = PageRequests.of(page, size, sort, SORTS, "username");
        UserStatus st = status == null || status.isBlank() ? null : parse(UserStatus.class, status, "status");
        String query = q == null || q.isBlank() ? null : q.trim();
        Instant now = Instant.now(clock);
        return PageResponse.of(users.search(query, st, pageable), u -> new UserSummary(u.getId(), u.getUsername(),
                u.getDisplayName(), u.getEmail(), u.getUserType().name(), u.getStatus().name(), u.isLocked(now),
                u.getLastLoginAt(), u.getVersion()));
    }

    @Transactional(readOnly = true)
    public UserDetail get(UUID id) {
        return detail(load(id));
    }

    @Transactional
    public RoleAssignmentResult create(CreateUserRequest req) {
        Actor actor = Actor.current();
        Instant now = Instant.now(clock);
        UserType type = parse(UserType.class, req.userType(), "userType");
        if (users.existsByUsername(req.username())) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "Username is already in use.");
        }
        if (users.existsByEmailIgnoreCase(req.email())) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "E-mail address is already in use.");
        }
        passwordPolicy.enforce(req.temporaryPassword(), req.username(), req.email());
        checkExpiry(type, null, req.accessExpiresAt(), now);
        List<Role> roleList = resolveRoles(req.roleCodes());
        List<SodPolicy.Conflict> conflicts = checkSod(permissionsOf(roleList));
        Set<String> requested = codes(roleList);
        // Financial roles wait for a second person's approval (C-19); the others apply now.
        Set<String> held = grants.heldBack(null, Set.of(), requested);
        User user = new User(UUID.randomUUID(), req.username(), req.email().trim(), req.displayName().trim(), type,
                encoder.encode(req.temporaryPassword()), true, now, actor.username());
        user.setAccessExpiresAt(req.accessExpiresAt());
        users.saveAndFlush(user);
        List<Role> granted = roleList.stream().filter(r -> !held.contains(r.getCode())).toList();
        assignments.replaceRoles(user.getId(), granted.stream().map(Role::getId).toList(), actor.username());
        audit.record("USER_CREATED", "USER", user.getId(), Map.of("username", user.getUsername(), "userType", type.name(),
                "roles", codes(granted), "awaitingApproval", held, "accessExpiresAt", String.valueOf(req.accessExpiresAt())));
        RoleGrantService.GrantView pending = held.isEmpty() ? null : grants.file(user.getId(), codes(granted), requested, held);
        return new RoleAssignmentResult(detail(user), conflicts, pending);
    }

    /**
     * Time-limited access (MS §9.3): an external auditor always has an expiry, and a new or changed expiry lies in the
     * future (to end access now, deactivate the user). An unchanged past expiry may stay, so an expired account's other
     * details can still be corrected.
     */
    private static void checkExpiry(UserType type, Instant current, Instant requested, Instant now) {
        if (type == UserType.EXTERNAL_AUDITOR && requested == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "An external auditor's access must have an expiry date.");
        }
        if (requested != null && !requested.equals(current) && !requested.isAfter(now)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "The access expiry must be in the future. To end access now, deactivate the user.");
        }
    }

    @Transactional
    public UserDetail update(UUID id, UpdateUserRequest req) {
        User user = loadVersioned(id, req.version());
        if (!user.getEmail().equalsIgnoreCase(req.email()) && users.existsByEmailIgnoreCase(req.email())) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "E-mail address is already in use.");
        }
        checkExpiry(user.getUserType(), user.getAccessExpiresAt(), req.accessExpiresAt(), Instant.now(clock));
        Map<String, Object> before = Map.of("email", user.getEmail(), "displayName", user.getDisplayName(),
                "accessExpiresAt", String.valueOf(user.getAccessExpiresAt()));
        user.updateProfile(req.email().trim(), req.displayName().trim(), req.accessExpiresAt(), Instant.now(clock),
                Actor.current().username());
        users.flush();
        audit.record("USER_UPDATED", "USER", id, Map.of("before", before, "after", Map.of("email", user.getEmail(),
                "displayName", user.getDisplayName(), "accessExpiresAt", String.valueOf(user.getAccessExpiresAt()))));
        return detail(user);
    }

    @Transactional
    public UserDetail setStatus(UUID id, long version, UserStatus status) {
        forbidSelf(id, "change your own account status");
        User user = loadVersioned(id, version);
        UserStatus before = user.getStatus();
        user.setStatus(status, Instant.now(clock), Actor.current().username());
        int revoked = status == UserStatus.INACTIVE ? sessions.revokeAll(id, "USER_DEACTIVATED") : 0;
        users.flush();
        audit.record(status == UserStatus.ACTIVE ? "USER_ACTIVATED" : "USER_DEACTIVATED", "USER", id,
                Map.of("before", before.name(), "after", status.name(), "sessionsRevoked", revoked));
        return detail(user);
    }

    @Transactional
    public UserDetail unlock(UUID id) {
        User user = load(id);
        user.unlock(Instant.now(clock), Actor.current().username());
        users.flush();
        audit.record("USER_UNLOCKED", "USER", id, Map.of());
        return detail(user);
    }

    @Transactional
    public RoleAssignmentResult setRoles(UUID id, long version, Set<String> roleCodes) {
        forbidSelf(id, "change your own roles");
        User user = loadVersioned(id, version);
        Set<String> before = assignments.roleCodes(id);
        List<Role> roleList = resolveRoles(roleCodes);
        List<SodPolicy.Conflict> warnings = checkSod(permissionsOf(roleList));
        Set<String> held = grants.heldBack(id, before, codes(roleList));
        if (!held.isEmpty()) {
            return new RoleAssignmentResult(detail(user), warnings, grants.file(id, before, codes(roleList), held));
        }
        assignments.replaceRoles(id, roleList.stream().map(Role::getId).toList(), Actor.current().username());
        user.touch(Instant.now(clock), Actor.current().username());
        users.flush();
        audit.record("USER_ROLES_CHANGED", "USER", id, Map.of("before", before, "after", codes(roleList),
                "sodWarnings", warnings.size()));
        return new RoleAssignmentResult(detail(user), warnings, null);
    }

    @Transactional
    public int revokeSessions(UUID id) {
        load(id);
        int revoked = sessions.revokeAll(id, "ADMIN_REVOKED");
        audit.record("USER_SESSIONS_REVOKED", "USER", id, Map.of("sessionsRevoked", revoked));
        return revoked;
    }

    @Transactional
    public UserDetail resetMfa(UUID id) {
        forbidSelf(id, "reset your own MFA");
        User user = load(id);
        Instant now = Instant.now(clock);
        List<MfaMethod> active = mfaMethods.findByUserIdAndStatus(id, MfaMethod.ACTIVE);
        active.forEach(m -> m.revoke(now));
        int codes = recoveryCodes.revokeAll(id, now);
        int revoked = sessions.revokeAll(id, "MFA_RESET");
        audit.record("USER_MFA_RESET", "USER", id, Map.of("methodsRevoked", active.size(), "recoveryCodesRevoked", codes,
                "sessionsRevoked", revoked));
        return detail(user);
    }

    @Transactional
    public UserDetail issueTemporaryPassword(UUID id, String temporaryPassword) {
        forbidSelf(id, "reset your own password through administration");
        User user = load(id);
        passwordPolicy.enforce(temporaryPassword, user.getUsername(), user.getEmail());
        user.changePassword(encoder.encode(temporaryPassword), true, Instant.now(clock), Actor.current().username());
        int revoked = sessions.revokeAll(id, "PASSWORD_RESET_BY_ADMIN");
        users.flush();
        audit.record("USER_TEMPORARY_PASSWORD_ISSUED", "USER", id, Map.of("sessionsRevoked", revoked));
        return detail(user);
    }

    List<SodPolicy.Conflict> checkSod(Set<String> permissions) {
        List<SodPolicy.Conflict> conflicts = SodPolicy.evaluate(permissions, catalog.sodRules());
        List<SodPolicy.Conflict> blocking = SodPolicy.blocking(conflicts);
        if (!blocking.isEmpty()) {
            throw new ApiException(ErrorCode.SOD_CONFLICT,
                    "The role combination violates segregation-of-duties rules.", Map.of("conflicts", blocking));
        }
        return conflicts;
    }

    private List<Role> resolveRoles(Set<String> roleCodes) {
        List<Role> found = roles.findByCodeIn(roleCodes);
        if (found.size() != roleCodes.size()) {
            Set<String> missing = new TreeSet<>(roleCodes);
            found.forEach(r -> missing.remove(r.getCode()));
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown role(s): " + String.join(", ", missing));
        }
        return found;
    }

    private static Set<String> permissionsOf(List<Role> roleList) {
        return roleList.stream().flatMap(r -> r.getPermissionCodes().stream()).collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> codes(List<Role> roleList) {
        return roleList.stream().map(Role::getCode).collect(Collectors.toCollection(TreeSet::new));
    }

    private User load(UUID id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("User"));
    }

    private User loadVersioned(UUID id, long version) {
        User user = load(id);
        if (user.getVersion() != version) {
            throw ApiException.staleVersion();
        }
        return user;
    }

    private static void forbidSelf(UUID id, String what) {
        if (id.equals(Actor.current().userId())) {
            throw new ApiException(ErrorCode.SELF_MODIFICATION_FORBIDDEN,
                    "You cannot " + what + ". Ask another administrator.");
        }
    }

    private UserDetail detail(User u) {
        Instant now = Instant.now(clock);
        boolean mfa = mfaMethods.findByUserIdAndMethodTypeAndStatus(u.getId(), MfaMethod.TOTP, MfaMethod.ACTIVE).isPresent();
        return new UserDetail(u.getId(), u.getUsername(), u.getDisplayName(), u.getEmail(), u.getUserType().name(),
                u.getStatus().name(), u.isLocked(now), u.getLockedUntil(), u.isMustChangePassword(), mfa,
                u.getAccessExpiresAt(), u.getLastLoginAt(), u.getPasswordChangedAt(), u.getCreatedAt(), u.getCreatedBy(),
                assignments.roleCodes(u.getId()), assignments.effectivePermissions(u.getId()),
                sessions.countActive(u.getId()), u.getVersion());
    }

    static <E extends Enum<E>> E parse(Class<E> type, String value, String field) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            Map<String, Object> props = new HashMap<>();
            props.put("errors", List.of(Map.of("field", field, "message", "unsupported value")));
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unsupported " + field + " '" + value + "'.", props);
        }
    }
}
