-- Phase 3e permissions, a proposed inventory clerk role (pending MIAA sign-off, C-08) and SoD rules.

INSERT INTO permissions (code, description, category, financial) VALUES
    ('INVENTORY_VIEW',   'View items, stock, movements and inventory reports',                                  'INVENTORY', FALSE),
    ('INVENTORY_MANAGE', 'Maintain items and warehouses; prepare goods receipts, issues, counts and transfers', 'INVENTORY', TRUE);

INSERT INTO roles (id, code, name, description, system_role, created_by, updated_by) VALUES
    (gen_random_uuid(), 'INVENTORY_CLERK', 'Inventory and Supply', 'Proposed role: receives, issues and counts stock (C-08)', TRUE,
     'system', 'system');

INSERT INTO role_permissions (role_id, permission_code)
SELECT r.id, p.code FROM roles r JOIN (VALUES
    ('INVENTORY_CLERK', 'INVENTORY_VIEW'), ('INVENTORY_CLERK', 'INVENTORY_MANAGE'), ('INVENTORY_CLERK', 'LEDGER_VIEW'),
    ('INVENTORY_CLERK', 'DOCUMENT_UPLOAD'), ('INVENTORY_CLERK', 'DOCUMENT_VIEW'),
    ('ACCOUNTANT', 'INVENTORY_VIEW'), ('CHIEF_ACCOUNTANT', 'INVENTORY_VIEW'), ('FINANCE_MANAGER', 'INVENTORY_VIEW'),
    ('FINANCIAL_REPORTING', 'INVENTORY_VIEW'), ('INTERNAL_AUDITOR', 'INVENTORY_VIEW'), ('EXTERNAL_AUDITOR', 'INVENTORY_VIEW'),
    ('MANAGEMENT_VIEWER', 'INVENTORY_VIEW')
) AS p (role_code, code) ON p.role_code = r.code;

INSERT INTO sod_conflict_rules (id, permission_a, permission_b, severity, rationale) VALUES
    (gen_random_uuid(), 'USER_ADMINISTER', 'INVENTORY_MANAGE', 'BLOCK', 'Technical administration must remain separate from financial authority'),
    (gen_random_uuid(), 'ROLE_ADMINISTER', 'INVENTORY_MANAGE', 'BLOCK', 'Technical administration must remain separate from financial authority'),
    (gen_random_uuid(), 'AUDIT_LOG_VIEW', 'INVENTORY_MANAGE', 'BLOCK', 'Auditor independence: reviewers of the audit trail may not originate or authorise transactions'),
    (gen_random_uuid(), 'INVENTORY_MANAGE', 'AP_INVOICE_APPROVE', 'BLOCK', 'Whoever receives goods may not approve the supplier''s invoice'),
    (gen_random_uuid(), 'INVENTORY_MANAGE', 'PAYMENT_RELEASE', 'BLOCK', 'Custody of stock separated from release of payments'),
    (gen_random_uuid(), 'INVENTORY_MANAGE', 'JOURNAL_POST', 'BLOCK', 'Custody of stock separated from posting authority');
