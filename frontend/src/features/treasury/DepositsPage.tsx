import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Select } from '../../components/Select';
import { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { settingsApi, treasuryApi } from '../../api/endpoints';
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
  const toggle = (id: string) => { const n = new Set(chosen); if (n.has(id)) n.delete(id); else n.add(id); setChosen(n); };
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
        <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs">
          <table className="min-w-full text-sm">
            <thead className="border-b border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="px-3 py-2" />
              <th className="px-3 py-2">Receipt</th><th className="px-3 py-2">Date</th><th className="px-3 py-2">Customer</th>
              <th className="px-3 py-2">Method</th><th className="px-3 py-2">Collector</th><th className="px-3 py-2 text-right">Amount</th></tr></thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id} className="border-t border-slate-100">
                  <td className="px-3 py-2"><input type="checkbox" aria-label={`Include ${r.receiptNumber}`} checked={chosen.has(r.id)} onChange={() => toggle(r.id)} /></td>
                  <td className="px-3 py-2 font-mono">{r.receiptNumber}</td><td className="px-3 py-2">{r.receiptDate}</td><td className="px-3 py-2">{r.customerName}</td>
                  <td className="px-3 py-2">{r.paymentMethod.replace('_', ' ')} {r.paymentReference ?? ''}</td><td className="px-3 py-2">{r.recordedBy}</td>
                  <td className="px-3 py-2 text-right"><Money value={r.amount} /></td>
                </tr>
              ))}
              {receipts.data?.length === 0 && <tr><td colSpan={7} className="px-4 py-6 text-center text-slate-500">No posted collections awaiting deposit.</td></tr>}
            </tbody>
            <tfoot className="border-t-2 border-slate-300 font-medium"><tr><td colSpan={6} className="px-3 py-2 text-right">Deposit total</td>
              <td className="px-3 py-2 text-right tabular-nums">{fromCents(total)}</td></tr></tfoot>
          </table>
        </div>
        <p className="text-xs text-slate-500">Only collections already posted by accounting can be deposited. Accounting posts the deposit to the bank account.</p>
        <Button busy={create.isPending} disabled={!bankAccountId || chosen.size === 0} onClick={() => create.mutate()}>Save deposit</Button>
      </div>
    </>
  );
}
