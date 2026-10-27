-- TOR Annex A (Controlling): the Budget Division evaluates and approves in-process disbursement vouchers against the
-- Corporate Operating Budget. TOR Annex A (Cash Flow and Treasury): checks for processed DVs are prepared and approved.

INSERT INTO permissions (code, description, category, financial) VALUES
    ('DV_BUDGET_CERTIFY', 'Certify that a disbursement voucher is covered by the Corporate Operating Budget (Budget Division)', 'BUDGET', TRUE),
    ('CHECK_APPROVE', 'Approve a prepared check before it is released (second person)', 'TREASURY', TRUE);

-- Budget officers certify DVs; to do so they read the voucher, its invoices and documents (read-only payables access).
INSERT INTO role_permissions (role_id, permission_code)
SELECT r.id, p.code FROM roles r CROSS JOIN (VALUES ('DV_BUDGET_CERTIFY'), ('AP_VIEW')) AS p(code)
WHERE r.code = 'BUDGET_OFFICER' ON CONFLICT DO NOTHING;
-- The finance manager approves checks prepared by the cashier.
INSERT INTO role_permissions (role_id, permission_code)
SELECT r.id, 'CHECK_APPROVE' FROM roles r WHERE r.code = 'FINANCE_MANAGER' ON CONFLICT DO NOTHING;

INSERT INTO sod_conflict_rules (id, permission_a, permission_b, severity, rationale) VALUES
    (gen_random_uuid(), 'DISBURSEMENT_PREPARE', 'DV_BUDGET_CERTIFY', 'BLOCK', 'Whoever prepares a DV may not also certify its budget cover'),
    (gen_random_uuid(), 'PAYMENT_RELEASE', 'CHECK_APPROVE', 'BLOCK', 'Whoever prepares and releases checks may not also approve them'),
    (gen_random_uuid(), 'DISBURSEMENT_PREPARE', 'CHECK_APPROVE', 'BLOCK', 'Whoever prepares a DV may not also approve its check');

-- The budget certification becomes the first step of the DV chain: BUDGET, CERTIFY, APPROVE.
UPDATE document_workflow_steps SET step_order = step_order + 100 WHERE document_type = 'DISBURSEMENT_VOUCHER';
UPDATE document_workflow_steps SET step_order = step_order - 99 WHERE document_type = 'DISBURSEMENT_VOUCHER';
INSERT INTO document_workflow_steps (id, document_type, step_order, step_code, required_permission, min_amount)
VALUES (gen_random_uuid(), 'DISBURSEMENT_VOUCHER', 1, 'BUDGET', 'DV_BUDGET_CERTIFY', NULL);
-- Vouchers already in the chain keep the step they had reached; the new step is not applied retroactively.
UPDATE disbursement_vouchers SET current_step_order = current_step_order + 1
WHERE status = 'SUBMITTED' AND current_step_order IS NOT NULL;

-- Checks: PREPARED (by the cashier) → ISSUED (approved by a second person) → RELEASED / CLEARED, or VOIDED.
ALTER TABLE check_register DROP CONSTRAINT check_register_status_check;
ALTER TABLE check_register ADD CONSTRAINT check_register_status_check
    CHECK (status IN ('PREPARED', 'ISSUED', 'RELEASED', 'VOIDED', 'CLEARED'));
ALTER TABLE check_register ADD COLUMN prepared_by_id UUID REFERENCES users (id);
ALTER TABLE check_register ADD COLUMN approved_by_id UUID REFERENCES users (id);
ALTER TABLE check_register ADD COLUMN approved_by VARCHAR(64);
ALTER TABLE check_register ADD COLUMN approved_at TIMESTAMPTZ;
ALTER TABLE check_register ADD CONSTRAINT ck_check_approved_by_another
    CHECK (approved_by_id IS NULL OR prepared_by_id IS NULL OR approved_by_id <> prepared_by_id);
DROP INDEX uq_check_live_dv;
CREATE UNIQUE INDEX uq_check_live_dv ON check_register (dv_id) WHERE status IN ('PREPARED', 'ISSUED', 'RELEASED');

-- Widening a VARCHAR needs no rewrite and leaves the append-only rows untouched.
ALTER TABLE approval_decisions ALTER COLUMN action TYPE VARCHAR(20);
ALTER TABLE approval_decisions DROP CONSTRAINT approval_decisions_action_check;
ALTER TABLE approval_decisions ADD CONSTRAINT approval_decisions_action_check
    CHECK (action IN ('SUBMIT', 'APPROVE', 'RETURN', 'REJECT', 'CANCEL', 'POST', 'INSTRUCT', 'CONFIRM', 'VOID', 'APPROVE_CHECK'));
