-- Phase 3d permissions, proposed role assignments (pending MIAA sign-off, C-08) and SoD rules.

INSERT INTO permissions (code, description, category, financial) VALUES
    ('FA_VIEW',   'View the asset register, depreciation, disposals and PPE reports',                              'FIXED_ASSETS', FALSE),
    ('FA_MANAGE', 'Maintain asset categories and AUC projects; register assets, prepare depreciation and disposals', 'FIXED_ASSETS', TRUE);

INSERT INTO role_permissions (role_id, permission_code)
SELECT r.id, p.code FROM roles r JOIN (VALUES
    ('ACCOUNTANT', 'FA_VIEW'), ('ACCOUNTANT', 'FA_MANAGE'),
    ('CHIEF_ACCOUNTANT', 'FA_VIEW'), ('FINANCE_MANAGER', 'FA_VIEW'), ('FINANCIAL_REPORTING', 'FA_VIEW'),
    ('INTERNAL_AUDITOR', 'FA_VIEW'), ('EXTERNAL_AUDITOR', 'FA_VIEW'), ('MANAGEMENT_VIEWER', 'FA_VIEW')
) AS p (role_code, code) ON p.role_code = r.code;

INSERT INTO sod_conflict_rules (id, permission_a, permission_b, severity, rationale)
SELECT gen_random_uuid(), a, 'FA_MANAGE', 'BLOCK', r FROM (VALUES
    ('USER_ADMINISTER', 'Technical administration must remain separate from financial authority'),
    ('ROLE_ADMINISTER', 'Technical administration must remain separate from financial authority'),
    ('AUDIT_LOG_VIEW', 'Auditor independence: reviewers of the audit trail may not originate or authorise transactions')
) AS v (a, r);
