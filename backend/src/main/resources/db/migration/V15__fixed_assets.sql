-- Phase 3d: asset categories, assets under construction, the asset register, depreciation runs and disposals.

CREATE TABLE asset_categories (
    id                          UUID PRIMARY KEY,
    code                        VARCHAR(30)  NOT NULL UNIQUE,
    name                        VARCHAR(200) NOT NULL,
    asset_account_id            UUID         NOT NULL REFERENCES chart_of_accounts (id),
    accumulated_account_id      UUID         NOT NULL REFERENCES chart_of_accounts (id),
    expense_account_id          UUID         NOT NULL REFERENCES chart_of_accounts (id),
    -- Proposed defaults for new assets; MIAA's useful-life and residual-value policy (C-30) governs.
    default_useful_life_months  SMALLINT     NOT NULL CHECK (default_useful_life_months BETWEEN 1 AND 1200),
    default_residual_rate       NUMERIC(5,4) CHECK (default_residual_rate IS NULL OR default_residual_rate BETWEEN 0 AND 1),
    status                      VARCHAR(10)  NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    placeholder                 BOOLEAN      NOT NULL DEFAULT FALSE,
    version                     BIGINT       NOT NULL DEFAULT 0,
    created_at                  TIMESTAMPTZ  NOT NULL,
    created_by                  VARCHAR(64)  NOT NULL
);

-- Costs of an asset under construction are posted to the AUC account with the project's subsidiary account.
CREATE TABLE auc_projects (
    id                     UUID PRIMARY KEY,
    code                   VARCHAR(40)  NOT NULL UNIQUE,
    name                   VARCHAR(200) NOT NULL,
    auc_account_id         UUID         NOT NULL REFERENCES chart_of_accounts (id),
    subsidiary_account_id  UUID         NOT NULL UNIQUE REFERENCES subsidiary_ledger_accounts (id),
    cost_center_id         UUID         REFERENCES cost_centers (id),
    status                 VARCHAR(10)  NOT NULL CHECK (status IN ('OPEN', 'CLOSED')),
    version                BIGINT       NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ  NOT NULL,
    created_by             VARCHAR(64)  NOT NULL
);

CREATE TABLE fixed_assets (
    id                     UUID PRIMARY KEY,
    asset_number           VARCHAR(30)   UNIQUE,
    category_id            UUID          NOT NULL REFERENCES asset_categories (id),
    description            VARCHAR(500)  NOT NULL,
    serial_number          VARCHAR(100),
    location               VARCHAR(200),
    custodian              VARCHAR(200),
    cost_center_id         UUID          REFERENCES cost_centers (id),
    acquisition_source     VARCHAR(8)    NOT NULL CHECK (acquisition_source IN ('INVOICE', 'AUC', 'OTHER')),
    supplier_invoice_id    UUID          REFERENCES supplier_invoices (id),
    auc_project_id         UUID          REFERENCES auc_projects (id),
    offset_account_id      UUID          REFERENCES chart_of_accounts (id),
    justification          VARCHAR(1000),
    acquisition_date       DATE          NOT NULL,
    in_service_date        DATE          NOT NULL,
    cost                   NUMERIC(19,2) NOT NULL CHECK (cost > 0),
    residual_value         NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (residual_value >= 0),
    useful_life_months     SMALLINT      NOT NULL CHECK (useful_life_months BETWEEN 1 AND 1200),
    status                 VARCHAR(18)   NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'FULLY_DEPRECIATED', 'DISPOSED', 'CANCELLED')),
    prepared_by_id         UUID          NOT NULL REFERENCES users (id),
    prepared_by            VARCHAR(64)   NOT NULL,
    activated_by           VARCHAR(64),
    activated_at           TIMESTAMPTZ,
    acquisition_entry_id   UUID          REFERENCES journal_entries (id),
    version                BIGINT        NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ   NOT NULL,
    updated_at             TIMESTAMPTZ   NOT NULL,
    updated_by             VARCHAR(64)   NOT NULL,
    CONSTRAINT ck_fa_residual CHECK (residual_value < cost),
    CONSTRAINT ck_fa_dates CHECK (in_service_date >= acquisition_date),
    CONSTRAINT ck_fa_source CHECK (
        (acquisition_source = 'INVOICE' AND supplier_invoice_id IS NOT NULL) OR
        (acquisition_source = 'AUC' AND auc_project_id IS NOT NULL) OR
        (acquisition_source = 'OTHER' AND offset_account_id IS NOT NULL AND justification IS NOT NULL))
);
CREATE INDEX ix_fa_category_status ON fixed_assets (category_id, status);

CREATE TABLE depreciation_runs (
    id               UUID PRIMARY KEY,
    period_id        UUID          NOT NULL REFERENCES accounting_periods (id),
    status           VARCHAR(10)   NOT NULL CHECK (status IN ('DRAFT', 'POSTED', 'CANCELLED')),
    total_amount     NUMERIC(19,2) NOT NULL,
    asset_count      INTEGER       NOT NULL,
    prepared_by_id   UUID          NOT NULL REFERENCES users (id),
    prepared_by      VARCHAR(64)   NOT NULL,
    posted_entry_id  UUID          REFERENCES journal_entries (id),
    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL,
    updated_at       TIMESTAMPTZ   NOT NULL,
    updated_by       VARCHAR(64)   NOT NULL
);
-- One live run per period.
CREATE UNIQUE INDEX uq_depreciation_run_period ON depreciation_runs (period_id) WHERE status <> 'CANCELLED';

CREATE TABLE depreciation_lines (
    run_id      UUID          NOT NULL REFERENCES depreciation_runs (id),
    asset_id    UUID          NOT NULL REFERENCES fixed_assets (id),
    period_id   UUID          NOT NULL REFERENCES accounting_periods (id),
    amount      NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    live        BOOLEAN       NOT NULL DEFAULT TRUE,
    PRIMARY KEY (run_id, asset_id)
);
-- An asset is depreciated at most once per period.
CREATE UNIQUE INDEX uq_depreciation_asset_period ON depreciation_lines (asset_id, period_id) WHERE live;

CREATE TABLE asset_disposals (
    id                   UUID PRIMARY KEY,
    asset_id             UUID          NOT NULL REFERENCES fixed_assets (id),
    disposal_date        DATE          NOT NULL,
    method               VARCHAR(10)   NOT NULL CHECK (method IN ('SALE', 'SCRAP', 'DONATION', 'TRANSFER', 'LOSS')),
    proceeds             NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (proceeds >= 0),
    proceeds_account_id  UUID          REFERENCES chart_of_accounts (id),
    reason               VARCHAR(1000) NOT NULL,
    status               VARCHAR(10)   NOT NULL CHECK (status IN ('DRAFT', 'POSTED', 'CANCELLED')),
    -- Frozen at posting.
    cost                 NUMERIC(19,2),
    accumulated          NUMERIC(19,2),
    gain_loss            NUMERIC(19,2),
    prepared_by_id       UUID          NOT NULL REFERENCES users (id),
    prepared_by          VARCHAR(64)   NOT NULL,
    posted_entry_id      UUID          REFERENCES journal_entries (id),
    version              BIGINT        NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ   NOT NULL,
    updated_at           TIMESTAMPTZ   NOT NULL,
    updated_by           VARCHAR(64)   NOT NULL,
    CONSTRAINT ck_disposal_proceeds CHECK (proceeds = 0 OR proceeds_account_id IS NOT NULL)
);
CREATE UNIQUE INDEX uq_disposal_live_asset ON asset_disposals (asset_id) WHERE status <> 'CANCELLED';
