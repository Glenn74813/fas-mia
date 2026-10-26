package com.miaa.cas.documents;

import com.miaa.cas.common.crypto.ContentCipher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Default storage: document bytes in PostgreSQL ({@code document_contents}), encrypted by the application
 * (AES-256-GCM, bound to the storage key) and also covered by the database's encryption at rest, backups and PITR, and is available to every instance. Swap for a
 * private object-storage implementation when volumes justify it (documents are capped at 10 MB).
 */
@Component
public class DatabaseDocumentStorage implements DocumentStorage {

    static final String KIND = "document";

    private final JdbcTemplate jdbc;
    private final ContentCipher cipher;

    public DatabaseDocumentStorage(JdbcTemplate jdbc, ContentCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    @Override
    public void put(String key, byte[] content) {
        jdbc.update("INSERT INTO document_contents (storage_key, content, encryption) VALUES (?, ?, ?)", key,
                cipher.seal(content, KIND, key), ContentCipher.SCHEME);
    }

    @Override
    public byte[] get(String key) {
        return jdbc.queryForObject("SELECT content, encryption FROM document_contents WHERE storage_key = ?",
                (rs, i) -> cipher.open(rs.getBytes(1), rs.getString(2), KIND, key), key);
    }
}
