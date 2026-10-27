-- Anomaly detection (TOR §2.2.3: "deploy anomaly detection algorithms to identify suspicious financial activities;
-- implement automated alerts"). Rule-based detectors write findings here; each finding is reported once (fingerprint)
-- and reviewed by an auditor as EXPLAINED or ESCALATED with a note. The people involved in the document are kept, so
-- none of them can review it.
CREATE TABLE anomaly_findings (
    id              UUID PRIMARY KEY,
    rule_code       VARCHAR(40)    NOT NULL,
    severity        VARCHAR(6)     NOT NULL CHECK (severity IN ('HIGH', 'MEDIUM', 'LOW')),
    fingerprint     VARCHAR(200)   NOT NULL UNIQUE,
    document_type   VARCHAR(30),
    document_id     UUID,
    reference       VARCHAR(100),
    amount          NUMERIC(19,2),
    occurred_on     DATE,
    title           VARCHAR(300)   NOT NULL,
    detail          VARCHAR(2000)  NOT NULL,
    involved_users  VARCHAR(64)[]  NOT NULL DEFAULT '{}',
    detected_at     TIMESTAMPTZ    NOT NULL,
    status          VARCHAR(10)    NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'EXPLAINED', 'ESCALATED')),
    review_note     VARCHAR(2000),
    reviewed_by_id  UUID           REFERENCES users (id),
    reviewed_by     VARCHAR(64),
    reviewed_at     TIMESTAMPTZ,
    version         BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT ck_anomaly_reviewed CHECK ((status = 'OPEN') = (reviewed_at IS NULL))
);
CREATE INDEX ix_anomaly_open ON anomaly_findings (severity, detected_at DESC) WHERE status = 'OPEN';

INSERT INTO permissions (code, description, category, financial) VALUES
    ('ANOMALY_VIEW', 'See anomaly findings (suspicious-activity detection)', 'AUDIT', FALSE),
    ('ANOMALY_REVIEW', 'Review anomaly findings as explained or escalated, and run detection', 'AUDIT', FALSE);
INSERT INTO role_permissions (role_id, permission_code)
SELECT r.id, 'ANOMALY_VIEW' FROM roles r WHERE r.code IN ('INTERNAL_AUDITOR', 'EXTERNAL_AUDITOR', 'CHIEF_ACCOUNTANT', 'FINANCE_MANAGER')
ON CONFLICT DO NOTHING;
INSERT INTO role_permissions (role_id, permission_code)
SELECT r.id, 'ANOMALY_REVIEW' FROM roles r WHERE r.code IN ('INTERNAL_AUDITOR', 'CHIEF_ACCOUNTANT')
ON CONFLICT DO NOTHING;
