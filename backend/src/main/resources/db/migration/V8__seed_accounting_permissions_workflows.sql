-- Phase 2 permissions, their (proposed) role assignments, SoD rules and default voucher workflows.
-- Like V4, these are proposals pending MIAA sign-off (C-08) and are editable through the role API.

INSERT INTO permissions (code, description, category, financial) VALUES
    ('ACCOUNT_ADMINISTER',        'Maintain the chart of accounts, cost centres and subsidiary-ledger accounts', 'ACCOUNTING', TRUE),
    ('PERIOD_OPEN',               'Create fiscal years and open accounting periods',                           'ACCOUNTING', TRUE),
    ('RECURRING_JOURNAL_MANAGE',  'Maintain recurring journal templates',                                      'ACCOUNTING', TRUE),
    ('LEDGER_REBUILD',            'Rebuild derived ledger balances from posted entries',                       'ACCOUNTING', TRUE),
    ('DOCUMENT_UPLOAD',           'Attach supporting documents to own draft vouchers',                         'RECORDS',    FALSE),
    ('DOCUMENT_VIEW',             'View and download supporting documents',                                    'RECORDS',    FALSE);

INSERT INTO role_permissions (role_id, permission_code)
SELECT r.id, p.code FROM roles r JOIN (VALUES
    ('CHIEF_ACCOUNTANT', 'ACCOUNT_ADMINISTER'),
    ('CHIEF_ACCOUNTANT', 'PERIOD_OPEN'),
    ('CHIEF_ACCOUNTANT', 'LEDGER_REBUILD'),
    ('CHIEF_ACCOUNTANT', 'DOCUMENT_VIEW'),
    ('ACCOUNTANT', 'DOCUMENT_UPLOAD'),
    ('ACCOUNTANT', 'DOCUMENT_VIEW'),
    ('ACCOUNTANT', 'RECURRING_JOURNAL_MANAGE'),
    ('FINANCE_MANAGER', 'DOCUMENT_VIEW'),
    ('FINANCIAL_REPORTING', 'DOCUMENT_VIEW'),
    ('INTERNAL_AUDITOR', 'DOCUMENT_VIEW'),
    ('EXTERNAL_AUDITOR', 'DOCUMENT_VIEW')
) AS p (role_code, code) ON p.role_code = r.code;

-- Extend the generated V4 rules to the new financial permissions.
INSERT INTO sod_conflict_rules (id, permission_a, permission_b, severity, rationale)
SELECT gen_random_uuid(), a.code, f.code, 'BLOCK', 'Technical administration must remain separate from financial authority'
FROM permissions a CROSS JOIN permissions f
WHERE a.code IN ('USER_ADMINISTER', 'ROLE_ADMINISTER')
  AND f.code IN ('ACCOUNT_ADMINISTER', 'PERIOD_OPEN', 'RECURRING_JOURNAL_MANAGE', 'LEDGER_REBUILD');

INSERT INTO sod_conflict_rules (id, permission_a, permission_b, severity, rationale)
SELECT gen_random_uuid(), 'AUDIT_LOG_VIEW', f.code, 'BLOCK', 'Auditor independence: reviewers of the audit trail may not originate or authorise transactions'
FROM permissions f WHERE f.code IN ('ACCOUNT_ADMINISTER', 'PERIOD_OPEN', 'RECURRING_JOURNAL_MANAGE', 'LEDGER_REBUILD');

INSERT INTO sod_conflict_rules (id, permission_a, permission_b, severity, rationale) VALUES
    (gen_random_uuid(), 'ACCOUNT_ADMINISTER', 'JOURNAL_CREATE', 'WARN', 'Whoever maintains accounts should not normally also prepare vouchers'),
    (gen_random_uuid(), 'PERIOD_CLOSE', 'PERIOD_REOPEN', 'WARN', 'Reopen approval should come from someone other than the period closer');

-- Default workflows: review then approval by different people, neither the preparer.
INSERT INTO approval_workflows (voucher_type, description, requires_attachment) VALUES
    ('STANDARD',  'Manual journal entry voucher',                         TRUE),
    ('ADJUSTING', 'Adjusting entry (may target the adjustment period)',   TRUE),
    ('REVERSAL',  'Reversal of a posted entry (reason recorded)',         FALSE),
    ('OPENING',   'Opening balances for the first period of a year',      TRUE),
    ('RECURRING', 'Voucher generated from an approved recurring template', FALSE),
    ('CLOSING',   'Year-end closing of revenue and expense accounts',     FALSE);

INSERT INTO approval_workflow_steps (id, voucher_type, step_order, step_code, required_permission, min_amount)
SELECT gen_random_uuid(), w.voucher_type, s.step_order, s.step_code, s.permission, NULL
FROM approval_workflows w CROSS JOIN (VALUES
    (1, 'REVIEW', 'JOURNAL_REVIEW'),
    (2, 'APPROVE', 'JOURNAL_APPROVE')
) AS s (step_order, step_code, permission);
