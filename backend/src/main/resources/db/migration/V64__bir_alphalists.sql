-- Details the BIR alphalists need and the tables did not hold. The file structures of RMC 7-2021 Annex A list an
-- individual payee by last, first and middle name, and the withholding agent by RDO code. The Summary Alphalist of
-- Withholding Taxes (SAWT) lists the certificates (BIR Form 2307) customers give for the tax they withheld from a receipt.
ALTER TABLE suppliers
    ADD COLUMN last_name   VARCHAR(30),
    ADD COLUMN first_name  VARCHAR(30),
    ADD COLUMN middle_name VARCHAR(30);

ALTER TABLE organizations
    ADD COLUMN rdo_code VARCHAR(3) CHECK (rdo_code IS NULL OR rdo_code ~ '^[0-9]{3}$');

-- One certificate per receipt. It often arrives after the receipt is posted, so it is kept apart from the receipt, which
-- stays as posted; replacing a certificate is recorded in the audit trail.
CREATE TABLE receipt_withholding_certificates (
    receipt_id     UUID          PRIMARY KEY REFERENCES receipts (id),
    atc            VARCHAR(5)    NOT NULL REFERENCES bir_atc_codes (atc),
    income_payment NUMERIC(19, 2) NOT NULL CHECK (income_payment > 0),
    received_on    DATE          NOT NULL,
    recorded_by    VARCHAR(100)  NOT NULL,
    recorded_at    TIMESTAMPTZ   NOT NULL
);
