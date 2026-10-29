-- C-32: the Request for Payment and the RIAR (inspection and acceptance report) are source documents prepared by
-- other offices; CAS records their reference numbers (and their scans as attachments), not a workflow for them.
ALTER TABLE supplier_invoices ADD COLUMN rfp_reference VARCHAR(60), ADD COLUMN riar_reference VARCHAR(60);
ALTER TABLE disbursement_vouchers ADD COLUMN rfp_reference VARCHAR(60);
