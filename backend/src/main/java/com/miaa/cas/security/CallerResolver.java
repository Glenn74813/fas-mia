package com.miaa.cas.security;

import com.miaa.cas.identity.RoleAssignments;
import com.miaa.cas.identity.domain.User;
import com.miaa.cas.identity.domain.UserRepository;
import com.miaa.cas.identity.domain.UserSession;
import com.miaa.cas.identity.domain.UserSessionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves a token's subject and session into live authorities on every request, so deactivation,
 * session revocation and role changes take effect immediately (ADR-002).
 */
@Service
public class CallerResolver {

    public static final String PASSWORD_CHANGE_REQUIRED = "PASSWORD_CHANGE_REQUIRED";

    public record Caller(CasPrincipal principal, List<GrantedAuthority> authorities) {
    }

    private final UserRepository users;
    private final UserSessionRepository sessions;
    private final RoleAssignments roleAssignments;
    private final Clock clock;

    public CallerResolver(UserRepository users, UserSessionRepository sessions, RoleAssignments roleAssignments,
            Clock clock) {
        this.users = users;
        this.sessions = sessions;
        this.roleAssignments = roleAssignments;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<Caller> resolve(UUID userId, UUID sessionId) {
        Instant now = Instant.now(clock);
        Optional<UserSession> session = sessions.findById(sessionId)
                .filter(s -> s.getUserId().equals(userId) && s.isActive(now));
        if (session.isEmpty()) {
            return Optional.empty();
        }
        Optional<User> user = users.findById(userId).filter(u -> u.canSignIn(now));
        if (user.isEmpty()) {
            return Optional.empty();
        }
        List<GrantedAuthority> authorities = new ArrayList<>();
        if (user.get().isMustChangePassword()) {
            authorities.add(new SimpleGrantedAuthority(PASSWORD_CHANGE_REQUIRED));
        } else {
            roleAssignments.roleCodes(userId).forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
            roleAssignments.effectivePermissions(userId)
                    .forEach(p -> authorities.add(new SimpleGrantedAuthority("PERM_" + p)));
        }
        return Optional.of(new Caller(new CasPrincipal(userId, user.get().getUsername(), sessionId), authorities));
    }
}
