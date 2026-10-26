-- Phase 4c: outbound integration transmissions with retries, supplier bank accounts for electronic
-- payment (encrypted, verified by a second person), and payment-file batches for ADA disbursements.

CREATE TABLE integration_transmissions (
    id                  UUID PRIMARY KEY,
    channel             VARCHAR(30)   NOT NULL,
    document_type       VARCHAR(30)   NOT NULL,
    document_id         UUID          NOT NULL,
    -- Same channel + key is one transmission, however often it is requested.
    idempotency_key     VARCHAR(120)  NOT NULL,
    payload             JSONB         NOT NULL,
    payload_sha256      CHAR(64)      NOT NULL,
    status              VARCHAR(12)   NOT NULL CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'ACKNOWLEDGED', 'REJECTED', 'DEAD', 'CANCELLED')),
    attempts            INTEGER       NOT NULL DEFAULT 0,
    next_attempt_at     TIMESTAMPTZ   NOT NULL,
    last_error          VARCHAR(1000),
    external_reference  VARCHAR(200),
    simulated           BOOLEAN       NOT NULL DEFAULT FALSE,
    requested_by        VARCHAR(64)   NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL,
    updated_at          TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uq_transmission_key UNIQUE (channel, idempotency_key)
);
CREATE INDEX ix_transmissions_due ON integration_transmissions (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX ix_transmissions_document ON integration_transmissions (document_type, document_id);

CREATE TABLE integration_attempts (
    transmission_id  UUID          NOT NULL REFERENCES integration_transmissions (id),
    attempt_no       INTEGER       NOT NULL,
    started_at       TIMESTAMPTZ   NOT NULL,
    finished_at      TIMESTAMPTZ   NOT NULL,
    outcome          VARCHAR(12)   NOT NULL CHECK (outcome IN ('SENT', 'ACKNOWLEDGED', 'RETRYABLE', 'REJECTED')),
    endpoint         VARCHAR(300),
    http_status      INTEGER,
    -- Response bodies are kept short and as received; they may carry the receiver's reference or error.
    response_excerpt VARCHAR(2000),
    PRIMARY KEY (transmission_id, attempt_no)
);

CREATE TABLE supplier_bank_accounts (
    id                    UUID PRIMARY KEY,
    supplier_id           UUID          NOT NULL REFERENCES suppliers (id),
    bank_name             VARCHAR(200)  NOT NULL,
    branch                VARCHAR(200),
    account_name          VARCHAR(200)  NOT NULL,
    -- AES-GCM ciphertext bound to the row id; only the last four digits are stored in clear.
    account_number_enc    BYTEA         NOT NULL,
    account_number_last4  CHAR(4)       NOT NULL,
    status                VARCHAR(10)   NOT NULL CHECK (status IN ('PENDING', 'VERIFIED', 'REJECTED', 'INACTIVE')),
    entered_by_id         UUID          NOT NULL REFERENCES users (id),
    entered_by            VARCHAR(64)   NOT NULL,
    entered_at            TIMESTAMPTZ   NOT NULL,
    verified_by           VARCHAR(64),
    verified_at           TIMESTAMPTZ,
    verification_note     VARCHAR(500),
    version               BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_sba_verifier CHECK (status <> 'VERIFIED' OR verified_by IS NOT NULL)
);
-- One account in use per supplier at a time.
CREATE UNIQUE INDEX uq_supplier_bank_live ON supplier_bank_accounts (supplier_id) WHERE status IN ('PENDING', 'VERIFIED');

CREATE TABLE payment_batches (
    id               UUID PRIMARY KEY,
    batch_number     VARCHAR(30)   NOT NULL UNIQUE,
    bank_account_id  UUID          NOT NULL REFERENCES bank_accounts (id),
    payment_date     DATE          NOT NULL,
    item_count       INTEGER       NOT NULL,
    total_amount     NUMERIC(19,2) NOT NULL,
    file_name        VARCHAR(200)  NOT NULL,
    content          BYTEA         NOT NULL,
    sha256           CHAR(64)      NOT NULL,
    created_by       VARCHAR(64)   NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL,
    download_count   INTEGER       NOT NULL DEFAULT 0
);

CREATE TABLE payment_batch_items (
    batch_id               UUID          NOT NULL REFERENCES payment_batches (id),
    line_no                INTEGER       NOT NULL,
    dv_id                  UUID          NOT NULL REFERENCES disbursement_vouchers (id),
    supplier_bank_account_id UUID        NOT NULL REFERENCES supplier_bank_accounts (id),
    amount                 NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    PRIMARY KEY (batch_id, line_no)
);
-- A DV may appear again in a later batch only after its earlier instruction was voided (checked in the service).
CREATE INDEX ix_payment_batch_dv ON payment_batch_items (dv_id);
