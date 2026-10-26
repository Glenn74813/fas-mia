package com.miaa.cas.common.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM application-level encryption for small secrets (e.g. TOTP seeds). Ciphertext layout:
 * {@code [version:1][iv:12][ciphertext+tag]}. The associated data binds a ciphertext to its owner so
 * it cannot be copied to another row.
 */
public class AesGcmEncryptor {

    private static final byte VERSION = 1;
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;

    public AesGcmEncryptor(byte[] keyBytes) {
        if (keyBytes == null || keyBytes.length != 32) {
            throw new IllegalArgumentException("Data encryption key must be exactly 32 bytes (AES-256)");
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    public static AesGcmEncryptor fromBase64(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException("CAS_DATA_ENCRYPTION_KEY is not configured (base64-encoded 32 bytes)");
        }
        return new AesGcmEncryptor(Base64.getDecoder().decode(base64Key.trim()));
    }

    public byte[] encrypt(byte[] plaintext, String associatedData) {
        try {
            byte[] iv = SecureTokens.randomBytes(IV_LENGTH);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            byte[] ct = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(1 + IV_LENGTH + ct.length).put(VERSION).put(iv).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public byte[] decrypt(byte[] ciphertext, String associatedData) {
        if (ciphertext == null || ciphertext.length < 1 + IV_LENGTH + TAG_BITS / 8 || ciphertext[0] != VERSION) {
            throw new IllegalArgumentException("Unsupported ciphertext format");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, ciphertext, 1, IV_LENGTH));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(ciphertext, 1 + IV_LENGTH, ciphertext.length - 1 - IV_LENGTH);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Decryption failed (wrong key or tampered data)", e);
        }
    }
}
