import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { apReportApi } from '../../api/endpoints';
import { useAuth } from '../../auth/AuthContext';
import { Badge } from '../../components/Badge';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Spinner } from '../../components/Spinner';

export function PayablesReportsPage() {
  const { hasPermission } = useAuth();
  const today = new Date();
  const [asOf, setAsOf] = useState(today.toISOString().slice(0, 10));
  const [year, setYear] = useState(today.getFullYear());
  const [quarter, setQuarter] = useState(Math.floor(today.getMonth() / 3) + 1);
  const aging = useQuery({ queryKey: ['ap-aging', asOf], queryFn: () => apReportApi.aging(asOf) });
  const recon = useQuery({ queryKey: ['ap-recon'], queryFn: apReportApi.reconciliation, enabled: hasPermission('LEDGER_VIEW') });
  const wht = useQuery({ queryKey: ['ap-wht', year, quarter], queryFn: () => apReportApi.withholding(year, quarter) });
  const advances = useQuery({ queryKey: ['ap-advances'], queryFn: apReportApi.advances });
  const cols = ['current', 'days1to30', 'days31to60', 'days61to90', 'over90', 'total'] as const;
  return (
    <>
      <PageHeader title="Payables reports" crumbs={[{ label: 'Payables' }, { label: 'Reports' }]} />
      {recon.data && (
        <section className="mb-4 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5 text-sm">
          <h2 className="mb-2 font-semibold">AP sub-ledger to general ledger {recon.data.reconciled ? <Badge tone="green">RECONCILED</Badge> : <Badge tone="red">DIFFERENCE</Badge>}</h2>
          <p>Open invoices <Money value={recon.data.subledgerOpenBalance} /> · Control account {recon.data.controlAccountCode} <Money value={recon.data.generalLedgerBalance} /> ·
            Difference <Money value={recon.data.difference} /></p>
        </section>
      )}
      <ErrorAlert error={recon.error} />
      <section className="mb-4 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
        <div className="mb-3 flex items-center justify-between"><h2 className="font-semibold">Aging of payables</h2>
          <label className="text-sm">As of <input type="date" value={asOf} onChange={(e) => setAsOf(e.target.value)} className="ml-2 rounded-md border border-slate-300 px-2 py-1" /></label></div>
        <ErrorAlert error={aging.error} />
        {aging.isPending ? <Spinner /> : aging.data && (
          <div className="overflow-x-auto"><table className="min-w-full text-sm">
            <thead className="border-b border-slate-200 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="py-1 pr-3">Supplier</th><th className="py-1 text-right">Current</th>
              <th className="py-1 text-right">1–30</th><th className="py-1 text-right">31–60</th><th className="py-1 text-right">61–90</th><th className="py-1 text-right">&gt; 90</th><th className="py-1 text-right">Total</th></tr></thead>
            <tbody>{aging.data.rows.map((r) => (
              <tr key={r.supplierId} className="border-t border-slate-100"><td className="py-1.5 pr-3">{r.supplierName}</td>
                {cols.map((c) => <td key={c} className="py-1.5 text-right"><Money value={r[c]} blankZero /></td>)}</tr>))}</tbody>
            <tfoot className="border-t-2 border-slate-300 font-medium"><tr><td className="py-1.5">Total</td>
              {cols.map((c) => <td key={c} className="py-1.5 text-right"><Money value={aging.data.totals[c]} /></td>)}</tr></tfoot>
          </table></div>
        )}
      </section>
      <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
        <div className="mb-3 flex flex-wrap items-center justify-between gap-2"><h2 className="font-semibold">Expanded withholding schedule</h2>
          <span className="flex gap-2 text-sm">
            <input aria-label="Year" value={year} onChange={(e) => setYear(Number(e.target.value.replace(/\D/g, '')) || year)} className="w-20 rounded-md border border-slate-300 px-2 py-1" />
            <select aria-label="Quarter" value={quarter} onChange={(e) => setQuarter(Number(e.target.value))} className="rounded-md border border-slate-300 px-2 py-1">
              {[1, 2, 3, 4].map((q) => <option key={q} value={q}>Q{q}</option>)}</select></span></div>
        {wht.data && <p className="mb-2 text-xs text-amber-800">{wht.data.note}</p>}
        <ErrorAlert error={wht.error} />
        {wht.data && <div className="overflow-x-auto"><table className="min-w-full text-sm">
          <thead className="border-b border-slate-200 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="py-1 pr-3">TIN</th><th className="py-1 pr-3">Payee</th><th className="py-1 pr-3">Tax code</th>
            <th className="py-1 text-right">Month 1</th><th className="py-1 text-right">Month 2</th><th className="py-1 text-right">Month 3</th><th className="py-1 text-right">Base</th>
            <th className="py-1 text-right">Withheld</th></tr></thead>
          <tbody>{wht.data.rows.map((r, i) => (
            <tr key={i} className="border-t border-slate-100"><td className="py-1.5 pr-3">{r.supplierTin ?? '—'}</td><td className="py-1.5 pr-3">{r.supplierName}</td>
              <td className="py-1.5 pr-3">{r.taxCode}</td><td className="py-1.5 text-right"><Money value={r.month1Base} blankZero /></td>
              <td className="py-1.5 text-right"><Money value={r.month2Base} blankZero /></td><td className="py-1.5 text-right"><Money value={r.month3Base} blankZero /></td>
              <td className="py-1.5 text-right"><Money value={r.totalBase} /></td><td className="py-1.5 text-right"><Money value={r.taxWithheld} /></td></tr>))}
            {wht.data.rows.length === 0 && <tr><td colSpan={8} className="py-4 text-center text-slate-500">No withholding in this quarter.</td></tr>}</tbody>
        </table></div>}
      </section>
      {advances.data && advances.data.rows.length > 0 && (
        <section className="mt-4 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
          <h2 className="mb-2 font-semibold">Supplier advances {advances.data.reconciliation && (advances.data.reconciliation.reconciled
            ? <Badge tone="green">RECONCILED</Badge> : <Badge tone="red">DIFFERENCE</Badge>)}</h2>
          <p className="mb-2 text-xs text-amber-800">{advances.data.note}</p>
          {advances.data.reconciliation && <p className="mb-2 text-sm">Register <Money value={advances.data.totalOutstanding} /> · Account
            {' '}{advances.data.reconciliation.controlAccountCode} <Money value={advances.data.reconciliation.generalLedgerBalance} /> · Difference
            {' '}<Money value={advances.data.reconciliation.difference} /></p>}
          <div className="overflow-x-auto"><table className="min-w-full text-sm">
            <thead className="border-b border-slate-200 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="py-1 pr-3">Supplier</th><th className="py-1 pr-3">Reference</th>
              <th className="py-1 pr-3">Date</th><th className="py-1 pr-3">Description</th><th className="py-1 pr-3">Opening entry</th>
              <th className="py-1 text-right">Outstanding</th></tr></thead>
            <tbody>{advances.data.rows.map((r) => (
              <tr key={r.id} className="border-t border-slate-100"><td className="py-1.5 pr-3">{r.supplierCode} {r.supplierName}</td>
                <td className="py-1.5 pr-3">{r.reference}</td><td className="py-1.5 pr-3">{r.advanceDate}</td><td className="py-1.5 pr-3">{r.description}</td>
                <td className="py-1.5 pr-3">{r.openingEntryNumber}</td><td className="py-1.5 text-right"><Money value={r.outstandingAmount} /></td></tr>))}</tbody>
          </table></div>
        </section>
      )}
    </>
  );
}
