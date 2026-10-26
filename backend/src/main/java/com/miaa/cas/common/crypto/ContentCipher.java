package com.miaa.cas.common.crypto;

import org.springframework.stereotype.Component;

/**
 * Encrypts stored files (documents, report files, payment files) with the data encryption key. The associated data
 * binds each ciphertext to its kind and row, so a file cannot be swapped into another row unnoticed.
 */
@Component
public class ContentCipher {

    public static final String SCHEME = "AES-256-GCM";
    public static final String NONE = "NONE";
    /** Bytes added by encryption: format version, IV and authentication tag. */
    public static final int OVERHEAD = 29;

    private final AesGcmEncryptor encryptor;

    public ContentCipher(AesGcmEncryptor encryptor) {
        this.encryptor = encryptor;
    }

    public byte[] seal(byte[] plain, String kind, String id) {
        return encryptor.encrypt(plain, kind + ":" + id);
    }

    /** Returns the plain content of a stored value, decrypting it when its scheme says it is encrypted. */
    public byte[] open(byte[] stored, String scheme, String kind, String id) {
        if (NONE.equals(scheme)) {
            return stored;
        }
        if (!SCHEME.equals(scheme)) {
            throw new IllegalStateException("Unknown content encryption scheme " + scheme);
        }
        return encryptor.decrypt(stored, kind + ":" + id);
    }
}
