package com.miaa.cas.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ChunkedFileCipherTest {

    private final AesGcmEncryptor key = new AesGcmEncryptor(new byte[32]);

    private byte[] seal(byte[] plain, String context) throws Exception {
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        try (OutputStream out = ChunkedFileCipher.encrypting(key, context, file)) {
            // Odd-sized writes cross chunk boundaries.
            for (int off = 0; off < plain.length; off += 70_001) {
                out.write(plain, off, Math.min(70_001, plain.length - off));
            }
        }
        return file.toByteArray();
    }

    private byte[] open(byte[] sealed, String context) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ChunkedFileCipher.decrypt(key, context, new ByteArrayInputStream(sealed), out);
        return out.toByteArray();
    }

    @Test
    void largeAndEmptyFilesRoundTrip() throws Exception {
        byte[] plain = new byte[ChunkedFileCipher.CHUNK * 2 + 12_345];
        new Random(7).nextBytes(plain);
        assertThat(open(seal(plain, "x"), "x")).isEqualTo(plain);
        assertThat(open(seal(new byte[0], "x"), "x")).isEmpty();
    }

    @Test
    void tamperingTruncationAndAnotherFilesContextAreRefused() throws Exception {
        byte[] plain = new byte[ChunkedFileCipher.CHUNK + 10];
        byte[] sealed = seal(plain, "export:a");

        byte[] flipped = sealed.clone();
        flipped[100] ^= 1;
        assertThatThrownBy(() -> open(flipped, "export:a")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> open(sealed, "export:b")).isInstanceOf(IllegalStateException.class);

        // Dropping the last chunk leaves a first chunk sealed as "more": refused, not a silently shorter file.
        int firstChunk = 4 + ((sealed[0] & 0xff) << 24 | (sealed[1] & 0xff) << 16 | (sealed[2] & 0xff) << 8 | (sealed[3] & 0xff));
        assertThatThrownBy(() -> open(Arrays.copyOf(sealed, firstChunk), "export:a")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> open(Arrays.copyOf(sealed, firstChunk + 10), "export:a")).hasMessageContaining("truncated");
    }
}
