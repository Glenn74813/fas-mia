-- Phase 3c: deposits of collections, imported bank statements, and bank reconciliation.

CREATE TABLE deposits (
    id                UUID PRIMARY KEY,
    deposit_number    VARCHAR(30)   NOT NULL UNIQUE,
    bank_account_id   UUID          NOT NULL REFERENCES bank_accounts (id),
    deposit_date      DATE          NOT NULL,
    bank_reference    VARCHAR(60),
    amount            NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    status            VARCHAR(10)   NOT NULL CHECK (status IN ('RECORDED', 'POSTED', 'CANCELLED')),
    prepared_by_id    UUID          NOT NULL REFERENCES users (id),
    prepared_by       VARCHAR(64)   NOT NULL,
    posted_entry_id   UUID          REFERENCES journal_entries (id),
    cancel_reason     VARCHAR(500),
    version           BIGINT        NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ   NOT NULL,
    updated_at        TIMESTAMPTZ   NOT NULL,
    updated_by        VARCHAR(64)   NOT NULL
);
CREATE INDEX ix_deposits_bank_date ON deposits (bank_account_id, deposit_date);

CREATE TABLE deposit_items (
    deposit_id   UUID          NOT NULL REFERENCES deposits (id),
    receipt_id   UUID          NOT NULL REFERENCES receipts (id),
    amount       NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    -- False once the deposit is cancelled, so the receipt can be deposited again.
    live         BOOLEAN       NOT NULL DEFAULT TRUE,
    PRIMARY KEY (deposit_id, receipt_id)
);
-- A collection is deposited at most once.
CREATE UNIQUE INDEX uq_deposit_item_live_receipt ON deposit_items (receipt_id) WHERE live;

CREATE TABLE bank_statements (
    id                   UUID PRIMARY KEY,
    bank_account_id      UUID          NOT NULL REFERENCES bank_accounts (id),
    format               VARCHAR(10)   NOT NULL CHECK (format IN ('CSV', 'MT940', 'CAMT053')),
    statement_reference  VARCHAR(60),
    period_start         DATE          NOT NULL,
    period_end           DATE          NOT NULL,
    opening_balance      NUMERIC(19,2) NOT NULL,
    closing_balance      NUMERIC(19,2) NOT NULL,
    file_name            VARCHAR(255)  NOT NULL,
    file_sha256          CHAR(64)      NOT NULL,
    line_count           INTEGER       NOT NULL,
    imported_at          TIMESTAMPTZ   NOT NULL,
    imported_by          VARCHAR(64)   NOT NULL,
    CONSTRAINT ck_statement_period CHECK (period_end >= period_start),
    CONSTRAINT uq_statement_file UNIQUE (bank_account_id, file_sha256)
);
CREATE INDEX ix_statements_bank_period ON bank_statements (bank_account_id, period_end);

-- Bank-side matches group statement lines with ledger lines of equal total.
CREATE TABLE bank_matches (
    id               UUID PRIMARY KEY,
    bank_account_id  UUID         NOT NULL REFERENCES bank_accounts (id),
    method           VARCHAR(6)   NOT NULL CHECK (method IN ('AUTO', 'MANUAL')),
    rule             VARCHAR(30)  NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    created_by       VARCHAR(64)  NOT NULL
);

CREATE TABLE bank_statement_lines (
    id              UUID PRIMARY KEY,
    statement_id    UUID          NOT NULL REFERENCES bank_statements (id),
    line_no         INTEGER       NOT NULL,
    value_date      DATE          NOT NULL,
    booking_date    DATE,
    -- Signed from the account holder's view: positive = money into the account.
    amount          NUMERIC(19,2) NOT NULL CHECK (amount <> 0),
    description     VARCHAR(500)  NOT NULL,
    bank_reference  VARCHAR(100),
    match_id        UUID          REFERENCES bank_matches (id),
    CONSTRAINT uq_statement_line UNIQUE (statement_id, line_no)
);
CREATE INDEX ix_statement_lines_match ON bank_statement_lines (match_id);

CREATE TABLE bank_match_ledger_lines (
    match_id          UUID          NOT NULL REFERENCES bank_matches (id),
    journal_line_id   UUID          NOT NULL UNIQUE REFERENCES journal_entry_lines (id),
    amount            NUMERIC(19,2) NOT NULL,
    PRIMARY KEY (match_id, journal_line_id)
);

CREATE TABLE bank_reconciliations (
    id                       UUID PRIMARY KEY,
    bank_account_id          UUID          NOT NULL REFERENCES bank_accounts (id),
    statement_id             UUID          NOT NULL UNIQUE REFERENCES bank_statements (id),
    as_of_date               DATE          NOT NULL,
    status                   VARCHAR(10)   NOT NULL CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'RETURNED')),
    current_step_order       SMALLINT,
    -- Figures frozen at submission.
    bank_balance             NUMERIC(19,2),
    deposits_in_transit      NUMERIC(19,2),
    outstanding_checks       NUMERIC(19,2),
    book_balance             NUMERIC(19,2),
    unrecorded_credits       NUMERIC(19,2),
    unrecorded_debits        NUMERIC(19,2),
    difference               NUMERIC(19,2),
    prepared_by_id           UUID          NOT NULL REFERENCES users (id),
    prepared_by              VARCHAR(64)   NOT NULL,
    approved_by              VARCHAR(64),
    approved_at              TIMESTAMPTZ,
    version                  BIGINT        NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ   NOT NULL,
    updated_at               TIMESTAMPTZ   NOT NULL,
    updated_by               VARCHAR(64)   NOT NULL
);

-- The reconciliation in which a match was made; it is frozen once that reconciliation is submitted.
ALTER TABLE bank_matches ADD COLUMN reconciliation_id UUID NOT NULL REFERENCES bank_reconciliations (id);
CREATE INDEX ix_bank_matches_recon ON bank_matches (reconciliation_id);
