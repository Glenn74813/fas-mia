-- Phase 4a: statement templates (versioned configuration mapping statement line codes of the chart to
-- presentation lines) and background report jobs with their exported files.

CREATE TABLE report_templates (
    id              UUID PRIMARY KEY,
    code            VARCHAR(40)   NOT NULL,
    version         INTEGER       NOT NULL,
    name            VARCHAR(200)  NOT NULL,
    statement_type  VARCHAR(8)    NOT NULL CHECK (statement_type IN ('SFP', 'SFPERF')),
    status          VARCHAR(10)   NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    placeholder     BOOLEAN       NOT NULL DEFAULT FALSE,
    -- Two line codes whose amounts must agree (e.g. total assets and total liabilities and equity).
    balance_check   VARCHAR(81),
    created_by_id   UUID          REFERENCES users (id),
    created_by      VARCHAR(64)   NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL,
    activated_by    VARCHAR(64),
    activated_at    TIMESTAMPTZ,
    CONSTRAINT uq_report_template_version UNIQUE (code, version)
);
CREATE UNIQUE INDEX uq_report_template_active ON report_templates (code) WHERE status = 'ACTIVE';

CREATE TABLE report_template_lines (
    template_id    UUID          NOT NULL REFERENCES report_templates (id) ON DELETE CASCADE,
    line_no        SMALLINT      NOT NULL,
    code           VARCHAR(40)   NOT NULL,
    label          VARCHAR(200)  NOT NULL,
    line_type      VARCHAR(8)    NOT NULL CHECK (line_type IN ('HEADER', 'LINE', 'SURPLUS', 'TOTAL')),
    fs_line_codes  VARCHAR(30)[] NOT NULL DEFAULT '{}',
    total_of       VARCHAR(40)[] NOT NULL DEFAULT '{}',
    -- +1 shows debit balances as positive (assets, expenses); -1 shows credit balances as positive.
    sign           SMALLINT      NOT NULL CHECK (sign IN (-1, 1)),
    indent         SMALLINT      NOT NULL DEFAULT 0 CHECK (indent BETWEEN 0 AND 4),
    PRIMARY KEY (template_id, line_no),
    CONSTRAINT uq_template_line_code UNIQUE (template_id, code)
);

CREATE TABLE report_jobs (
    id             UUID PRIMARY KEY,
    report_type    VARCHAR(30)  NOT NULL CHECK (report_type IN ('FINANCIAL_STATEMENT', 'TRIAL_BALANCE')),
    format         VARCHAR(4)   NOT NULL CHECK (format IN ('CSV', 'XLSX', 'PDF')),
    parameters     JSONB        NOT NULL,
    status         VARCHAR(8)   NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'DONE', 'FAILED', 'EXPIRED')),
    requested_by_id UUID        NOT NULL REFERENCES users (id),
    requested_by   VARCHAR(64)  NOT NULL,
    requested_at   TIMESTAMPTZ  NOT NULL,
    started_at     TIMESTAMPTZ,
    finished_at    TIMESTAMPTZ,
    error          VARCHAR(1000),
    file_name      VARCHAR(200),
    content_type   VARCHAR(100),
    content        BYTEA,
    sha256         CHAR(64),
    expires_at     TIMESTAMPTZ
);
CREATE INDEX ix_report_jobs_queue ON report_jobs (status, requested_at) WHERE status = 'QUEUED';
CREATE INDEX ix_report_jobs_requester ON report_jobs (requested_by_id, requested_at DESC);
