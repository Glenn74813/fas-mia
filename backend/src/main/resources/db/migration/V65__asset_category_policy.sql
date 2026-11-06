-- Depreciation and measurement policy per asset class (C-30, C-07), set by the client on the Asset setup page instead of
-- server-wide settings. Defaults keep today's behaviour: depreciation from the month after the asset is placed in
-- service, no charge in the disposal month, carried at cost.
ALTER TABLE asset_categories
    ADD COLUMN depreciation_start         VARCHAR(12) NOT NULL DEFAULT 'NEXT_MONTH'
        CHECK (depreciation_start IN ('SAME_MONTH', 'NEXT_MONTH')),
    ADD COLUMN charge_disposal_month      BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN measurement_model          VARCHAR(12) NOT NULL DEFAULT 'COST'
        CHECK (measurement_model IN ('COST', 'REVALUATION')),
    -- How often a class carried at revalued amounts is revalued (PAS 16 requires it to be regular); none under cost.
    ADD COLUMN revaluation_interval_years SMALLINT CHECK (revaluation_interval_years BETWEEN 1 AND 10),
    ADD CONSTRAINT ck_asset_category_revaluation_interval
        CHECK ((measurement_model = 'REVALUATION') = (revaluation_interval_years IS NOT NULL));
