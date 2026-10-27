-- Overhead allocation (TOR §3.5.1, Annex B.7, B.8): a cycle is a set of rules, each moving the period balance of a
-- sender account in a sender cost centre to receiving cost centres by a key: fixed percentages or statistical
-- quantities (for example floor area or headcount). Running a cycle for a period creates one draft journal voucher,
-- which is reviewed, approved and posted like any other. The cycles and keys themselves are MIAA's (C-10).
CREATE TABLE allocation_cycles (
    id             UUID PRIMARY KEY,
    code           VARCHAR(30)    NOT NULL UNIQUE,
    name           VARCHAR(200)   NOT NULL,
    description    VARCHAR(1000),
    status         VARCHAR(10)    NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_by     VARCHAR(64)    NOT NULL,
    created_at     TIMESTAMPTZ    NOT NULL,
    updated_by     VARCHAR(64)    NOT NULL,
    updated_at     TIMESTAMPTZ    NOT NULL,
    version        BIGINT         NOT NULL DEFAULT 0
);

CREATE TABLE allocation_rules (
    id                     UUID PRIMARY KEY,
    cycle_id               UUID           NOT NULL REFERENCES allocation_cycles (id) ON DELETE CASCADE,
    seq                    INT            NOT NULL,
    description            VARCHAR(300),
    sender_account_id      UUID           NOT NULL REFERENCES chart_of_accounts (id),
    sender_cost_center_id  UUID           NOT NULL REFERENCES cost_centers (id),
    -- The account debited at the receivers; the sender's account when NULL.
    receiver_account_id    UUID           REFERENCES chart_of_accounts (id),
    key_type               VARCHAR(10)    NOT NULL CHECK (key_type IN ('PERCENT', 'QUANTITY')),
    UNIQUE (cycle_id, seq)
);

CREATE TABLE allocation_receivers (
    rule_id         UUID           NOT NULL REFERENCES allocation_rules (id) ON DELETE CASCADE,
    cost_center_id  UUID           NOT NULL REFERENCES cost_centers (id),
    weight          NUMERIC(19,4)  NOT NULL CHECK (weight > 0),
    PRIMARY KEY (rule_id, cost_center_id)
);

CREATE TABLE allocation_runs (
    id           UUID PRIMARY KEY,
    cycle_id     UUID           NOT NULL REFERENCES allocation_cycles (id),
    period_id    UUID           NOT NULL REFERENCES accounting_periods (id),
    voucher_id   UUID           NOT NULL REFERENCES journal_vouchers (id),
    total        NUMERIC(19,2)  NOT NULL,
    run_by       VARCHAR(64)    NOT NULL,
    run_at       TIMESTAMPTZ    NOT NULL
);
CREATE INDEX ix_allocation_runs_cycle ON allocation_runs (cycle_id, period_id);

INSERT INTO permissions (code, description, category, financial) VALUES
    ('ALLOCATION_MANAGE', 'Define overhead allocation cycles and run them into draft journal vouchers', 'ACCOUNTING', TRUE);
INSERT INTO role_permissions (role_id, permission_code)
SELECT r.id, 'ALLOCATION_MANAGE' FROM roles r WHERE r.code IN ('CHIEF_ACCOUNTANT', 'ACCOUNTANT')
ON CONFLICT DO NOTHING;
