package com.miaa.cas.security.jwt;

import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.miaa.cas.config.CasProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

/**
 * Issues and validates CAS JWTs. Two audiences are used: {@code cas-api} for access tokens and
 * {@code cas-mfa} for the short-lived challenge token between password and second-factor steps.
 * Only RS256 is accepted; issuer, audience, expiry and not-before are always validated.
 */
@Service
public class TokenService {

    public static final String API_AUDIENCE = "cas-api";
    public static final String MFA_AUDIENCE = "cas-mfa";
    public static final String CLAIM_SESSION = "sid";
    public static final String CLAIM_PURPOSE = "purpose";

    public enum MfaPurpose { VERIFY, ENROLL }

    public record MfaChallenge(UUID userId, MfaPurpose purpose, String challengeId, Instant expiresAt) {
    }

    public record AccessToken(String value, Instant expiresAt) {
    }

    private final CasProperties.Jwt cfg;
    private final Clock clock;
    private final JwtEncoder encoder;
    private final String keyId;
    private final JwtDecoder apiDecoder;
    private final JwtDecoder mfaDecoder;

    public TokenService(CasProperties properties, JwtKeyProvider keys, Clock clock) {
        this.cfg = properties.jwt();
        this.clock = clock;
        RSAKey signing = keys.signingKey();
        this.keyId = signing.getKeyID();
        this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signing)));
        this.apiDecoder = decoder(keys.verificationKeys(), API_AUDIENCE);
        this.mfaDecoder = decoder(keys.verificationKeys(), MFA_AUDIENCE);
    }

    public JwtDecoder apiDecoder() {
        return apiDecoder;
    }

    public AccessToken issueAccessToken(UUID userId, UUID sessionId) {
        Instant now = Instant.now(clock);
        Instant exp = now.plus(cfg.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(cfg.issuer())
                .audience(List.of(API_AUDIENCE))
                .subject(userId.toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .notBefore(now)
                .expiresAt(exp)
                .claim(CLAIM_SESSION, sessionId.toString())
                .build();
        return new AccessToken(encode(claims), exp);
    }

    public String issueMfaToken(UUID userId, MfaPurpose purpose) {
        Instant now = Instant.now(clock);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(cfg.issuer())
                .audience(List.of(MFA_AUDIENCE))
                .subject(userId.toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .notBefore(now)
                .expiresAt(now.plus(cfg.mfaTokenTtl()))
                .claim(CLAIM_PURPOSE, purpose.name())
                .build();
        return encode(claims);
    }

    public MfaChallenge parseMfaToken(String token, MfaPurpose expected) {
        try {
            Jwt jwt = mfaDecoder.decode(token);
            String claim = jwt.getClaimAsString(CLAIM_PURPOSE);
            if (claim == null || jwt.getSubject() == null) {
                throw new IllegalArgumentException("Missing claims");
            }
            MfaPurpose purpose = MfaPurpose.valueOf(claim);
            if (purpose != expected) {
                throw new ApiException(ErrorCode.AUTH_TOKEN_INVALID, "The sign-in step is not valid. Start again.");
            }
            return new MfaChallenge(UUID.fromString(jwt.getSubject()), purpose, jwt.getId(), jwt.getExpiresAt());
        } catch (JwtException | IllegalArgumentException e) {
            throw new ApiException(ErrorCode.AUTH_TOKEN_INVALID, "The sign-in step has expired or is invalid. Start again.");
        }
    }

    private String encode(JwtClaimsSet claims) {
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).type("JWT").build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private JwtDecoder decoder(List<RSAKey> verificationKeys, String audience) {
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256,
                new ImmutableJWKSet<>(new JWKSet(List.<JWK>copyOf(verificationKeys)))));
        // Claim validation is done by Spring's validators below; Nimbus only verifies the signature.
        processor.setJWTClaimsSetVerifier((claims, context) -> { });
        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        JwtTimestampValidator timestamps = new JwtTimestampValidator(java.time.Duration.ofSeconds(30));
        timestamps.setClock(clock);
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(timestamps,
                new JwtIssuerValidator(cfg.issuer()), new JwtAudienceValidator(audience));
        decoder.setJwtValidator(validator);
        return decoder;
    }
}
