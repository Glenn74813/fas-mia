-- Phase 4c permissions, proposed role assignments (pending MIAA sign-off, C-08) and SoD rules.

INSERT INTO permissions (code, description, category, financial) VALUES
    ('INTEGRATION_VIEW',     'View the integration transmission log',                                   'INTEGRATION', FALSE),
    ('INTEGRATION_MANAGE',   'Queue, retry and cancel integration transmissions',                       'INTEGRATION', FALSE),
    ('SUPPLIER_BANK_VERIFY', 'Verify supplier bank accounts used for electronic payments',              'PAYABLES',    TRUE);

INSERT INTO role_permissions (role_id, permission_code)
SELECT r.id, p.code FROM roles r JOIN (VALUES
    ('CHIEF_ACCOUNTANT', 'INTEGRATION_VIEW'), ('CHIEF_ACCOUNTANT', 'INTEGRATION_MANAGE'), ('CHIEF_ACCOUNTANT', 'SUPPLIER_BANK_VERIFY'),
    ('ACCOUNTANT', 'INTEGRATION_VIEW'), ('FINANCE_MANAGER', 'INTEGRATION_VIEW'),
    ('SYSTEM_ADMINISTRATOR', 'INTEGRATION_VIEW'), ('SYSTEM_ADMINISTRATOR', 'INTEGRATION_MANAGE'),
    ('INTERNAL_AUDITOR', 'INTEGRATION_VIEW'), ('EXTERNAL_AUDITOR', 'INTEGRATION_VIEW')
) AS p (role_code, code) ON p.role_code = r.code;

INSERT INTO sod_conflict_rules (id, permission_a, permission_b, severity, rationale) VALUES
    (gen_random_uuid(), 'USER_ADMINISTER', 'SUPPLIER_BANK_VERIFY', 'BLOCK', 'Technical administration must remain separate from financial authority'),
    (gen_random_uuid(), 'ROLE_ADMINISTER', 'SUPPLIER_BANK_VERIFY', 'BLOCK', 'Technical administration must remain separate from financial authority'),
    (gen_random_uuid(), 'AUDIT_LOG_VIEW', 'SUPPLIER_BANK_VERIFY', 'BLOCK', 'Auditor independence: reviewers of the audit trail may not originate or authorise transactions'),
    (gen_random_uuid(), 'SUPPLIER_BANK_VERIFY', 'PAYMENT_RELEASE', 'BLOCK', 'Whoever verifies payee bank details may not release payments'),
    (gen_random_uuid(), 'SUPPLIER_MANAGE', 'SUPPLIER_BANK_VERIFY', 'WARN', 'Permitted at role level; the person who entered the bank details can never verify them');
