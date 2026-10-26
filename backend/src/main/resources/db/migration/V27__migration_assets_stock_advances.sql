-- Phase 5b: opening balances for the fixed-asset register, stock on hand, unapplied collections and supplier
-- advances. Like the 5a open items, nothing here posts: each record names the posted opening entry that holds
-- its balance and must agree with it.

ALTER TABLE migration_batches DROP CONSTRAINT migration_batches_kind_check;
ALTER TABLE migration_batches ADD CONSTRAINT migration_batches_kind_check CHECK (kind IN ('GL_OPENING', 'AR_OPEN_ITEMS',
    'AP_OPEN_ITEMS', 'AR_UNAPPLIED', 'AP_ADVANCES', 'FA_REGISTER', 'INVENTORY_STOCK'));

-- Fixed assets: the legacy accumulated depreciation at cutover, and the life still to run from the cutover
-- month. Depreciation of a migrated asset continues from that basis instead of being recomputed from its
-- original in-service date, so differences in legacy policy or rounding are not re-charged in CAS.
ALTER TABLE fixed_assets ADD COLUMN opening_accumulated NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (opening_accumulated >= 0);
ALTER TABLE fixed_assets ADD COLUMN depreciation_basis_date DATE;
ALTER TABLE fixed_assets ADD COLUMN remaining_life_months SMALLINT CHECK (remaining_life_months IS NULL OR remaining_life_months >= 0);
ALTER TABLE fixed_assets ADD COLUMN migration_batch_id UUID REFERENCES migration_batches (id);
ALTER TABLE fixed_assets DROP CONSTRAINT fixed_assets_acquisition_source_check;
ALTER TABLE fixed_assets ALTER COLUMN acquisition_source TYPE VARCHAR(10);
ALTER TABLE fixed_assets ADD CONSTRAINT fixed_assets_acquisition_source_check
    CHECK (acquisition_source IN ('INVOICE', 'AUC', 'OTHER', 'MIGRATION'));
ALTER TABLE fixed_assets DROP CONSTRAINT ck_fa_source;
ALTER TABLE fixed_assets ADD CONSTRAINT ck_fa_source CHECK (
    (acquisition_source = 'INVOICE' AND supplier_invoice_id IS NOT NULL) OR
    (acquisition_source = 'AUC' AND auc_project_id IS NOT NULL) OR
    (acquisition_source = 'OTHER' AND offset_account_id IS NOT NULL AND justification IS NOT NULL) OR
    (acquisition_source = 'MIGRATION' AND migration_batch_id IS NOT NULL));
ALTER TABLE fixed_assets ADD CONSTRAINT ck_fa_migration_basis CHECK (
    (migration_batch_id IS NULL AND depreciation_basis_date IS NULL AND remaining_life_months IS NULL AND opening_accumulated = 0) OR
    (migration_batch_id IS NOT NULL AND depreciation_basis_date IS NOT NULL AND remaining_life_months IS NOT NULL
        AND remaining_life_months <= useful_life_months AND opening_accumulated <= cost - residual_value));

-- Stock on hand at cutover is one posted OPENING inventory document per batch.
ALTER TABLE inventory_documents DROP CONSTRAINT inventory_documents_kind_check;
ALTER TABLE inventory_documents ADD CONSTRAINT inventory_documents_kind_check
    CHECK (kind IN ('GOODS_RECEIPT', 'ISSUE', 'COUNT', 'TRANSFER', 'OPENING'));
ALTER TABLE inventory_documents ADD COLUMN migration_batch_id UUID REFERENCES migration_batches (id);
ALTER TABLE inventory_documents ADD CONSTRAINT ck_inv_doc_migration CHECK ((kind = 'OPENING') = (migration_batch_id IS NOT NULL));
ALTER TABLE inventory_movements DROP CONSTRAINT inventory_movements_movement_type_check;
ALTER TABLE inventory_movements ADD CONSTRAINT inventory_movements_movement_type_check
    CHECK (movement_type IN ('RECEIPT', 'ISSUE', 'COUNT_GAIN', 'COUNT_LOSS', 'TRANSFER_OUT', 'TRANSFER_IN', 'OPENING'));

-- Unapplied collections at cutover are posted receipts carrying only an unapplied amount. They were collected
-- and deposited in the legacy system, so they never appear as cash to deposit or in a daily collection report.
ALTER TABLE receipts ADD COLUMN migration_batch_id UUID REFERENCES migration_batches (id);
ALTER TABLE receipts DROP CONSTRAINT receipts_payment_method_check;
ALTER TABLE receipts ADD CONSTRAINT receipts_payment_method_check CHECK (payment_method IN ('CASH', 'CHECK', 'BANK_TRANSFER', 'MIGRATED'));
ALTER TABLE receipts ADD CONSTRAINT ck_receipt_migration CHECK ((payment_method = 'MIGRATED') = (migration_batch_id IS NOT NULL));
CREATE INDEX ix_receipts_migration ON receipts (migration_batch_id) WHERE migration_batch_id IS NOT NULL;

-- Supplier advances outstanding at cutover. CAS has no process yet for paying or liquidating advances (C-31);
-- this register holds the migrated balances per supplier so they reconcile with the ledger from day one.
CREATE TABLE supplier_advances (
    id                  UUID PRIMARY KEY,
    supplier_id         UUID          NOT NULL REFERENCES suppliers (id),
    reference           VARCHAR(60)   NOT NULL,
    advance_date        DATE          NOT NULL,
    description         VARCHAR(1000) NOT NULL,
    amount              NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    outstanding_amount  NUMERIC(19,2) NOT NULL CHECK (outstanding_amount >= 0),
    status              VARCHAR(10)   NOT NULL CHECK (status IN ('OPEN', 'CLOSED')),
    posted_entry_id     UUID          NOT NULL REFERENCES journal_entries (id),
    migration_batch_id  UUID          NOT NULL REFERENCES migration_batches (id),
    prepared_by_id      UUID          NOT NULL REFERENCES users (id),
    prepared_by         VARCHAR(64)   NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_advance_outstanding CHECK (outstanding_amount <= amount)
);
CREATE UNIQUE INDEX uq_supplier_advance_ref ON supplier_advances (supplier_id, lower(reference));
CREATE INDEX ix_supplier_advances_supplier ON supplier_advances (supplier_id, status);
