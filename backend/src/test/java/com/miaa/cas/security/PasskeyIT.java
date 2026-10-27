package com.miaa.cas.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaa.cas.support.AbstractIntegrationTest;
import com.webauthn4j.converter.AttestationObjectConverter;
import com.webauthn4j.converter.AuthenticatorDataConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.attestation.AttestationObject;
import com.webauthn4j.data.attestation.authenticator.AAGUID;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.authenticator.AuthenticatorData;
import com.webauthn4j.data.attestation.authenticator.EC2COSEKey;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Passkeys as a second factor (TOR §4.2). The test plays the authenticator: a P-256 key pair, a "none" attestation
 * and real assertions signed over authenticatorData ‖ SHA-256(clientDataJSON), built with webauthn4j's converters.
 */
class PasskeyIT extends AbstractIntegrationTest {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final ObjectConverter CONVERTER = new ObjectConverter();
    private static final byte UP = 0x01;
    private static final byte UV = 0x04;
    private static final byte AT = 0x40;

    @Value("${cas.frontend-base-url}")
    String origin;

    /** A software authenticator holding one passkey. */
    private final class Authenticator {
        final KeyPair keys;
        final byte[] credentialId = new byte[16];
        long counter;

        Authenticator() throws Exception {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            keys = g.generateKeyPair();
            new SecureRandom().nextBytes(credentialId);
        }

        String attestation(String challenge) throws Exception {
            byte[] clientData = clientData("webauthn.create", challenge, origin);
            AttestedCredentialData acd = new AttestedCredentialData(AAGUID.ZERO, credentialId,
                    EC2COSEKey.create((ECPublicKey) keys.getPublic(), COSEAlgorithmIdentifier.ES256));
            AuthenticatorData<com.webauthn4j.data.extension.authenticator.RegistrationExtensionAuthenticatorOutput> data =
                    new AuthenticatorData<>(rpIdHash(), (byte) (UP | UV | AT), 0, acd);
            byte[] attestationObject = new AttestationObjectConverter(CONVERTER).convertToBytes(
                    new AttestationObject(data, new NoneAttestationStatement()));
            return toJson(Map.of("id", B64.encodeToString(credentialId), "rawId", B64.encodeToString(credentialId), "type", "public-key",
                    "clientExtensionResults", Map.of(), "response", Map.of("clientDataJSON", B64.encodeToString(clientData),
                            "attestationObject", B64.encodeToString(attestationObject), "transports", List.of("internal"))));
        }

        String assertion(String challenge, String withOrigin) throws Exception {
            byte[] clientData = clientData("webauthn.get", challenge, withOrigin);
            byte[] authData = new AuthenticatorDataConverter(CONVERTER).convert(
                    new AuthenticatorData<>(rpIdHash(), (byte) (UP | UV), ++counter));
            ByteArrayOutputStream signed = new ByteArrayOutputStream();
            signed.write(authData);
            signed.write(MessageDigest.getInstance("SHA-256").digest(clientData));
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initSign(keys.getPrivate());
            s.update(signed.toByteArray());
            return toJson(Map.of("id", B64.encodeToString(credentialId), "rawId", B64.encodeToString(credentialId), "type", "public-key",
                    "clientExtensionResults", Map.of(), "response", Map.of("clientDataJSON", B64.encodeToString(clientData),
                            "authenticatorData", B64.encodeToString(authData), "signature", B64.encodeToString(s.sign()))));
        }

        private byte[] rpIdHash() throws Exception {
            return MessageDigest.getInstance("SHA-256").digest(URI.create(origin).getHost().getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void aPasskeyIsAddedWithACurrentCodeAndThenSignsIn() throws Exception {
        TestUser user = createUser("pkuser", "ACCOUNTANT");
        Session session = login(user);
        allowCodeReuse(user.id());

        mvc.perform(post("/api/v1/auth/passkeys/options").header("Authorization", session.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("code", "000000")))).andExpect(status().isUnauthorized());
        JsonNode options = body(mvc.perform(post("/api/v1/auth/passkeys/options").header("Authorization", session.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("code", currentCode(user.totpSecret())))))
                .andExpect(status().isOk()).andReturn());
        assertThat(options.at("/rp/id").asText()).isEqualTo(URI.create(origin).getHost());
        assertThat(options.at("/authenticatorSelection/userVerification").asText()).isEqualTo("required");

        Authenticator device = new Authenticator();
        JsonNode added = body(register(session, "Work laptop", device.attestation(options.get("challenge").asText()))
                .andExpect(status().isCreated()).andReturn());
        assertThat(added.get("name").asText()).isEqualTo("Work laptop");
        assertThat(body(mvc.perform(get("/api/v1/auth/passkeys").header("Authorization", session.bearer())).andReturn())).hasSize(1);
        assertThat(countSecurityEvents(user.id(), "PASSKEY_REGISTERED")).isEqualTo(1);

        // Sign in: password, then the passkey instead of the code.
        JsonNode step = passwordStep(user.username(), user.password());
        assertThat(step.get("methods").toString()).contains("PASSKEY");
        String token = step.get("mfaToken").asText();
        JsonNode signIn = body(passkeyOptions(token).andExpect(status().isOk()).andReturn());
        assertThat(signIn.at("/allowCredentials/0/id").asText()).isEqualTo(B64.encodeToString(device.credentialId));
        String assertion = device.assertion(signIn.get("challenge").asText(), origin);
        JsonNode tokens = body(passkeyVerify(token, assertion).andExpect(status().isOk()).andReturn());
        assertThat(tokens.get("accessToken").asText()).isNotBlank();
        assertThat(countSecurityEvents(user.id(), "MFA_PASSKEY_USED")).isEqualTo(1);
        // The challenge is spent: the same assertion cannot be replayed.
        passkeyVerify(token, assertion).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT sign_count FROM webauthn_credentials WHERE user_id = ?", Long.class, user.id())).isEqualTo(1);
    }

    @Test
    void anAssertionFromAnotherSiteOrStepIsRefused() throws Exception {
        TestUser user = createUser("pkorigin", "ACCOUNTANT");
        Authenticator device = addPasskey(user);
        String token = passwordStep(user.username(), user.password()).get("mfaToken").asText();
        String challenge = body(passkeyOptions(token).andReturn()).get("challenge").asText();
        // Signed for a phishing site: refused and counted as a failure.
        passkeyVerify(token, device.assertion(challenge, "https://cas-miaa.example")).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT failed_login_count FROM users WHERE id = ?", Integer.class, user.id())).isEqualTo(1);
        // A challenge belongs to its own sign-in step.
        String other = passwordStep(user.username(), user.password()).get("mfaToken").asText();
        String fresh = body(passkeyOptions(token).andReturn()).get("challenge").asText();
        passkeyVerify(other, device.assertion(fresh, origin)).andExpect(status().isUnauthorized());
    }

    @Test
    void passkeysCanBeRemovedAndAnAdministratorResetWithdrawsThem() throws Exception {
        TestUser user = createUser("pkreset", "ACCOUNTANT");
        addPasskey(user);
        Session session = login(user);
        String id = body(mvc.perform(get("/api/v1/auth/passkeys").header("Authorization", session.bearer())).andReturn()).get(0).get("id").asText();
        mvc.perform(delete("/api/v1/auth/passkeys/" + id).header("Authorization", login(createUser("pkother", "ACCOUNTANT")).bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/auth/passkeys/" + id).header("Authorization", session.bearer())).andExpect(status().isNoContent());
        assertThat(passwordStep(user.username(), user.password()).get("methods").toString()).doesNotContain("PASSKEY");

        addPasskey(user);
        Session admin = login(createUser("pkadmin", "SYSTEM_ADMINISTRATOR"));
        mvc.perform(post("/api/v1/users/{id}/mfa/reset", user.id()).header("Authorization", admin.bearer())).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webauthn_credentials WHERE user_id = ? AND revoked_at IS NULL", Long.class,
                user.id())).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private Authenticator addPasskey(TestUser user) throws Exception {
        Session session = login(user);
        allowCodeReuse(user.id());
        String challenge = body(mvc.perform(post("/api/v1/auth/passkeys/options").header("Authorization", session.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("code", currentCode(user.totpSecret())))))
                .andExpect(status().isOk()).andReturn()).get("challenge").asText();
        Authenticator device = new Authenticator();
        register(session, "Phone", device.attestation(challenge)).andExpect(status().isCreated());
        allowCodeReuse(user.id());
        return device;
    }

    private ResultActions register(Session session, String name, String credentialJson) throws Exception {
        return mvc.perform(post("/api/v1/auth/passkeys").header("Authorization", session.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":" + toJson(name) + ",\"credential\":" + credentialJson + "}"));
    }

    private ResultActions passkeyOptions(String mfaToken) throws Exception {
        return mvc.perform(post("/api/v1/auth/mfa/passkey/options").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("mfaToken", mfaToken))));
    }

    private ResultActions passkeyVerify(String mfaToken, String credentialJson) throws Exception {
        return mvc.perform(post("/api/v1/auth/mfa/passkey/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"mfaToken\":" + toJson(mfaToken) + ",\"credential\":" + credentialJson + "}"));
    }

    private static byte[] clientData(String type, String challenge, String origin) {
        return ("{\"type\":\"" + type + "\",\"challenge\":\"" + challenge + "\",\"origin\":\"" + origin + "\",\"crossOrigin\":false}")
                .getBytes(StandardCharsets.UTF_8);
    }
}
