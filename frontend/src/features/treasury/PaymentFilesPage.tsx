import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { paymentFileApi, settingsApi } from '../../api/endpoints';
import type { PaymentBatch } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Field } from '../../components/Field';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Pagination } from '../../components/Pagination';
import { formatDateTime } from '../../utils/format';
import { fromCents, sumCents } from '../../utils/money';

export function PaymentFilesPage() {
  const { hasPermission } = useAuth();
  const release = hasPermission('PAYMENT_RELEASE');
  const qc = useQueryClient();
  const banks = useQuery({ queryKey: ['bank-accounts'], queryFn: settingsApi.bankAccounts });
  const eligible = useQuery({ queryKey: ['payment-eligible'], queryFn: paymentFileApi.eligible, enabled: release });
  const [page, setPage] = useState(0);
  const batches = useQuery({ queryKey: ['payment-batches', page], queryFn: () => paymentFileApi.list(page), placeholderData: keepPreviousData });
  const createKey = useRef<string | null>(null);
  const [bankId, setBank] = useState('');
  const [date, setDate] = useState(new Date().toISOString().slice(0, 10));
  const [chosen, setChosen] = useState<Set<string>>(new Set());
  const create = useMutation({
    mutationFn: () => {
      // One key per payment file: a retry after a lost response returns the same file instead of a second batch.
      createKey.current ??= crypto.randomUUID();
      return paymentFileApi.create(bankId, date, [...chosen], createKey.current);
    },
    onSuccess: () => { createKey.current = null; setChosen(new Set()); qc.invalidateQueries({ queryKey: ['payment-eligible'] }); qc.invalidateQueries({ queryKey: ['payment-batches'] }); } });
  const dl = useMutation({ mutationFn: (b: PaymentBatch) => paymentFileApi.download(b), onSuccess: () => qc.invalidateQueries({ queryKey: ['payment-batches'] }) });
  const rows = eligible.data ?? [];
  const total = sumCents(rows.filter((r) => chosen.has(r.dvId)).map((r) => r.amount));
  const toggle = (id: string) => { const n = new Set(chosen); if (n.has(id)) n.delete(id); else n.add(id); setChosen(n); };
  return (
    <>
      <PageHeader title="ADA payment files" crumbs={[{ label: 'Treasury' }, { label: 'Payment files' }]} />
      {release && (
        <section className="mb-4 space-y-3 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5 text-sm">
          <h2 className="font-semibold">Approved vouchers awaiting payment</h2>
          <ErrorAlert error={eligible.error ?? create.error} />
          <table className="min-w-full"><thead className="border-b border-slate-200 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th /><th className="py-1 pr-3">DV</th><th className="py-1 pr-3">Payee</th>
            <th className="py-1 pr-3">Payee account</th><th className="py-1 text-right">Amount</th></tr></thead>
            <tbody>{rows.map((r) => (
              <tr key={r.dvId} className="border-t border-slate-100"><td className="py-1.5 pr-2"><input type="checkbox" aria-label={`Include ${r.dvNumber}`}
                disabled={!!r.blockedReason} checked={chosen.has(r.dvId)} onChange={() => toggle(r.dvId)} /></td>
                <td className="py-1.5 pr-3">{r.dvNumber}</td><td className="py-1.5 pr-3">{r.supplierName}</td>
                <td className="py-1.5 pr-3">{r.blockedReason ? <span className="text-amber-800">{r.blockedReason}</span> : `${r.payeeBank} ${r.payeeAccountMasked}`}</td>
                <td className="py-1.5 text-right"><Money value={r.amount} /></td></tr>))}
              {rows.length === 0 && <tr><td colSpan={5} className="py-3 text-center text-slate-500">No approved vouchers awaiting payment.</td></tr>}</tbody></table>
          <div className="flex flex-wrap items-end gap-3">
            <label>Debit account <select value={bankId} onChange={(e) => setBank(e.target.value)} className="ml-1 rounded-md border border-slate-300 px-2 py-1">
              <option value="">Select…</option>{(banks.data ?? []).filter((b) => b.status === 'ACTIVE').map((b) => <option key={b.id} value={b.id}>{b.code} ****{b.accountNumberLast4}</option>)}</select></label>
            <Field label="Payment date" type="date" value={date} onChange={(e) => setDate(e.target.value)} />
            <span>Total {fromCents(total)}</span>
            <Button busy={create.isPending} disabled={!bankId || chosen.size === 0} onClick={() => create.mutate()}>Create payment file</Button>
          </div>
          <p className="text-xs text-slate-500">Creating the file issues each voucher's ADA instruction. Confirm release on each voucher once the bank has processed it.
            The file uses the CAS generic layout until the banks' formats are confirmed (C-11) and contains full account numbers: store it only where the bank upload requires.</p>
        </section>
      )}
      <ErrorAlert error={batches.error ?? dl.error} />
      <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs"><table className="min-w-full text-sm">
        <thead className="border-b border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="px-3 py-2">Batch</th><th className="px-3 py-2">Debit account</th>
          <th className="px-3 py-2">Payment date</th><th className="px-3 py-2">Items</th><th className="px-3 py-2 text-right">Total</th><th className="px-3 py-2">Created</th>
          <th className="px-3 py-2">Downloads</th><th /></tr></thead>
        <tbody>{(batches.data?.items ?? []).map((b) => (
          <tr key={b.id} className="border-t border-slate-100" title={b.items.map((i) => `${i.dvNumber} ${i.payee} ${i.account} ${i.dvStatus}`).join('\n')}>
            <td className="px-3 py-2 font-mono">{b.batchNumber}</td><td className="px-3 py-2">{b.bankAccountCode}</td><td className="px-3 py-2">{b.paymentDate}</td>
            <td className="px-3 py-2">{b.itemCount}</td><td className="px-3 py-2 text-right"><Money value={b.totalAmount} /></td>
            <td className="px-3 py-2">{b.createdBy}, {formatDateTime(b.createdAt)}</td><td className="px-3 py-2">{b.downloadCount}</td>
            <td className="px-3 py-2 text-right">{release && <button className="text-brand-700 hover:underline" onClick={() => dl.mutate(b)}>Download</button>}</td></tr>))}
          {batches.data?.items.length === 0 && <tr><td colSpan={8} className="px-4 py-6 text-center text-slate-500">No payment files yet.</td></tr>}</tbody>
      </table></div>
      {batches.data && <Pagination page={batches.data.page} totalPages={batches.data.totalPages} totalItems={batches.data.totalItems} onPage={setPage} />}
    </>
  );
}
