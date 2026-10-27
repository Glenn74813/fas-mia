import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { customerApi, receiptApi } from '../../api/endpoints';
import { useAuth } from '../../auth/AuthContext';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Field } from '../../components/Field';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Pagination } from '../../components/Pagination';
import { SortHeader } from '../../components/SortHeader';
import { SearchSelect } from '../../components/SearchSelect';
import { Spinner } from '../../components/Spinner';
import { StatusBadge } from '../../components/StatusBadge';
import { fromCents, isValidAmount, sumCents, toCents } from '../../utils/money';
import { KIND_LABEL } from './BillingsPage';

export function ReceiptsPage() {
  const { hasPermission } = useAuth();
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(0);
  const [sort, setSort] = useState('');
  const onSort = (next: string) => { setSort(next); setPage(0); };
  const list = useQuery({ queryKey: ['receipts', status, page, sort], placeholderData: keepPreviousData,
    queryFn: () => receiptApi.list({ status, page, size: 25, sort: sort || undefined }) });
  return (
    <>
      <PageHeader title="Collections" crumbs={[{ label: 'Receivables' }, { label: 'Collections' }]}
        actions={hasPermission('COLLECTION_RECORD') && (
          <Link to="/receivables/receipts/new" className="rounded-md bg-brand-700 px-4 py-2 text-sm font-medium text-white hover:bg-brand-900">Record collection</Link>
        )} />
      <div className="mb-4 flex gap-3">
        <select aria-label="Status" value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }}
          className="rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm">
          {['', 'RECORDED', 'POSTED', 'CANCELLED'].map((s) => <option key={s} value={s}>{s || 'All statuses'}</option>)}
        </select>
      </div>
      <ErrorAlert error={list.error} />
      {list.isPending ? <Spinner /> : list.data && (
        <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs">
          <table className="min-w-full text-sm">
            <thead className="border-b border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500">
              <tr><th className="px-3 py-2">Receipt</th><th className="px-3 py-2">Customer</th><SortHeader label="Date" field="receiptDate" sort={sort} onSort={onSort} descFirst />
                <th className="px-3 py-2">Method</th><th className="px-3 py-2">Status</th><SortHeader label="Amount" field="amount" sort={sort} onSort={onSort} descFirst align="right" />
                <th className="px-3 py-2 text-right">Tax withheld</th><th className="px-3 py-2 text-right">Unapplied</th></tr>
            </thead>
            <tbody>
              {list.data.items.map((r) => (
                <tr key={r.id} className="border-t border-slate-100">
                  <td className="whitespace-nowrap px-3 py-2"><Link className="text-brand-700 hover:underline" to={`/receivables/receipts/${r.id}`}>{r.receiptNumber}</Link></td>
                  <td className="px-3 py-2">{r.customerName}</td><td className="whitespace-nowrap px-3 py-2">{r.receiptDate}</td>
                  <td className="px-3 py-2">{r.paymentMethod.replace('_', ' ')}</td><td className="px-3 py-2"><StatusBadge status={r.status} /></td>
                  <td className="px-3 py-2 text-right"><Money value={r.amount} /></td>
                  <td className="px-3 py-2 text-right"><Money value={r.taxWithheld} blankZero /></td>
                  <td className="px-3 py-2 text-right"><Money value={r.unappliedAmount} blankZero /></td>
                </tr>
              ))}
              {list.data.items.length === 0 && <tr><td colSpan={8} className="px-4 py-6 text-center text-slate-500">No collections.</td></tr>}
            </tbody>
          </table>
        </div>
      )}
      {list.data && <Pagination page={list.data.page} totalPages={list.data.totalPages} totalItems={list.data.totalItems} onPage={setPage} />}
    </>
  );
}

export function NewReceiptPage() {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const recordKey = useRef<string | null>(null);
  const today = new Date().toISOString().slice(0, 10);
  const [form, setForm] = useState({ customerId: '', receiptDate: today, paymentMethod: 'CHECK', paymentReference: '',
    amount: '', taxWithheld: '', remarks: '' });
  const [amounts, setAmounts] = useState<Record<string, string>>({});
  const open = useQuery({ queryKey: ['open-items', form.customerId], enabled: !!form.customerId,
    queryFn: () => customerApi.openItems(form.customerId) });
  const chosen = Object.entries(amounts).filter(([, v]) => v.trim() !== '');
  const received = (toCents(form.amount || '0') ?? 0n) + (toCents(form.taxWithheld || '0') ?? 0n);
  const allocated = sumCents(chosen.map(([, v]) => v));
  const record = useMutation({
    mutationFn: () => {
      // One key per collection: a retry after a lost response returns the same receipt instead of recording it twice.
      recordKey.current ??= crypto.randomUUID();
      return receiptApi.record({ ...form, paymentReference: form.paymentReference || null, remarks: form.remarks || null,
        taxWithheld: form.taxWithheld || '0', allocations: chosen.map(([documentId, amount]) => ({ documentId, amount })) }, recordKey.current);
    },
    onSuccess: (r) => { recordKey.current = null; qc.invalidateQueries({ queryKey: ['receipts'] }); navigate(`/receivables/receipts/${r.id}`); },
  });
  const valid = form.customerId && isValidAmount(form.amount) && form.amount && isValidAmount(form.taxWithheld)
    && (form.paymentMethod === 'CASH' || form.paymentReference.trim()) && chosen.every(([, v]) => isValidAmount(v)) && allocated <= received;
  const select = 'block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm';
  return (
    <>
      <PageHeader title="Record collection" crumbs={[{ label: 'Receivables' }, { label: 'Collections', to: '/receivables/receipts' }, { label: 'New' }]} />
      <div className="space-y-4">
        <ErrorAlert error={record.error} />
        <div className="grid gap-4 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5 md:grid-cols-3">
          <SearchSelect id="payer" label="Customer" value={form.customerId} queryKey="customers" placeholder="Select…"
            search={(q) => customerApi.list(q, 0, 50)} optionLabel={(c) => `${c.code} — ${c.name}`}
            onChange={(id) => { setForm({ ...form, customerId: id }); setAmounts({}); }} />
          <Field label="Receipt date" type="date" value={form.receiptDate} onChange={(e) => setForm({ ...form, receiptDate: e.target.value })} />
          <div className="space-y-1.5"><label htmlFor="method" className="block text-sm font-medium text-slate-700">Payment method</label>
            <select id="method" value={form.paymentMethod} className={select} onChange={(e) => setForm({ ...form, paymentMethod: e.target.value })}>
              <option value="CASH">Cash</option><option value="CHECK">Check</option><option value="BANK_TRANSFER">Bank transfer</option></select></div>
          <Field label={form.paymentMethod === 'CHECK' ? 'Check number / bank' : 'Reference'} value={form.paymentReference} maxLength={60}
            onChange={(e) => setForm({ ...form, paymentReference: e.target.value })} />
          <Field label="Amount received" inputMode="decimal" value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value.replace(/[^\d.]/g, '') })} />
          <Field label="Tax withheld by customer" hint="Backed by the customer's withholding certificate" inputMode="decimal" value={form.taxWithheld}
            onChange={(e) => setForm({ ...form, taxWithheld: e.target.value.replace(/[^\d.]/g, '') })} />
          <div className="md:col-span-3"><Field label="Remarks" value={form.remarks} maxLength={500} onChange={(e) => setForm({ ...form, remarks: e.target.value })} /></div>
        </div>
        {form.customerId && (
          <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs">
            <table className="min-w-full text-sm">
              <thead className="border-b border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="px-3 py-2">Document</th>
                <th className="px-3 py-2">Due</th><th className="px-3 py-2 text-right">Open</th><th className="px-3 py-2 text-right">Available</th>
                <th className="px-3 py-2 text-right">Apply</th></tr></thead>
              <tbody>
                {(open.data ?? []).map((o) => (
                  <tr key={o.id} className="border-t border-slate-100"><td className="px-3 py-2">{KIND_LABEL[o.kind]} {o.documentNumber}</td><td className="px-3 py-2">{o.dueDate}</td>
                    <td className="px-3 py-2 text-right"><Money value={o.openBalance} /></td><td className="px-3 py-2 text-right"><Money value={o.available} /></td>
                    <td className="px-3 py-2 text-right"><input aria-label={`Apply to ${o.documentNumber}`} inputMode="decimal" value={amounts[o.id] ?? ''} placeholder="0.00"
                      onChange={(e) => setAmounts({ ...amounts, [o.id]: e.target.value.replace(/[^\d.]/g, '') })}
                      className="w-32 rounded-md border border-slate-300 px-2 py-1 text-right" />
                      <button type="button" className="ml-2 text-xs text-brand-700" onClick={() => setAmounts({ ...amounts, [o.id]: o.available })}>full</button></td></tr>
                ))}
                {open.data?.length === 0 && <tr><td colSpan={5} className="px-4 py-6 text-center text-slate-500">No open invoices — the whole amount will be unapplied.</td></tr>}
              </tbody>
              <tfoot className="border-t-2 border-slate-300 font-medium">
                <tr><td colSpan={4} className="px-3 py-2 text-right">Applied</td><td className="px-3 py-2 text-right tabular-nums">{fromCents(allocated)}</td></tr>
                <tr><td colSpan={4} className="px-3 py-2 text-right">Unapplied (credit to customer)</td>
                  <td className={`px-3 py-2 text-right tabular-nums ${allocated > received ? 'text-red-700' : ''}`}>{fromCents(received - allocated)}</td></tr>
              </tfoot>
            </table>
          </div>
        )}
        <p className="text-xs text-slate-500">The receipt number is assigned when you save (interim series pending the BIR-registered official receipts, C-29).
          Accounting posts it to the ledger.</p>
        <Button busy={record.isPending} disabled={!valid} onClick={() => record.mutate()}>Save receipt</Button>
      </div>
    </>
  );
}
