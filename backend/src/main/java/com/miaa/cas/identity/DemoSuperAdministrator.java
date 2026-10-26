package com.miaa.cas.identity;

import com.miaa.cas.audit.Actor;
import com.miaa.cas.audit.AuditService;
import com.miaa.cas.identity.domain.User;
import com.miaa.cas.identity.domain.UserRepository;
import com.miaa.cas.identity.domain.UserType;
import com.miaa.cas.security.password.PasswordPolicy;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * DEMONSTRATION ONLY (dev profile): a "super administrator" holding every internal permission, so one account can show
 * the whole system. Created from {@code CAS_DEMO_SUPERADMIN_*} when the username does not exist yet; on every dev start
 * the role is topped up with permissions added by later migrations.
 *
 * <p>The role deliberately breaks the segregation-of-duties rules, so it is written directly here: role and user
 * administration would refuse to create it, change its permissions or grant it to anyone, for the same reason.
 * Record-level controls still apply to this account: it cannot approve or post what it prepared itself, and it still
 * signs in with an authenticator code. PORTAL_SELF_VIEW is left out because it marks a concessionaire, which would
 * hide the internal dashboard and inbox.
 */
@Component
@Profile("dev")
public class DemoSuperAdministrator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSuperAdministrator.class);
    static final String ROLE = "DEMO_SUPERADMIN";

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final RoleAssignments assignments;
    private final PasswordPolicy policy;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final Clock clock;
    private final String username;
    private final String email;
    private final String password;

    public DemoSuperAdministrator(JdbcTemplate jdbc, UserRepository users, RoleAssignments assignments, PasswordPolicy policy,
            PasswordEncoder encoder, AuditService audit, Clock clock,
            @Value("${cas.demo.superadmin.username:}") String username,
            @Value("${cas.demo.superadmin.email:}") String email,
            @Value("${cas.demo.superadmin.password:}") String password) {
        this.jdbc = jdbc;
        this.users = users;
        this.assignments = assignments;
        this.policy = policy;
        this.encoder = encoder;
        this.audit = audit;
        this.clock = clock;
        this.username = username;
        this.email = email;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (username == null || username.isBlank()) {
            return;
        }
        UUID roleId = ensureRole();
        if (users.existsByUsername(username)) {
            return;
        }
        policy.enforce(password, username, email);
        Instant now = Instant.now(clock);
        User user = users.saveAndFlush(new User(UUID.randomUUID(), username, email, "Demo Super Administrator", UserType.INTERNAL,
                encoder.encode(password), false, now, "system"));
        assignments.replaceRoles(user.getId(), List.of(roleId), "system");
        audit.record(Actor.SYSTEM, "DEMO_SUPERADMIN_CREATED", "USER", user.getId(), Map.of("username", username));
        log.warn("Demo super administrator '{}' created with every internal permission. Demonstration use only.", username);
    }

    private UUID ensureRole() {
        List<UUID> existing = jdbc.queryForList("SELECT id FROM roles WHERE code = ?", UUID.class, ROLE);
        UUID roleId = existing.isEmpty() ? UUID.randomUUID() : existing.get(0);
        if (existing.isEmpty()) {
            jdbc.update("""
                    INSERT INTO roles (id, code, name, description, system_role, created_by, updated_by)
                    VALUES (?, ?, 'Demo Super Administrator', ?, TRUE, 'system', 'system')
                    """, roleId, ROLE, "DEMONSTRATION ONLY: every internal permission, ignoring segregation of duties. Never use with real data.");
        }
        int added = jdbc.update("""
                INSERT INTO role_permissions (role_id, permission_code)
                SELECT ?, code FROM permissions WHERE code <> 'PORTAL_SELF_VIEW'
                ON CONFLICT DO NOTHING
                """, roleId);
        if (added > 0) {
            audit.record(Actor.SYSTEM, "DEMO_SUPERADMIN_ROLE_SYNCED", "ROLE", roleId, Map.of("permissionsAdded", added));
        }
        return roleId;
    }
}
