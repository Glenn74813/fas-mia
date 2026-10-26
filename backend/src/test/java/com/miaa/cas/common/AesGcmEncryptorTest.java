package com.miaa.cas.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miaa.cas.common.crypto.AesGcmEncryptor;
import com.miaa.cas.common.crypto.SecureTokens;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AesGcmEncryptorTest {

    private final AesGcmEncryptor encryptor = new AesGcmEncryptor(SecureTokens.randomBytes(32));

    @Test
    void roundTripsWithRandomIv() {
        byte[] plain = "totp-seed".getBytes(StandardCharsets.UTF_8);
        byte[] a = encryptor.encrypt(plain, "user-1");
        byte[] b = encryptor.encrypt(plain, "user-1");
        assertThat(a).isNotEqualTo(b);
        assertThat(encryptor.decrypt(a, "user-1")).isEqualTo(plain);
    }

    @Test
    void rejectsWrongContextTamperingAndWrongKey() {
        byte[] ct = encryptor.encrypt("secret".getBytes(StandardCharsets.UTF_8), "user-1");
        assertThatThrownBy(() -> encryptor.decrypt(ct, "user-2")).isInstanceOf(IllegalStateException.class);
        byte[] tampered = ct.clone();
        tampered[tampered.length - 1] ^= 1;
        assertThatThrownBy(() -> encryptor.decrypt(tampered, "user-1")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AesGcmEncryptor(SecureTokens.randomBytes(32)).decrypt(ct, "user-1"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void requiresAes256Key() {
        assertThatThrownBy(() -> new AesGcmEncryptor(new byte[16])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AesGcmEncryptor.fromBase64("")).isInstanceOf(IllegalStateException.class);
    }
}
