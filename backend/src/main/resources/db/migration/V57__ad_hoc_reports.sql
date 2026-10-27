-- Ad hoc reports (TOR Annex B.5): accounting staff build reports over approved datasets (ledger lines, supplier
-- invoices, billings, disbursements, receipts) by choosing columns, filters, grouping, totals and order. Definitions
-- are saved, optionally shared with others who may see the dataset, and exported through the ordinary report job.
CREATE TABLE report_definitions (
    id           UUID PRIMARY KEY,
    name         VARCHAR(120)   NOT NULL,
    dataset      VARCHAR(30)    NOT NULL,
    definition   JSONB          NOT NULL,
    shared       BOOLEAN        NOT NULL DEFAULT FALSE,
    owner_id     UUID           NOT NULL REFERENCES users (id),
    owner        VARCHAR(64)    NOT NULL,
    created_at   TIMESTAMPTZ    NOT NULL,
    updated_at   TIMESTAMPTZ    NOT NULL,
    version      BIGINT         NOT NULL DEFAULT 0
);
CREATE INDEX ix_report_definitions_owner ON report_definitions (owner_id);

ALTER TABLE report_jobs DROP CONSTRAINT report_jobs_report_type_check;
ALTER TABLE report_jobs ADD CONSTRAINT report_jobs_report_type_check CHECK (report_type IN ('FINANCIAL_STATEMENT', 'TRIAL_BALANCE',
    'BUDGET_VS_ACTUAL', 'AP_AGING', 'AR_AGING', 'PPE_SCHEDULE', 'INVENTORY_STOCK', 'AUDIT_TRAIL', 'CASH_DISBURSEMENT_JOURNAL',
    'COLLECTION_SUMMARY', 'GENERAL_LEDGER', 'SUBSIDIARY_LEDGER', 'DAILY_COLLECTION_REPORT', 'BANK_RECONCILIATION_STATEMENT',
    'WITHHOLDING_SCHEDULE', 'VARIANCE_ANALYSIS', 'ABSTRACT_OF_BILLS', 'AD_HOC'));
