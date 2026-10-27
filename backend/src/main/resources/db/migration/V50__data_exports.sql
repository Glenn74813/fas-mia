-- Full data export (TOR §3.6: MIAA keeps full access to its data and gets it back in a machine-readable format at
-- termination). One holder of DATA_EXPORT requests an export with a purpose; another approves it; a background worker
-- writes every table as CSV with the data dictionary, the supporting documents and a manifest of SHA-256 checksums
-- into one ZIP, encrypted at rest, which only the requester can download until it expires.
CREATE TABLE data_exports (
    id              UUID PRIMARY KEY,
    kind            VARCHAR(10)    NOT NULL CHECK (kind IN ('FULL')),
    purpose         VARCHAR(500)   NOT NULL,
    status          VARCHAR(10)    NOT NULL CHECK (status IN ('REQUESTED', 'APPROVED', 'RUNNING', 'DONE', 'FAILED', 'REJECTED', 'EXPIRED')),
    requested_by_id UUID           NOT NULL REFERENCES users (id),
    requested_by    VARCHAR(64)    NOT NULL,
    requested_at    TIMESTAMPTZ    NOT NULL,
    decided_by_id   UUID           REFERENCES users (id),
    decided_by      VARCHAR(64),
    decided_at      TIMESTAMPTZ,
    decision_note   VARCHAR(500),
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    file_name       VARCHAR(200),
    size_bytes      BIGINT,
    sha256          CHAR(64),
    error           VARCHAR(1000),
    expires_at      TIMESTAMPTZ,
    version         BIGINT         NOT NULL DEFAULT 0,
    -- Two people: the approver is never the requester.
    CONSTRAINT ck_export_two_person CHECK (decided_by_id IS NULL OR decided_by_id <> requested_by_id),
    CONSTRAINT ck_export_decided CHECK ((status = 'REQUESTED') = (decided_at IS NULL))
);
CREATE INDEX ix_data_exports_status ON data_exports (status, requested_at);

INSERT INTO permissions (code, description, category, financial) VALUES
    ('DATA_EXPORT', 'Request and approve full exports of the system''s data (two people)', 'ADMINISTRATION', FALSE);
INSERT INTO role_permissions (role_id, permission_code)
SELECT r.id, 'DATA_EXPORT' FROM roles r WHERE r.code IN ('SYSTEM_ADMINISTRATOR', 'FINANCE_MANAGER')
ON CONFLICT DO NOTHING;
