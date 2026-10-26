-- Phase 3c permissions, proposed role assignments (pending MIAA sign-off, C-08), SoD rules and workflow.

INSERT INTO permissions (code, description, category, financial) VALUES
    ('TREASURY_VIEW',                'View deposits, daily collection reports, cash position, statements and reconciliations', 'TREASURY', FALSE),
    ('DEPOSIT_PREPARE',              'Prepare deposit slips for collections',                                                 'TREASURY', TRUE),
    ('BANK_STATEMENT_IMPORT',        'Import bank statements',                                                               'TREASURY', TRUE),
    ('BANK_RECONCILIATION_PREPARE',  'Match bank statement lines and prepare bank reconciliations',                          'TREASURY', TRUE);

INSERT INTO role_permissions (role_id, permission_code)
SELECT r.id, p.code FROM roles r JOIN (VALUES
    ('CASHIER', 'TREASURY_VIEW'), ('CASHIER', 'DEPOSIT_PREPARE'),
    ('ACCOUNTANT', 'TREASURY_VIEW'), ('ACCOUNTANT', 'BANK_STATEMENT_IMPORT'), ('ACCOUNTANT', 'BANK_RECONCILIATION_PREPARE'),
    ('CHIEF_ACCOUNTANT', 'TREASURY_VIEW'),
    ('FINANCE_MANAGER', 'TREASURY_VIEW'), ('FINANCE_MANAGER', 'BANK_RECONCILIATION_APPROVE'),
    ('FINANCIAL_REPORTING', 'TREASURY_VIEW'), ('INTERNAL_AUDITOR', 'TREASURY_VIEW'), ('EXTERNAL_AUDITOR', 'TREASURY_VIEW'),
    ('MANAGEMENT_VIEWER', 'TREASURY_VIEW')
) AS p (role_code, code) ON p.role_code = r.code;

INSERT INTO sod_conflict_rules (id, permission_a, permission_b, severity, rationale)
SELECT gen_random_uuid(), a.code, f.code, 'BLOCK', 'Technical administration must remain separate from financial authority'
FROM permissions a CROSS JOIN permissions f
WHERE a.code IN ('USER_ADMINISTER', 'ROLE_ADMINISTER')
  AND f.code IN ('DEPOSIT_PREPARE', 'BANK_STATEMENT_IMPORT', 'BANK_RECONCILIATION_PREPARE');

INSERT INTO sod_conflict_rules (id, permission_a, permission_b, severity, rationale)
SELECT gen_random_uuid(), 'AUDIT_LOG_VIEW', f.code, 'BLOCK', 'Auditor independence: reviewers of the audit trail may not originate or authorise transactions'
FROM permissions f WHERE f.code IN ('DEPOSIT_PREPARE', 'BANK_STATEMENT_IMPORT', 'BANK_RECONCILIATION_PREPARE');

INSERT INTO sod_conflict_rules (id, permission_a, permission_b, severity, rationale) VALUES
    (gen_random_uuid(), 'COLLECTION_RECORD', 'BANK_RECONCILIATION_PREPARE', 'BLOCK', 'Custody of cash separated from reconciliation'),
    (gen_random_uuid(), 'PAYMENT_RELEASE', 'BANK_RECONCILIATION_PREPARE', 'BLOCK', 'Whoever releases payments may not reconcile the bank'),
    (gen_random_uuid(), 'DEPOSIT_PREPARE', 'BANK_RECONCILIATION_PREPARE', 'BLOCK', 'Whoever deposits cash may not reconcile the bank'),
    (gen_random_uuid(), 'DEPOSIT_PREPARE', 'BANK_RECONCILIATION_APPROVE', 'BLOCK', 'Whoever deposits cash may not approve the reconciliation'),
    (gen_random_uuid(), 'PAYMENT_RELEASE', 'BANK_RECONCILIATION_APPROVE', 'BLOCK', 'Whoever releases payments may not approve the reconciliation'),
    (gen_random_uuid(), 'BANK_RECONCILIATION_PREPARE', 'BANK_RECONCILIATION_APPROVE', 'WARN', 'Permitted at role level; the preparer can never approve their own reconciliation');

INSERT INTO document_workflow_steps (id, document_type, step_order, step_code, required_permission, min_amount) VALUES
    (gen_random_uuid(), 'BANK_RECONCILIATION', 1, 'APPROVE', 'BANK_RECONCILIATION_APPROVE', NULL);
