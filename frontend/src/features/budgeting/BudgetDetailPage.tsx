import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Combobox } from '../../components/Combobox';
import { useState } from 'react';
import { useParams } from 'react-router';
import { budgetApi } from '../../api/endpoints';
import type { BudgetView } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { ActionDialog, COMMON_ACTIONS } from '../../components/ActionDialog';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Spinner } from '../../components/Spinner';
import { StatusBadge } from '../../components/StatusBadge';
import { useChart, useCostCenters } from '../../hooks/useAccounting';
import { formatDateTime } from '../../utils/format';
import { isValidAmount } from '../../utils/money';

type Row = { accountId: string; costCenterId: string | null; amount: string };

export function BudgetDetailPage() {
  const { id = '' } = useParams();
  const qc = useQueryClient();
  const { hasPermission } = useAuth();
  const query = useQuery({ queryKey: ['budget', id], queryFn: () => budgetApi.get(id) });
  const adjustments = useQuery({ queryKey: ['budget-adjustments', id], queryFn: () => budgetApi.adjustments(id) });
  const [dialog, setDialog] = useState<'submit' | 'approve' | 'return' | null>(null);
  const refresh = (b: BudgetView) => { qc.setQueryData(['budget', id], b); qc.invalidateQueries({ queryKey: ['budgets'] }); };
  const act = useMutation({ mutationFn: ({ kind, comment }: { kind: 'submit' | 'approve' | 'return'; comment: string }) =>
    budgetApi.act(id, kind, query.data!.version, comment), onSuccess: (b) => { refresh(b); setDialog(null); } });
  if (query.isPending) return <Spinner />;
  if (query.error) return <ErrorAlert error={query.error} />;
  const b = query.data;
  const can = (a: string) => b.availableActions.includes(a);
  return (
    <>
      <PageHeader title={b.name} crumbs={[{ label: 'Budget' }, { label: 'Budgets', to: '/budgets' }, { label: String(b.fiscalYear) }]}
        actions={<div className="flex gap-2">
          {can('SUBMIT') && <Button onClick={() => { act.reset(); setDialog('submit'); }}>Submit</Button>}
          {can('APPROVE') && <Button onClick={() => { act.reset(); setDialog('approve'); }}>Approve</Button>}
          {can('RETURN') && <Button variant="secondary" onClick={() => { act.reset(); setDialog('return'); }}>Return</Button>}
        </div>} />
      <div className="mb-4 flex flex-wrap items-center gap-3 text-sm"><StatusBadge status={b.status} />
        <span>Prepared by {b.preparedBy}</span>{b.approvedBy && <span>· approved by {b.approvedBy} {formatDateTime(b.approvedAt)}</span>}</div>
      {can('EDIT') ? <AllocationEditor budget={b} onSaved={refresh} /> : <Utilisation budget={b} />}
      {b.status === 'APPROVED' && <Adjustments budget={b} canRequest={can('REQUEST_ADJUSTMENT')} canDecide={hasPermission('BUDGET_APPROVE')}
        list={adjustments.data ?? []} onChange={() => { qc.invalidateQueries({ queryKey: ['budget', id] }); qc.invalidateQueries({ queryKey: ['budget-adjustments', id] }); }} />}
      <ActionDialog spec={dialog ? COMMON_ACTIONS[dialog]! : null} busy={act.isPending} error={act.error} onClose={() => setDialog(null)}
        onConfirm={(comment) => act.mutate({ kind: dialog!, comment })} />
    </>
  );
}

function Utilisation({ budget }: { budget: BudgetView }) {
  return (
    <div className="mb-4 overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs">
      <table className="min-w-full text-sm">
        <thead className="border-b border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="px-3 py-2">Account</th><th className="px-3 py-2">Cost centre</th>
          <th className="px-3 py-2 text-right">Allotment</th><th className="px-3 py-2 text-right">Reserved</th><th className="px-3 py-2 text-right">Actual</th>
          <th className="px-3 py-2 text-right">Available</th><th className="px-3 py-2 text-right">Used</th></tr></thead>
        <tbody>{budget.allocations.map((a) => (
          <tr key={a.id} className="border-t border-slate-100"><td className="px-3 py-2"><span className="font-mono">{a.accountCode}</span> {a.accountName}</td>
            <td className="px-3 py-2">{a.costCenterCode ?? 'All'}</td><td className="px-3 py-2 text-right"><Money value={a.allotment} /></td>
            <td className="px-3 py-2 text-right"><Money value={a.reserved} blankZero /></td><td className="px-3 py-2 text-right"><Money value={a.actual} blankZero /></td>
            <td className={`px-3 py-2 text-right ${a.available.startsWith('-') ? 'text-red-700' : ''}`}><Money value={a.available} /></td>
            <td className="px-3 py-2 text-right">
              <span className="inline-block w-24 rounded bg-slate-100 align-middle" aria-hidden><span className="block h-2 rounded bg-brand-600"
                style={{ width: `${Math.min(100, Number(a.utilizationPercent))}%` }} /></span> {a.utilizationPercent}%</td></tr>))}</tbody>
        <tfoot className="border-t-2 border-slate-300 font-medium"><tr><td colSpan={2} className="px-3 py-2">Total</td>
          <td className="px-3 py-2 text-right"><Money value={budget.totalAllotment} /></td><td className="px-3 py-2 text-right"><Money value={budget.totalReserved} /></td>
          <td className="px-3 py-2 text-right"><Money value={budget.totalActual} /></td><td className="px-3 py-2 text-right"><Money value={budget.totalAvailable} /></td><td /></tr></tfoot>
      </table>
    </div>
  );
}

function AllocationEditor({ budget, onSaved }: { budget: BudgetView; onSaved: (b: BudgetView) => void }) {
  const chart = useChart();
  const costCenters = useCostCenters();
  const accounts = (chart.data?.accounts ?? []).filter((a) => a.postable && a.budgetControlled && a.status === 'ACTIVE');
  const [rows, setRows] = useState<Row[]>(budget.allocations.length
    ? budget.allocations.map((a) => ({ accountId: a.accountId, costCenterId: a.costCenterId, amount: a.allotment }))
    : [{ accountId: '', costCenterId: null, amount: '' }]);
  const save = useMutation({ mutationFn: () => budgetApi.setAllocations(budget.id, budget.version, rows), onSuccess: onSaved });
  const set = (i: number, p: Partial<Row>) => setRows(rows.map((r, j) => (j === i ? { ...r, ...p } : r)));
  return (
    <section className="mb-4 space-y-3 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
      <h2 className="font-semibold">Allocations</h2>
      <p className="text-xs text-slate-500">Only budget-controlled accounts can be allotted. Allot per cost centre or for the whole account (“All”), not both.</p>
      <ErrorAlert error={save.error} />
      {rows.map((r, i) => (
        <div key={i} className="flex flex-wrap items-center gap-2 text-sm">
          <Combobox aria-label={`Allocation ${i + 1} account`} value={r.accountId} onChange={(v) => set(i, { accountId: v })}
            className="w-72 rounded-md border border-slate-300 px-2 py-1" placeholder="Account…"
              options={accounts.map((a) => ({ value: a.id, label: `${a.code} — ${a.name}` }))} />
          <Combobox aria-label={`Allocation ${i + 1} cost centre`} value={r.costCenterId ?? ''} onChange={(v) => set(i, { costCenterId: v || null })}
            className="w-40 rounded-md border border-slate-300 px-2 py-1" placeholder="All cost centres"
              options={(costCenters.data ?? []).map((c) => ({ value: c.id, label: `${c.code} — ${c.name}` }))} />
          <input aria-label={`Allocation ${i + 1} amount`} inputMode="decimal" value={r.amount} placeholder="0.00"
            onChange={(e) => set(i, { amount: e.target.value.replace(/[^\d.]/g, '') })}
            className={`w-36 rounded border px-2 py-1 text-right ${isValidAmount(r.amount) ? 'border-slate-300' : 'border-red-500'}`} />
          <button type="button" className="text-red-700" onClick={() => setRows(rows.filter((_, j) => j !== i))}>Remove</button>
        </div>
      ))}
      <div className="flex gap-3">
        <button type="button" className="text-sm text-brand-700" onClick={() => setRows([...rows, { accountId: '', costCenterId: null, amount: '' }])}>+ Add allocation</button>
        <Button busy={save.isPending} disabled={rows.some((r) => !r.accountId || !r.amount || !isValidAmount(r.amount))} onClick={() => save.mutate()}>Save allocations</Button>
      </div>
      <Utilisation budget={budget} />
    </section>
  );
}

function Adjustments({ budget, canRequest, canDecide, list, onChange }: { budget: BudgetView; canRequest: boolean; canDecide: boolean;
  list: { id: string; fromLabel: string; toLabel: string; amount: string; reason: string; status: string; requestedBy: string }[];
  onChange: () => void }) {
  const [form, setForm] = useState({ fromAllocationId: '', toAllocationId: '', amount: '', reason: '' });
  const request = useMutation({ mutationFn: () => budgetApi.requestAdjustment(budget.id, { ...form, fromAllocationId: form.fromAllocationId || null }),
    onSuccess: () => { setForm({ fromAllocationId: '', toAllocationId: '', amount: '', reason: '' }); onChange(); } });
  const decide = useMutation({ mutationFn: ({ id, approve }: { id: string; approve: boolean }) => budgetApi.decideAdjustment(id, approve), onSuccess: onChange });
  const label = (a: BudgetView['allocations'][number]) => `${a.accountCode}${a.costCenterCode ? ' / ' + a.costCenterCode : ''}`;
  return (
    <section className="space-y-3 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
      <h2 className="font-semibold">Adjustments</h2>
      <ErrorAlert error={request.error ?? decide.error} />
      {canRequest && (
        <div className="flex flex-wrap items-end gap-2 text-sm">
          <select aria-label="From allocation" value={form.fromAllocationId} onChange={(e) => setForm({ ...form, fromAllocationId: e.target.value })}
            className="rounded-md border border-slate-300 px-2 py-1"><option value="">Supplemental (new money)</option>
            {budget.allocations.map((a) => <option key={a.id} value={a.id}>From {label(a)}</option>)}</select>
          <select aria-label="To allocation" value={form.toAllocationId} onChange={(e) => setForm({ ...form, toAllocationId: e.target.value })}
            className="rounded-md border border-slate-300 px-2 py-1"><option value="">To…</option>
            {budget.allocations.map((a) => <option key={a.id} value={a.id}>To {label(a)}</option>)}</select>
          <input aria-label="Adjustment amount" placeholder="Amount" value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value.replace(/[^\d.]/g, '') })}
            className="w-32 rounded-md border border-slate-300 px-2 py-1 text-right" />
          <input aria-label="Adjustment reason" placeholder="Reason (min. 10 characters)" value={form.reason} onChange={(e) => setForm({ ...form, reason: e.target.value })}
            className="w-72 rounded-md border border-slate-300 px-2 py-1" />
          <Button busy={request.isPending} disabled={!form.toAllocationId || !isValidAmount(form.amount) || !form.amount || form.reason.trim().length < 10}
            onClick={() => request.mutate()}>Request</Button>
        </div>
      )}
      <ul className="divide-y divide-slate-100 text-sm">
        {list.map((a) => (
          <li key={a.id} className="flex flex-wrap items-center justify-between gap-2 py-2">
            <span>{a.fromLabel} → {a.toLabel}: <Money value={a.amount} /> — “{a.reason}” ({a.requestedBy}) <StatusBadge status={a.status} /></span>
            {a.status === 'PENDING' && canDecide && <span className="flex gap-2">
              <Button onClick={() => decide.mutate({ id: a.id, approve: true })}>Approve</Button>
              <Button variant="secondary" onClick={() => decide.mutate({ id: a.id, approve: false })}>Reject</Button></span>}
          </li>
        ))}
        {list.length === 0 && <li className="py-2 text-slate-500">No adjustments.</li>}
      </ul>
    </section>
  );
}
