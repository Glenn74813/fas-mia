package com.miaa.cas.common.crypto;

import com.miaa.cas.audit.Actor;
import com.miaa.cas.audit.AuditService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Encrypts files stored before application-level encryption existed (V35), a batch at a time, until none are left.
 * Safe with several instances (FOR UPDATE SKIP LOCKED). Each batch is one transaction and is audited.
 */
@Component
public class StoredContentEncryptionJob {

    private static final Logger log = LoggerFactory.getLogger(StoredContentEncryptionJob.class);
    static final int BATCH = 50;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ContentCipher cipher;
    private final AuditService audit;

    public StoredContentEncryptionJob(JdbcTemplate jdbc, TransactionTemplate tx, ContentCipher cipher, AuditService audit) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.cipher = cipher;
        this.audit = audit;
    }

    @Scheduled(initialDelayString = "${cas.crypto.backfill-initial-delay:PT1M}", fixedDelayString = "${cas.crypto.backfill-interval:PT10M}")
    public void run() {
        int total = 0;
        int n;
        do {
            Integer done = tx.execute(s -> encryptBatch());
            n = done == null ? 0 : done;
            total += n;
        } while (n > 0);
        if (total > 0) {
            log.info("Encrypted {} stored file(s) written before application-level encryption", total);
        }
    }

    /** Encrypts up to one batch of each kind; returns how many rows it encrypted. */
    int encryptBatch() {
        int n = 0;
        for (Map<String, Object> r : jdbc.queryForList("SELECT storage_key, content FROM document_contents WHERE encryption = 'NONE' "
                + "LIMIT " + BATCH + " FOR UPDATE SKIP LOCKED")) {
            String key = (String) r.get("storage_key");
            jdbc.update("UPDATE document_contents SET content = ?, encryption = ? WHERE storage_key = ?",
                    cipher.seal((byte[]) r.get("content"), "document", key), ContentCipher.SCHEME, key);
            n++;
        }
        n += encryptTable("report_jobs", "report-job", "content IS NOT NULL AND ");
        n += encryptTable("payment_batches", "payment-file", "");
        if (n > 0) {
            audit.record(Actor.SYSTEM, "STORED_CONTENT_ENCRYPTED", "STORED_CONTENT", null, Map.of("rows", n));
        }
        return n;
    }

    private int encryptTable(String table, String kind, String extraCondition) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, content FROM " + table + " WHERE " + extraCondition
                + "content_encryption = 'NONE' LIMIT " + BATCH + " FOR UPDATE SKIP LOCKED");
        for (Map<String, Object> r : rows) {
            UUID id = (UUID) r.get("id");
            jdbc.update("UPDATE " + table + " SET content = ?, content_encryption = ? WHERE id = ?",
                    cipher.seal((byte[]) r.get("content"), kind, id.toString()), ContentCipher.SCHEME, id);
        }
        return rows.size();
    }
}
