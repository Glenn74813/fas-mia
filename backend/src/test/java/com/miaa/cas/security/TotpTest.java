package com.miaa.cas.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.miaa.cas.security.mfa.Totp;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TotpTest {

    // RFC 6238 Appendix B, SHA-1 seed; 6-digit codes are the last six digits of the published values.
    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void matchesRfc6238Vectors() {
        assertThat(Totp.code(RFC_SECRET, Totp.stepAt(Instant.ofEpochSecond(59)))).isEqualTo("287082");
        assertThat(Totp.code(RFC_SECRET, Totp.stepAt(Instant.ofEpochSecond(1111111109)))).isEqualTo("081804");
        assertThat(Totp.code(RFC_SECRET, Totp.stepAt(Instant.ofEpochSecond(1234567890)))).isEqualTo("005924");
        assertThat(Totp.code(RFC_SECRET, Totp.stepAt(Instant.ofEpochSecond(20000000000L)))).isEqualTo("353130");
    }

    @Test
    void acceptsAdjacentStepAndRejectsReplay() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        long step = Totp.stepAt(now);
        String previous = Totp.code(RFC_SECRET, step - 1);
        assertThat(Totp.verify(RFC_SECRET, previous, now, null, 1)).hasValue(step - 1);
        assertThat(Totp.verify(RFC_SECRET, previous, now, step - 1, 1)).isEmpty();
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step - 2), now, null, 1)).isEmpty();
    }

    @Test
    void rejectsMalformedCodes() {
        Instant now = Instant.now();
        assertThat(Totp.verify(RFC_SECRET, null, now, null, 1)).isEmpty();
        assertThat(Totp.verify(RFC_SECRET, "12345", now, null, 1)).isEmpty();
        assertThat(Totp.verify(RFC_SECRET, "abcdef", now, null, 1)).isEmpty();
    }

    @Test
    void buildsOtpauthUri() {
        assertThat(Totp.otpauthUri("AVTECH CAS", "j.cruz", "ABC"))
                .isEqualTo("otpauth://totp/AVTECH%20CAS:j.cruz?secret=ABC&issuer=AVTECH%20CAS&algorithm=SHA1&digits=6&period=30");
    }
}
