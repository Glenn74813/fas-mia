-- Recurring report runs (MS §11.8). A schedule queues an ordinary report job for its owner; parameters may use
-- relative tokens such as {prevMonthStart} that are resolved when the job is queued. The owner must still be active and
-- hold the report's permission at every run, otherwise the schedule is suspended.

CREATE TABLE report_schedules (
    id              UUID PRIMARY KEY,
    owner_id        UUID          NOT NULL REFERENCES users (id),
    owner           VARCHAR(64)   NOT NULL,
    name            VARCHAR(200)  NOT NULL,
    report_type     VARCHAR(40)   NOT NULL,
    format          VARCHAR(5)    NOT NULL CHECK (format IN ('CSV', 'XLSX', 'PDF')),
    parameters      JSONB         NOT NULL DEFAULT '{}'::jsonb,
    frequency       VARCHAR(8)    NOT NULL CHECK (frequency IN ('DAILY', 'WEEKLY', 'MONTHLY')),
    run_hour        SMALLINT      NOT NULL CHECK (run_hour BETWEEN 0 AND 23),
    day_of_week     SMALLINT      CHECK (day_of_week BETWEEN 1 AND 7),
    day_of_month    SMALLINT      CHECK (day_of_month BETWEEN 1 AND 28),
    status          VARCHAR(10)   NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED')),
    status_reason   VARCHAR(500),
    next_run_at     TIMESTAMPTZ   NOT NULL,
    last_run_at     TIMESTAMPTZ,
    last_job_id     UUID          REFERENCES report_jobs (id),
    created_at      TIMESTAMPTZ   NOT NULL,
    version         BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_rs_weekly CHECK (frequency <> 'WEEKLY' OR day_of_week IS NOT NULL),
    CONSTRAINT ck_rs_monthly CHECK (frequency <> 'MONTHLY' OR day_of_month IS NOT NULL)
);
CREATE INDEX ix_report_schedules_due ON report_schedules (next_run_at) WHERE status = 'ACTIVE';
CREATE INDEX ix_report_schedules_owner ON report_schedules (owner_id, status);

-- The operational report types (Cash Disbursement Journal, collection summary, ledgers, DCR, bank reconciliation
-- statement, withholding schedule) run as ordinary report jobs.
ALTER TABLE report_jobs ALTER COLUMN report_type TYPE VARCHAR(40);
ALTER TABLE report_jobs DROP CONSTRAINT report_jobs_report_type_check;
ALTER TABLE report_jobs ADD CONSTRAINT report_jobs_report_type_check CHECK (report_type IN ('FINANCIAL_STATEMENT', 'TRIAL_BALANCE',
    'BUDGET_VS_ACTUAL', 'AP_AGING', 'AR_AGING', 'PPE_SCHEDULE', 'INVENTORY_STOCK', 'AUDIT_TRAIL', 'CASH_DISBURSEMENT_JOURNAL',
    'COLLECTION_SUMMARY', 'GENERAL_LEDGER', 'SUBSIDIARY_LEDGER', 'DAILY_COLLECTION_REPORT', 'BANK_RECONCILIATION_STATEMENT',
    'WITHHOLDING_SCHEDULE'));
