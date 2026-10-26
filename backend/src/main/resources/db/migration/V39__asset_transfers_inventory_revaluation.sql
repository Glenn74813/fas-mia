-- Fixed assets and inventory (MS §11.5, §11.7): asset transfers between units with history, and inventory price
-- adjustments (revaluation of an item's average-cost pool).

-- An asset transfer moves an asset to another cost centre, location or custodian. One person prepares it and a different
-- person posts it; a cost-centre change also reclassifies cost and accumulated depreciation when the accounts carry
-- cost centres.
CREATE TABLE asset_transfers (
    id                  UUID PRIMARY KEY,
    asset_id            UUID          NOT NULL REFERENCES fixed_assets (id),
    transfer_date       DATE          NOT NULL,
    from_cost_center_id UUID          REFERENCES cost_centers (id),
    to_cost_center_id   UUID          REFERENCES cost_centers (id),
    from_location       VARCHAR(200),
    to_location         VARCHAR(200),
    from_custodian      VARCHAR(200),
    to_custodian        VARCHAR(200),
    reason              VARCHAR(1000) NOT NULL,
    status              VARCHAR(10)   NOT NULL CHECK (status IN ('DRAFT', 'POSTED', 'CANCELLED')),
    prepared_by_id      UUID          NOT NULL REFERENCES users (id),
    prepared_by         VARCHAR(64)   NOT NULL,
    posted_by           VARCHAR(64),
    posted_at           TIMESTAMPTZ,
    posted_entry_id     UUID          REFERENCES journal_entries (id),
    version             BIGINT        NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ   NOT NULL,
    updated_at          TIMESTAMPTZ   NOT NULL,
    updated_by          VARCHAR(64)   NOT NULL
);
CREATE UNIQUE INDEX uq_asset_transfer_draft ON asset_transfers (asset_id) WHERE status = 'DRAFT';
CREATE INDEX ix_asset_transfers_asset ON asset_transfers (asset_id, transfer_date);

-- Inventory price adjustment: restates an item's average-cost pool; it moves value, not quantity, and belongs to no warehouse.
ALTER TABLE inventory_documents DROP CONSTRAINT inventory_documents_kind_check;
ALTER TABLE inventory_documents ADD CONSTRAINT inventory_documents_kind_check
    CHECK (kind IN ('GOODS_RECEIPT', 'ISSUE', 'COUNT', 'TRANSFER', 'OPENING', 'REVALUATION'));
ALTER TABLE inventory_document_lines ALTER COLUMN warehouse_id DROP NOT NULL;
ALTER TABLE inventory_movements DROP CONSTRAINT inventory_movements_movement_type_check;
ALTER TABLE inventory_movements ADD CONSTRAINT inventory_movements_movement_type_check
    CHECK (movement_type IN ('RECEIPT', 'ISSUE', 'COUNT_GAIN', 'COUNT_LOSS', 'TRANSFER_OUT', 'TRANSFER_IN', 'OPENING', 'REVALUATION'));
ALTER TABLE inventory_movements ALTER COLUMN warehouse_id DROP NOT NULL;
ALTER TABLE inventory_movements ADD CONSTRAINT ck_inventory_movement_warehouse
    CHECK (warehouse_id IS NOT NULL OR movement_type = 'REVALUATION');
