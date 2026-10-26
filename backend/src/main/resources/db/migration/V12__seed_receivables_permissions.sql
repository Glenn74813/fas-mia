-- Phase 3b permissions, proposed role assignments (pending MIAA sign-off, C-08), SoD rules and workflows.

INSERT INTO permissions (code, description, category, financial) VALUES
    ('AR_VIEW',             'View customers, contracts, billings, collections and receivables reports', 'RECEIVABLES', FALSE),
    ('CUSTOMER_MANAGE',     'Maintain customers, concessionaires and their contracts',                 'RECEIVABLES', FALSE),
    ('AR_BILLING_PREPARE',  'Prepare billing invoices and credit/debit memos',                         'RECEIVABLES', TRUE),
    ('AR_BILLING_APPROVE',  'Approve billing invoices and credit/debit memos',                         'RECEIVABLES', TRUE);

INSERT INTO role_permissions (role_id, permission_code)
SELECT r.id, p.code FROM roles r JOIN (VALUES
    ('ACCOUNTANT', 'AR_VIEW'), ('ACCOUNTANT', 'CUSTOMER_MANAGE'), ('ACCOUNTANT', 'AR_BILLING_PREPARE'),
    ('CHIEF_ACCOUNTANT', 'AR_VIEW'), ('CHIEF_ACCOUNTANT', 'AR_BILLING_APPROVE'),
    ('FINANCE_MANAGER', 'AR_VIEW'),
    ('CASHIER', 'AR_VIEW'), ('CASHIER', 'DOCUMENT_UPLOAD'),
    ('FINANCIAL_REPORTING', 'AR_VIEW'),
    ('INTERNAL_AUDITOR', 'AR_VIEW'), ('EXTERNAL_AUDITOR', 'AR_VIEW'),
    ('MANAGEMENT_VIEWER', 'AR_VIEW')
) AS p (role_code, code) ON p.role_code = r.code;

INSERT INTO sod_conflict_rules (id, permission_a, permission_b, severity, rationale)
SELECT gen_random_uuid(), a.code, f.code, 'BLOCK', 'Technical administration must remain separate from financial authority'
FROM permissions a CROSS JOIN permissions f
WHERE a.code IN ('USER_ADMINISTER', 'ROLE_ADMINISTER') AND f.code IN ('AR_BILLING_PREPARE', 'AR_BILLING_APPROVE');

INSERT INTO sod_conflict_rules (id, permission_a, permission_b, severity, rationale)
SELECT gen_random_uuid(), 'AUDIT_LOG_VIEW', f.code, 'BLOCK', 'Auditor independence: reviewers of the audit trail may not originate or authorise transactions'
FROM permissions f WHERE f.code IN ('AR_BILLING_PREPARE', 'AR_BILLING_APPROVE');

INSERT INTO sod_conflict_rules (id, permission_a, permission_b, severity, rationale) VALUES
    (gen_random_uuid(), 'COLLECTION_RECORD', 'AR_BILLING_PREPARE', 'BLOCK', 'Collectors may not bill the same customers (lapping risk)'),
    (gen_random_uuid(), 'COLLECTION_RECORD', 'CUSTOMER_MANAGE', 'BLOCK', 'Collectors may not maintain customer records'),
    (gen_random_uuid(), 'AR_BILLING_PREPARE', 'AR_BILLING_APPROVE', 'WARN', 'Permitted at role level; the preparer can never approve their own billing');

INSERT INTO document_workflow_steps (id, document_type, step_order, step_code, required_permission, min_amount) VALUES
    (gen_random_uuid(), 'AR_INVOICE', 1, 'APPROVE', 'AR_BILLING_APPROVE', NULL),
    (gen_random_uuid(), 'AR_MEMO', 1, 'APPROVE', 'AR_BILLING_APPROVE', NULL);
