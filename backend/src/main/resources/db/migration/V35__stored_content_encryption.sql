-- Application-level encryption of stored files (MS §9.4): supporting documents, generated report files and ADA
-- payment files (which carry payee bank account numbers). AES-256-GCM with CAS_DATA_ENCRYPTION_KEY, each ciphertext
-- bound to its row. The column says how each row is stored; rows written before this migration stay readable as
-- NONE until the background job (StoredContentEncryptionJob) encrypts them.

ALTER TABLE document_contents ADD COLUMN encryption VARCHAR(12) NOT NULL DEFAULT 'NONE'
    CHECK (encryption IN ('NONE', 'AES-256-GCM'));
ALTER TABLE report_jobs ADD COLUMN content_encryption VARCHAR(12) NOT NULL DEFAULT 'NONE'
    CHECK (content_encryption IN ('NONE', 'AES-256-GCM'));
ALTER TABLE payment_batches ADD COLUMN content_encryption VARCHAR(12) NOT NULL DEFAULT 'NONE'
    CHECK (content_encryption IN ('NONE', 'AES-256-GCM'));

CREATE INDEX ix_document_contents_plain ON document_contents (storage_key) WHERE encryption = 'NONE';
