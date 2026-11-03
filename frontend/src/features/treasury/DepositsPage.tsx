import { ChevronDown } from 'lucide-react';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Select } from '../../components/Select';
import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { settingsApi, treasuryApi } from '../../api/endpoints';
import type { UndepositedReceipt } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Field } from '../../components/Field';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Pagination } from '../../components/Pagination';
import { Spinner } from '../../components/Spinner';
import { StatusBadge } from '../../components/StatusBadge';
import { fromCents, sumCents } from '../../utils/money';

export function DepositsPage() {
  const { hasPermission } = useAuth();
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(0);
  const list = useQuery({ queryKey: ['deposits', status, page], queryFn: () => treasuryApi.deposits(status, page), placeholderData: keepPreviousData });
  return (
    <>
      <PageHeader title="Deposits" crumbs={[{ label: 'Treasury' }, { label: 'Deposits' }]}
        actions={hasPermission('DEPOSIT_PREPARE') && (
          <Link to="/treasury/deposits/new" className="rounded-md bg-brand-700 px-4 py-2 text-sm font-medium text-white hover:bg-brand-900">New deposit</Link>
        )} />
      <Select aria-label="Status" value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }}
        className="mb-4 rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm">
        {['', 'RECORDED', 'POSTED', 'CANCELLED'].map((s) => <option key={s} value={s}>{s || 'All statuses'}</option>)}
      </Select>
      <ErrorAlert error={list.error} />
      {list.isPending ? <Spinner /> : (
        <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs">
          <table className="min-w-full text-sm">
            <thead className="border-b border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="px-3 py-2">Deposit</th>
              <th className="px-3 py-2">Bank</th><th className="px-3 py-2">Date</th><th className="px-3 py-2">Slip / reference</th>
              <th className="px-3 py-2">Items</th><th className="px-3 py-2">Status</th><th className="px-3 py-2 text-right">Amount</th></tr></thead>
            <tbody>
              {(list.data?.items ?? []).map((d) => (
                <tr key={d.id} className="border-t border-slate-100">
                  <td className="px-3 py-2"><Link className="text-brand-700 hover:underline" to={`/treasury/deposits/${d.id}`}>{d.depositNumber}</Link></td>
                  <td className="px-3 py-2">{d.bankAccountCode}</td><td className="px-3 py-2">{d.depositDate}</td>
                  <td className="px-3 py-2">{d.bankReference ?? '—'}</td><td className="px-3 py-2">{d.items}</td>
                  <td className="px-3 py-2"><StatusBadge status={d.status} /></td><td className="px-3 py-2 text-right"><Money value={d.amount} /></td>
                </tr>
              ))}
              {list.data?.items.length === 0 && <tr><td colSpan={7} className="px-4 py-6 text-center text-slate-500">No deposits.</td></tr>}
            </tbody>
          </table>
        </div>
      )}
      {list.data && <Pagination page={list.data.page} totalPages={list.data.totalPages} totalItems={list.data.totalItems} onPage={setPage} />}
    </>
  );
}

export function NewDepositPage() {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const banks = useQuery({ queryKey: ['bank-accounts'], queryFn: settingsApi.bankAccounts });
  const receipts = useQuery({ queryKey: ['undeposited'], queryFn: treasuryApi.undeposited });
  const [bankAccountId, setBank] = useState('');
  const [depositDate, setDate] = useState(new Date().toISOString().slice(0, 10));
  const [bankReference, setRef] = useState('');
  const [chosen, setChosen] = useState<Set<string>>(new Set());
  const rows = receipts.data ?? [];
  const total = sumCents(rows.filter((r) => chosen.has(r.id)).map((r) => r.amount));
  const create = useMutation({
    mutationFn: () => treasuryApi.createDeposit({ bankAccountId, depositDate, bankReference: bankReference || null, receiptIds: [...chosen] }),
    onSuccess: (d) => { qc.invalidateQueries({ queryKey: ['deposits'] }); qc.invalidateQueries({ queryKey: ['undeposited'] }); navigate(`/treasury/deposits/${d.id}`); },
  });
  return (
    <>
      <PageHeader title="New deposit" crumbs={[{ label: 'Treasury' }, { label: 'Deposits', to: '/treasury/deposits' }, { label: 'New' }]} />
      <div className="space-y-4">
        <ErrorAlert error={create.error ?? receipts.error} />
        <div className="grid gap-4 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5 md:grid-cols-3">
          <div className="space-y-1.5"><label htmlFor="bank" className="block text-sm font-medium text-slate-700">Bank account</label>
            <Select id="bank" value={bankAccountId} onChange={(e) => setBank(e.target.value)} className="block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm">
              <option value="">Select…</option>
              {(banks.data ?? []).filter((b) => b.status === 'ACTIVE').map((b) => <option key={b.id} value={b.id}>{b.code} — {b.bankName} ****{b.accountNumberLast4}</option>)}
            </Select></div>
          <Field label="Deposit date" type="date" value={depositDate} onChange={(e) => setDate(e.target.value)} />
          <Field label="Deposit slip number" value={bankReference} maxLength={60} onChange={(e) => setRef(e.target.value)} />
        </div>
        <div className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
          <ReceiptPicker receipts={rows} chosen={chosen} onChange={setChosen} loading={receipts.isPending} />
          <p className="mt-3 flex justify-between border-t border-slate-200 pt-3 text-sm font-medium">
            <span>Deposit total ({chosen.size} {chosen.size === 1 ? 'receipt' : 'receipts'})</span><span className="tabular-nums"><Money value={fromCents(total)} /></span></p>
        </div>
        <p className="text-xs text-slate-500">Only collections already posted by accounting can be deposited. Accounting posts the deposit to the bank account.</p>
        <Button busy={create.isPending} disabled={!bankAccountId || chosen.size === 0} onClick={() => create.mutate()}>Save deposit</Button>
      </div>
    </>
  );
}

const method = (r: UndepositedReceipt) => `${r.paymentMethod.replace('_', ' ')}${r.paymentReference ? ` ${r.paymentReference}` : ''}`;

/**
 * The receipts to deposit, chosen in a searchable drop-down instead of a long table: the page stays one screen high however
 * many collections are waiting. The chosen receipts show as removable chips under it.
 */
function ReceiptPicker({ receipts, chosen, onChange, loading }: {
  receipts: UndepositedReceipt[]; chosen: Set<string>; onChange: (next: Set<string>) => void; loading: boolean;
}) {
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState('');
  const box = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const outside = (e: MouseEvent) => { if (!box.current?.contains(e.target as Node)) setOpen(false); };
    const escape = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false); };
    document.addEventListener('mousedown', outside);
    document.addEventListener('keydown', escape);
    return () => { document.removeEventListener('mousedown', outside); document.removeEventListener('keydown', escape); };
  }, [open]);
  const text = q.trim().toLowerCase();
  const shown = receipts.filter((r) => !text || `${r.receiptNumber} ${r.customerName} ${method(r)} ${r.recordedBy} ${r.receiptDate}`
    .toLowerCase().includes(text));
  const set = (ids: string[], on: boolean) => { const n = new Set(chosen); ids.forEach((id) => (on ? n.add(id) : n.delete(id))); onChange(n); };
  const picked = receipts.filter((r) => chosen.has(r.id));
  const allShown = shown.length > 0 && shown.every((r) => chosen.has(r.id));
  return (
    <div ref={box} className="relative">
      <label htmlFor="receipt-picker" className="mb-1.5 block text-sm font-medium text-slate-700">Receipts to deposit</label>
      <button id="receipt-picker" type="button" aria-haspopup="dialog" aria-expanded={open} disabled={loading} onClick={() => setOpen((o) => !o)}
        className="flex h-10 w-full items-center justify-between rounded-lg border border-slate-300 bg-white px-3 text-left text-sm shadow-xs hover:border-slate-400 disabled:opacity-60">
        <span className={chosen.size ? 'text-slate-900' : 'text-slate-400'}>
          {loading ? 'Loading collections…' : receipts.length === 0 ? 'No posted collections awaiting deposit'
            : chosen.size ? `${chosen.size} of ${receipts.length} receipts selected` : `Choose from ${receipts.length} receipts awaiting deposit…`}</span>
        <ChevronDown aria-hidden className="h-4 w-4 text-slate-400" />
      </button>
      {open && (
        <div role="dialog" aria-label="Choose receipts" className="absolute left-0 right-0 z-20 mt-1 rounded-lg border border-slate-200 bg-white shadow-lg">
          <div className="flex flex-wrap items-center gap-2 border-b border-slate-100 p-2">
            <input autoFocus aria-label="Search receipts" placeholder="Search receipt, customer, check no., collector or date" value={q}
              onChange={(e) => setQ(e.target.value)} className="min-w-0 flex-1 rounded-md border border-slate-300 px-2 py-1.5 text-sm" />
            <button type="button" className="text-xs font-medium text-brand-700 hover:underline disabled:text-slate-400" disabled={shown.length === 0}
              onClick={() => set(shown.map((r) => r.id), !allShown)}>{allShown ? 'Clear shown' : `Select all shown (${shown.length})`}</button>
          </div>
          <ul className="max-h-72 overflow-y-auto py-1 text-sm">
            {shown.map((r) => (
              <li key={r.id}>
                <label className="flex cursor-pointer items-center gap-3 px-3 py-1.5 hover:bg-slate-50">
                  <input type="checkbox" aria-label={`Include ${r.receiptNumber}`} checked={chosen.has(r.id)} onChange={(e) => set([r.id], e.target.checked)} />
                  <span className="w-36 shrink-0 font-mono">{r.receiptNumber}</span>
                  <span className="min-w-0 flex-1 truncate">{r.customerName}<span className="text-slate-500"> · {r.receiptDate} · {method(r)}</span></span>
                  <span className="shrink-0 tabular-nums"><Money value={r.amount} /></span>
                </label>
              </li>
            ))}
            {shown.length === 0 && <li className="px-3 py-4 text-center text-slate-500">No receipt matches.</li>}
          </ul>
          <div className="flex justify-end border-t border-slate-100 p-2"><Button variant="secondary" onClick={() => setOpen(false)}>Done</Button></div>
        </div>
      )}
      {picked.length > 0 && (
        <ul aria-label="Selected receipts" className="mt-3 flex max-h-28 flex-wrap gap-1.5 overflow-y-auto">
          {picked.map((r) => (
            <li key={r.id} className="inline-flex items-center gap-1.5 rounded-full bg-slate-100 py-0.5 pl-2.5 pr-1 text-xs">
              <span className="font-mono">{r.receiptNumber}</span><span className="tabular-nums text-slate-600"><Money value={r.amount} /></span>
              <button type="button" aria-label={`Remove ${r.receiptNumber}`} onClick={() => set([r.id], false)}
                className="rounded-full px-1 text-slate-500 hover:bg-slate-200 hover:text-slate-800">×</button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
