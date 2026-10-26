package com.miaa.cas.security.jwt;

import com.miaa.cas.config.CasProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Loads the RS256 signing key pair (PKCS#8 private key, X.509 public key, PEM) from mounted secret
 * files. An optional previous public key keeps tokens signed before a rotation verifiable. Ephemeral
 * keys are only allowed when {@code cas.jwt.allow-ephemeral-keys=true} (dev/test profiles).
 */
@Component
public class JwtKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyProvider.class);

    private final RSAKey signingKey;
    private final List<RSAKey> verificationKeys;

    public JwtKeyProvider(CasProperties properties) {
        CasProperties.Jwt cfg = properties.jwt();
        try {
            if (isBlank(cfg.privateKeyPath()) || isBlank(cfg.publicKeyPath())) {
                if (!cfg.allowEphemeralKeys()) {
                    throw new IllegalStateException(
                            "JWT key pair not configured: set CAS_JWT_PRIVATE_KEY_PATH and CAS_JWT_PUBLIC_KEY_PATH");
                }
                log.warn("Using an EPHEMERAL JWT signing key (development/test only). Tokens will not survive a restart "
                        + "and will not validate across multiple instances.");
                KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
                gen.initialize(3072);
                KeyPair pair = gen.generateKeyPair();
                signingKey = toJwk((RSAPublicKey) pair.getPublic(), (RSAPrivateKey) pair.getPrivate());
            } else {
                signingKey = toJwk(readPublic(Path.of(cfg.publicKeyPath())), readPrivate(Path.of(cfg.privateKeyPath())));
            }
            List<RSAKey> keys = new ArrayList<>();
            keys.add(signingKey.toPublicJWK());
            if (!isBlank(cfg.previousPublicKeyPath())) {
                keys.add(toJwk(readPublic(Path.of(cfg.previousPublicKeyPath())), null));
            }
            verificationKeys = List.copyOf(keys);
        } catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException | JOSEException e) {
            throw new IllegalStateException("Unable to load JWT keys", e);
        }
    }

    public RSAKey signingKey() {
        return signingKey;
    }

    public List<RSAKey> verificationKeys() {
        return verificationKeys;
    }

    private static RSAKey toJwk(RSAPublicKey publicKey, RSAPrivateKey privateKey) throws JOSEException {
        RSAKey.Builder builder = new RSAKey.Builder(publicKey);
        if (privateKey != null) {
            builder.privateKey(privateKey);
        }
        RSAKey withoutId = builder.build();
        return new RSAKey.Builder(withoutId).keyID(withoutId.computeThumbprint().toString()).build();
    }

    private static RSAPublicKey readPublic(Path path) throws IOException, NoSuchAlgorithmException,
            InvalidKeySpecException {
        byte[] der = pemBody(Files.readString(path), "PUBLIC KEY");
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    private static RSAPrivateKey readPrivate(Path path) throws IOException, NoSuchAlgorithmException,
            InvalidKeySpecException {
        byte[] der = pemBody(Files.readString(path), "PRIVATE KEY");
        return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static byte[] pemBody(String pem, String type) {
        String begin = "-----BEGIN " + type + "-----";
        String end = "-----END " + type + "-----";
        int start = pem.indexOf(begin);
        int stop = pem.indexOf(end);
        if (start < 0 || stop < 0) {
            throw new IllegalArgumentException("Expected a PEM '" + type + "' block");
        }
        return Base64.getMimeDecoder().decode(pem.substring(start + begin.length(), stop));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
