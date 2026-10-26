-- Access governance (MS §9.3): maker-checker for financial access (C-19), per-role approval limits (C-24) and periodic
-- access reviews (FR-RBAC-08). Role assignments of the new permissions are proposals pending MIAA sign-off (C-08, C-19).

-- A role change that adds a role carrying financial permissions waits for a second person's approval.
CREATE TABLE role_grant_requests (
    id                UUID PRIMARY KEY,
    user_id           UUID          NOT NULL REFERENCES users (id),
    roles_before      VARCHAR(1000) NOT NULL,
    roles_requested   VARCHAR(1000) NOT NULL,
    financial_added   VARCHAR(1000) NOT NULL,
    status            VARCHAR(10)   NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    requested_by_id   UUID          NOT NULL REFERENCES users (id),
    requested_by      VARCHAR(64)   NOT NULL,
    requested_at      TIMESTAMPTZ   NOT NULL,
    decided_by_id     UUID          REFERENCES users (id),
    decided_by        VARCHAR(64),
    decided_at        TIMESTAMPTZ,
    decision_note     VARCHAR(1000),
    version           BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_rgr_two_person CHECK (decided_by_id IS NULL OR (decided_by_id <> requested_by_id AND decided_by_id <> user_id)),
    CONSTRAINT ck_rgr_decided CHECK ((status IN ('APPROVED', 'REJECTED')) = (decided_by_id IS NOT NULL))
);
CREATE UNIQUE INDEX uq_rgr_pending ON role_grant_requests (user_id) WHERE status = 'PENDING';

-- The most a holder of the role may approve on one document of the type. No values are seeded (C-24).
CREATE TABLE approval_limits (
    role_code      VARCHAR(64)   NOT NULL REFERENCES roles (code),
    document_type  VARCHAR(40)   NOT NULL,
    max_amount     NUMERIC(19,2) NOT NULL CHECK (max_amount >= 0),
    updated_by     VARCHAR(64)   NOT NULL,
    updated_at     TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (role_code, document_type)
);

-- Review cycles over the access_reviews table of V1.
CREATE TABLE access_review_cycles (
    id          UUID PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    due_date    DATE         NOT NULL,
    status      VARCHAR(10)  NOT NULL CHECK (status IN ('OPEN', 'CLOSED')),
    started_by  VARCHAR(64)  NOT NULL,
    started_at  TIMESTAMPTZ  NOT NULL,
    closed_by   VARCHAR(64),
    closed_at   TIMESTAMPTZ,
    version     BIGINT       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uq_review_cycle_open ON access_review_cycles (status) WHERE status = 'OPEN';

ALTER TABLE access_reviews ADD COLUMN cycle_id UUID REFERENCES access_review_cycles (id);
ALTER TABLE access_reviews ADD COLUMN roles_snapshot VARCHAR(1000);
ALTER TABLE access_reviews ADD COLUMN financial BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE access_reviews ADD COLUMN reviewer VARCHAR(64);
ALTER TABLE access_reviews ADD COLUMN actioned_by VARCHAR(64);
ALTER TABLE access_reviews ADD COLUMN actioned_at TIMESTAMPTZ;
ALTER TABLE access_reviews ADD COLUMN action_note VARCHAR(1000);
ALTER TABLE access_reviews ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE access_reviews ADD CONSTRAINT ck_access_review_outcome CHECK ((outcome IS NULL) = (reviewed_at IS NULL));
CREATE UNIQUE INDEX uq_access_review_cycle_user ON access_reviews (cycle_id, user_id);

INSERT INTO permissions (code, description, category, financial) VALUES
    ('ACCESS_GRANT_APPROVE',  'Approve or reject grants of roles carrying financial permissions (C-19)', 'ADMINISTRATION', FALSE),
    ('ACCESS_REVIEW_MANAGE',  'Start and close periodic access review cycles',                         'AUDIT',          FALSE),
    ('ACCESS_REVIEW_PERFORM', 'Review users'' access in an access review cycle',                        'AUDIT',          FALSE),
    ('APPROVAL_LIMIT_MANAGE', 'Set per-role approval limits by document type (C-24)',                  'ADMINISTRATION', FALSE);

-- Proposed assignments pending MIAA sign-off (C-08, C-19, C-24).
INSERT INTO role_permissions (role_id, permission_code)
SELECT r.id, p.code FROM roles r JOIN (VALUES
    ('FINANCE_MANAGER', 'ACCESS_GRANT_APPROVE'), ('CHIEF_ACCOUNTANT', 'ACCESS_GRANT_APPROVE'),
    ('INTERNAL_AUDITOR', 'ACCESS_REVIEW_MANAGE'),
    ('INTERNAL_AUDITOR', 'ACCESS_REVIEW_PERFORM'), ('FINANCE_MANAGER', 'ACCESS_REVIEW_PERFORM'), ('CHIEF_ACCOUNTANT', 'ACCESS_REVIEW_PERFORM'),
    ('FINANCE_MANAGER', 'APPROVAL_LIMIT_MANAGE')
) AS p (role_code, code) ON p.role_code = r.code;

INSERT INTO sod_conflict_rules (id, permission_a, permission_b, severity, rationale) VALUES
    (gen_random_uuid(), 'USER_ADMINISTER', 'ACCESS_GRANT_APPROVE', 'BLOCK', 'Whoever assigns roles may not also approve financial access grants'),
    (gen_random_uuid(), 'ROLE_ADMINISTER', 'ACCESS_GRANT_APPROVE', 'BLOCK', 'Whoever defines roles may not also approve financial access grants'),
    (gen_random_uuid(), 'USER_ADMINISTER', 'APPROVAL_LIMIT_MANAGE', 'BLOCK', 'Technical administration must remain separate from financial authority'),
    (gen_random_uuid(), 'USER_ADMINISTER', 'ACCESS_REVIEW_PERFORM', 'BLOCK', 'Access reviews must be independent of the administrators who grant access');
