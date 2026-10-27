-- Lower of cost and net realisable value (TOR Annex B.4): a year-end review snapshots each item's quantity and
-- weighted-average cost, its movement rate and coverage (slow-moving flags), and the market value per unit entered or
-- imported for it. The write-down is proposed as a draft inventory revaluation (unit cost restated to market value),
-- which is reviewed and posted like any other inventory document. The measurement basis and the slow-moving rule are
-- proposals for MIAA's accounting policy (C-07).
CREATE TABLE inventory_nrv_reviews (
    id                    UUID PRIMARY KEY,
    as_of                 DATE           NOT NULL,
    status                VARCHAR(10)    NOT NULL CHECK (status IN ('OPEN', 'PROPOSED', 'CANCELLED')),
    coverage_threshold    NUMERIC(6,1)   NOT NULL,
    note                  VARCHAR(1000),
    proposed_document_id  UUID           REFERENCES inventory_documents (id),
    prepared_by_id        UUID           NOT NULL REFERENCES users (id),
    prepared_by           VARCHAR(64)    NOT NULL,
    created_at            TIMESTAMPTZ    NOT NULL,
    updated_at            TIMESTAMPTZ    NOT NULL,
    version               BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT ck_nrv_proposed CHECK ((status = 'PROPOSED') = (proposed_document_id IS NOT NULL))
);

CREATE TABLE inventory_nrv_lines (
    review_id           UUID           NOT NULL REFERENCES inventory_nrv_reviews (id) ON DELETE CASCADE,
    item_id             UUID           NOT NULL REFERENCES inventory_items (id),
    quantity            NUMERIC(19,4)  NOT NULL,
    unit_cost           NUMERIC(19,6)  NOT NULL,
    carrying_value      NUMERIC(19,2)  NOT NULL,
    last_movement_date  DATE,
    issued_12m          NUMERIC(19,4)  NOT NULL,
    -- Months of stock at the last twelve months' issue rate; NULL when nothing was issued.
    coverage_months     NUMERIC(10,1),
    slow_moving         BOOLEAN        NOT NULL,
    market_unit_value   NUMERIC(19,6),
    price_source        VARCHAR(200),
    write_down          NUMERIC(19,2)  NOT NULL DEFAULT 0,
    PRIMARY KEY (review_id, item_id)
);
