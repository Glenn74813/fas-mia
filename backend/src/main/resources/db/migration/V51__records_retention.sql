-- Records retention (TOR §4.5, Annex A.9): every closed fiscal year is kept at least cas.records.retention-years
-- (10) after it ends; it can be archived as a sealed export of its records, documents and audit trail (two people,
-- like a full export); and legal holds record that records must not be disposed of. Purging records is not provided:
-- posted records and the hash-chained audit trail are immutable until MIAA decides how lawful disposal works (C-05,
-- C-13).
ALTER TABLE data_exports DROP CONSTRAINT data_exports_kind_check;
ALTER TABLE data_exports ADD CONSTRAINT data_exports_kind_check CHECK (kind IN ('FULL', 'ARCHIVE'));
ALTER TABLE data_exports ADD COLUMN fiscal_year_id UUID REFERENCES fiscal_years (id);
ALTER TABLE data_exports ADD CONSTRAINT ck_export_archive_year CHECK ((kind = 'ARCHIVE') = (fiscal_year_id IS NOT NULL));

CREATE TABLE legal_holds (
    id              UUID PRIMARY KEY,
    -- NULL holds every record of every year.
    fiscal_year_id  UUID           REFERENCES fiscal_years (id),
    reason          VARCHAR(500)   NOT NULL,
    reference       VARCHAR(100),
    placed_by_id    UUID           NOT NULL REFERENCES users (id),
    placed_by       VARCHAR(64)    NOT NULL,
    placed_at       TIMESTAMPTZ    NOT NULL,
    released_by_id  UUID           REFERENCES users (id),
    released_by     VARCHAR(64),
    released_at     TIMESTAMPTZ,
    release_note    VARCHAR(500),
    version         BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT ck_hold_release CHECK ((released_at IS NULL) = (released_by_id IS NULL)
        AND (released_at IS NULL OR release_note IS NOT NULL))
);
CREATE INDEX ix_legal_holds_active ON legal_holds (fiscal_year_id) WHERE released_at IS NULL;

INSERT INTO permissions (code, description, category, financial) VALUES
    ('RECORDS_RETENTION_MANAGE', 'Place and release legal holds on records', 'ADMINISTRATION', FALSE);
INSERT INTO role_permissions (role_id, permission_code)
SELECT r.id, 'RECORDS_RETENTION_MANAGE' FROM roles r WHERE r.code = 'CHIEF_ACCOUNTANT'
ON CONFLICT DO NOTHING;
