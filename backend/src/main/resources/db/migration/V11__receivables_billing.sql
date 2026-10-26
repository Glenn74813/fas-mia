-- Phase 3b: customers / concessionaires, contracts, billing invoices, credit and debit memos,
-- receipts (collections) with allocations, and the link from portal users to their customer.

CREATE TABLE customers (
    id                     UUID PRIMARY KEY,
    code                   VARCHAR(40)   NOT NULL UNIQUE,
    name                   VARCHAR(200)  NOT NULL,
    customer_type          VARCHAR(16)   NOT NULL CHECK (customer_type IN ('CONCESSIONAIRE', 'AIRLINE', 'GOVERNMENT', 'OTHER')),
    tin                    VARCHAR(20)   CHECK (tin IS NULL OR tin ~ '^[0-9]{3}-?[0-9]{3}-?[0-9]{3}(-?[0-9]{3,5})?$'),
    address                VARCHAR(500),
    email                  VARCHAR(254),
    credit_limit           NUMERIC(19,2) CHECK (credit_limit IS NULL OR credit_limit >= 0),
    payment_terms_days     SMALLINT      NOT NULL DEFAULT 30 CHECK (payment_terms_days BETWEEN 0 AND 365),
    subsidiary_account_id  UUID          NOT NULL UNIQUE REFERENCES subsidiary_ledger_accounts (id),
    status                 VARCHAR(10)   NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    version                BIGINT        NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ   NOT NULL,
    created_by             VARCHAR(64)   NOT NULL,
    updated_at             TIMESTAMPTZ   NOT NULL,
    updated_by             VARCHAR(64)   NOT NULL
);

-- Concessionaire portal users see only their own customer (object-level authorisation).
ALTER TABLE users ADD COLUMN customer_id UUID REFERENCES customers (id);
-- Only concessionaire users may be linked to a customer.
ALTER TABLE users ADD CONSTRAINT ck_users_customer_link CHECK (customer_id IS NULL OR user_type = 'CONCESSIONAIRE');
CREATE INDEX ix_users_customer ON users (customer_id) WHERE customer_id IS NOT NULL;

CREATE TABLE customer_contracts (
    id                  UUID PRIMARY KEY,
    customer_id         UUID          NOT NULL REFERENCES customers (id),
    contract_number     VARCHAR(60)   NOT NULL UNIQUE,
    description         VARCHAR(500)  NOT NULL,
    start_date          DATE          NOT NULL,
    end_date            DATE,
    monthly_amount      NUMERIC(19,2) NOT NULL CHECK (monthly_amount > 0),
    revenue_account_id  UUID          NOT NULL REFERENCES chart_of_accounts (id),
    cost_center_id      UUID          REFERENCES cost_centers (id),
    vat_code_id         UUID          REFERENCES tax_codes (id),
    status              VARCHAR(10)   NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED', 'ENDED')),
    version             BIGINT        NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ   NOT NULL,
    created_by          VARCHAR(64)   NOT NULL,
    CONSTRAINT ck_contract_dates CHECK (end_date IS NULL OR end_date >= start_date)
);

-- Billing invoices and memos share one table: kind distinguishes them.
CREATE TABLE billing_documents (
    id                   UUID PRIMARY KEY,
    kind                 VARCHAR(12)   NOT NULL CHECK (kind IN ('INVOICE', 'CREDIT_MEMO', 'DEBIT_MEMO')),
    document_number      VARCHAR(30)   UNIQUE,
    customer_id          UUID          NOT NULL REFERENCES customers (id),
    contract_id          UUID          REFERENCES customer_contracts (id),
    billing_period       CHAR(7)       CHECK (billing_period IS NULL OR billing_period ~ '^[0-9]{4}-[0-9]{2}$'),
    related_invoice_id   UUID          REFERENCES billing_documents (id),
    document_date        DATE          NOT NULL,
    due_date             DATE          NOT NULL,
    period_id            UUID          NOT NULL REFERENCES accounting_periods (id),
    description          VARCHAR(1000) NOT NULL,
    status               VARCHAR(12)   NOT NULL CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'POSTED', 'RETURNED', 'REJECTED', 'CANCELLED')),
    current_step_order   SMALLINT,
    vat_code_id          UUID          REFERENCES tax_codes (id),
    net_amount           NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (net_amount >= 0),
    vat_amount           NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (vat_amount >= 0),
    gross_amount         NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (gross_amount >= 0),
    -- For invoices and debit memos: collections and credit memos applied so far.
    settled_amount       NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (settled_amount >= 0),
    source               VARCHAR(10)   NOT NULL CHECK (source IN ('MANUAL', 'CONTRACT')),
    prepared_by_id       UUID          NOT NULL REFERENCES users (id),
    prepared_by          VARCHAR(64)   NOT NULL,
    posted_entry_id      UUID          REFERENCES journal_entries (id),
    version              BIGINT        NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ   NOT NULL,
    updated_at           TIMESTAMPTZ   NOT NULL,
    updated_by           VARCHAR(64)   NOT NULL,
    CONSTRAINT ck_bd_due CHECK (due_date >= document_date),
    CONSTRAINT ck_bd_settled CHECK (settled_amount <= gross_amount),
    CONSTRAINT ck_bd_memo_ref CHECK (kind = 'INVOICE' OR related_invoice_id IS NOT NULL OR kind = 'DEBIT_MEMO')
);
-- A contract is billed at most once per period (excluding rejected/cancelled attempts).
CREATE UNIQUE INDEX uq_contract_period_billing ON billing_documents (contract_id, billing_period)
    WHERE contract_id IS NOT NULL AND status NOT IN ('REJECTED', 'CANCELLED');
CREATE INDEX ix_billing_customer ON billing_documents (customer_id, status, due_date);

CREATE TABLE billing_document_lines (
    id                  UUID PRIMARY KEY,
    document_id         UUID          NOT NULL REFERENCES billing_documents (id) ON DELETE CASCADE,
    line_no             SMALLINT      NOT NULL,
    revenue_account_id  UUID          NOT NULL REFERENCES chart_of_accounts (id),
    cost_center_id      UUID          REFERENCES cost_centers (id),
    description         VARCHAR(500)  NOT NULL,
    net_amount          NUMERIC(19,2) NOT NULL CHECK (net_amount > 0),
    CONSTRAINT uq_bdl_line UNIQUE (document_id, line_no)
);

-- Collections. Numbers are system-generated pending the BIR-approved official receipt series (C-06).
CREATE TABLE receipts (
    id                 UUID PRIMARY KEY,
    receipt_number     VARCHAR(30)   NOT NULL UNIQUE,
    customer_id        UUID          NOT NULL REFERENCES customers (id),
    receipt_date       DATE          NOT NULL,
    payment_method     VARCHAR(16)   NOT NULL CHECK (payment_method IN ('CASH', 'CHECK', 'BANK_TRANSFER')),
    payment_reference  VARCHAR(60),
    amount             NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    tax_withheld       NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (tax_withheld >= 0),
    unapplied_amount   NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (unapplied_amount >= 0),
    status             VARCHAR(10)   NOT NULL CHECK (status IN ('RECORDED', 'POSTED', 'CANCELLED')),
    remarks            VARCHAR(500),
    recorded_by_id     UUID          NOT NULL REFERENCES users (id),
    recorded_by        VARCHAR(64)   NOT NULL,
    posted_entry_id    UUID          REFERENCES journal_entries (id),
    cancel_reason      VARCHAR(500),
    version            BIGINT        NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ   NOT NULL,
    updated_at         TIMESTAMPTZ   NOT NULL,
    updated_by         VARCHAR(64)   NOT NULL
);
CREATE INDEX ix_receipts_customer ON receipts (customer_id, receipt_date);
CREATE INDEX ix_receipts_status_date ON receipts (status, receipt_date);

-- Applications of collections (or of later-applied unapplied amounts) to invoices / debit memos.
CREATE TABLE receipt_allocations (
    id               UUID PRIMARY KEY,
    receipt_id       UUID          NOT NULL REFERENCES receipts (id),
    document_id      UUID          NOT NULL REFERENCES billing_documents (id),
    amount           NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    applied_later    BOOLEAN       NOT NULL DEFAULT FALSE,
    posted_entry_id  UUID          REFERENCES journal_entries (id),
    created_at       TIMESTAMPTZ   NOT NULL,
    created_by       VARCHAR(64)   NOT NULL
);
CREATE INDEX ix_receipt_alloc_document ON receipt_allocations (document_id);
CREATE INDEX ix_receipt_alloc_receipt ON receipt_allocations (receipt_id);
