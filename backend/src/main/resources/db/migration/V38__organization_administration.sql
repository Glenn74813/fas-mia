-- Organisation administration (MS §6.2, §4 item 18, FR-ORG-01) over the V1 tables. MIAA's offices and departments
-- are entered by administrators; none are seeded (the structure is MIAA's to supply).

-- A department may name the cost centre its work is usually charged to.
ALTER TABLE departments ADD COLUMN cost_center_id UUID REFERENCES cost_centers (id);

-- One current office assignment per user; the history is kept.
ALTER TABLE user_office_assignments ADD COLUMN ended_by VARCHAR(64);
CREATE UNIQUE INDEX uq_uoa_current ON user_office_assignments (user_id) WHERE effective_to IS NULL;

INSERT INTO permissions (code, description, category, financial) VALUES
    ('ORGANIZATION_ADMINISTER', 'Maintain organisations, offices, departments and users'' office assignments', 'ADMINISTRATION', FALSE);

-- Proposed assignment pending MIAA sign-off (C-08).
INSERT INTO role_permissions (role_id, permission_code)
SELECT r.id, 'ORGANIZATION_ADMINISTER' FROM roles r WHERE r.code = 'SYSTEM_ADMINISTRATOR';
