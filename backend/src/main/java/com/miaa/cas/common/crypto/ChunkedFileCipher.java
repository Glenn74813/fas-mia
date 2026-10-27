package com.miaa.cas.common.crypto;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * Streams a large file through {@link AesGcmEncryptor} in 1 MiB chunks, so neither writing nor reading holds the
 * whole file in memory. Each chunk is stored as a 4-byte length and its ciphertext, sealed with associated data
 * {@code <context>:<index>:<more|last>}: chunks cannot be reordered, swapped between files or dropped from the end
 * without decryption failing.
 */
public final class ChunkedFileCipher {

    static final int CHUNK = 1 << 20;

    private ChunkedFileCipher() {
    }

    /** An output stream that encrypts what is written to it into {@code sink}; closing it writes the last chunk. */
    public static OutputStream encrypting(AesGcmEncryptor encryptor, String context, OutputStream sink) {
        DataOutputStream out = new DataOutputStream(sink);
        return new OutputStream() {
            private final byte[] buf = new byte[CHUNK];
            private int len;
            private long index;
            private boolean closed;

            @Override
            public void write(int b) throws IOException {
                if (len == CHUNK) {
                    flushChunk(false);
                }
                buf[len++] = (byte) b;
            }

            @Override
            public void write(byte[] b, int off, int n) throws IOException {
                while (n > 0) {
                    if (len == CHUNK) {
                        flushChunk(false);
                    }
                    int k = Math.min(n, CHUNK - len);
                    System.arraycopy(b, off, buf, len, k);
                    len += k;
                    off += k;
                    n -= k;
                }
            }

            private void flushChunk(boolean last) throws IOException {
                byte[] sealed = encryptor.encrypt(Arrays.copyOf(buf, len), aad(context, index++, last));
                out.writeInt(sealed.length);
                out.write(sealed);
                len = 0;
            }

            @Override
            public void close() throws IOException {
                if (!closed) {
                    closed = true;
                    flushChunk(true);
                    out.close();
                }
            }
        };
    }

    /** Decrypts a file written by {@link #encrypting} into {@code sink}; fails if any chunk was altered, moved or cut off. */
    public static void decrypt(AesGcmEncryptor encryptor, String context, InputStream source, OutputStream sink) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(source));
        long index = 0;
        while (true) {
            int n;
            try {
                n = in.readInt();
            } catch (EOFException e) {
                throw new IOException("The encrypted file is truncated.");
            }
            if (n <= 0 || n > CHUNK + 64) {
                throw new IOException("The encrypted file is corrupt.");
            }
            byte[] sealed = in.readNBytes(n);
            if (sealed.length != n) {
                throw new IOException("The encrypted file is truncated.");
            }
            boolean last = peekEnd(in);
            sink.write(encryptor.decrypt(sealed, aad(context, index++, last)));
            if (last) {
                return;
            }
        }
    }

    private static boolean peekEnd(DataInputStream in) throws IOException {
        in.mark(1);
        boolean end = in.read() < 0;
        if (!end) {
            in.reset();
        }
        return end;
    }

    private static String aad(String context, long index, boolean last) {
        return context + ":" + index + ":" + (last ? "last" : "more");
    }
}
