package com.miaa.cas.security.mfa;

import com.miaa.cas.common.error.ApiException;
import com.miaa.cas.common.error.ErrorCode;
import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.AttestedCredentialDataConverter;
import com.webauthn4j.converter.exception.DataConversionException;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.credential.CredentialRecordImpl;
import com.webauthn4j.data.AuthenticationData;
import com.webauthn4j.data.AuthenticationParameters;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.RegistrationData;
import com.webauthn4j.data.RegistrationParameters;
import com.webauthn4j.data.attestation.authenticator.AuthenticatorData;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.server.ServerProperty;
import com.webauthn4j.verifier.exception.VerificationException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Passkeys (WebAuthn Level 2) as a second factor (TOR §4.2, biometric login), verified by webauthn4j. Registration and
 * authentication require user verification (biometric or device PIN); attestation is not requested ("none"), so no
 * authenticator vendor is trusted or excluded. Challenges are 32 random bytes, single use, valid for
 * {@link #CHALLENGE_LIFETIME}; an authentication challenge is bound to one sign-in step. Accepted origins and the
 * relying-party id come from {@code cas.webauthn.*}, defaulting to the frontend address.
 */
@Service
public class PasskeyService {

    static final Duration CHALLENGE_LIFETIME = Duration.ofMinutes(5);
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final List<PublicKeyCredentialParameters> ALGORITHMS = List.of(
            new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256),
            new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.RS256));

    public record Passkey(UUID id, String name, Instant createdAt, Instant lastUsedAt) {
    }

    private final JdbcTemplate jdbc;
    private final WebAuthnManager manager = WebAuthnManager.createNonStrictWebAuthnManager();
    private final AttestedCredentialDataConverter credentialData = new AttestedCredentialDataConverter(new ObjectConverter());
    private final SecureRandom random = new SecureRandom();
    private final Set<Origin> origins;
    private final String rpId;
    private final String rpName;

    public PasskeyService(JdbcTemplate jdbc, @Value("${cas.webauthn.origins:${cas.frontend-base-url}}") String origins,
            @Value("${cas.webauthn.rp-id:}") String rpId, @Value("${cas.webauthn.rp-name:MIAA CAS}") String rpName) {
        this.jdbc = jdbc;
        List<String> list = Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> s.replaceAll("/+$", "")).toList();
        this.origins = list.stream().map(Origin::new).collect(Collectors.toUnmodifiableSet());
        this.rpId = rpId == null || rpId.isBlank() ? URI.create(list.get(0)).getHost() : rpId.trim();
        this.rpName = rpName;
    }

    public boolean has(UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM webauthn_credentials WHERE user_id = ? AND revoked_at IS NULL)", Boolean.class, userId));
    }

    @Transactional(readOnly = true)
    public List<Passkey> list(UUID userId) {
        return jdbc.query("SELECT id, name, created_at, last_used_at FROM webauthn_credentials WHERE user_id = ? AND revoked_at IS NULL "
                + "ORDER BY created_at", (rs, i) -> new Passkey(rs.getObject(1, UUID.class), rs.getString(2), rs.getTimestamp(3).toInstant(),
                rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant()), userId);
    }

    /** Options for navigator.credentials.create (JSON form, binary fields base64url). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> registrationOptions(UUID userId, String username, String displayName, Instant now) {
        byte[] challenge = newChallenge(userId, "REGISTER", null, now);
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("challenge", B64.encodeToString(challenge));
        o.put("rp", Map.of("id", rpId, "name", rpName));
        o.put("user", Map.of("id", B64.encodeToString(uuidBytes(userId)), "name", username, "displayName", displayName));
        o.put("pubKeyCredParams", List.of(Map.of("type", "public-key", "alg", -7), Map.of("type", "public-key", "alg", -257)));
        o.put("timeout", CHALLENGE_LIFETIME.toMillis());
        o.put("attestation", "none");
        o.put("authenticatorSelection", Map.of("residentKey", "preferred", "userVerification", "required"));
        o.put("excludeCredentials", credentialIds(userId).stream().map(id -> Map.of("type", "public-key", "id", B64.encodeToString(id))).toList());
        return o;
    }

    /** Verifies and stores a new passkey; the registration challenge is spent. */
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = ApiException.class)
    public Passkey register(UUID userId, String name, String responseJson, Instant now) {
        byte[] challenge = takeChallenge(userId, "REGISTER", null, now);
        RegistrationData data;
        try {
            data = manager.verifyRegistrationResponseJSON(responseJson,
                    new RegistrationParameters(new ServerProperty(origins, rpId, new DefaultChallenge(challenge)), ALGORITHMS, true, true));
        } catch (DataConversionException | VerificationException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The passkey could not be verified: " + e.getMessage());
        }
        AuthenticatorData<?> auth = data.getAttestationObject().getAuthenticatorData();
        byte[] credentialId = auth.getAttestedCredentialData().getCredentialId();
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM webauthn_credentials WHERE credential_id = ?)",
                Boolean.class, (Object) credentialId))) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "This passkey is already registered.");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO webauthn_credentials (id, user_id, credential_id, attested_credential_data, sign_count, uv_initialized,
                    backup_eligible, backup_state, name, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, userId, credentialId, credentialData.convert(auth.getAttestedCredentialData()), auth.getSignCount(), auth.isFlagUV(),
                auth.isFlagBE(), auth.isFlagBS(), name.trim(), Timestamp.from(now));
        return new Passkey(id, name.trim(), now, null);
    }

    /** Options for navigator.credentials.get at the sign-in step (the user's passkeys only). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> authenticationOptions(UUID userId, String signInStep, Instant now) {
        byte[] challenge = newChallenge(userId, "AUTHENTICATE", signInStep, now);
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("challenge", B64.encodeToString(challenge));
        o.put("rpId", rpId);
        o.put("timeout", CHALLENGE_LIFETIME.toMillis());
        o.put("userVerification", "required");
        o.put("allowCredentials", credentialIds(userId).stream().map(id -> Map.of("type", "public-key", "id", B64.encodeToString(id))).toList());
        return o;
    }

    /** True when the assertion verifies against one of the user's passkeys for this sign-in step; the challenge is spent. */
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = ApiException.class)
    public boolean authenticate(UUID userId, String signInStep, String responseJson, Instant now) {
        byte[] challenge;
        try {
            challenge = takeChallenge(userId, "AUTHENTICATE", signInStep, now);
        } catch (ApiException e) {
            return false;
        }
        try {
            AuthenticationData parsed = manager.parseAuthenticationResponseJSON(responseJson);
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT id, attested_credential_data, sign_count, uv_initialized, backup_eligible, backup_state
                    FROM webauthn_credentials WHERE user_id = ? AND credential_id = ? AND revoked_at IS NULL""",
                    userId, parsed.getCredentialId());
            if (rows.isEmpty()) {
                return false;
            }
            Map<String, Object> r = rows.get(0);
            CredentialRecordImpl record = new CredentialRecordImpl(new NoneAttestationStatement(), (Boolean) r.get("uv_initialized"),
                    (Boolean) r.get("backup_eligible"), (Boolean) r.get("backup_state"), ((Number) r.get("sign_count")).longValue(),
                    credentialData.convert((byte[]) r.get("attested_credential_data")), null, null, null, null);
            AuthenticationData verified = manager.verify(parsed, new AuthenticationParameters(
                    new ServerProperty(origins, rpId, new DefaultChallenge(challenge)), record, credentialIds(userId), true, true));
            long count = verified.getAuthenticatorData().getSignCount();
            jdbc.update("UPDATE webauthn_credentials SET sign_count = GREATEST(sign_count, ?), backup_state = ?, last_used_at = ? WHERE id = ?",
                    count, verified.getAuthenticatorData().isFlagBS(), Timestamp.from(now), r.get("id"));
            return true;
        } catch (DataConversionException | VerificationException | IllegalArgumentException e) {
            return false;
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean revoke(UUID userId, UUID passkeyId, Instant now) {
        return jdbc.update("UPDATE webauthn_credentials SET revoked_at = ? WHERE id = ? AND user_id = ? AND revoked_at IS NULL",
                Timestamp.from(now), passkeyId, userId) == 1;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int revokeAll(UUID userId, Instant now) {
        return jdbc.update("UPDATE webauthn_credentials SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL", Timestamp.from(now), userId);
    }

    private List<byte[]> credentialIds(UUID userId) {
        return jdbc.queryForList("SELECT credential_id FROM webauthn_credentials WHERE user_id = ? AND revoked_at IS NULL", byte[].class, userId);
    }

    private byte[] newChallenge(UUID userId, String purpose, String boundTo, Instant now) {
        byte[] challenge = new byte[32];
        random.nextBytes(challenge);
        jdbc.update("UPDATE webauthn_challenges SET used_at = ? WHERE user_id = ? AND purpose = ? AND used_at IS NULL",
                Timestamp.from(now), userId, purpose);
        jdbc.update("INSERT INTO webauthn_challenges (id, user_id, purpose, challenge, bound_to, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), userId, purpose, challenge, boundTo, Timestamp.from(now), Timestamp.from(now.plus(CHALLENGE_LIFETIME)));
        return challenge;
    }

    private byte[] takeChallenge(UUID userId, String purpose, String boundTo, Instant now) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, challenge FROM webauthn_challenges
                WHERE user_id = ? AND purpose = ? AND used_at IS NULL AND expires_at > ? AND bound_to IS NOT DISTINCT FROM ?
                ORDER BY created_at DESC LIMIT 1 FOR UPDATE""", userId, purpose, Timestamp.from(now), boundTo);
        if (rows.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_STATE, "The passkey request has expired. Start again.");
        }
        jdbc.update("UPDATE webauthn_challenges SET used_at = ? WHERE id = ?", Timestamp.from(now), rows.get(0).get("id"));
        return (byte[]) rows.get(0).get("challenge");
    }

    static byte[] uuidBytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    static byte[] decode(String b64url) {
        return B64D.decode(b64url);
    }
}
