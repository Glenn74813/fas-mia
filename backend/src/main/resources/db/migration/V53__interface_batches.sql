-- Inbound interfaces (TOR §4.6.2, Annex D): payroll summaries, FMIS budget data and asset information arrive as CSV,
-- JSON or XML, from a person or an API client. Each file is validated into a batch; a person with the matching
-- permission then accepts it, which creates draft records (a journal voucher, budget allocations, fixed assets) that
-- go through the normal review and approval. Nothing is posted by an import. The field layouts are proposals until
-- MIAA supplies each system's layout (C-37).
CREATE TABLE interface_batches (
    id                 UUID PRIMARY KEY,
    interface          VARCHAR(20)    NOT NULL CHECK (interface IN ('PAYROLL', 'FMIS_BUDGET', 'ASSETS')),
    format             VARCHAR(4)     NOT NULL CHECK (format IN ('CSV', 'JSON', 'XML')),
    source_reference   VARCHAR(100)   NOT NULL,
    file_sha256        CHAR(64)       NOT NULL,
    status             VARCHAR(10)    NOT NULL CHECK (status IN ('VALIDATED', 'INVALID', 'ACCEPTED', 'REJECTED')),
    row_count          INT            NOT NULL,
    -- The parsed batch (header fields and rows) and, when invalid, the problems found.
    content            JSONB          NOT NULL,
    errors             JSONB          NOT NULL DEFAULT '[]'::jsonb,
    received_by_id     UUID           REFERENCES users (id),
    received_by        VARCHAR(64)    NOT NULL,
    received_at        TIMESTAMPTZ    NOT NULL,
    decided_by_id      UUID           REFERENCES users (id),
    decided_by         VARCHAR(64),
    decided_at         TIMESTAMPTZ,
    decision_note      VARCHAR(500),
    result_type        VARCHAR(30),
    result_ids         UUID[],
    version            BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT ck_interface_decided CHECK ((status IN ('ACCEPTED', 'REJECTED')) = (decided_at IS NOT NULL))
);
-- The same file is taken once per interface (a rejected or invalid one may be sent again).
CREATE UNIQUE INDEX uq_interface_file ON interface_batches (interface, file_sha256) WHERE status IN ('VALIDATED', 'ACCEPTED');
CREATE INDEX ix_interface_batches_received ON interface_batches (received_at DESC);

INSERT INTO permissions (code, description, category, financial) VALUES
    ('INTERFACE_SUBMIT', 'API clients: send payroll, FMIS budget or asset files for review (creates nothing until accepted)', 'INTEGRATION', FALSE);
