import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { assetApi } from '../../api/endpoints';
import { useAuth } from '../../auth/AuthContext';
import { Badge } from '../../components/Badge';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Spinner } from '../../components/Spinner';
import type { PpeRow } from '../../api/types';

const COLS: [keyof PpeRow, string][] = [['openingCost', 'Cost, opening'], ['additions', 'Additions'], ['revaluations', 'Revaluations'],
  ['disposalsCost', 'Disposals'], ['closingCost', 'Cost, closing'], ['openingAccumulated', 'Accum., opening'], ['depreciation', 'Depreciation'],
  ['eliminatedAccumulated', 'Eliminated on revaluation'], ['disposalsAccumulated', 'Disposals'], ['closingAccumulated', 'Accum., closing'],
  ['openingImpairment', 'Impairment, opening'], ['impairment', 'Impairment'], ['disposalsImpairment', 'Disposals'],
  ['closingImpairment', 'Impairment, closing'], ['netBookValue', 'Net book value']];
/** Shown only when some category has an amount in them: most periods have no impairment or revaluation. */
const OPTIONAL = new Set<keyof PpeRow>(['revaluations', 'eliminatedAccumulated', 'openingImpairment', 'impairment', 'disposalsImpairment',
  'closingImpairment']);

export function FaReportsPage() {
  const { hasPermission } = useAuth();
  const year = new Date().getFullYear();
  const [from, setFrom] = useState(`${year}-01-01`);
  const [to, setTo] = useState(`${year}-12-31`);
  const sched = useQuery({ queryKey: ['ppe-schedule', from, to], queryFn: () => assetApi.schedule(from, to), enabled: !!from && !!to });
  const recon = useQuery({ queryKey: ['fa-recon'], queryFn: assetApi.reconciliation, enabled: hasPermission('LEDGER_VIEW') });
  return (
    <>
      <PageHeader title="PPE reports" crumbs={[{ label: 'Fixed assets' }, { label: 'Reports' }]} />
      {recon.data && (
        <section className="mb-4 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5 text-sm">
          <h2 className="mb-2 font-semibold">Asset register to general ledger {recon.data.reconciled ? <Badge tone="green">RECONCILED</Badge> : <Badge tone="red">DIFFERENCE</Badge>}</h2>
          <table className="min-w-full"><thead className="border-b border-slate-200 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="py-1 pr-3">Account</th><th className="py-1 pr-3">Side</th>
            <th className="py-1 text-right">Register</th><th className="py-1 text-right">Ledger</th><th className="py-1 text-right">Difference</th></tr></thead>
            <tbody>{recon.data.rows.map((r) => (
              <tr key={r.accountCode + r.side} className="border-t border-slate-100"><td className="py-1.5 pr-3">{r.accountCode} {r.accountName}</td>
                <td className="py-1.5 pr-3">{r.side === 'COST' ? 'Cost' : 'Accumulated depreciation'}</td><td className="py-1.5 text-right"><Money value={r.register} /></td>
                <td className="py-1.5 text-right"><Money value={r.generalLedger} /></td><td className="py-1.5 text-right"><Money value={r.difference} /></td></tr>))}</tbody></table>
          <p className="mt-2 text-xs text-slate-500">A difference usually means a manual voucher to an asset account, or a purchased asset not yet registered.</p>
        </section>
      )}
      <ErrorAlert error={recon.error} />
      <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
        <div className="mb-3 flex flex-wrap items-center justify-between gap-2"><h2 className="font-semibold">PPE schedule</h2>
          <span className="flex gap-2 text-sm">
            <label>From <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} className="ml-1 rounded-md border border-slate-300 px-2 py-1" /></label>
            <label>To <input type="date" value={to} onChange={(e) => setTo(e.target.value)} className="ml-1 rounded-md border border-slate-300 px-2 py-1" /></label></span></div>
        <ErrorAlert error={sched.error} />
        {sched.isPending ? <Spinner /> : sched.data && (() => {
          const cols = COLS.filter(([k]) => !OPTIONAL.has(k) || sched.data.rows.some((r) => Number(r[k]) !== 0));
          return (
          <div className="overflow-x-auto"><table className="min-w-full text-sm">
            <thead className="border-b border-slate-200 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="py-1 pr-3">Category</th>
              {cols.map(([k, l]) => <th key={k} className="py-1 pl-2 text-right">{l}</th>)}</tr></thead>
            <tbody>{sched.data.rows.map((r) => (
              <tr key={r.categoryCode} className="border-t border-slate-100"><td className="py-1.5 pr-3">{r.categoryCode}</td>
                {cols.map(([k]) => <td key={k} className="py-1.5 pl-2 text-right"><Money value={r[k] as string} blankZero /></td>)}</tr>))}</tbody>
            <tfoot className="border-t-2 border-slate-300 font-medium"><tr><td className="py-1.5">Total</td>
              {cols.map(([k]) => <td key={k} className="py-1.5 pl-2 text-right"><Money value={sched.data.totals[k] as string} /></td>)}</tr></tfoot>
          </table></div>
          );
        })()}
        {sched.data && <p className="mt-2 text-xs text-slate-500">{sched.data.note}</p>}
      </section>
    </>
  );
}
