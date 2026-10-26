-- Default permission catalogue, roles and segregation-of-duties rules.
-- These role definitions are PROPOSALS pending MIAA sign-off of the role-permission matrix
-- (docs/03-clarification-register.md C-08). They are data, editable through the role API.
-- Financial permissions exist now so SoD rules can be enforced from day one; the
-- corresponding modules are delivered in Phases 2-4.

INSERT INTO permissions (code, description, category, financial) VALUES
    ('USER_VIEW',                   'View user accounts',                                  'ADMINISTRATION', FALSE),
    ('USER_ADMINISTER',             'Create, update, activate and deactivate users',       'ADMINISTRATION', FALSE),
    ('ROLE_VIEW',                   'View roles and permissions',                           'ADMINISTRATION', FALSE),
    ('ROLE_ADMINISTER',             'Create roles and change role permissions',             'ADMINISTRATION', FALSE),
    ('SESSION_REVOKE',              'Revoke other users'' sessions',                        'ADMINISTRATION', FALSE),
    ('SECURITY_EVENT_VIEW',         'View security and authentication events',              'AUDIT',          FALSE),
    ('AUDIT_LOG_VIEW',              'View and verify the audit trail',                      'AUDIT',          FALSE),
    ('DASHBOARD_VIEW',              'View management dashboards',                           'REPORTING',      FALSE),
    ('LEDGER_VIEW',                 'View general and subsidiary ledgers',                  'ACCOUNTING',     FALSE),
    ('JOURNAL_CREATE',              'Prepare journal entry vouchers',                       'ACCOUNTING',     TRUE),
    ('JOURNAL_SUBMIT',              'Submit journal entry vouchers for review',             'ACCOUNTING',     TRUE),
    ('JOURNAL_REVIEW',              'Review journal entry vouchers',                        'ACCOUNTING',     TRUE),
    ('JOURNAL_APPROVE',             'Approve journal entry vouchers',                       'ACCOUNTING',     TRUE),
    ('JOURNAL_POST',                'Post approved vouchers to the general ledger',         'ACCOUNTING',     TRUE),
    ('JOURNAL_REVERSE',             'Reverse posted journal entries',                       'ACCOUNTING',     TRUE),
    ('PERIOD_CLOSE',                'Close accounting periods',                             'ACCOUNTING',     TRUE),
    ('PERIOD_REOPEN',               'Reopen closed accounting periods',                     'ACCOUNTING',     TRUE),
    ('BUDGET_VIEW',                 'View budgets and availability',                        'BUDGET',         FALSE),
    ('BUDGET_RESERVE',              'Reserve budget for in-process vouchers',               'BUDGET',         TRUE),
    ('BUDGET_APPROVE',              'Approve budgets and budget adjustments',               'BUDGET',         TRUE),
    ('DISBURSEMENT_PREPARE',        'Prepare disbursement vouchers',                        'PAYABLES',       TRUE),
    ('PAYMENT_APPROVE',             'Approve payments',                                     'PAYABLES',       TRUE),
    ('COLLECTION_RECORD',           'Record collections and receipts',                      'TREASURY',       TRUE),
    ('BANK_RECONCILIATION_APPROVE', 'Approve bank reconciliations',                         'TREASURY',       TRUE),
    ('FINANCIAL_REPORT_VIEW',       'View financial reports',                               'REPORTING',      FALSE),
    ('FINANCIAL_REPORT_EXPORT',     'Export financial reports',                             'REPORTING',      FALSE),
    ('DOCUMENT_PURGE_REQUEST',      'Request lawful purge of retained records',             'RECORDS',        FALSE),
    ('DOCUMENT_PURGE_APPROVE',      'Approve lawful purge of retained records',             'RECORDS',        FALSE),
    ('PORTAL_SELF_VIEW',            'Concessionaire: view own account in the portal',       'PORTAL',         FALSE);

CREATE TEMPORARY TABLE seed_role_permissions (role_code VARCHAR(64), name VARCHAR(120), permission_code VARCHAR(64)) ON COMMIT DROP;
INSERT INTO seed_role_permissions (role_code, name, permission_code) VALUES
    ('SYSTEM_ADMINISTRATOR', 'System Administrator', 'USER_VIEW'),
    ('SYSTEM_ADMINISTRATOR', 'System Administrator', 'USER_ADMINISTER'),
    ('SYSTEM_ADMINISTRATOR', 'System Administrator', 'ROLE_VIEW'),
    ('SYSTEM_ADMINISTRATOR', 'System Administrator', 'ROLE_ADMINISTER'),
    ('SYSTEM_ADMINISTRATOR', 'System Administrator', 'SESSION_REVOKE'),
    ('SYSTEM_ADMINISTRATOR', 'System Administrator', 'SECURITY_EVENT_VIEW'),
    ('CHIEF_ACCOUNTANT', 'Chief Accountant', 'LEDGER_VIEW'),
    ('CHIEF_ACCOUNTANT', 'Chief Accountant', 'JOURNAL_REVIEW'),
    ('CHIEF_ACCOUNTANT', 'Chief Accountant', 'JOURNAL_APPROVE'),
    ('CHIEF_ACCOUNTANT', 'Chief Accountant', 'JOURNAL_POST'),
    ('CHIEF_ACCOUNTANT', 'Chief Accountant', 'JOURNAL_REVERSE'),
    ('CHIEF_ACCOUNTANT', 'Chief Accountant', 'PERIOD_CLOSE'),
    ('CHIEF_ACCOUNTANT', 'Chief Accountant', 'FINANCIAL_REPORT_VIEW'),
    ('CHIEF_ACCOUNTANT', 'Chief Accountant', 'FINANCIAL_REPORT_EXPORT'),
    ('ACCOUNTANT', 'Accountant', 'LEDGER_VIEW'),
    ('ACCOUNTANT', 'Accountant', 'JOURNAL_CREATE'),
    ('ACCOUNTANT', 'Accountant', 'JOURNAL_SUBMIT'),
    ('ACCOUNTANT', 'Accountant', 'JOURNAL_REVIEW'),
    ('FINANCE_MANAGER', 'Finance Manager', 'LEDGER_VIEW'),
    ('FINANCE_MANAGER', 'Finance Manager', 'PAYMENT_APPROVE'),
    ('FINANCE_MANAGER', 'Finance Manager', 'BUDGET_APPROVE'),
    ('FINANCE_MANAGER', 'Finance Manager', 'PERIOD_REOPEN'),
    ('FINANCE_MANAGER', 'Finance Manager', 'FINANCIAL_REPORT_VIEW'),
    ('FINANCE_MANAGER', 'Finance Manager', 'FINANCIAL_REPORT_EXPORT'),
    ('FINANCE_MANAGER', 'Finance Manager', 'DASHBOARD_VIEW'),
    ('BUDGET_OFFICER', 'Budget Officer', 'LEDGER_VIEW'),
    ('BUDGET_OFFICER', 'Budget Officer', 'BUDGET_VIEW'),
    ('BUDGET_OFFICER', 'Budget Officer', 'BUDGET_RESERVE'),
    ('CASHIER', 'Collection and Cashiering', 'LEDGER_VIEW'),
    ('CASHIER', 'Collection and Cashiering', 'COLLECTION_RECORD'),
    ('DISBURSEMENT_OFFICER', 'Disbursement Officer', 'LEDGER_VIEW'),
    ('DISBURSEMENT_OFFICER', 'Disbursement Officer', 'DISBURSEMENT_PREPARE'),
    ('FINANCIAL_REPORTING', 'Financial Reporting', 'LEDGER_VIEW'),
    ('FINANCIAL_REPORTING', 'Financial Reporting', 'FINANCIAL_REPORT_VIEW'),
    ('FINANCIAL_REPORTING', 'Financial Reporting', 'FINANCIAL_REPORT_EXPORT'),
    ('INTERNAL_AUDITOR', 'Internal Auditor', 'LEDGER_VIEW'),
    ('INTERNAL_AUDITOR', 'Internal Auditor', 'AUDIT_LOG_VIEW'),
    ('INTERNAL_AUDITOR', 'Internal Auditor', 'SECURITY_EVENT_VIEW'),
    ('INTERNAL_AUDITOR', 'Internal Auditor', 'FINANCIAL_REPORT_VIEW'),
    ('EXTERNAL_AUDITOR', 'External Auditor (COA)', 'LEDGER_VIEW'),
    ('EXTERNAL_AUDITOR', 'External Auditor (COA)', 'AUDIT_LOG_VIEW'),
    ('EXTERNAL_AUDITOR', 'External Auditor (COA)', 'FINANCIAL_REPORT_VIEW'),
    ('MANAGEMENT_VIEWER', 'Management (read-only)', 'FINANCIAL_REPORT_VIEW'),
    ('MANAGEMENT_VIEWER', 'Management (read-only)', 'DASHBOARD_VIEW'),
    ('CONCESSIONAIRE', 'Concessionaire', 'PORTAL_SELF_VIEW');

INSERT INTO roles (id, code, name, system_role, created_by, updated_by)
SELECT gen_random_uuid(), role_code, min(name), TRUE, 'system', 'system'
FROM seed_role_permissions GROUP BY role_code;

INSERT INTO role_permissions (role_id, permission_code)
SELECT r.id, s.permission_code FROM seed_role_permissions s JOIN roles r ON r.code = s.role_code;

-- Technical administration must never be combined with financial authority.
INSERT INTO sod_conflict_rules (id, permission_a, permission_b, severity, rationale)
SELECT gen_random_uuid(), a.code, f.code, 'BLOCK', 'Technical administration must remain separate from financial authority'
FROM permissions a CROSS JOIN permissions f
WHERE a.code IN ('USER_ADMINISTER', 'ROLE_ADMINISTER') AND f.financial;

-- Auditor independence: audit-trail reviewers may not originate or approve transactions.
INSERT INTO sod_conflict_rules (id, permission_a, permission_b, severity, rationale)
SELECT gen_random_uuid(), 'AUDIT_LOG_VIEW', f.code, 'BLOCK', 'Auditor independence: reviewers of the audit trail may not originate or authorise transactions'
FROM permissions f WHERE f.financial;

INSERT INTO sod_conflict_rules (id, permission_a, permission_b, severity, rationale) VALUES
    (gen_random_uuid(), 'JOURNAL_CREATE', 'JOURNAL_POST', 'BLOCK', 'Maker of a voucher may not hold posting authority'),
    (gen_random_uuid(), 'JOURNAL_CREATE', 'JOURNAL_APPROVE', 'WARN', 'Permitted at role level; transaction-level rule prevents approving own vouchers'),
    (gen_random_uuid(), 'DISBURSEMENT_PREPARE', 'PAYMENT_APPROVE', 'BLOCK', 'Maker-checker on payments'),
    (gen_random_uuid(), 'COLLECTION_RECORD', 'BANK_RECONCILIATION_APPROVE', 'BLOCK', 'Custody of cash separated from reconciliation'),
    (gen_random_uuid(), 'DOCUMENT_PURGE_REQUEST', 'DOCUMENT_PURGE_APPROVE', 'BLOCK', 'Dual control over destruction of records');

INSERT INTO organizations (id, code, name, created_by, updated_by)
VALUES (gen_random_uuid(), 'MIAA', 'Manila International Airport Authority', 'system', 'system');
