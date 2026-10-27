import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { inventoryApi } from '../../api/endpoints';
import { useAuth } from '../../auth/AuthContext';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Field } from '../../components/Field';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Spinner } from '../../components/Spinner';
import { isValidAmount } from '../../utils/money';

export function InventoryReportsPage() {
  const { hasPermission } = useAuth();
  const qc = useQueryClient();
  const recon = useQuery({ queryKey: ['inventory-recon'], queryFn: inventoryApi.reconciliation, enabled: hasPermission('LEDGER_VIEW') });
  const grir = useQuery({ queryKey: ['gr-ir'], queryFn: inventoryApi.grIr });
  const [m, setM] = useState({ grnId: '', invoiceId: '', amount: '' });
  const match = useMutation({ mutationFn: () => inventoryApi.match(m.grnId, m.invoiceId, m.amount),
    onSuccess: (g) => { qc.setQueryData(['gr-ir'], g); qc.invalidateQueries({ queryKey: ['inventory-recon'] }); setM({ grnId: '', invoiceId: '', amount: '' }); } });
  const grn = grir.data?.receipts.find((r) => r.id === m.grnId);
  const invoices = (grir.data?.invoices ?? []).filter((v) => !grn || v.supplierId === grn.supplierId);
  return (
    <>
      <PageHeader title="Inventory reports" crumbs={[{ label: 'Inventory' }, { label: 'Reports' }]} />
      {recon.data && (
        <section className="mb-4 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5 text-sm">
          <h2 className="mb-2 font-semibold">Inventory and GR/IR to general ledger {recon.data.reconciled ? <Badge tone="green">RECONCILED</Badge> : <Badge tone="red">DIFFERENCE</Badge>}</h2>
          <table className="min-w-full"><thead className="border-b border-slate-200 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="py-1 pr-3">Account</th><th className="py-1 pr-3">Register</th>
            <th className="py-1 text-right">Register</th><th className="py-1 text-right">Ledger</th><th className="py-1 text-right">Difference</th></tr></thead>
            <tbody>{recon.data.rows.map((r) => (
              <tr key={r.accountCode + r.side} className="border-t border-slate-100"><td className="py-1.5 pr-3">{r.accountCode} {r.accountName}</td>
                <td className="py-1.5 pr-3">{r.side === 'GR_IR' ? 'Open receipts less unmatched invoices' : 'Stock at average cost'}</td>
                <td className="py-1.5 text-right"><Money value={r.register} /></td><td className="py-1.5 text-right"><Money value={r.generalLedger} /></td>
                <td className="py-1.5 text-right"><Money value={r.difference} /></td></tr>))}</tbody></table>
        </section>
      )}
      <ErrorAlert error={recon.error ?? grir.error} />
      {grir.isPending ? <Spinner /> : grir.data && (
        <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5 text-sm">
          <h2 className="mb-1 font-semibold">GR/IR clearing</h2>
          <p className="mb-3 text-xs text-slate-500">Goods received but not yet matched to an invoice, and invoices charged to GR/IR not yet matched to a receipt.
            Price differences that remain after matching are cleared by journal voucher.</p>
          <div className="grid gap-4 lg:grid-cols-2">
            <table className="min-w-full"><thead className="border-b border-slate-200 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="py-1 pr-3">Receipt</th><th className="py-1 pr-3">Supplier</th>
              <th className="py-1 text-right">Open</th></tr></thead>
              <tbody>{grir.data.receipts.map((r) => (
                <tr key={r.id} className="border-t border-slate-100"><td className="py-1.5 pr-3">{r.documentNumber} ({r.documentDate})</td><td className="py-1.5 pr-3">{r.supplierName}</td>
                  <td className="py-1.5 text-right"><Money value={r.open} /></td></tr>))}
                {grir.data.receipts.length === 0 && <tr><td colSpan={3} className="py-3 text-center text-slate-500">None open.</td></tr>}</tbody>
              <tfoot className="border-t-2 border-slate-300 font-medium"><tr><td colSpan={2}>Total</td><td className="text-right"><Money value={grir.data.openReceipts} /></td></tr></tfoot></table>
            <table className="min-w-full"><thead className="border-b border-slate-200 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="py-1 pr-3">Invoice</th><th className="py-1 pr-3">Supplier</th>
              <th className="py-1 text-right">Unmatched</th></tr></thead>
              <tbody>{grir.data.invoices.map((v) => (
                <tr key={v.id} className="border-t border-slate-100"><td className="py-1.5 pr-3">{v.invoiceNumber} ({v.invoiceDate})</td><td className="py-1.5 pr-3">{v.supplierName}</td>
                  <td className="py-1.5 text-right"><Money value={v.available} /></td></tr>))}
                {grir.data.invoices.length === 0 && <tr><td colSpan={3} className="py-3 text-center text-slate-500">None unmatched.</td></tr>}</tbody>
              <tfoot className="border-t-2 border-slate-300 font-medium"><tr><td colSpan={2}>Total</td><td className="text-right"><Money value={grir.data.unmatchedInvoices} /></td></tr></tfoot></table>
          </div>
          {hasPermission('AP_INVOICE_PREPARE') && (
            <div className="mt-4 space-y-3 rounded-md border border-slate-200 p-4">
              <h3 className="font-medium">Match a receipt to an invoice</h3>
              <ErrorAlert error={match.error} />
              <div className="grid gap-3 md:grid-cols-3">
                <label className="block">Goods receipt
                  <select value={m.grnId} onChange={(e) => setM({ ...m, grnId: e.target.value, invoiceId: '' })} className="mt-1.5 block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs">
                    <option value="">Select…</option>{grir.data.receipts.map((r) => <option key={r.id} value={r.id}>{r.documentNumber} — open {r.open}</option>)}</select></label>
                <label className="block">Supplier invoice
                  <select value={m.invoiceId} onChange={(e) => setM({ ...m, invoiceId: e.target.value })} className="mt-1.5 block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs">
                    <option value="">Select…</option>{invoices.map((v) => <option key={v.id} value={v.id}>{v.invoiceNumber} — {v.available}</option>)}</select></label>
                <Field label="Amount to match" inputMode="decimal" value={m.amount} onChange={(e) => setM({ ...m, amount: e.target.value.replace(/[^\d.]/g, '') })} />
              </div>
              <Button variant="secondary" busy={match.isPending} disabled={!m.grnId || !m.invoiceId || !m.amount || !isValidAmount(m.amount)} onClick={() => match.mutate()}>Match</Button>
            </div>
          )}
        </section>
      )}
    </>
  );
}
