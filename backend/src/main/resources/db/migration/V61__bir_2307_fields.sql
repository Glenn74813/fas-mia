-- Details BIR Form 2307 prints and the tables did not hold: the ZIP codes and foreign address of the payee, the
-- registered address and ZIP code of the payor, and the alphanumeric tax code (ATC) of each withholding tax code.
-- All are optional here: a certificate missing any of them is generated as a draft.
ALTER TABLE suppliers
    ADD COLUMN zip_code        VARCHAR(4) CHECK (zip_code IS NULL OR zip_code ~ '^[0-9]{4}$'),
    ADD COLUMN foreign_address VARCHAR(500);

ALTER TABLE organizations
    ADD COLUMN address  VARCHAR(500),
    ADD COLUMN zip_code VARCHAR(4) CHECK (zip_code IS NULL OR zip_code ~ '^[0-9]{4}$');

ALTER TABLE tax_codes
    ADD COLUMN atc VARCHAR(5) CHECK (atc IS NULL OR atc ~ '^[A-Z]{2}[0-9]{3}$');
