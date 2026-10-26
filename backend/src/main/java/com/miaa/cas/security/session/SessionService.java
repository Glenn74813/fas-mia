package com.miaa.cas.security.session;

import com.miaa.cas.audit.Actor;
import com.miaa.cas.audit.SecurityEventService;
import com.miaa.cas.audit.SecurityEventService.Severity;
import com.miaa.cas.common.crypto.SecureTokens;
import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.common.web.ClientInfo;
import com.miaa.cas.config.CasProperties;
import com.miaa.cas.identity.domain.RefreshToken;
import com.miaa.cas.identity.domain.RefreshTokenRepository;
import com.miaa.cas.identity.domain.User;
import com.miaa.cas.identity.domain.UserRepository;
import com.miaa.cas.identity.domain.UserSession;
import com.miaa.cas.identity.domain.UserSessionRepository;
import com.miaa.cas.security.jwt.TokenService;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Session lifecycle: creation after successful MFA, refresh-token rotation with reuse detection,
 * logout and administrative revocation. See docs/05-security-design.md section 2.
 */
@Service
public class SessionService {

    public record IssuedTokens(TokenService.AccessToken accessToken, String refreshToken, Instant refreshExpiresAt,
            UUID userId, UUID refreshTokenId) {
    }

    private final UserSessionRepository sessions;
    private final RefreshTokenRepository refreshTokens;
    private final UserRepository users;
    private final TokenService tokens;
    private final SecurityEventService securityEvents;
    private final CasProperties.Session cfg;
    private final Clock clock;

    public SessionService(UserSessionRepository sessions, RefreshTokenRepository refreshTokens, UserRepository users,
            TokenService tokens, SecurityEventService securityEvents, CasProperties properties, Clock clock) {
        this.sessions = sessions;
        this.refreshTokens = refreshTokens;
        this.users = users;
        this.tokens = tokens;
        this.securityEvents = securityEvents;
        this.cfg = properties.session();
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public IssuedTokens start(User user, ClientInfo client) {
        Instant now = Instant.now(clock);
        UserSession session = sessions.save(new UserSession(UUID.randomUUID(), user.getId(), now,
                now.plus(cfg.absoluteTtl()), client.ip(), client.userAgent()));
        return issue(session, now);
    }

    /** Rotates the presented refresh token. Reuse of a consumed token revokes the whole session. */
    @Transactional(noRollbackFor = ApiException.class)
    public IssuedTokens refresh(String rawToken) {
        Instant now = Instant.now(clock);
        RefreshToken token = refreshTokens.findByHashForUpdate(SecureTokens.sha256Hex(rawToken))
                .orElseThrow(SessionService::invalid);
        UserSession session = sessions.findById(token.getSessionId()).orElseThrow(SessionService::invalid);
        Actor actor = new Actor(session.getUserId(), null);
        RefreshToken retiredSuccessor = null;
        if (token.getUsedAt() != null) {
            retiredSuccessor = graceSuccessor(token, session, now);
            if (retiredSuccessor == null) {
                boolean wasActive = session.isActive(now);
                session.revoke(now, "REFRESH_TOKEN_REUSE");
                if (wasActive) {
                    securityEvents.record(actor, "REFRESH_TOKEN_REUSE_DETECTED", "DENIED", Severity.HIGH,
                            Map.of("sessionId", session.getId().toString()));
                }
                throw invalid();
            }
            securityEvents.record(actor, "REFRESH_TOKEN_GRACE_REUSE", "SUCCESS", Severity.LOW,
                    Map.of("sessionId", session.getId().toString()));
        }
        if (!session.isActive(now) || !token.getExpiresAt().isAfter(now)) {
            throw invalid();
        }
        User user = users.findById(session.getUserId()).orElseThrow(SessionService::invalid);
        if (!user.canSignIn(now)) {
            session.revoke(now, "USER_INACTIVE");
            throw invalid();
        }
        IssuedTokens issued = issue(session, now);
        if (retiredSuccessor != null) {
            retiredSuccessor.consume(now, issued.refreshTokenId());
        } else {
            token.consume(now, issued.refreshTokenId());
        }
        session.refreshed(now);
        return issued;
    }

    @Transactional
    public Optional<UUID> logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        Instant now = Instant.now(clock);
        return refreshTokens.findByHash(SecureTokens.sha256Hex(rawToken))
                .flatMap(t -> sessions.findById(t.getSessionId()))
                .map(s -> {
                    s.revoke(now, "LOGOUT");
                    return s.getUserId();
                });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int revokeAll(UUID userId, String reason) {
        Instant now = Instant.now(clock);
        var active = sessions.findActive(userId, now);
        active.forEach(s -> s.revoke(now, reason));
        return active.size();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int revokeAllExcept(UUID userId, UUID keepSessionId, String reason) {
        Instant now = Instant.now(clock);
        var active = sessions.findActive(userId, now).stream().filter(s -> !s.getId().equals(keepSessionId)).toList();
        active.forEach(s -> s.revoke(now, reason));
        return active.size();
    }

    @Transactional(readOnly = true)
    public int countActive(UUID userId) {
        return sessions.findActive(userId, Instant.now(clock)).size();
    }

    @Transactional(readOnly = true)
    public Optional<UserSession> find(UUID sessionId) {
        return sessions.findById(sessionId);
    }

    /**
     * A consumed token is tolerated once within the grace window when its successor was never used:
     * the client most likely lost the rotation response (aborted request, reload). Returns the
     * successor to retire, or null when this is genuine reuse.
     */
    private RefreshToken graceSuccessor(RefreshToken token, UserSession session, Instant now) {
        if (!session.isActive(now) || token.getReplacedById() == null
                || token.getUsedAt().plus(cfg.refreshReuseGrace()).isBefore(now)) {
            return null;
        }
        return refreshTokens.findByIdForUpdate(token.getReplacedById())
                .filter(successor -> successor.getUsedAt() == null)
                .orElse(null);
    }

    private IssuedTokens issue(UserSession session, Instant now) {
        String raw = SecureTokens.newToken();
        RefreshToken stored = refreshTokens.save(new RefreshToken(UUID.randomUUID(), session.getId(),
                SecureTokens.sha256Hex(raw), now, session.getExpiresAt()));
        return new IssuedTokens(tokens.issueAccessToken(session.getUserId(), session.getId()), raw,
                session.getExpiresAt(), session.getUserId(), stored.getId());
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.AUTH_SESSION_INVALID, "Your session has ended. Sign in again.");
    }
}
