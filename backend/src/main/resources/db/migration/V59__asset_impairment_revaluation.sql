-- Impairment (PAS 36) and revaluation (PAS 16, elimination method) of fixed assets.
--
-- The register keeps an asset's original cost and its posted depreciation lines untouched. Each posted impairment or
-- revaluation updates running balances on the asset and restarts depreciation from the new carrying amount:
--   gross carrying amount     = cost + cost_adjustment
--   accumulated depreciation  = opening_accumulated + posted depreciation - depreciation_eliminated
--   carrying amount           = gross carrying amount - accumulated depreciation - accumulated_impairment

-- Accumulated impairment is kept per asset class in the official chart, beside accumulated depreciation.
ALTER TABLE asset_categories ADD COLUMN accumulated_impairment_account_id UUID REFERENCES chart_of_accounts (id);

ALTER TABLE fixed_assets
    -- Revaluations: change to the gross carrying amount, and accumulated depreciation eliminated against it.
    ADD COLUMN cost_adjustment          NUMERIC(19,2) NOT NULL DEFAULT 0,
    ADD COLUMN depreciation_eliminated  NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (depreciation_eliminated >= 0),
    ADD COLUMN accumulated_impairment   NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (accumulated_impairment >= 0),
    -- This asset's share of the revaluation surplus, and the decreases charged to profit or loss not yet reversed
    -- (a later increase reverses those first).
    ADD COLUMN revaluation_surplus      NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (revaluation_surplus >= 0),
    ADD COLUMN losses_in_profit         NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (losses_in_profit >= 0),
    -- Depreciation basis after the latest remeasurement: from basis_month, accumulated depreciation grows from
    -- basis_accumulated by basis_depreciable spread straight-line over basis_remaining_months.
    ADD COLUMN basis_month              DATE,
    ADD COLUMN basis_accumulated        NUMERIC(19,2),
    ADD COLUMN basis_depreciable        NUMERIC(19,2),
    ADD COLUMN basis_remaining_months   SMALLINT,
    ADD CONSTRAINT ck_fa_gross_carrying CHECK (cost + cost_adjustment >= 0),
    ADD CONSTRAINT ck_fa_remeasured_basis CHECK (
        (basis_month IS NULL AND basis_accumulated IS NULL AND basis_depreciable IS NULL AND basis_remaining_months IS NULL) OR
        (basis_month IS NOT NULL AND basis_accumulated >= 0 AND basis_depreciable >= 0 AND basis_remaining_months >= 0));

CREATE TABLE asset_remeasurements (
    id                      UUID PRIMARY KEY,
    asset_id                UUID          NOT NULL REFERENCES fixed_assets (id),
    kind                    VARCHAR(12)   NOT NULL CHECK (kind IN ('IMPAIRMENT', 'REVALUATION')),
    remeasured_on           DATE          NOT NULL,
    -- The recoverable amount (impairment) or fair value (revaluation): the carrying amount after posting.
    new_carrying_amount     NUMERIC(19,2) NOT NULL CHECK (new_carrying_amount >= 0),
    -- Reassessed remaining useful life; null keeps the asset's current schedule.
    remaining_life_months   SMALLINT      CHECK (remaining_life_months IS NULL OR remaining_life_months BETWEEN 0 AND 1200),
    basis_reference         VARCHAR(200),
    reason                  VARCHAR(1000) NOT NULL,
    status                  VARCHAR(10)   NOT NULL CHECK (status IN ('DRAFT', 'POSTED', 'CANCELLED')),
    -- Frozen at posting.
    carrying_before         NUMERIC(19,2),
    cost_change             NUMERIC(19,2),
    depreciation_eliminated NUMERIC(19,2),
    impairment_change       NUMERIC(19,2),
    surplus_change          NUMERIC(19,2),
    loss_in_profit          NUMERIC(19,2),
    reversal_in_profit      NUMERIC(19,2),
    basis_remaining_months  SMALLINT,
    prepared_by_id          UUID          NOT NULL REFERENCES users (id),
    prepared_by             VARCHAR(64)   NOT NULL,
    posted_entry_id         UUID          REFERENCES journal_entries (id),
    version                 BIGINT        NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ   NOT NULL,
    updated_at              TIMESTAMPTZ   NOT NULL,
    updated_by              VARCHAR(64)   NOT NULL
);
CREATE INDEX ix_asset_remeasurements_asset ON asset_remeasurements (asset_id, remeasured_on);
-- One remeasurement waiting to be posted per asset.
CREATE UNIQUE INDEX uq_asset_remeasurement_draft ON asset_remeasurements (asset_id) WHERE status = 'DRAFT';

-- A disposal also derecognises the accumulated impairment; frozen at posting like cost and accumulated depreciation.
ALTER TABLE asset_disposals ADD COLUMN accumulated_impairment NUMERIC(19,2);
