-- General ledger (MS §11.1, §5.4): open-item clearing (FR-GL-17), and the closing schedule with day-end, month-end,
-- quarter-end and year-end close checklists.

-- ---------------------------------------------------------------- open-item clearing
-- Posted lines on an open-item managed account stay open until a group of them that nets to zero is cleared.
-- Clearing posts nothing; it records which items settle each other, and can be reversed with a reason.
ALTER TABLE chart_of_accounts ADD COLUMN open_item_managed BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE gl_clearings (
    id               UUID PRIMARY KEY,
    account_id       UUID          NOT NULL REFERENCES chart_of_accounts (id),
    rule             VARCHAR(10)   NOT NULL CHECK (rule IN ('MANUAL', 'REFERENCE', 'AMOUNT')),
    line_count       INTEGER       NOT NULL CHECK (line_count >= 2),
    total            NUMERIC(19,2) NOT NULL CHECK (total > 0),
    note             VARCHAR(500),
    status           VARCHAR(10)   NOT NULL CHECK (status IN ('ACTIVE', 'REVERSED')),
    cleared_by_id    UUID          NOT NULL REFERENCES users (id),
    cleared_by       VARCHAR(64)   NOT NULL,
    cleared_at       TIMESTAMPTZ   NOT NULL,
    reversed_by      VARCHAR(64),
    reversed_at      TIMESTAMPTZ,
    reversal_reason  VARCHAR(500),
    CONSTRAINT ck_clearing_reversed CHECK ((status = 'REVERSED') = (reversed_at IS NOT NULL))
);
CREATE INDEX ix_gl_clearings_account ON gl_clearings (account_id, cleared_at);

CREATE TABLE gl_clearing_lines (
    clearing_id  UUID    NOT NULL REFERENCES gl_clearings (id),
    line_id      UUID    NOT NULL REFERENCES journal_entry_lines (id),
    active       BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (clearing_id, line_id)
);
-- A line belongs to at most one active clearing.
CREATE UNIQUE INDEX uq_gl_clearing_line_active ON gl_clearing_lines (line_id) WHERE active;

-- ---------------------------------------------------------------- close calendar
-- Manual close tasks; the automatic reconciliation and exception checks are computed, not stored. Due date = the
-- day, period, quarter or year end plus the offset (calendar days).
CREATE TABLE close_tasks (
    id                      UUID PRIMARY KEY,
    code                    VARCHAR(30)   NOT NULL UNIQUE,
    name                    VARCHAR(200)  NOT NULL,
    frequency               VARCHAR(12)   NOT NULL CHECK (frequency IN ('DAY_END', 'MONTH_END', 'QUARTER_END', 'YEAR_END')),
    due_offset_days         INTEGER       NOT NULL CHECK (due_offset_days BETWEEN 0 AND 90),
    responsible_permission  VARCHAR(64)   NOT NULL REFERENCES permissions (code),
    instructions            VARCHAR(1000),
    sort_order              INTEGER       NOT NULL DEFAULT 100,
    status                  VARCHAR(10)   NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    placeholder             BOOLEAN       NOT NULL DEFAULT FALSE,
    version                 BIGINT        NOT NULL DEFAULT 0,
    updated_by              VARCHAR(64)   NOT NULL,
    updated_at              TIMESTAMPTZ   NOT NULL
);

-- One completion per task and scope: a date (DAY_END), a period id (MONTH_END), '<fiscal year id>:Q<n>' (QUARTER_END)
-- or a fiscal year id (YEAR_END).
CREATE TABLE close_task_completions (
    id               UUID PRIMARY KEY,
    task_id          UUID          NOT NULL REFERENCES close_tasks (id),
    scope_key        VARCHAR(60)   NOT NULL,
    outcome          VARCHAR(16)   NOT NULL CHECK (outcome IN ('DONE', 'NOT_APPLICABLE')),
    note             VARCHAR(1000) NOT NULL,
    completed_by_id  UUID          NOT NULL REFERENCES users (id),
    completed_by     VARCHAR(64)   NOT NULL,
    completed_at     TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uq_close_task_scope UNIQUE (task_id, scope_key)
);

INSERT INTO permissions (code, description, category, financial) VALUES
    ('GL_CLEARING',       'Clear and un-clear open items on open-item managed accounts', 'ACCOUNTING', FALSE),
    ('CLOSE_TASK_MANAGE', 'Maintain the close calendar (close tasks, owners and due offsets)', 'ACCOUNTING', FALSE);

-- Proposed assignments pending MIAA sign-off (C-08).
INSERT INTO role_permissions (role_id, permission_code)
SELECT r.id, p.code FROM roles r JOIN (VALUES
    ('ACCOUNTANT', 'GL_CLEARING'), ('CHIEF_ACCOUNTANT', 'GL_CLEARING'), ('CHIEF_ACCOUNTANT', 'CLOSE_TASK_MANAGE')
) AS p (role_code, code) ON p.role_code = r.code;

-- Placeholder close tasks so the calendar is not empty; MIAA's close calendar replaces them (C-33).
INSERT INTO close_tasks (id, code, name, frequency, due_offset_days, responsible_permission, instructions, sort_order, status, placeholder,
    updated_by, updated_at) VALUES
    (gen_random_uuid(), 'PLC-DAY-CASH', '[PLACEHOLDER] Count the day''s collections and prepare the deposit', 'DAY_END', 1, 'DEPOSIT_PREPARE',
     'Replace with MIAA''s day-end procedure (C-33).', 10, 'ACTIVE', TRUE, 'system', now()),
    (gen_random_uuid(), 'PLC-MONTH-REVIEW', '[PLACEHOLDER] Review accruals and unposted documents', 'MONTH_END', 5, 'PERIOD_CLOSE',
     'Replace with MIAA''s month-end procedure (C-33).', 10, 'ACTIVE', TRUE, 'system', now()),
    (gen_random_uuid(), 'PLC-QTR-REVIEW', '[PLACEHOLDER] Review the quarter''s reconciliations and schedules', 'QUARTER_END', 10, 'PERIOD_CLOSE',
     'Replace with MIAA''s quarter-end procedure (C-33).', 10, 'ACTIVE', TRUE, 'system', now()),
    (gen_random_uuid(), 'PLC-YEAR-REVIEW', '[PLACEHOLDER] Review year-end adjustments before the closing entry', 'YEAR_END', 15, 'PERIOD_CLOSE',
     'Replace with MIAA''s year-end procedure (C-33).', 10, 'ACTIVE', TRUE, 'system', now());
