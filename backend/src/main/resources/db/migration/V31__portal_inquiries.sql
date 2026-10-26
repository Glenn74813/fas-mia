-- Concessionaire inquiries and payment references submitted through the portal (MS §11.3). A payment reference
-- informs the cashier; it posts nothing and is not a receipt. Staff answer in CAS and the concessionaire is notified.

CREATE TABLE portal_inquiries (
    id                 UUID PRIMARY KEY,
    reference          VARCHAR(30)   NOT NULL UNIQUE,
    customer_id        UUID          NOT NULL REFERENCES customers (id),
    submitted_by_id    UUID          NOT NULL REFERENCES users (id),
    submitted_by       VARCHAR(64)   NOT NULL,
    kind               VARCHAR(17)   NOT NULL CHECK (kind IN ('PAYMENT_REFERENCE', 'QUESTION')),
    subject            VARCHAR(200)  NOT NULL,
    message            VARCHAR(4000) NOT NULL,
    billing_id         UUID          REFERENCES billing_documents (id),
    payment_date       DATE,
    payment_amount     NUMERIC(19,2) CHECK (payment_amount IS NULL OR payment_amount > 0),
    payment_method     VARCHAR(16)   CHECK (payment_method IS NULL OR payment_method IN ('CASH', 'CHECK', 'BANK_TRANSFER')),
    payment_reference  VARCHAR(60),
    status             VARCHAR(10)   NOT NULL CHECK (status IN ('OPEN', 'ANSWERED', 'CLOSED')),
    response           VARCHAR(4000),
    responded_by       VARCHAR(64),
    responded_at       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ   NOT NULL,
    version            BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_pi_payment CHECK (kind <> 'PAYMENT_REFERENCE'
        OR (payment_date IS NOT NULL AND payment_amount IS NOT NULL AND payment_method IS NOT NULL AND payment_reference IS NOT NULL)),
    CONSTRAINT ck_pi_answered CHECK ((status = 'OPEN') = (response IS NULL))
);
CREATE INDEX ix_portal_inquiries_customer ON portal_inquiries (customer_id, created_at);
CREATE INDEX ix_portal_inquiries_status ON portal_inquiries (status, created_at);

INSERT INTO permissions (code, description, category, financial) VALUES
    ('PORTAL_INQUIRY_RESPOND', 'Answer concessionaire inquiries and payment references', 'RECEIVABLES', FALSE);

-- Proposed assignment pending MIAA sign-off (C-08).
INSERT INTO role_permissions (role_id, permission_code)
SELECT r.id, p.code FROM roles r JOIN (VALUES ('ACCOUNTANT', 'PORTAL_INQUIRY_RESPOND'), ('CASHIER', 'PORTAL_INQUIRY_RESPOND'))
    AS p (role_code, code) ON p.role_code = r.code;
