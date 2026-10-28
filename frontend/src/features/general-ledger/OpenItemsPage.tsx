import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Combobox } from '../../components/Combobox';
import { useState } from 'react';
import { clearingApi } from '../../api/endpoints';
import type { Clearing, ClearingProposal } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { ActionDialog } from '../../components/ActionDialog';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Field } from '../../components/Field';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Pagination } from '../../components/Pagination';
import { Spinner } from '../../components/Spinner';
import { StatusBadge } from '../../components/StatusBadge';
import { postableAccounts, useChart } from '../../hooks/useAccounting';
import { formatDateTime } from '../../utils/format';

/** Open-item clearing (FR-GL-17): items on clearing, suspense and advance accounts stay open until matched. */
export function OpenItemsPage() {
  const { hasPermission } = useAuth();
  const qc = useQueryClient();
  const accounts = useQuery({ queryKey: ['open-item-accounts'], queryFn: clearingApi.accounts });
  const [accountId, setAccountId] = useState('');
  const selected = accounts.data?.find((a) => a.id === accountId) ?? null;
  const admin = hasPermission('ACCOUNT_ADMINISTER');
  const chart = useChart(admin);
  const [toManage, setToManage] = useState('');
  const refresh = () => {
    void qc.invalidateQueries({ queryKey: ['open-item-accounts'] });
    void qc.invalidateQueries({ queryKey: ['open-items'] });
    void qc.invalidateQueries({ queryKey: ['clearings'] });
  };
  const manage = useMutation({ mutationFn: ({ id, managed }: { id: string; managed: boolean }) => clearingApi.setManaged(id, managed),
    onSuccess: (_d, v) => { setToManage(''); if (!v.managed && v.id === accountId) setAccountId(''); refresh(); } });
  const managedIds = new Set((accounts.data ?? []).map((a) => a.id));
  const candidates = postableAccounts(chart.data?.accounts).filter((a) => ['ASSET', 'LIABILITY'].includes(a.accountType) && !managedIds.has(a.id));

  return (
    <>
      <PageHeader title="Open items" crumbs={[{ label: 'General ledger' }, { label: 'Open items' }]} />
      <p className="mb-4 max-w-3xl text-sm text-slate-600">On an open-item managed account every posted line stays open until it is
        cleared with other lines of the same subsidiary account that net to zero. Clearing posts nothing and can be reversed.</p>
      <ErrorAlert error={accounts.error ?? manage.error} />
      <section className="mb-4 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5" aria-label="Managed accounts">
        <h2 className="mb-2 font-semibold">Managed accounts</h2>
        {accounts.isPending ? <Spinner /> : (
          <table className="w-full text-sm">
            <thead><tr className="text-left text-xs uppercase text-slate-500"><th className="py-1">Account</th>
              <th className="py-1 text-right">Open items</th><th className="py-1 text-right">Open balance</th><th /></tr></thead>
            <tbody>
              {(accounts.data ?? []).map((a) => (
                <tr key={a.id} className={`border-t border-slate-100 ${a.id === accountId ? 'bg-brand-50' : ''}`}>
                  <td className="py-2"><button className="text-brand-700 hover:underline" onClick={() => setAccountId(a.id)}>{a.code} {a.name}</button></td>
                  <td className="py-2 text-right">{a.openItems}</td>
                  <td className="py-2 text-right"><Money value={a.openNet} /></td>
                  <td className="py-2 text-right">{admin && <button className="text-xs text-red-700 hover:underline" disabled={manage.isPending}
                    onClick={() => manage.mutate({ id: a.id, managed: false })}>Stop managing</button>}</td>
                </tr>
              ))}
              {accounts.data?.length === 0 && <tr><td colSpan={4} className="py-2 text-slate-500">No account is managed by open item yet.</td></tr>}
            </tbody>
          </table>
        )}
        {admin && (
          <div className="mt-3 flex flex-wrap items-end gap-2 border-t border-slate-100 pt-3">
            <label className="text-sm font-medium text-slate-700">Account to manage by open item
              <Combobox value={toManage} onChange={(v) => setToManage(v)}
                className="block w-80 rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm" placeholder="Choose…"
              options={candidates.map((a) => ({ value: a.id, label: `${a.code} — ${a.name}` }))} /></label>
            <Button variant="secondary" disabled={!toManage} busy={manage.isPending}
              onClick={() => manage.mutate({ id: toManage, managed: true })}>Manage by open item</Button>
          </div>
        )}
      </section>
      {selected && <AccountItems key={selected.id} accountId={selected.id} title={`${selected.code} ${selected.name}`} onChange={refresh} />}
    </>
  );
}

function AccountItems({ accountId, title, onChange }: { accountId: string; title: string; onChange: () => void }) {
  const { hasPermission } = useAuth();
  const canClear = hasPermission('GL_CLEARING');
  const [page, setPage] = useState(0);
  const items = useQuery({ queryKey: ['open-items', accountId, page], queryFn: () => clearingApi.items(accountId, page) });
  const history = useQuery({ queryKey: ['clearings', accountId], queryFn: () => clearingApi.history(accountId) });
  const [picked, setPicked] = useState<string[]>([]);
  const [note, setNote] = useState('');
  const [rule, setRule] = useState<'REFERENCE' | 'AMOUNT'>('REFERENCE');
  const [proposals, setProposals] = useState<ClearingProposal[] | null>(null);
  const [reversing, setReversing] = useState<Clearing | null>(null);
  const done = () => { setPicked([]); setNote(''); setProposals(null); setReversing(null); onChange(); };
  const clear = useMutation({ mutationFn: () => clearingApi.clear(accountId, picked, note.trim()), onSuccess: done });
  const preview = useMutation({ mutationFn: () => clearingApi.autoClear(accountId, rule, false), onSuccess: setProposals });
  const apply = useMutation({ mutationFn: () => clearingApi.autoClear(accountId, rule, true), onSuccess: done });
  const reverse = useMutation({ mutationFn: (reason: string) => clearingApi.reverse(reversing!.id, reason), onSuccess: done });
  const rows = items.data?.items ?? [];
  const net = rows.filter((r) => picked.includes(r.lineId)).reduce((s, r) => s + Number(r.debit) - Number(r.credit), 0);
  const toggle = (id: string) => setPicked(picked.includes(id) ? picked.filter((p) => p !== id) : [...picked, id]);

  return (
    <>
      <section className="mb-4 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5" aria-label="Open items of the account">
        <h2 className="mb-2 font-semibold">Open items — {title}</h2>
        <ErrorAlert error={items.error ?? clear.error ?? preview.error ?? apply.error} />
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead><tr className="text-left text-xs uppercase text-slate-500">
              {canClear && <th className="w-8 py-1" />}<th className="py-1">Date</th><th className="py-1">Entry</th><th className="py-1">Description</th>
              <th className="py-1">Subsidiary</th><th className="py-1 text-right">Debit</th><th className="py-1 text-right">Credit</th>
              <th className="py-1 text-right">Age (days)</th></tr></thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.lineId} className="border-t border-slate-100">
                  {canClear && <td className="py-1"><input type="checkbox" aria-label={`Select ${r.entryNumber} ${r.description ?? ''}`}
                    checked={picked.includes(r.lineId)} onChange={() => toggle(r.lineId)} /></td>}
                  <td className="py-1">{r.accountingDate}</td><td className="py-1">{r.entryNumber}</td><td className="py-1">{r.description ?? '—'}</td>
                  <td className="py-1">{r.subsidiaryCode ?? '—'}</td>
                  <td className="py-1 text-right"><Money value={r.debit} blankZero /></td>
                  <td className="py-1 text-right"><Money value={r.credit} blankZero /></td>
                  <td className={`py-1 text-right ${r.ageDays > 90 ? 'font-semibold text-red-700' : ''}`}>{r.ageDays}</td>
                </tr>
              ))}
              {items.data && rows.length === 0 && <tr><td colSpan={8} className="py-2 text-slate-500">No open items.</td></tr>}
            </tbody>
          </table>
        </div>
        {items.data && <Pagination page={items.data.page} totalPages={items.data.totalPages} totalItems={items.data.totalItems} onPage={setPage} />}
        {canClear && (
          <div className="mt-3 grid gap-3 border-t border-slate-100 pt-3 md:grid-cols-2">
            <div>
              <p className="text-sm">{picked.length} selected · net <strong>{net.toFixed(2)}</strong></p>
              <Field label="Clearing note" value={note} maxLength={500} onChange={(e) => setNote(e.target.value)} />
              <Button className="mt-2" disabled={picked.length < 2 || Math.abs(net) > 0.004 || note.trim().length === 0} busy={clear.isPending}
                onClick={() => clear.mutate()}>Clear selected</Button>
            </div>
            <div>
              <label className="text-sm font-medium text-slate-700">Automatic clearing rule
                <select value={rule} onChange={(e) => { setRule(e.target.value as 'REFERENCE' | 'AMOUNT'); setProposals(null); }}
                  className="block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm">
                  <option value="REFERENCE">By reference (same description, netting to zero)</option>
                  <option value="AMOUNT">By amount (equal and opposite pair, oldest first)</option>
                </select></label>
              <div className="mt-2 flex gap-2">
                <Button variant="secondary" busy={preview.isPending} onClick={() => preview.mutate()}>Preview</Button>
                {proposals && proposals.length > 0 && <Button busy={apply.isPending} onClick={() => apply.mutate()}>
                  Apply {proposals.length} clearing{proposals.length === 1 ? '' : 's'}</Button>}
              </div>
              {proposals && (
                <ul className="mt-2 text-sm" aria-label="Proposed clearings">
                  {proposals.map((p) => <li key={p.lineIds.join()}>{p.key} — {p.lineIds.length} items, <Money value={p.total} /></li>)}
                  {proposals.length === 0 && <li className="text-slate-500">Nothing to clear by this rule.</li>}
                </ul>
              )}
            </div>
          </div>
        )}
      </section>
      <section className="mb-4 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5" aria-label="Clearing history">
        <h2 className="mb-2 font-semibold">Clearing history</h2>
        <ErrorAlert error={history.error} />
        <ul className="space-y-2 text-sm">
          {(history.data ?? []).map((c) => (
            <li key={c.id} className="rounded-md border border-slate-200 p-2">
              <div className="flex flex-wrap items-center gap-2"><StatusBadge status={c.status} /><span>{c.rule.toLowerCase()}</span>
                <span>{c.lineCount} items, <Money value={c.total} /></span><span className="text-slate-500">{c.entryNumbers.join(', ')}</span></div>
              <p className="text-xs text-slate-500">{c.note ?? ''} · by {c.clearedBy} {formatDateTime(c.clearedAt)}
                {c.reversedBy ? ` · reversed by ${c.reversedBy}: ${c.reversalReason}` : ''}</p>
              {canClear && c.status === 'ACTIVE' && <button className="text-xs text-red-700 hover:underline" onClick={() => setReversing(c)}>Reverse</button>}
            </li>
          ))}
          {history.data?.length === 0 && <li className="text-slate-500">No clearings yet.</li>}
        </ul>
      </section>
      <ActionDialog spec={reversing && { title: 'Reverse clearing', text: 'The items become open again.', confirm: 'Reverse', danger: true,
        commentRequired: true, commentLabel: 'Reason', minComment: 5 }} busy={reverse.isPending} error={reverse.error}
        onConfirm={(reason) => reverse.mutate(reason)} onClose={() => setReversing(null)} />
    </>
  );
}
