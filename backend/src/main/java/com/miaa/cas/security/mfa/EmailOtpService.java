package com.miaa.cas.security.mfa;

import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.notifications.OutboxService;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * E-mail one-time codes as a second factor (TOR §4.2). A six-digit code is bound to one sign-in step (the MFA token's
 * id), valid for at most {@link #LIFETIME} and that step's own lifetime, stored as an Argon2 hash, and allows
 * {@link #MAX_ATTEMPTS} tries. Sending is limited to one code a minute and {@link #MAX_PER_HOUR} an hour per user; a new
 * code withdraws the previous one. Delivery goes through the notification outbox (SMTP in production). Off unless
 * {@code cas.mfa.email-otp.enabled} is true, because it needs AVTECH's mail relay.
 */
@Service
public class EmailOtpService {

    public static final String TEMPLATE = "mfa-email-code";
    static final Duration LIFETIME = Duration.ofMinutes(5);
    static final int MAX_ATTEMPTS = 5;
    static final int MAX_PER_HOUR = 5;

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final OutboxService outbox;
    private final boolean enabled;
    private final SecureRandom random = new SecureRandom();

    public record Sent(String maskedEmail, Instant expiresAt) {
    }

    public EmailOtpService(JdbcTemplate jdbc, PasswordEncoder encoder, OutboxService outbox,
            @Value("${cas.mfa.email-otp.enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.outbox = outbox;
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * Issues a code for this sign-in step and queues the e-mail; the caller holds the user's row lock. Refusals are
     * expected outcomes and must not mark the caller's transaction for rollback (its security events are kept).
     */
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = ApiException.class)
    public Sent send(UUID userId, String challengeId, String email, Instant stepExpiresAt, Instant now) {
        if (!enabled) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Codes by e-mail are not enabled. Use your authenticator app.");
        }
        if (email == null || email.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Your account has no e-mail address. Use your authenticator app.");
        }
        List<Timestamp> recent = jdbc.queryForList("SELECT created_at FROM mfa_email_codes WHERE user_id = ? AND created_at > ? "
                + "ORDER BY created_at DESC", Timestamp.class, userId, Timestamp.from(now.minus(Duration.ofHours(1))));
        if (!recent.isEmpty() && recent.get(0).toInstant().isAfter(now.minus(Duration.ofMinutes(1)))) {
            throw new ApiException(ErrorCode.RATE_LIMITED, "A code was sent less than a minute ago. Check your e-mail, or wait a minute.");
        }
        if (recent.size() >= MAX_PER_HOUR) {
            throw new ApiException(ErrorCode.RATE_LIMITED, "Too many codes were requested this hour. Use your authenticator app, or try later.");
        }
        jdbc.update("UPDATE mfa_email_codes SET revoked_at = ? WHERE user_id = ? AND used_at IS NULL AND revoked_at IS NULL",
                Timestamp.from(now), userId);
        String code = String.format("%06d", random.nextInt(1_000_000));
        Instant expires = now.plus(LIFETIME).isBefore(stepExpiresAt) ? now.plus(LIFETIME) : stepExpiresAt;
        jdbc.update("INSERT INTO mfa_email_codes (id, user_id, challenge_id, code_hash, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), userId, challengeId, encoder.encode(code), Timestamp.from(now), Timestamp.from(expires));
        outbox.enqueueEmail(email, TEMPLATE, Map.of("code", code, "minutes", Math.max(1, Duration.between(now, expires).toMinutes())));
        return new Sent(mask(email), expires);
    }

    /** True when the code matches the live code of this sign-in step, which is then spent; every try is counted. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean verify(UUID userId, String challengeId, String code, Instant now) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, code_hash, attempts FROM mfa_email_codes
                WHERE user_id = ? AND challenge_id = ? AND used_at IS NULL AND revoked_at IS NULL AND expires_at > ?
                ORDER BY created_at DESC LIMIT 1""", userId, challengeId, Timestamp.from(now));
        if (rows.isEmpty()) {
            return false;
        }
        Map<String, Object> row = rows.get(0);
        int attempts = ((Number) row.get("attempts")).intValue() + 1;
        boolean match = code != null && code.matches("\\d{6}") && encoder.matches(code, (String) row.get("code_hash"));
        if (match) {
            jdbc.update("UPDATE mfa_email_codes SET attempts = ?, used_at = ? WHERE id = ?", attempts, Timestamp.from(now), row.get("id"));
        } else {
            jdbc.update("UPDATE mfa_email_codes SET attempts = ?, revoked_at = CASE WHEN ? >= ? THEN ? ELSE revoked_at END WHERE id = ?",
                    attempts, attempts, MAX_ATTEMPTS, Timestamp.from(now), row.get("id"));
        }
        return match;
    }

    /** "j***@miaa.gov.ph": enough to recognise the address, not enough to harvest it. */
    static String mask(String email) {
        int at = email.indexOf('@');
        return at <= 0 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }
}
