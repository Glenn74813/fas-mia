-- Phase 3e: inventory accounting at weighted average cost. Items keep one cost pool (quantity and
-- value) across warehouses; quantities are also kept per warehouse. Every quantity or value change is
-- an immutable movement produced by posting an inventory document.

CREATE TABLE warehouses (
    id          UUID PRIMARY KEY,
    code        VARCHAR(30)  NOT NULL UNIQUE,
    name        VARCHAR(200) NOT NULL,
    status      VARCHAR(10)  NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at  TIMESTAMPTZ  NOT NULL,
    created_by  VARCHAR(64)  NOT NULL
);

CREATE TABLE inventory_items (
    id                      UUID PRIMARY KEY,
    code                    VARCHAR(40)  NOT NULL UNIQUE,
    name                    VARCHAR(200) NOT NULL,
    unit_of_measure         VARCHAR(20)  NOT NULL,
    inventory_account_id    UUID         NOT NULL REFERENCES chart_of_accounts (id),
    consumption_account_id  UUID         NOT NULL REFERENCES chart_of_accounts (id),
    status                  VARCHAR(10)  NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    version                 BIGINT       NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ  NOT NULL,
    created_by              VARCHAR(64)  NOT NULL
);

-- The weighted-average cost pool of an item: average = value / quantity.
CREATE TABLE inventory_valuations (
    item_id   UUID          PRIMARY KEY REFERENCES inventory_items (id),
    quantity  NUMERIC(19,4) NOT NULL CHECK (quantity >= 0),
    value     NUMERIC(19,2) NOT NULL CHECK (value >= 0),
    CONSTRAINT ck_valuation_empty CHECK (quantity > 0 OR value = 0)
);

CREATE TABLE inventory_balances (
    item_id       UUID          NOT NULL REFERENCES inventory_items (id),
    warehouse_id  UUID          NOT NULL REFERENCES warehouses (id),
    quantity      NUMERIC(19,4) NOT NULL CHECK (quantity >= 0),
    PRIMARY KEY (item_id, warehouse_id)
);

CREATE TABLE inventory_documents (
    id               UUID PRIMARY KEY,
    document_number  VARCHAR(30)   UNIQUE,
    kind             VARCHAR(16)   NOT NULL CHECK (kind IN ('GOODS_RECEIPT', 'ISSUE', 'COUNT', 'TRANSFER')),
    document_date    DATE          NOT NULL,
    supplier_id      UUID          REFERENCES suppliers (id),
    reference        VARCHAR(100),
    cost_center_id   UUID          REFERENCES cost_centers (id),
    description      VARCHAR(1000) NOT NULL,
    status           VARCHAR(10)   NOT NULL CHECK (status IN ('DRAFT', 'POSTED', 'CANCELLED')),
    total_value      NUMERIC(19,2),
    prepared_by_id   UUID          NOT NULL REFERENCES users (id),
    prepared_by      VARCHAR(64)   NOT NULL,
    posted_by        VARCHAR(64),
    posted_entry_id  UUID          REFERENCES journal_entries (id),
    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL,
    updated_at       TIMESTAMPTZ   NOT NULL,
    updated_by       VARCHAR(64)   NOT NULL,
    CONSTRAINT ck_grn_supplier CHECK (kind <> 'GOODS_RECEIPT' OR supplier_id IS NOT NULL)
);
CREATE INDEX ix_inventory_documents_kind ON inventory_documents (kind, status, document_date);

CREATE TABLE inventory_document_lines (
    document_id      UUID          NOT NULL REFERENCES inventory_documents (id) ON DELETE CASCADE,
    line_no          SMALLINT      NOT NULL,
    item_id          UUID          NOT NULL REFERENCES inventory_items (id),
    warehouse_id     UUID          NOT NULL REFERENCES warehouses (id),
    to_warehouse_id  UUID          REFERENCES warehouses (id),
    -- Receipt / issue / transfer: quantity moved (> 0). Count: quantity counted (>= 0).
    quantity         NUMERIC(19,4) NOT NULL CHECK (quantity >= 0),
    unit_cost        NUMERIC(19,6) CHECK (unit_cost IS NULL OR unit_cost >= 0),
    -- Set at posting.
    system_quantity  NUMERIC(19,4),
    value            NUMERIC(19,2),
    PRIMARY KEY (document_id, line_no)
);

CREATE TABLE inventory_movements (
    id             UUID PRIMARY KEY,
    document_id    UUID          NOT NULL REFERENCES inventory_documents (id),
    line_no        SMALLINT      NOT NULL,
    item_id        UUID          NOT NULL REFERENCES inventory_items (id),
    warehouse_id   UUID          NOT NULL REFERENCES warehouses (id),
    movement_type  VARCHAR(14)   NOT NULL CHECK (movement_type IN ('RECEIPT', 'ISSUE', 'COUNT_GAIN', 'COUNT_LOSS', 'TRANSFER_OUT', 'TRANSFER_IN')),
    movement_date  DATE          NOT NULL,
    quantity       NUMERIC(19,4) NOT NULL,
    value          NUMERIC(19,2) NOT NULL,
    -- Item cost pool after the movement.
    pool_quantity  NUMERIC(19,4) NOT NULL,
    pool_value     NUMERIC(19,2) NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL
);
CREATE INDEX ix_inventory_movements_item ON inventory_movements (item_id, created_at);

CREATE FUNCTION inventory_movements_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'inventory movements are immutable';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_inventory_movements_immutable BEFORE UPDATE OR DELETE ON inventory_movements
    FOR EACH ROW EXECUTE FUNCTION inventory_movements_immutable();

-- Supplier invoices charged to the GR/IR clearing account matched to goods receipts.
CREATE TABLE gr_ir_matches (
    id           UUID PRIMARY KEY,
    grn_id       UUID          NOT NULL REFERENCES inventory_documents (id),
    invoice_id   UUID          NOT NULL REFERENCES supplier_invoices (id),
    amount       NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    created_at   TIMESTAMPTZ   NOT NULL,
    created_by   VARCHAR(64)   NOT NULL
);
CREATE INDEX ix_gr_ir_grn ON gr_ir_matches (grn_id);
CREATE INDEX ix_gr_ir_invoice ON gr_ir_matches (invoice_id);
