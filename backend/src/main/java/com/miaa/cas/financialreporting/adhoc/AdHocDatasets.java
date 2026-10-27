package com.miaa.cas.financialreporting.adhoc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The approved datasets an ad hoc report may draw on (TOR Annex B.5). Each is a fixed query with a catalogue of
 * columns; a report can only name these columns, so it can never reach other tables or inject SQL, and each dataset
 * needs the view permission of its area.
 */
final class AdHocDatasets {

    enum Type { TEXT, DATE, AMOUNT, NUMBER }

    record Column(String key, String label, Type type, String sql) {
    }

    record Dataset(String code, String label, String permission, String from, Map<String, Column> columns) {
    }

    private AdHocDatasets() {
    }

    static final Map<String, Dataset> ALL = new LinkedHashMap<>();

    static {
        add(withColumns("LEDGER_LINES", "Posted ledger lines", "LEDGER_VIEW", """
                journal_entry_lines l JOIN journal_entries e ON e.id = l.entry_id JOIN chart_of_accounts a ON a.id = l.account_id
                JOIN accounting_periods p ON p.id = l.period_id LEFT JOIN cost_centers cc ON cc.id = l.cost_center_id
                LEFT JOIN subsidiary_ledger_accounts sl ON sl.id = l.subsidiary_account_id""", List.of(
                new Column("entryNumber", "Entry", Type.TEXT, "e.entry_number"),
                new Column("accountingDate", "Date", Type.DATE, "l.accounting_date"),
                new Column("period", "Period", Type.TEXT, "p.name"),
                new Column("accountCode", "Account", Type.TEXT, "a.code"),
                new Column("accountName", "Account name", Type.TEXT, "a.name"),
                new Column("costCenter", "Cost centre", Type.TEXT, "cc.code"),
                new Column("subsidiary", "Subsidiary account", Type.TEXT, "sl.code"),
                new Column("source", "Source", Type.TEXT, "e.source_type"),
                new Column("description", "Description", Type.TEXT, "coalesce(l.description, e.description)"),
                new Column("debit", "Debit", Type.AMOUNT, "l.debit"),
                new Column("credit", "Credit", Type.AMOUNT, "l.credit"),
                new Column("net", "Debit - credit", Type.AMOUNT, "(l.debit - l.credit)"))));
        add(withColumns("AP_INVOICES", "Supplier invoices", "AP_VIEW", """
                supplier_invoices i JOIN suppliers s ON s.id = i.supplier_id""", List.of(
                new Column("invoiceNumber", "Invoice", Type.TEXT, "i.invoice_number"),
                new Column("invoiceDate", "Invoice date", Type.DATE, "i.invoice_date"),
                new Column("dueDate", "Due", Type.DATE, "i.due_date"),
                new Column("supplier", "Supplier", Type.TEXT, "s.name"),
                new Column("status", "Status", Type.TEXT, "i.status"),
                new Column("net", "Net", Type.AMOUNT, "i.net_amount"),
                new Column("vat", "VAT", Type.AMOUNT, "i.vat_amount"),
                new Column("ewt", "EWT", Type.AMOUNT, "i.ewt_amount"),
                new Column("payable", "Payable", Type.AMOUNT, "i.payable_amount"),
                new Column("paid", "Paid", Type.AMOUNT, "i.paid_amount"),
                new Column("open", "Open", Type.AMOUNT, "(i.payable_amount - i.paid_amount)"))));
        add(withColumns("AR_BILLINGS", "Billings", "AR_VIEW", """
                billing_documents b JOIN customers c ON c.id = b.customer_id""", List.of(
                new Column("documentNumber", "Document", Type.TEXT, "b.document_number"),
                new Column("kind", "Kind", Type.TEXT, "b.kind"),
                new Column("documentDate", "Date", Type.DATE, "b.document_date"),
                new Column("dueDate", "Due", Type.DATE, "b.due_date"),
                new Column("billingPeriod", "Billing period", Type.TEXT, "b.billing_period"),
                new Column("customer", "Customer", Type.TEXT, "c.name"),
                new Column("status", "Status", Type.TEXT, "b.status"),
                new Column("net", "Net", Type.AMOUNT, "b.net_amount"),
                new Column("vat", "VAT", Type.AMOUNT, "b.vat_amount"),
                new Column("gross", "Gross", Type.AMOUNT, "b.gross_amount"),
                new Column("settled", "Settled", Type.AMOUNT, "b.settled_amount"),
                new Column("open", "Open", Type.AMOUNT, "(b.gross_amount - b.settled_amount)"))));
        add(withColumns("DISBURSEMENTS", "Disbursement vouchers", "AP_VIEW", """
                disbursement_vouchers d JOIN suppliers s ON s.id = d.supplier_id""", List.of(
                new Column("dvNumber", "DV", Type.TEXT, "d.dv_number"),
                new Column("supplier", "Supplier", Type.TEXT, "s.name"),
                new Column("status", "Status", Type.TEXT, "d.status"),
                new Column("paymentMode", "Mode", Type.TEXT, "d.payment_mode"),
                new Column("paymentDate", "Payment date", Type.DATE, "d.payment_date"),
                new Column("createdDate", "Prepared", Type.DATE, "CAST(d.created_at AT TIME ZONE 'Asia/Manila' AS DATE)"),
                new Column("amount", "Amount", Type.AMOUNT, "d.amount"))));
        add(withColumns("RECEIPTS", "Receipts", "AR_VIEW", """
                receipts r JOIN customers c ON c.id = r.customer_id""", List.of(
                new Column("receiptNumber", "Receipt", Type.TEXT, "r.receipt_number"),
                new Column("receiptDate", "Date", Type.DATE, "r.receipt_date"),
                new Column("customer", "Customer", Type.TEXT, "c.name"),
                new Column("method", "Method", Type.TEXT, "r.payment_method"),
                new Column("status", "Status", Type.TEXT, "r.status"),
                new Column("amount", "Amount", Type.AMOUNT, "r.amount"),
                new Column("taxWithheld", "Tax withheld", Type.AMOUNT, "r.tax_withheld"),
                new Column("unapplied", "Unapplied", Type.AMOUNT, "r.unapplied_amount"))));
    }

    private static void add(Dataset d) {
        ALL.put(d.code(), d);
    }

    private static Dataset withColumns(String code, String label, String permission, String from, List<Column> columns) {
        Map<String, Column> m = new LinkedHashMap<>();
        columns.forEach(c -> m.put(c.key(), c));
        return new Dataset(code, label, permission, from, m);
    }
}
