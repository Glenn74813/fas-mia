package com.miaa.cas.security.mfa;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.codec.binary.Base32;

/** RFC 6238 TOTP (HMAC-SHA1, 6 digits, 30-second step) compatible with common authenticator apps. */
public final class Totp {

    public static final int DIGITS = 6;
    public static final long STEP_SECONDS = 30;
    private static final int SECRET_BYTES = 20;

    private Totp() {
    }

    public static byte[] newSecret() {
        return com.miaa.cas.common.crypto.SecureTokens.randomBytes(SECRET_BYTES);
    }

    public static String toBase32(byte[] secret) {
        return new Base32().encodeToString(secret).replace("=", "");
    }

    public static long stepAt(Instant instant) {
        return Math.floorDiv(instant.getEpochSecond(), STEP_SECONDS);
    }

    public static String code(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return String.format("%0" + DIGITS + "d", binary % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 unavailable", e);
        }
    }

    /**
     * Verifies {@code code} within +/- {@code window} steps of {@code now}. Steps at or before
     * {@code lastUsedStep} are rejected so a code can never be replayed.
     *
     * @return the matched step, or empty when the code is invalid or replayed
     */
    public static OptionalLong verify(byte[] secret, String code, Instant now, Long lastUsedStep, int window) {
        if (code == null || !code.matches("\\d{" + DIGITS + "}")) {
            return OptionalLong.empty();
        }
        long current = stepAt(now);
        for (long step = current - window; step <= current + window; step++) {
            if (lastUsedStep != null && step <= lastUsedStep) {
                continue;
            }
            if (MessageDigest.isEqual(code(secret, step).getBytes(StandardCharsets.US_ASCII), code.getBytes(StandardCharsets.US_ASCII))) {
                return OptionalLong.of(step);
            }
        }
        return OptionalLong.empty();
    }

    public static String otpauthUri(String issuer, String account, String base32Secret) {
        String i = java.net.URLEncoder.encode(issuer, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        String a = java.net.URLEncoder.encode(account, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        return "otpauth://totp/" + i + ":" + a + "?secret=" + base32Secret + "&issuer=" + i
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }
}
