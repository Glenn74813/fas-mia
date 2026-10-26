-- Phase 5a: opening-balance migration. A batch is one uploaded CAS-template CSV with every row validated and
-- kept. A GL opening trial balance becomes a draft OPENING journal voucher; AR and AP open items are tied to
-- the posted opening entry, must reconcile with it per party, and are applied by a second person.

CREATE TABLE migration_batches (
    id                  UUID PRIMARY KEY,
    kind                VARCHAR(16)   NOT NULL CHECK (kind IN ('GL_OPENING', 'AR_OPEN_ITEMS', 'AP_OPEN_ITEMS')),
    status              VARCHAR(10)   NOT NULL CHECK (status IN ('VALIDATED', 'INVALID', 'APPLIED', 'CANCELLED')),
    description         VARCHAR(500)  NOT NULL,
    file_name           VARCHAR(255)  NOT NULL,
    file_sha256         CHAR(64)      NOT NULL,
    row_count           INTEGER       NOT NULL CHECK (row_count >= 0),
    error_count         INTEGER       NOT NULL CHECK (error_count >= 0),
    -- GL: total debits. AR/AP: total outstanding.
    total_amount        NUMERIC(19,2) NOT NULL DEFAULT 0,
    -- File-level problems (unbalanced trial balance, wrong period, ...); row problems are kept per row.
    batch_errors        JSONB         NOT NULL DEFAULT '[]'::jsonb,
    cutover_date        DATE          NOT NULL,
    -- AR/AP: the posted opening entry the items belong to.
    opening_entry_id    UUID          REFERENCES journal_entries (id),
    -- GL: the opening voucher created from the batch.
    journal_voucher_id  UUID          REFERENCES journal_vouchers (id),
    uploaded_by_id      UUID          NOT NULL REFERENCES users (id),
    uploaded_by         VARCHAR(64)   NOT NULL,
    uploaded_at         TIMESTAMPTZ   NOT NULL,
    applied_by_id       UUID          REFERENCES users (id),
    applied_by          VARCHAR(64),
    applied_at          TIMESTAMPTZ,
    cancel_reason       VARCHAR(500),
    version             BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_mb_opening_entry CHECK ((kind = 'GL_OPENING') = (opening_entry_id IS NULL)),
    -- Open items are applied by someone other than the uploader. The GL batch only creates a draft voucher;
    -- its approval and posting are the journal workflow's.
    CONSTRAINT ck_mb_two_person CHECK (kind = 'GL_OPENING' OR applied_by_id IS NULL OR applied_by_id <> uploaded_by_id),
    CONSTRAINT ck_mb_applied CHECK ((status = 'APPLIED') = (applied_at IS NOT NULL))
);
-- The same file is never applied twice, and an opening entry receives one applied batch of each kind.
CREATE UNIQUE INDEX uq_mb_applied_file ON migration_batches (kind, file_sha256) WHERE status = 'APPLIED';
CREATE UNIQUE INDEX uq_mb_applied_entry ON migration_batches (kind, opening_entry_id)
    WHERE status = 'APPLIED' AND opening_entry_id IS NOT NULL;
CREATE INDEX ix_mb_kind_status ON migration_batches (kind, status, uploaded_at);

CREATE TABLE migration_batch_rows (
    batch_id  UUID     NOT NULL REFERENCES migration_batches (id),
    row_no    INTEGER  NOT NULL,
    data      JSONB    NOT NULL,
    errors    JSONB    NOT NULL DEFAULT '[]'::jsonb,
    PRIMARY KEY (batch_id, row_no)
);

CREATE OR REPLACE FUNCTION migration_rows_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'migration batch rows are immutable';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_migration_batch_rows_immutable BEFORE UPDATE OR DELETE ON migration_batch_rows
    FOR EACH ROW EXECUTE FUNCTION migration_rows_immutable();

-- Migrated open items: their GL effect is the opening entry, recorded as their posted entry.
ALTER TABLE billing_documents ADD COLUMN migration_batch_id UUID REFERENCES migration_batches (id);
ALTER TABLE billing_documents DROP CONSTRAINT billing_documents_source_check;
ALTER TABLE billing_documents ADD CONSTRAINT billing_documents_source_check CHECK (source IN ('MANUAL', 'CONTRACT', 'MIGRATION'));
ALTER TABLE billing_documents ADD CONSTRAINT ck_bd_migration CHECK ((source = 'MIGRATION') = (migration_batch_id IS NOT NULL));
ALTER TABLE supplier_invoices ADD COLUMN migration_batch_id UUID REFERENCES migration_batches (id);
CREATE INDEX ix_billing_migration ON billing_documents (migration_batch_id) WHERE migration_batch_id IS NOT NULL;
CREATE INDEX ix_supplier_invoices_migration ON supplier_invoices (migration_batch_id) WHERE migration_batch_id IS NOT NULL;

INSERT INTO permissions (code, description, category, financial) VALUES
    ('MIGRATION_PREPARE', 'Upload and validate opening-balance migration batches', 'MIGRATION', TRUE),
    ('MIGRATION_APPROVE', 'Apply validated open-item migration batches', 'MIGRATION', TRUE);

-- Proposed assignments pending MIAA sign-off (C-08).
INSERT INTO role_permissions (role_id, permission_code)
SELECT r.id, p.code FROM roles r JOIN (VALUES
    ('ACCOUNTANT', 'MIGRATION_PREPARE'), ('CHIEF_ACCOUNTANT', 'MIGRATION_APPROVE')
) AS p (role_code, code) ON p.role_code = r.code;

INSERT INTO sod_conflict_rules (id, permission_a, permission_b, severity, rationale)
SELECT gen_random_uuid(), a.code, f.code, 'BLOCK', 'Technical administration must remain separate from financial authority'
FROM permissions a CROSS JOIN permissions f
WHERE a.code IN ('USER_ADMINISTER', 'ROLE_ADMINISTER') AND f.code IN ('MIGRATION_PREPARE', 'MIGRATION_APPROVE');

INSERT INTO sod_conflict_rules (id, permission_a, permission_b, severity, rationale)
SELECT gen_random_uuid(), 'AUDIT_LOG_VIEW', f.code, 'BLOCK', 'Auditor independence: reviewers of the audit trail may not originate or authorise transactions'
FROM permissions f WHERE f.code IN ('MIGRATION_PREPARE', 'MIGRATION_APPROVE');

INSERT INTO sod_conflict_rules (id, permission_a, permission_b, severity, rationale) VALUES
    (gen_random_uuid(), 'MIGRATION_PREPARE', 'MIGRATION_APPROVE', 'WARN', 'Permitted at role level; the uploader of a batch can never apply it');
