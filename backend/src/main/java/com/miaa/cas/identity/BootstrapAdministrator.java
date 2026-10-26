package com.miaa.cas.identity;

import com.miaa.cas.audit.Actor;
import com.miaa.cas.audit.AuditService;
import com.miaa.cas.config.CasProperties;
import com.miaa.cas.identity.domain.Role;
import com.miaa.cas.identity.domain.RoleRepository;
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
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first System Administrator from {@code CAS_BOOTSTRAP_ADMIN_*} when no active
 * administrator exists. The password is temporary (must be changed) and MFA enrolment is required at
 * first sign-in. Remove the bootstrap variables from the environment once the account exists.
 */
@Component
public class BootstrapAdministrator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdministrator.class);
    static final String ADMIN_ROLE = "SYSTEM_ADMINISTRATOR";

    private final CasProperties properties;
    private final UserRepository users;
    private final RoleRepository roles;
    private final RoleAssignments assignments;
    private final PasswordPolicy policy;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final Clock clock;

    public BootstrapAdministrator(CasProperties properties, UserRepository users, RoleRepository roles,
            RoleAssignments assignments, PasswordPolicy policy, PasswordEncoder encoder, AuditService audit, Clock clock) {
        this.properties = properties;
        this.users = users;
        this.roles = roles;
        this.assignments = assignments;
        this.policy = policy;
        this.encoder = encoder;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        CasProperties.Bootstrap cfg = properties.bootstrap();
        if (cfg.adminUsername() == null || cfg.adminUsername().isBlank()) {
            return;
        }
        if (assignments.anyActiveUserHasRole(ADMIN_ROLE) || users.existsByUsername(cfg.adminUsername())) {
            log.info("Bootstrap administrator not created: an administrator or the username already exists");
            return;
        }
        policy.enforce(cfg.adminPassword(), cfg.adminUsername(), cfg.adminEmail());
        Role role = roles.findByCode(ADMIN_ROLE).orElseThrow();
        Instant now = Instant.now(clock);
        User user = users.saveAndFlush(new User(UUID.randomUUID(), cfg.adminUsername(), cfg.adminEmail(),
                "System Administrator", UserType.INTERNAL, encoder.encode(cfg.adminPassword()), true, now, "system"));
        assignments.replaceRoles(user.getId(), List.of(role.getId()), "system");
        audit.record(Actor.SYSTEM, "BOOTSTRAP_ADMIN_CREATED", "USER", user.getId(), Map.of("username", user.getUsername()));
        log.warn("Bootstrap administrator '{}' created. Remove CAS_BOOTSTRAP_ADMIN_* from the environment.",
                user.getUsername());
    }
}
