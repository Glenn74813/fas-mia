-- Phase 3a permissions, proposed role assignments (pending MIAA sign-off, C-08), SoD rules and
-- document workflows for supplier invoices, disbursement vouchers, budgets and budget adjustments.

INSERT INTO permissions (code, description, category, financial) VALUES
    ('AP_VIEW',              'View suppliers, invoices, disbursement vouchers and payables reports', 'PAYABLES', FALSE),
    ('SUPPLIER_MANAGE',      'Maintain the supplier master file',                                    'PAYABLES', FALSE),
    ('AP_INVOICE_PREPARE',   'Register supplier invoices',                                           'PAYABLES', TRUE),
    ('AP_INVOICE_APPROVE',   'Approve supplier invoices for recognition as payables',                'PAYABLES', TRUE),
    ('DV_CERTIFY',           'Certify disbursement vouchers (completeness of documents and funds)',  'PAYABLES', TRUE),
    ('PAYMENT_RELEASE',      'Issue checks / payment instructions and confirm payment release',      'TREASURY', TRUE),
    ('BANK_ACCOUNT_MANAGE',  'Maintain bank accounts used for payments and collections',             'TREASURY', TRUE),
    ('BUDGET_PREPARE',       'Prepare budgets and request budget adjustments',                       'BUDGET',   TRUE);

INSERT INTO role_permissions (role_id, permission_code)
SELECT r.id, p.code FROM roles r JOIN (VALUES
    ('ACCOUNTANT', 'AP_VIEW'), ('ACCOUNTANT', 'SUPPLIER_MANAGE'), ('ACCOUNTANT', 'AP_INVOICE_PREPARE'),
    ('ACCOUNTANT', 'DV_CERTIFY'), ('ACCOUNTANT', 'BUDGET_VIEW'),
    ('CHIEF_ACCOUNTANT', 'AP_VIEW'), ('CHIEF_ACCOUNTANT', 'AP_INVOICE_APPROVE'), ('CHIEF_ACCOUNTANT', 'BUDGET_VIEW'),
    ('CHIEF_ACCOUNTANT', 'BANK_ACCOUNT_MANAGE'),
    ('FINANCE_MANAGER', 'AP_VIEW'), ('FINANCE_MANAGER', 'BUDGET_VIEW'),
    ('DISBURSEMENT_OFFICER', 'AP_VIEW'), ('DISBURSEMENT_OFFICER', 'DOCUMENT_UPLOAD'), ('DISBURSEMENT_OFFICER', 'DOCUMENT_VIEW'),
    ('CASHIER', 'AP_VIEW'), ('CASHIER', 'PAYMENT_RELEASE'), ('CASHIER', 'DOCUMENT_VIEW'),
    ('BUDGET_OFFICER', 'BUDGET_PREPARE'),
    ('FINANCIAL_REPORTING', 'AP_VIEW'), ('FINANCIAL_REPORTING', 'BUDGET_VIEW'),
    ('INTERNAL_AUDITOR', 'AP_VIEW'), ('INTERNAL_AUDITOR', 'BUDGET_VIEW'),
    ('EXTERNAL_AUDITOR', 'AP_VIEW'), ('EXTERNAL_AUDITOR', 'BUDGET_VIEW')
) AS p (role_code, code) ON p.role_code = r.code;

-- Extend the standing rules to the new financial permissions.
INSERT INTO sod_conflict_rules (id, permission_a, permission_b, severity, rationale)
SELECT gen_random_uuid(), a.code, f.code, 'BLOCK', 'Technical administration must remain separate from financial authority'
FROM permissions a CROSS JOIN permissions f
WHERE a.code IN ('USER_ADMINISTER', 'ROLE_ADMINISTER')
  AND f.code IN ('AP_INVOICE_PREPARE', 'AP_INVOICE_APPROVE', 'DV_CERTIFY', 'PAYMENT_RELEASE', 'BANK_ACCOUNT_MANAGE', 'BUDGET_PREPARE');

INSERT INTO sod_conflict_rules (id, permission_a, permission_b, severity, rationale)
SELECT gen_random_uuid(), 'AUDIT_LOG_VIEW', f.code, 'BLOCK', 'Auditor independence: reviewers of the audit trail may not originate or authorise transactions'
FROM permissions f WHERE f.code IN ('AP_INVOICE_PREPARE', 'AP_INVOICE_APPROVE', 'DV_CERTIFY', 'PAYMENT_RELEASE',
                                    'BANK_ACCOUNT_MANAGE', 'BUDGET_PREPARE');

INSERT INTO sod_conflict_rules (id, permission_a, permission_b, severity, rationale) VALUES
    (gen_random_uuid(), 'SUPPLIER_MANAGE', 'PAYMENT_APPROVE', 'BLOCK', 'Whoever maintains supplier records may not approve payments to them'),
    (gen_random_uuid(), 'SUPPLIER_MANAGE', 'PAYMENT_RELEASE', 'BLOCK', 'Whoever maintains supplier records may not release payments'),
    (gen_random_uuid(), 'DISBURSEMENT_PREPARE', 'PAYMENT_RELEASE', 'BLOCK', 'Preparer of a disbursement may not release the payment'),
    (gen_random_uuid(), 'PAYMENT_APPROVE', 'PAYMENT_RELEASE', 'BLOCK', 'Approval and custody of payments are separated'),
    (gen_random_uuid(), 'AP_INVOICE_PREPARE', 'AP_INVOICE_APPROVE', 'WARN', 'Permitted at role level; the preparer can never approve their own invoice'),
    (gen_random_uuid(), 'BUDGET_PREPARE', 'BUDGET_APPROVE', 'BLOCK', 'Budget preparation and approval are separated'),
    (gen_random_uuid(), 'BANK_ACCOUNT_MANAGE', 'PAYMENT_RELEASE', 'BLOCK', 'Whoever defines bank accounts may not release payments');

INSERT INTO document_workflow_steps (id, document_type, step_order, step_code, required_permission, min_amount) VALUES
    (gen_random_uuid(), 'AP_INVOICE', 1, 'APPROVE', 'AP_INVOICE_APPROVE', NULL),
    (gen_random_uuid(), 'DISBURSEMENT_VOUCHER', 1, 'CERTIFY', 'DV_CERTIFY', NULL),
    (gen_random_uuid(), 'DISBURSEMENT_VOUCHER', 2, 'APPROVE', 'PAYMENT_APPROVE', NULL),
    (gen_random_uuid(), 'BUDGET', 1, 'APPROVE', 'BUDGET_APPROVE', NULL);
