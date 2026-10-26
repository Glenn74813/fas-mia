-- Support tickets (MS §11.11, §22): severity and category, attachments (supporting_documents, owner TICKET),
-- requester closure confirmation with auto-close of unconfirmed resolutions, and change control on change
-- requests and enhancements: a rollback plan before approval, staging validation and release notes before release.

CREATE TABLE ticket_categories (
    id           UUID PRIMARY KEY,
    code         VARCHAR(30)  NOT NULL UNIQUE,
    name         VARCHAR(120) NOT NULL,
    sort_order   INTEGER      NOT NULL DEFAULT 100,
    status       VARCHAR(10)  NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    placeholder  BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_by   VARCHAR(64)  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL
);

-- Placeholder categories until MIAA's service catalogue is agreed (C-34).
INSERT INTO ticket_categories (id, code, name, sort_order, status, placeholder, updated_by, updated_at) VALUES
    (gen_random_uuid(), 'PLC-ACCESS',      '[PLACEHOLDER] Access and user accounts', 10, 'ACTIVE', TRUE, 'system', now()),
    (gen_random_uuid(), 'PLC-DEFECT',      '[PLACEHOLDER] Application defect',       20, 'ACTIVE', TRUE, 'system', now()),
    (gen_random_uuid(), 'PLC-DATA',        '[PLACEHOLDER] Data correction',          30, 'ACTIVE', TRUE, 'system', now()),
    (gen_random_uuid(), 'PLC-REPORT',      '[PLACEHOLDER] Reports and printing',     40, 'ACTIVE', TRUE, 'system', now()),
    (gen_random_uuid(), 'PLC-INTEGRATION', '[PLACEHOLDER] Interfaces and integrations', 50, 'ACTIVE', TRUE, 'system', now()),
    (gen_random_uuid(), 'PLC-OTHER',       '[PLACEHOLDER] Other',                    90, 'ACTIVE', TRUE, 'system', now());

-- Tickets raised before V41 keep NULL severity and category; new tickets must give both (enforced by the service).
ALTER TABLE tickets
    ADD COLUMN severity                 VARCHAR(2)    CHECK (severity IN ('S1', 'S2', 'S3', 'S4')),
    ADD COLUMN category_id              UUID          REFERENCES ticket_categories (id),
    ADD COLUMN closure_confirmed        BOOLEAN,
    ADD COLUMN satisfaction             SMALLINT      CHECK (satisfaction BETWEEN 1 AND 5),
    ADD COLUMN rollback_plan            VARCHAR(4000),
    ADD COLUMN staging_validated_at     TIMESTAMPTZ,
    ADD COLUMN staging_validated_by_id  UUID          REFERENCES users (id),
    ADD COLUMN staging_validated_by     VARCHAR(64),
    ADD COLUMN staging_validation_note  VARCHAR(4000),
    ADD COLUMN release_notes            VARCHAR(4000),
    ADD COLUMN release_reference        VARCHAR(60),
    ADD CONSTRAINT ck_ticket_staging CHECK ((staging_validated_at IS NULL) = (staging_validated_by IS NULL));
CREATE INDEX ix_tickets_resolved ON tickets (resolved_at) WHERE status = 'RESOLVED';
