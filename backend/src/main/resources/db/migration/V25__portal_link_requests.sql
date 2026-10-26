-- Phase 4e: linking a concessionaire portal account to a customer is requested by one person, with the
-- identity evidence relied on, and approved by another (C-18). Self-registration does not exist.

CREATE TABLE portal_link_requests (
    id               UUID PRIMARY KEY,
    customer_id      UUID          NOT NULL REFERENCES customers (id),
    user_id          UUID          NOT NULL REFERENCES users (id),
    status           VARCHAR(10)   NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    evidence         VARCHAR(1000) NOT NULL,
    requested_by_id  UUID          NOT NULL REFERENCES users (id),
    requested_by     VARCHAR(64)   NOT NULL,
    requested_at     TIMESTAMPTZ   NOT NULL,
    decided_by_id    UUID          REFERENCES users (id),
    decided_by       VARCHAR(64),
    decided_at       TIMESTAMPTZ,
    decision_note    VARCHAR(500),
    version          BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_plr_decider CHECK (decided_by_id IS NULL OR decided_by_id <> requested_by_id)
);
CREATE UNIQUE INDEX uq_plr_pending_user ON portal_link_requests (user_id) WHERE status = 'PENDING';

INSERT INTO permissions (code, description, category, financial) VALUES
    ('PORTAL_ACCESS_APPROVE', 'Approve linking a concessionaire portal account to a customer', 'RECEIVABLES', FALSE);

-- Proposed assignment pending MIAA sign-off (C-08, C-18).
INSERT INTO role_permissions (role_id, permission_code)
SELECT r.id, 'PORTAL_ACCESS_APPROVE' FROM roles r WHERE r.code = 'CHIEF_ACCOUNTANT';
