-- A fingerprint of the financial state: row counts of the records that matter, ledger totals, a digest of every
-- account's balance, the last journal number per year, the heads of both audit hash chains and a digest of the
-- stored documents' SHA-256 values. Two databases with equal fingerprints hold the same books and audit trail.
SELECT 'count.' || t, n::text FROM (
  SELECT 'users' AS t, count(*) AS n FROM users UNION ALL
  SELECT 'journal_vouchers', count(*) FROM journal_vouchers UNION ALL
  SELECT 'journal_entries', count(*) FROM journal_entries UNION ALL
  SELECT 'journal_entry_lines', count(*) FROM journal_entry_lines UNION ALL
  SELECT 'billing_documents', count(*) FROM billing_documents UNION ALL
  SELECT 'receipts', count(*) FROM receipts UNION ALL
  SELECT 'receipt_allocations', count(*) FROM receipt_allocations UNION ALL
  SELECT 'supplier_invoices', count(*) FROM supplier_invoices UNION ALL
  SELECT 'disbursement_vouchers', count(*) FROM disbursement_vouchers UNION ALL
  SELECT 'fixed_assets', count(*) FROM fixed_assets UNION ALL
  SELECT 'inventory_movements', count(*) FROM inventory_movements UNION ALL
  SELECT 'migration_batches', count(*) FROM migration_batches UNION ALL
  SELECT 'supporting_documents', count(*) FROM supporting_documents UNION ALL
  SELECT 'audit_events', count(*) FROM audit_events UNION ALL
  SELECT 'security_events', count(*) FROM security_events
) c
UNION ALL SELECT 'ledger.total_debit', coalesce(sum(debit), 0)::text FROM journal_entry_lines
UNION ALL SELECT 'ledger.total_credit', coalesce(sum(credit), 0)::text FROM journal_entry_lines
UNION ALL SELECT 'ledger.balances_md5', md5(coalesce(string_agg(account_id || ':' || bal, ',' ORDER BY account_id), ''))
  FROM (SELECT account_id, sum(debit - credit) AS bal FROM journal_entry_lines GROUP BY account_id) b
UNION ALL SELECT 'journal.last_number.' || split_part(entry_number, '-', 2), max(entry_number) FROM journal_entries GROUP BY 1
UNION ALL SELECT 'audit.head', coalesce((SELECT id || ':' || row_hash FROM audit_events ORDER BY id DESC LIMIT 1), 'none')
UNION ALL SELECT 'security.head', coalesce((SELECT id || ':' || row_hash FROM security_events ORDER BY id DESC LIMIT 1), 'none')
UNION ALL SELECT 'documents.sha256_md5', md5(coalesce(string_agg(sha256, ',' ORDER BY id), '')) FROM supporting_documents
ORDER BY 1;
