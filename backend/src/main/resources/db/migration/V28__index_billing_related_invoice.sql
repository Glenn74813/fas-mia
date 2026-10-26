-- Phase 5c finding: AR aging and open-item queries look up each invoice's credit memos by related_invoice_id,
-- which had no index, so every invoice scanned the whole billing table (quadratic in volume: 3.9 s for 8,000
-- documents in the performance kit's seed). Only memos carry the column, so the index is partial and small.
CREATE INDEX ix_billing_related_invoice ON billing_documents (related_invoice_id) WHERE related_invoice_id IS NOT NULL;
