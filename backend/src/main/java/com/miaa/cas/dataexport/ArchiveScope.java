package com.miaa.cas.dataexport;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The records of one fiscal year, for its archive: a filter per table. Transactions belong to the year by their period
 * or, where they have none, by their date; their lines, decisions and supporting documents follow them. Reference data
 * (accounts, cost centres, suppliers, customers, users without secrets) is included whole so the archive can be read
 * on its own. Tables not listed are not part of a year's records.
 */
final class ArchiveScope {

    private ArchiveScope() {
    }

    /** Table name to SQL condition. Built only from the fiscal year's id and dates, which come from the database. */
    static Map<String, String> filters(UUID fiscalYear, LocalDate start, LocalDate end) {
        String fy = "'" + fiscalYear + "'::uuid";
        String between = " BETWEEN DATE '" + start + "' AND DATE '" + end + "'";
        String periods = "(SELECT id FROM accounting_periods WHERE fiscal_year_id = " + fy + ")";
        String entries = "(SELECT id FROM journal_entries WHERE fiscal_year_id = " + fy + ")";
        String vouchers = "(SELECT id FROM journal_vouchers WHERE period_id IN " + periods + ")";
        String invoices = "(SELECT id FROM supplier_invoices WHERE period_id IN " + periods + ")";
        String dvs = "(SELECT id FROM disbursement_vouchers WHERE posted_entry_id IN " + entries + " OR payment_date" + between + ")";
        String billings = "(SELECT id FROM billing_documents WHERE period_id IN " + periods + ")";
        String receipts = "(SELECT id FROM receipts WHERE receipt_date" + between + ")";
        String deposits = "(SELECT id FROM deposits WHERE deposit_date" + between + ")";
        String budgets = "(SELECT id FROM budgets WHERE fiscal_year_id = " + fy + ")";
        String runs = "(SELECT id FROM depreciation_runs WHERE period_id IN " + periods + ")";
        String inventory = "(SELECT id FROM inventory_documents WHERE document_date" + between + ")";
        String statements = "(SELECT id FROM bank_statements WHERE period_end" + between + ")";
        String disposals = "(SELECT id FROM asset_disposals WHERE disposal_date" + between + ")";
        String transfers = "(SELECT id FROM asset_transfers WHERE transfer_date" + between + ")";
        String advances = "(SELECT id FROM supplier_advances WHERE advance_date" + between + ")";
        String adjustments = "(SELECT id FROM budget_adjustments WHERE budget_id IN " + budgets + ")";

        Map<String, String> f = new LinkedHashMap<>();
        f.put("fiscal_years", "id = " + fy);
        f.put("accounting_periods", "fiscal_year_id = " + fy);
        f.put("period_status_changes", "period_id IN " + periods);
        f.put("ledger_balances", "period_id IN " + periods);
        f.put("journal_entries", "fiscal_year_id = " + fy);
        f.put("journal_entry_lines", "entry_id IN " + entries);
        f.put("journal_vouchers", "id IN " + vouchers);
        f.put("journal_voucher_lines", "voucher_id IN " + vouchers);
        f.put("supplier_invoices", "id IN " + invoices);
        f.put("supplier_invoice_lines", "invoice_id IN " + invoices);
        f.put("disbursement_vouchers", "id IN " + dvs);
        f.put("disbursement_voucher_lines", "dv_id IN " + dvs);
        f.put("check_register", "dv_id IN " + dvs);
        f.put("payment_batches", "payment_date" + between);
        f.put("payment_batch_items", "dv_id IN " + dvs);
        f.put("supplier_advances", "id IN " + advances);
        f.put("billing_documents", "id IN " + billings);
        f.put("billing_document_lines", "document_id IN " + billings);
        f.put("receipts", "id IN " + receipts);
        f.put("receipt_allocations", "receipt_id IN " + receipts);
        f.put("deposits", "id IN " + deposits);
        f.put("deposit_items", "deposit_id IN " + deposits);
        f.put("bank_statements", "id IN " + statements);
        f.put("bank_statement_lines", "statement_id IN " + statements);
        f.put("bank_reconciliations", "as_of_date" + between);
        f.put("budgets", "id IN " + budgets);
        f.put("budget_allocations", "budget_id IN " + budgets);
        f.put("budget_adjustments", "id IN " + adjustments);
        f.put("depreciation_runs", "id IN " + runs);
        f.put("depreciation_lines", "run_id IN " + runs);
        f.put("asset_disposals", "id IN " + disposals);
        f.put("asset_transfers", "id IN " + transfers);
        f.put("inventory_documents", "id IN " + inventory);
        f.put("inventory_document_lines", "document_id IN " + inventory);
        f.put("inventory_movements", "movement_date" + between);
        // Each subquery above is "(SELECT id FROM ...)": strip the outer brackets to UNION them.
        String documents = String.join(" UNION ", List.of(vouchers, invoices, dvs, billings, receipts, deposits, inventory, statements,
                disposals, transfers, advances, adjustments).stream().map(s -> s.substring(1, s.length() - 1)).toList());
        f.put("approval_decisions", "document_id IN (" + documents + ")");
        f.put("supporting_documents", "owner_id IN (" + documents + ")");
        // The audit trail of the year (Manila time).
        f.put("audit_events", "occurred_at >= TIMESTAMPTZ '" + start + " 00:00:00+08' AND occurred_at < TIMESTAMPTZ '"
                + end.plusDays(1) + " 00:00:00+08'");
        // Reference data, whole.
        for (String t : List.of("organizations", "offices", "departments", "chart_of_accounts", "cost_centers", "subsidiary_ledger_accounts",
                "tax_codes", "gl_account_mappings", "suppliers", "customers", "customer_contracts", "bank_accounts", "fixed_assets",
                "asset_categories", "inventory_items", "warehouses", "users", "roles")) {
            f.put(t, "TRUE");
        }
        return f;
    }
}
