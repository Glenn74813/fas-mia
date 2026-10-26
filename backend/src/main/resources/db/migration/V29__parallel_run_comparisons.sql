-- Phase 5e: parallel-run comparison. A legacy trial balance for one CAS period is compared account by account
-- with CAS. Every difference needs a written explanation, and a second person signs the comparison off only when
-- nothing is unexplained (MS §18: "no unexplained financial reconciliation discrepancies").

CREATE TABLE parallel_runs (
    id                  UUID PRIMARY KEY,
    period_id           UUID          NOT NULL REFERENCES accounting_periods (id),
    basis               VARCHAR(8)    NOT NULL CHECK (basis IN ('CLOSING', 'PERIOD')),
    legacy_system       VARCHAR(100)  NOT NULL,
    file_name           VARCHAR(255)  NOT NULL,
    file_sha256         CHAR(64)      NOT NULL,
    status              VARCHAR(10)   NOT NULL CHECK (status IN ('OPEN', 'SIGNED_OFF', 'CANCELLED')),
    legacy_total        NUMERIC(19,2) NOT NULL,
    cas_total           NUMERIC(19,2) NOT NULL,
    compared_at         TIMESTAMPTZ   NOT NULL,
    uploaded_by_id      UUID          NOT NULL REFERENCES users (id),
    uploaded_by         VARCHAR(64)   NOT NULL,
    uploaded_at         TIMESTAMPTZ   NOT NULL,
    signed_off_by_id    UUID          REFERENCES users (id),
    signed_off_by       VARCHAR(64),
    signed_off_at       TIMESTAMPTZ,
    sign_off_note       VARCHAR(1000),
    cancel_reason       VARCHAR(500),
    version             BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_pr_two_person CHECK (signed_off_by_id IS NULL OR signed_off_by_id <> uploaded_by_id),
    CONSTRAINT ck_pr_signed CHECK ((status = 'SIGNED_OFF') = (signed_off_at IS NOT NULL))
);
CREATE INDEX ix_parallel_runs_period ON parallel_runs (period_id, uploaded_at);

-- Amounts are signed balances, debit positive: the closing balance or the period's net movement.
CREATE TABLE parallel_run_lines (
    id            UUID PRIMARY KEY,
    run_id        UUID          NOT NULL REFERENCES parallel_runs (id),
    line_type     VARCHAR(12)   NOT NULL CHECK (line_type IN ('MATCHED', 'DIFFERENT', 'CAS_ONLY', 'LEGACY_ONLY', 'UNMAPPED')),
    account_id    UUID          REFERENCES chart_of_accounts (id),
    account_code  VARCHAR(40),
    account_name  VARCHAR(200),
    legacy_codes  VARCHAR(2000),
    legacy_amount NUMERIC(19,2) NOT NULL,
    cas_amount    NUMERIC(19,2) NOT NULL,
    difference    NUMERIC(19,2) NOT NULL,
    explanation   VARCHAR(1000),
    explained_by  VARCHAR(64),
    explained_at  TIMESTAMPTZ,
    CONSTRAINT ck_prl_account CHECK ((line_type = 'UNMAPPED') = (account_id IS NULL))
);
CREATE INDEX ix_parallel_run_lines_run ON parallel_run_lines (run_id, line_type);
