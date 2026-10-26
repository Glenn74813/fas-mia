-- Phase 4b: management reports and the audit-trail export run through the same report job queue.
ALTER TABLE report_jobs DROP CONSTRAINT report_jobs_report_type_check;
ALTER TABLE report_jobs ADD CONSTRAINT report_jobs_report_type_check CHECK (report_type IN (
    'FINANCIAL_STATEMENT', 'TRIAL_BALANCE', 'BUDGET_VS_ACTUAL', 'AP_AGING', 'AR_AGING', 'PPE_SCHEDULE', 'INVENTORY_STOCK',
    'AUDIT_TRAIL'));
