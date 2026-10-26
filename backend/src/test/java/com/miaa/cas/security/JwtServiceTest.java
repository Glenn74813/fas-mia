package com.miaa.cas.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.security.jwt.JwtKeyProvider;
import com.miaa.cas.security.jwt.TokenService;
import com.miaa.cas.security.jwt.TokenService.MfaPurpose;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

class JwtServiceTest {

    private final JwtKeyProvider keys = new JwtKeyProvider(TestProperties.defaults());
    private final TokenService tokens = new TokenService(TestProperties.defaults(), keys, Clock.systemUTC());

    @Test
    void accessTokenHasMinimalValidatedClaims() {
        UUID user = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        Jwt jwt = tokens.apiDecoder().decode(tokens.issueAccessToken(user, session).value());
        assertThat(jwt.getSubject()).isEqualTo(user.toString());
        assertThat(jwt.getClaimAsString("sid")).isEqualTo(session.toString());
        assertThat(jwt.getAudience()).containsExactly("cas-api");
        assertThat(jwt.getId()).isNotBlank();
        assertThat(jwt.getHeaders()).containsEntry("alg", "RS256").containsKey("kid");
        assertThat(jwt.getClaims().keySet()).containsExactlyInAnyOrder("iss", "aud", "sub", "jti", "iat", "nbf", "exp", "sid");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void mfaTokenRejectedAsAccessTokenAndViceVersa() {
        String mfa = tokens.issueMfaToken(UUID.randomUUID(), MfaPurpose.VERIFY);
        assertThatThrownBy(() -> tokens.apiDecoder().decode(mfa)).isInstanceOf(JwtException.class);
        String access = tokens.issueAccessToken(UUID.randomUUID(), UUID.randomUUID()).value();
        assertThatThrownBy(() -> tokens.parseMfaToken(access, MfaPurpose.VERIFY)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> tokens.parseMfaToken(mfa, MfaPurpose.ENROLL)).isInstanceOf(ApiException.class);
        assertThat(tokens.parseMfaToken(mfa, MfaPurpose.VERIFY).purpose()).isEqualTo(MfaPurpose.VERIFY);
    }

    @Test
    void tamperedAndForeignTokensRejected() {
        String token = tokens.issueAccessToken(UUID.randomUUID(), UUID.randomUUID()).value();
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "AA." + parts[2];
        assertThatThrownBy(() -> tokens.apiDecoder().decode(tampered)).isInstanceOf(JwtException.class);

        TokenService other = new TokenService(TestProperties.defaults(), new JwtKeyProvider(TestProperties.defaults()),
                Clock.systemUTC());
        String foreign = other.issueAccessToken(UUID.randomUUID(), UUID.randomUUID()).value();
        assertThatThrownBy(() -> tokens.apiDecoder().decode(foreign)).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> tokens.apiDecoder().decode("eyJhbGciOiJub25lIn0.eyJzdWIiOiJ4In0."))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void expiredTokenRejected() {
        Clock past = Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC);
        TokenService issuer = new TokenService(TestProperties.defaults(), keys, past);
        String old = issuer.issueAccessToken(UUID.randomUUID(), UUID.randomUUID()).value();
        assertThatThrownBy(() -> tokens.apiDecoder().decode(old)).isInstanceOf(JwtException.class);
    }
}
