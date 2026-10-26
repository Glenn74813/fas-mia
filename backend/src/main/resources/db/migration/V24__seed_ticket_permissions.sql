-- Phase 4d permissions and a proposed support role (pending MIAA sign-off, C-08). Raising and following
-- one's own tickets is open to every internal role.

INSERT INTO permissions (code, description, category, financial) VALUES
    ('TICKET_CREATE',   'Raise support tickets and follow one''s own',                        'SUPPORT', FALSE),
    ('TICKET_VIEW_ALL', 'View all support tickets',                                           'SUPPORT', FALSE),
    ('TICKET_WORK',     'Acknowledge, assign, work on and resolve support tickets',           'SUPPORT', FALSE),
    ('TICKET_APPROVE',  'Approve or reject change requests and enhancements',                 'SUPPORT', FALSE),
    ('TICKET_ADMIN',    'Maintain SLA policies and the holiday calendar',                     'SUPPORT', FALSE);

INSERT INTO roles (id, code, name, description, system_role, created_by, updated_by) VALUES
    (gen_random_uuid(), 'SUPPORT_AGENT', 'Application Support', 'Proposed role: works support tickets (C-08)', TRUE, 'system', 'system');

INSERT INTO role_permissions (role_id, permission_code)
SELECT r.id, 'TICKET_CREATE' FROM roles r WHERE r.code <> 'CONCESSIONAIRE';

INSERT INTO role_permissions (role_id, permission_code)
SELECT r.id, p.code FROM roles r JOIN (VALUES
    ('SUPPORT_AGENT', 'TICKET_VIEW_ALL'), ('SUPPORT_AGENT', 'TICKET_WORK'),
    ('SYSTEM_ADMINISTRATOR', 'TICKET_VIEW_ALL'), ('SYSTEM_ADMINISTRATOR', 'TICKET_WORK'), ('SYSTEM_ADMINISTRATOR', 'TICKET_ADMIN'),
    ('FINANCE_MANAGER', 'TICKET_VIEW_ALL'), ('FINANCE_MANAGER', 'TICKET_APPROVE'),
    ('INTERNAL_AUDITOR', 'TICKET_VIEW_ALL')
) AS p (role_code, code) ON p.role_code = r.code;

INSERT INTO sod_conflict_rules (id, permission_a, permission_b, severity, rationale) VALUES
    (gen_random_uuid(), 'TICKET_WORK', 'TICKET_APPROVE', 'WARN', 'Whoever implements a change should not approve it; the requester can never approve their own');
