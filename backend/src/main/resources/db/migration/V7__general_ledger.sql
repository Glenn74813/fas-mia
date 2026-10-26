-- Phase 2: chart of accounts, fiscal calendar, journal vouchers, immutable journal entries,
-- derived ledger balances, approval workflow, supporting documents, idempotency.
-- Money: NUMERIC(19,2) in PHP (the functional currency). Foreign-currency lines are not yet supported (C-21).

CREATE TABLE currencies (
    code         CHAR(3)      PRIMARY KEY,
    name         VARCHAR(60)  NOT NULL,
    minor_units  SMALLINT     NOT NULL CHECK (minor_units BETWEEN 0 AND 4),
    functional   BOOLEAN      NOT NULL DEFAULT FALSE
);
INSERT INTO currencies (code, name, minor_units, functional) VALUES ('PHP', 'Philippine peso', 2, TRUE);
CREATE UNIQUE INDEX uq_currencies_functional ON currencies (functional) WHERE functional;

-- ---------------------------------------------------------------- fiscal calendar
CREATE TABLE fiscal_years (
    id          UUID PRIMARY KEY,
    fiscal_year INT          NOT NULL UNIQUE CHECK (fiscal_year BETWEEN 2000 AND 2200),
    start_date  DATE         NOT NULL,
    end_date    DATE         NOT NULL,
    status      VARCHAR(10)  NOT NULL CHECK (status IN ('OPEN', 'CLOSED')),
    closed_at   TIMESTAMPTZ,
    closed_by   VARCHAR(64),
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL,
    created_by  VARCHAR(64)  NOT NULL,
    CONSTRAINT ck_fy_dates CHECK (end_date > start_date)
);

CREATE TABLE accounting_periods (
    id              UUID PRIMARY KEY,
    fiscal_year_id  UUID         NOT NULL REFERENCES fiscal_years (id),
    period_number   SMALLINT     NOT NULL CHECK (period_number BETWEEN 1 AND 13),
    name            VARCHAR(40)  NOT NULL,
    period_type     VARCHAR(12)  NOT NULL CHECK (period_type IN ('REGULAR', 'ADJUSTMENT')),
    start_date      DATE         NOT NULL,
    end_date        DATE         NOT NULL,
    status          VARCHAR(12)  NOT NULL CHECK (status IN ('FUTURE', 'OPEN', 'SOFT_CLOSED', 'CLOSED')),
    -- Global ordering key (fiscal_year * 100 + period_number) used for cumulative balances.
    sequence_key    INT          NOT NULL UNIQUE,
    version         BIGINT       NOT NULL DEFAULT 0,
    updated_at      TIMESTAMPTZ  NOT NULL,
    updated_by      VARCHAR(64)  NOT NULL,
    CONSTRAINT uq_period_number UNIQUE (fiscal_year_id, period_number),
    CONSTRAINT ck_period_dates CHECK (end_date >= start_date),
    CONSTRAINT ck_adjustment_is_13 CHECK ((period_type = 'ADJUSTMENT') = (period_number = 13))
);
CREATE INDEX ix_periods_dates ON accounting_periods (start_date, end_date);

-- Close/open/reopen history; reopen requests need a second user's approval.
CREATE TABLE period_status_changes (
    id              UUID PRIMARY KEY,
    period_id       UUID         NOT NULL REFERENCES accounting_periods (id),
    change_type     VARCHAR(12)  NOT NULL CHECK (change_type IN ('OPEN', 'SOFT_CLOSE', 'CLOSE', 'REOPEN')),
    from_status     VARCHAR(12)  NOT NULL,
    to_status       VARCHAR(12)  NOT NULL,
    request_status  VARCHAR(10)  NOT NULL CHECK (request_status IN ('PENDING', 'APPROVED', 'REJECTED', 'APPLIED')),
    reason          VARCHAR(1000),
    waived_checks   JSONB        NOT NULL DEFAULT '[]'::jsonb,
    requested_by_id UUID         REFERENCES users (id),  -- NULL for system actions (e.g. dev seeding)
    requested_by    VARCHAR(64)  NOT NULL,
    requested_at    TIMESTAMPTZ  NOT NULL,
    decided_by_id   UUID         REFERENCES users (id),
    decided_by      VARCHAR(64),
    decided_at      TIMESTAMPTZ,
    decision_note   VARCHAR(1000),
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_reopen_two_people CHECK (decided_by_id IS NULL OR change_type <> 'REOPEN' OR decided_by_id <> requested_by_id)
);
CREATE UNIQUE INDEX uq_pending_reopen ON period_status_changes (period_id) WHERE request_status = 'PENDING';

-- ---------------------------------------------------------------- chart of accounts & dimensions
CREATE TABLE chart_of_accounts (
    id                   UUID PRIMARY KEY,
    code                 VARCHAR(30)   NOT NULL UNIQUE CHECK (code ~ '^[A-Za-z0-9][A-Za-z0-9.-]{0,29}$'),
    name                 VARCHAR(200)  NOT NULL,
    description          VARCHAR(1000),
    account_type         VARCHAR(10)   NOT NULL CHECK (account_type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    normal_balance       VARCHAR(6)    NOT NULL CHECK (normal_balance IN ('DEBIT', 'CREDIT')),
    parent_id            UUID          REFERENCES chart_of_accounts (id),
    postable             BOOLEAN       NOT NULL,
    status               VARCHAR(10)   NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    effective_from       DATE          NOT NULL,
    effective_to         DATE,
    fs_line_code         VARCHAR(30),
    requires_cost_center BOOLEAN       NOT NULL DEFAULT FALSE,
    subsidiary_type      VARCHAR(12)   CHECK (subsidiary_type IN ('SUPPLIER', 'CUSTOMER', 'EMPLOYEE', 'OTHER')),
    budget_controlled    BOOLEAN       NOT NULL DEFAULT FALSE,
    tax_code             VARCHAR(20),
    placeholder          BOOLEAN       NOT NULL DEFAULT FALSE,
    version              BIGINT        NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ   NOT NULL,
    created_by           VARCHAR(64)   NOT NULL,
    updated_at           TIMESTAMPTZ   NOT NULL,
    updated_by           VARCHAR(64)   NOT NULL,
    CONSTRAINT ck_coa_dates CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT ck_coa_not_self_parent CHECK (parent_id IS NULL OR parent_id <> id)
);
CREATE INDEX ix_coa_parent ON chart_of_accounts (parent_id);
CREATE INDEX ix_coa_type ON chart_of_accounts (account_type);

CREATE TABLE cost_centers (
    id           UUID PRIMARY KEY,
    code         VARCHAR(30)  NOT NULL UNIQUE,
    name         VARCHAR(200) NOT NULL,
    status       VARCHAR(10)  NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    placeholder  BOOLEAN      NOT NULL DEFAULT FALSE,
    version      BIGINT       NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL,
    created_by   VARCHAR(64)  NOT NULL
);

-- Subsidiary-ledger parties. Phase 3 suppliers/concessionaires will reference these rows.
CREATE TABLE subsidiary_ledger_accounts (
    id          UUID PRIMARY KEY,
    sl_type     VARCHAR(12)  NOT NULL CHECK (sl_type IN ('SUPPLIER', 'CUSTOMER', 'EMPLOYEE', 'OTHER')),
    code        VARCHAR(40)  NOT NULL,
    name        VARCHAR(200) NOT NULL,
    status      VARCHAR(10)  NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL,
    created_by  VARCHAR(64)  NOT NULL,
    CONSTRAINT uq_sl_type_code UNIQUE (sl_type, code)
);

-- Gap-free official numbering per document type and fiscal year (row-locked counter).
CREATE TABLE numbering_sequences (
    document_type  VARCHAR(20)  NOT NULL,
    fiscal_year    INT          NOT NULL,
    prefix         VARCHAR(20)  NOT NULL,
    next_value     BIGINT       NOT NULL CHECK (next_value > 0),
    PRIMARY KEY (document_type, fiscal_year)
);

-- ---------------------------------------------------------------- approval workflow configuration
CREATE TABLE approval_workflows (
    voucher_type         VARCHAR(12)  PRIMARY KEY CHECK (voucher_type IN ('STANDARD', 'ADJUSTING', 'REVERSAL', 'OPENING', 'RECURRING', 'CLOSING')),
    description          VARCHAR(300) NOT NULL,
    requires_attachment  BOOLEAN      NOT NULL
);

CREATE TABLE approval_workflow_steps (
    id                   UUID PRIMARY KEY,
    voucher_type         VARCHAR(12)   NOT NULL REFERENCES approval_workflows (voucher_type),
    step_order           SMALLINT      NOT NULL CHECK (step_order > 0),
    step_code            VARCHAR(20)   NOT NULL,
    required_permission  VARCHAR(64)   NOT NULL REFERENCES permissions (code),
    -- Step applies only when the voucher total is at least this amount (NULL = always).
    min_amount           NUMERIC(19,2) CHECK (min_amount IS NULL OR min_amount >= 0),
    CONSTRAINT uq_workflow_step UNIQUE (voucher_type, step_order)
);

-- ---------------------------------------------------------------- journal vouchers (mutable until posted)
CREATE TABLE journal_vouchers (
    id                     UUID PRIMARY KEY,
    voucher_number         VARCHAR(30)   UNIQUE,
    voucher_type           VARCHAR(12)   NOT NULL REFERENCES approval_workflows (voucher_type),
    status                 VARCHAR(12)   NOT NULL CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'POSTED', 'RETURNED', 'REJECTED', 'CANCELLED')),
    accounting_date        DATE          NOT NULL,
    period_id              UUID          NOT NULL REFERENCES accounting_periods (id),
    description            VARCHAR(1000) NOT NULL,
    supporting_reference   VARCHAR(200),
    source_type            VARCHAR(30)   NOT NULL DEFAULT 'MANUAL',
    source_reference       VARCHAR(100),
    currency_code          CHAR(3)       NOT NULL DEFAULT 'PHP' REFERENCES currencies (code),
    total_debit            NUMERIC(19,2) NOT NULL DEFAULT 0,
    total_credit           NUMERIC(19,2) NOT NULL DEFAULT 0,
    current_step_order     SMALLINT,
    prepared_by_id         UUID          NOT NULL REFERENCES users (id),
    prepared_by            VARCHAR(64)   NOT NULL,
    submitted_at           TIMESTAMPTZ,
    posted_entry_id        UUID,
    reversal_of_entry_id   UUID,
    recurring_journal_id   UUID,
    version                BIGINT        NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ   NOT NULL,
    updated_at             TIMESTAMPTZ   NOT NULL,
    updated_by             VARCHAR(64)   NOT NULL
);
CREATE INDEX ix_jv_status ON journal_vouchers (status, current_step_order);
CREATE INDEX ix_jv_period ON journal_vouchers (period_id, status);
CREATE INDEX ix_jv_date ON journal_vouchers (accounting_date);
CREATE INDEX ix_jv_preparer ON journal_vouchers (prepared_by_id);
-- At most one live (not rejected/cancelled) reversal per posted entry.
CREATE UNIQUE INDEX uq_jv_live_reversal ON journal_vouchers (reversal_of_entry_id)
    WHERE reversal_of_entry_id IS NOT NULL AND status NOT IN ('REJECTED', 'CANCELLED');

CREATE TABLE journal_voucher_lines (
    id                     UUID PRIMARY KEY,
    voucher_id             UUID          NOT NULL REFERENCES journal_vouchers (id) ON DELETE CASCADE,
    line_no                SMALLINT      NOT NULL CHECK (line_no > 0),
    account_id             UUID          NOT NULL REFERENCES chart_of_accounts (id),
    cost_center_id         UUID          REFERENCES cost_centers (id),
    subsidiary_account_id  UUID          REFERENCES subsidiary_ledger_accounts (id),
    description            VARCHAR(500),
    debit                  NUMERIC(19,2) NOT NULL DEFAULT 0,
    credit                 NUMERIC(19,2) NOT NULL DEFAULT 0,
    CONSTRAINT uq_jvl_line UNIQUE (voucher_id, line_no),
    CONSTRAINT ck_jvl_one_side CHECK (debit >= 0 AND credit >= 0 AND ((debit > 0 AND credit = 0) OR (credit > 0 AND debit = 0)))
);
CREATE INDEX ix_jvl_account ON journal_voucher_lines (account_id);

-- Append-only decision log (submit / step approvals / return / reject / cancel / post).
CREATE TABLE approval_decisions (
    id              UUID PRIMARY KEY,
    document_type   VARCHAR(30)   NOT NULL,
    document_id     UUID          NOT NULL,
    action          VARCHAR(12)   NOT NULL CHECK (action IN ('SUBMIT', 'APPROVE', 'RETURN', 'REJECT', 'CANCEL', 'POST')),
    step_order      SMALLINT,
    step_code       VARCHAR(20),
    actor_id        UUID          NOT NULL REFERENCES users (id),
    actor_username  VARCHAR(64)   NOT NULL,
    comment         VARCHAR(1000),
    decided_at      TIMESTAMPTZ   NOT NULL
);
CREATE INDEX ix_decisions_document ON approval_decisions (document_type, document_id, decided_at);
CREATE TRIGGER trg_approval_decisions_immutable BEFORE UPDATE OR DELETE ON approval_decisions
    FOR EACH ROW EXECUTE FUNCTION cas_reject_modification();
CREATE TRIGGER trg_approval_decisions_no_truncate BEFORE TRUNCATE ON approval_decisions
    FOR EACH STATEMENT EXECUTE FUNCTION cas_reject_modification();

-- ---------------------------------------------------------------- posted journal entries (immutable)
CREATE TABLE journal_entries (
    id                    UUID PRIMARY KEY,
    entry_number          VARCHAR(30)   NOT NULL UNIQUE,
    voucher_id            UUID          NOT NULL UNIQUE REFERENCES journal_vouchers (id),
    voucher_type          VARCHAR(12)   NOT NULL,
    fiscal_year_id        UUID          NOT NULL REFERENCES fiscal_years (id),
    period_id             UUID          NOT NULL REFERENCES accounting_periods (id),
    accounting_date       DATE          NOT NULL,
    description           VARCHAR(1000) NOT NULL,
    source_type           VARCHAR(30)   NOT NULL,
    source_reference      VARCHAR(100),
    currency_code         CHAR(3)       NOT NULL REFERENCES currencies (code),
    total_debit           NUMERIC(19,2) NOT NULL,
    total_credit          NUMERIC(19,2) NOT NULL,
    reversal_of_entry_id  UUID          UNIQUE REFERENCES journal_entries (id),
    prepared_by           VARCHAR(64)   NOT NULL,
    posted_by_id          UUID          NOT NULL REFERENCES users (id),
    posted_by             VARCHAR(64)   NOT NULL,
    posted_at             TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_je_balanced CHECK (total_debit = total_credit AND total_debit > 0)
);
CREATE INDEX ix_je_period ON journal_entries (period_id);
CREATE INDEX ix_je_date ON journal_entries (accounting_date);

ALTER TABLE journal_vouchers ADD CONSTRAINT fk_jv_posted_entry FOREIGN KEY (posted_entry_id) REFERENCES journal_entries (id);
ALTER TABLE journal_vouchers ADD CONSTRAINT fk_jv_reversal_of FOREIGN KEY (reversal_of_entry_id) REFERENCES journal_entries (id);

CREATE TABLE journal_entry_lines (
    id                     UUID PRIMARY KEY,
    entry_id               UUID          NOT NULL REFERENCES journal_entries (id),
    line_no                SMALLINT      NOT NULL,
    account_id             UUID          NOT NULL REFERENCES chart_of_accounts (id),
    cost_center_id         UUID          REFERENCES cost_centers (id),
    subsidiary_account_id  UUID          REFERENCES subsidiary_ledger_accounts (id),
    description            VARCHAR(500),
    debit                  NUMERIC(19,2) NOT NULL,
    credit                 NUMERIC(19,2) NOT NULL,
    -- Denormalised from the header for ledger queries.
    period_id              UUID          NOT NULL REFERENCES accounting_periods (id),
    accounting_date        DATE          NOT NULL,
    CONSTRAINT uq_jel_line UNIQUE (entry_id, line_no),
    CONSTRAINT ck_jel_one_side CHECK (debit >= 0 AND credit >= 0 AND ((debit > 0 AND credit = 0) OR (credit > 0 AND debit = 0)))
);
CREATE INDEX ix_jel_account_date ON journal_entry_lines (account_id, accounting_date);
CREATE INDEX ix_jel_account_period ON journal_entry_lines (account_id, period_id);
CREATE INDEX ix_jel_subsidiary ON journal_entry_lines (subsidiary_account_id, account_id) WHERE subsidiary_account_id IS NOT NULL;
CREATE INDEX ix_jel_cost_center ON journal_entry_lines (cost_center_id) WHERE cost_center_id IS NOT NULL;

CREATE TRIGGER trg_journal_entries_immutable BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION cas_reject_modification();
CREATE TRIGGER trg_journal_entries_no_truncate BEFORE TRUNCATE ON journal_entries
    FOR EACH STATEMENT EXECUTE FUNCTION cas_reject_modification();
CREATE TRIGGER trg_journal_entry_lines_immutable BEFORE UPDATE OR DELETE ON journal_entry_lines
    FOR EACH ROW EXECUTE FUNCTION cas_reject_modification();
CREATE TRIGGER trg_journal_entry_lines_no_truncate BEFORE TRUNCATE ON journal_entry_lines
    FOR EACH STATEMENT EXECUTE FUNCTION cas_reject_modification();

-- Last line of defence: at COMMIT every entry must have >= 2 lines, balance, and match its header.
CREATE FUNCTION cas_check_entry_balanced() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_lines INT;
    v_debit NUMERIC(19,2);
    v_credit NUMERIC(19,2);
    v_header_debit NUMERIC(19,2);
BEGIN
    SELECT count(*), coalesce(sum(debit), 0), coalesce(sum(credit), 0)
      INTO v_lines, v_debit, v_credit FROM journal_entry_lines WHERE entry_id = NEW.entry_id;
    SELECT total_debit INTO v_header_debit FROM journal_entries WHERE id = NEW.entry_id;
    IF v_lines < 2 OR v_debit <> v_credit OR v_debit <> v_header_debit THEN
        RAISE EXCEPTION 'Journal entry % is not balanced (lines=%, debit=%, credit=%, header=%)',
            NEW.entry_id, v_lines, v_debit, v_credit, v_header_debit USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER trg_journal_entry_balanced AFTER INSERT ON journal_entry_lines
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION cas_check_entry_balanced();

-- Derived per-account, per-period totals. Not a source of truth: rebuildable from journal_entry_lines.
CREATE TABLE ledger_balances (
    account_id    UUID          NOT NULL REFERENCES chart_of_accounts (id),
    period_id     UUID          NOT NULL REFERENCES accounting_periods (id),
    debit_total   NUMERIC(21,2) NOT NULL DEFAULT 0,
    credit_total  NUMERIC(21,2) NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (account_id, period_id)
);
CREATE INDEX ix_ledger_balances_period ON ledger_balances (period_id);

-- ---------------------------------------------------------------- supporting documents
CREATE TABLE supporting_documents (
    id              UUID PRIMARY KEY,
    owner_type      VARCHAR(30)   NOT NULL,
    owner_id        UUID          NOT NULL,
    file_name       VARCHAR(255)  NOT NULL,
    content_type    VARCHAR(100)  NOT NULL,
    size_bytes      BIGINT        NOT NULL CHECK (size_bytes > 0),
    sha256          CHAR(64)      NOT NULL,
    scan_status     VARCHAR(12)   NOT NULL CHECK (scan_status IN ('NOT_SCANNED', 'CLEAN', 'INFECTED')),
    storage_key     VARCHAR(200)  NOT NULL,
    uploaded_by_id  UUID          NOT NULL REFERENCES users (id),
    uploaded_by     VARCHAR(64)   NOT NULL,
    uploaded_at     TIMESTAMPTZ   NOT NULL,
    removed_at      TIMESTAMPTZ,
    removed_by      VARCHAR(64)
);
CREATE INDEX ix_documents_owner ON supporting_documents (owner_type, owner_id);

-- Default storage backend keeps content in the database so every instance can serve it.
-- An object-storage backend can replace it via the DocumentStorage interface.
CREATE TABLE document_contents (
    storage_key  VARCHAR(200) PRIMARY KEY,
    content      BYTEA        NOT NULL
);

-- ---------------------------------------------------------------- recurring journals
CREATE TABLE recurring_journals (
    id              UUID PRIMARY KEY,
    name            VARCHAR(200)  NOT NULL,
    description     VARCHAR(1000) NOT NULL,
    frequency       VARCHAR(10)   NOT NULL CHECK (frequency IN ('MONTHLY', 'QUARTERLY', 'ANNUALLY')),
    day_of_month    SMALLINT      NOT NULL CHECK (day_of_month BETWEEN 1 AND 28),
    next_run_date   DATE          NOT NULL,
    end_date        DATE,
    status          VARCHAR(10)   NOT NULL CHECK (status IN ('ACTIVE', 'PAUSED', 'ENDED')),
    owner_id        UUID          NOT NULL REFERENCES users (id),
    owner_username  VARCHAR(64)   NOT NULL,
    version         BIGINT        NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL
);

CREATE TABLE recurring_journal_lines (
    id                     UUID PRIMARY KEY,
    recurring_journal_id   UUID          NOT NULL REFERENCES recurring_journals (id) ON DELETE CASCADE,
    line_no                SMALLINT      NOT NULL,
    account_id             UUID          NOT NULL REFERENCES chart_of_accounts (id),
    cost_center_id         UUID          REFERENCES cost_centers (id),
    subsidiary_account_id  UUID          REFERENCES subsidiary_ledger_accounts (id),
    description            VARCHAR(500),
    debit                  NUMERIC(19,2) NOT NULL DEFAULT 0,
    credit                 NUMERIC(19,2) NOT NULL DEFAULT 0,
    CONSTRAINT uq_rjl_line UNIQUE (recurring_journal_id, line_no),
    CONSTRAINT ck_rjl_one_side CHECK (debit >= 0 AND credit >= 0 AND ((debit > 0 AND credit = 0) OR (credit > 0 AND debit = 0)))
);
ALTER TABLE journal_vouchers ADD CONSTRAINT fk_jv_recurring FOREIGN KEY (recurring_journal_id) REFERENCES recurring_journals (id);

-- ---------------------------------------------------------------- idempotency
CREATE TABLE idempotency_keys (
    user_id          UUID          NOT NULL,
    operation        VARCHAR(60)   NOT NULL,
    idempotency_key  VARCHAR(100)  NOT NULL,
    request_hash     CHAR(64)      NOT NULL,
    response_status  INT           NOT NULL,
    response_body    JSONB         NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (user_id, operation, idempotency_key)
);
