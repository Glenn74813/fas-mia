package com.miaa.cas.security.mfa;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-time recovery codes that stand in for the authenticator when it is lost (MS §9.2). Ten codes of ten characters
 * (50 bits each) from an alphabet without look-alike characters, shown once and stored as Argon2 hashes. Callers
 * hold the user's row lock, which serialises issuing and spending.
 */
@Service
public class RecoveryCodeService {

    public static final int CODES_PER_SET = 10;
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 10;

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final SecureRandom random = new SecureRandom();

    public RecoveryCodeService(JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    /** Withdraws any unused codes and issues a new set; the plain codes are returned once and never stored. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<String> issue(UUID userId, Instant now) {
        revokeAll(userId, now);
        List<String> codes = new ArrayList<>(CODES_PER_SET);
        for (int i = 0; i < CODES_PER_SET; i++) {
            String code = newCode();
            jdbc.update("INSERT INTO mfa_recovery_codes (id, user_id, code_hash, created_at) VALUES (?, ?, ?, ?)",
                    UUID.randomUUID(), userId, encoder.encode(code), Timestamp.from(now));
            codes.add(code.substring(0, 5) + "-" + code.substring(5));
        }
        return codes;
    }

    /** Spends the matching unused code; false when none matches. Spaces, hyphens and letter case are ignored. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean consume(UUID userId, String code, Instant now) {
        String normalised = normalise(code);
        if (normalised.length() != CODE_LENGTH) {
            return false;
        }
        List<Unused> unused = jdbc.query("""
                SELECT id, code_hash FROM mfa_recovery_codes
                WHERE user_id = ? AND used_at IS NULL AND revoked_at IS NULL
                """, (rs, i) -> new Unused(rs.getObject(1, UUID.class), rs.getString(2)), userId);
        for (Unused u : unused) {
            if (encoder.matches(normalised, u.hash())) {
                jdbc.update("UPDATE mfa_recovery_codes SET used_at = ? WHERE id = ? AND used_at IS NULL",
                        Timestamp.from(now), u.id());
                return true;
            }
        }
        return false;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int revokeAll(UUID userId, Instant now) {
        return jdbc.update("UPDATE mfa_recovery_codes SET revoked_at = ? WHERE user_id = ? AND used_at IS NULL AND revoked_at IS NULL",
                Timestamp.from(now), userId);
    }

    @Transactional(readOnly = true)
    public int remaining(UUID userId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM mfa_recovery_codes WHERE user_id = ? AND used_at IS NULL AND revoked_at IS NULL",
                Integer.class, userId);
        return n == null ? 0 : n;
    }

    static String normalise(String code) {
        return code == null ? "" : code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    private String newCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private record Unused(UUID id, String hash) {
    }
}
