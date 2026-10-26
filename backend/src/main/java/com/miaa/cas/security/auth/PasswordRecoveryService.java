package com.miaa.cas.security.auth;

import com.miaa.cas.audit.Actor;
import com.miaa.cas.audit.AuditService;
import com.miaa.cas.audit.SecurityEventService;
import com.miaa.cas.audit.SecurityEventService.Severity;
import com.miaa.cas.common.crypto.SecureTokens;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.config.CasProperties;
import com.miaa.cas.identity.domain.PasswordResetToken;
import com.miaa.cas.identity.domain.PasswordResetTokenRepository;
import com.miaa.cas.identity.domain.User;
import com.miaa.cas.identity.domain.UserRepository;
import com.miaa.cas.notifications.OutboxService;
import com.miaa.cas.security.password.PasswordPolicy;
import com.miaa.cas.security.session.SessionService;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Self-service password recovery with expiring, single-use tokens delivered by e-mail through the
 * outbox. The response never reveals whether an account exists. MFA is still required at the next
 * sign-in, so a mailbox compromise alone does not grant access.
 */
@Service
public class PasswordRecoveryService {

    public static final String TEMPLATE = "PASSWORD_RESET";

    private final UserRepository users;
    private final PasswordResetTokenRepository resetTokens;
    private final OutboxService outbox;
    private final PasswordPolicy policy;
    private final PasswordEncoder encoder;
    private final SessionService sessions;
    private final AuditService audit;
    private final SecurityEventService securityEvents;
    private final CasProperties properties;
    private final Clock clock;

    public PasswordRecoveryService(UserRepository users, PasswordResetTokenRepository resetTokens, OutboxService outbox,
            PasswordPolicy policy, PasswordEncoder encoder, SessionService sessions, AuditService audit,
            SecurityEventService securityEvents, CasProperties properties, Clock clock) {
        this.users = users;
        this.resetTokens = resetTokens;
        this.outbox = outbox;
        this.policy = policy;
        this.encoder = encoder;
        this.sessions = sessions;
        this.audit = audit;
        this.securityEvents = securityEvents;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public void requestReset(String identifier) {
        Instant now = Instant.now(clock);
        users.findByIdentifier(identifier.trim()).filter(u -> u.canSignIn(now)).ifPresentOrElse(user -> {
            String raw = SecureTokens.newToken();
            resetTokens.save(new PasswordResetToken(UUID.randomUUID(), user.getId(), SecureTokens.sha256Hex(raw), now,
                    now.plus(properties.password().resetTokenTtl())));
            outbox.enqueueEmail(user.getEmail(), TEMPLATE, Map.of(
                    "displayName", user.getDisplayName(),
                    "resetUrl", properties.frontendBaseUrl() + "/reset-password#token=" + raw,
                    "expiresInMinutes", properties.password().resetTokenTtl().toMinutes()));
            securityEvents.record(new Actor(user.getId(), user.getUsername()), "PASSWORD_RESET_REQUESTED", "SUCCESS",
                    Severity.INFO, Map.of());
        }, () -> securityEvents.record(Actor.anonymous(identifier), "PASSWORD_RESET_REQUESTED", "FAILURE",
                Severity.LOW, Map.of("reason", "UNKNOWN_OR_INACTIVE_ACCOUNT")));
    }

    @Transactional
    public void reset(String rawToken, String newPassword) {
        Instant now = Instant.now(clock);
        PasswordResetToken token = resetTokens.findByHashForUpdate(SecureTokens.sha256Hex(rawToken))
                .filter(t -> t.isUsable(now))
                .orElseThrow(() -> new ApiException(ErrorCode.AUTH_TOKEN_INVALID,
                        "The reset link is invalid or has expired. Request a new one."));
        User user = users.findByIdForUpdate(token.getUserId()).filter(u -> u.canSignIn(now))
                .orElseThrow(() -> new ApiException(ErrorCode.AUTH_TOKEN_INVALID,
                        "The reset link is invalid or has expired. Request a new one."));
        policy.enforce(newPassword, user.getUsername(), user.getEmail());
        token.markUsed(now);
        user.changePassword(encoder.encode(newPassword), false, now, user.getUsername());
        user.unlock(now, user.getUsername());
        int revoked = sessions.revokeAll(user.getId(), "PASSWORD_RESET");
        audit.record(new Actor(user.getId(), user.getUsername()), "PASSWORD_RESET_COMPLETED", "USER", user.getId(),
                Map.of("sessionsRevoked", revoked));
    }
}
