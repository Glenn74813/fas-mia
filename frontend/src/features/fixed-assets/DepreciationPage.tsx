import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Select } from '../../components/Select';
import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { assetApi } from '../../api/endpoints';
import { useAuth } from '../../auth/AuthContext';
import { ActionDialog, type ActionSpec } from '../../components/ActionDialog';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Pagination } from '../../components/Pagination';
import { Spinner } from '../../components/Spinner';
import { StatusBadge } from '../../components/StatusBadge';
import { allPeriods, useFiscalYears } from '../../hooks/useAccounting';

export function DepreciationRunsPage() {
  const { hasPermission } = useAuth();
  const navigate = useNavigate();
  const [page, setPage] = useState(0);
  const settings = useQuery({ queryKey: ['depreciation-settings'], queryFn: assetApi.depreciationSettings });
  const runs = useQuery({ queryKey: ['depreciation-runs', page], queryFn: () => assetApi.runs(page), placeholderData: keepPreviousData });
  const years = useFiscalYears();
  const periods = allPeriods(years.data).filter((p) => p.periodType === 'REGULAR' && p.status === 'OPEN');
  const [periodId, setPeriod] = useState('');
  const prepare = useMutation({ mutationFn: () => assetApi.prepareRun(periodId), onSuccess: (r) => navigate(`/fixed-assets/depreciation/${r.id}`) });
  return (
    <>
      <PageHeader title="Depreciation runs" crumbs={[{ label: 'Fixed assets' }, { label: 'Depreciation' }]} />
      {hasPermission('FA_MANAGE') && (
        <section className="mb-4 flex flex-wrap items-end gap-3 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
          <label className="text-sm">Period
            <Select value={periodId} onChange={(e) => setPeriod(e.target.value)} className="ml-2 rounded-md border border-slate-300 px-2 py-1">
              <option value="">Select an open period…</option>{periods.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}</Select></label>
          <Button busy={prepare.isPending} disabled={!periodId} onClick={() => prepare.mutate()}>Compute depreciation</Button>
          <p className="w-full text-xs text-slate-500">Straight-line, monthly amounts. Months not yet charged are caught up; nothing is charged twice.
            {settings.data?.frequency === 'ANNUAL' ? ' Runs are annual: only the fiscal year\'s last regular period can be run.' : ''} Start convention,
            useful lives and run frequency are AVTECH policy (C-30).</p>
          <div className="w-full"><ErrorAlert error={prepare.error} /></div>
        </section>
      )}
      <ErrorAlert error={runs.error} />
      {runs.isPending ? <Spinner /> : (
        <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs"><table className="min-w-full text-sm">
          <thead className="border-b border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="px-3 py-2">Period</th><th className="px-3 py-2">Status</th>
            <th className="px-3 py-2">Assets</th><th className="px-3 py-2">Prepared by</th><th className="px-3 py-2 text-right">Total</th></tr></thead>
          <tbody>{(runs.data?.items ?? []).map((r) => (
            <tr key={r.id} className="border-t border-slate-100"><td className="px-3 py-2"><Link className="text-brand-700 hover:underline" to={`/fixed-assets/depreciation/${r.id}`}>{r.periodName}</Link></td>
              <td className="px-3 py-2"><StatusBadge status={r.status} /></td><td className="px-3 py-2">{r.assetCount}</td><td className="px-3 py-2">{r.preparedBy}</td>
              <td className="px-3 py-2 text-right"><Money value={r.totalAmount} /></td></tr>))}
            {runs.data?.items.length === 0 && <tr><td colSpan={5} className="px-4 py-6 text-center text-slate-500">No runs yet.</td></tr>}</tbody>
        </table></div>
      )}
      {runs.data && <Pagination page={runs.data.page} totalPages={runs.data.totalPages} totalItems={runs.data.totalItems} onPage={setPage} />}
    </>
  );
}

const SPECS: Record<'post' | 'cancel', ActionSpec> = {
  post: { title: 'Post depreciation?', text: 'Records Dr depreciation expense / Cr accumulated depreciation for every asset listed.', confirm: 'Post' },
  cancel: { title: 'Cancel this run?', text: 'Nothing is posted; a new run can be computed.', confirm: 'Cancel run', danger: true },
};

export function DepreciationRunPage() {
  const { id = '' } = useParams();
  const qc = useQueryClient();
  const query = useQuery({ queryKey: ['depreciation-run', id], queryFn: () => assetApi.run(id) });
  const [dialog, setDialog] = useState<'post' | 'cancel' | null>(null);
  const act = useMutation({ mutationFn: (k: 'post' | 'cancel') => assetApi.runAction(id, k, query.data!.version),
    onSuccess: (r) => { qc.setQueryData(['depreciation-run', id], r); qc.invalidateQueries({ queryKey: ['depreciation-runs'] }); setDialog(null); } });
  if (query.isPending) return <Spinner />;
  if (query.error) return <ErrorAlert error={query.error} />;
  const r = query.data;
  return (
    <>
      <PageHeader title={`Depreciation ${r.periodName}`} crumbs={[{ label: 'Fixed assets' }, { label: 'Depreciation', to: '/fixed-assets/depreciation' }, { label: r.periodName }]}
        actions={<div className="flex gap-2">
          {r.availableActions.includes('POST') && <Button onClick={() => { act.reset(); setDialog('post'); }}>Post</Button>}
          {r.availableActions.includes('CANCEL') && <Button variant="secondary" onClick={() => { act.reset(); setDialog('cancel'); }}>Cancel</Button>}
        </div>} />
      <section className="mb-4 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5 text-sm">
        <StatusBadge status={r.status} /> <span className="ml-2">{r.assetCount} assets · total <Money value={r.totalAmount} /> · prepared by {r.preparedBy}</span>
        {r.postedEntryId && <span className="ml-2">· entry <Link className="text-brand-700 hover:underline" to={`/general-ledger/entries/${r.postedEntryId}`}>{r.postedEntryNumber}</Link></span>}
      </section>
      <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs"><table className="min-w-full text-sm">
        <thead className="border-b border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="px-3 py-2">Asset</th><th className="px-3 py-2">Description</th>
          <th className="px-3 py-2">Category</th><th className="px-3 py-2 text-right">This run</th><th className="px-3 py-2 text-right">Accumulated after</th>
          <th className="px-3 py-2 text-right">NBV after</th></tr></thead>
        <tbody>{r.lines.map((l) => (
          <tr key={l.assetId} className="border-t border-slate-100"><td className="px-3 py-2"><Link className="text-brand-700 hover:underline" to={`/fixed-assets/${l.assetId}`}>{l.assetNumber}</Link></td>
            <td className="px-3 py-2">{l.description}</td><td className="px-3 py-2">{l.categoryCode}</td><td className="px-3 py-2 text-right"><Money value={l.amount} /></td>
            <td className="px-3 py-2 text-right"><Money value={l.accumulatedAfter} /></td><td className="px-3 py-2 text-right"><Money value={l.netBookValueAfter} /></td></tr>))}</tbody>
      </table></div>
      <ActionDialog spec={dialog ? SPECS[dialog] : null} busy={act.isPending} error={act.error} onClose={() => setDialog(null)} onConfirm={() => act.mutate(dialog!)} />
    </>
  );
}
