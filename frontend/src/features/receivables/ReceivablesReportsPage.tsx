import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { arReportApi } from '../../api/endpoints';
import type { ControlReconciliation } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { Badge } from '../../components/Badge';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Spinner } from '../../components/Spinner';

const LABEL: Record<string, string> = { AR_TRADE_RECEIVABLE: 'Open invoices vs AR control', UNAPPLIED_COLLECTIONS: 'Unapplied receipts vs unapplied control' };

function ReconLine({ r }: { r: ControlReconciliation }) {
  return (
    <p>{LABEL[r.key]} ({r.controlAccountCode}): sub-ledger <Money value={r.subledgerBalance} /> · ledger <Money value={r.generalLedgerBalance} /> ·
      difference <Money value={r.difference} /> {r.reconciled ? <Badge tone="green">OK</Badge> : <Badge tone="red">DIFFERENCE</Badge>}</p>
  );
}

export function ReceivablesReportsPage() {
  const { hasPermission } = useAuth();
  const [asOf, setAsOf] = useState(new Date().toISOString().slice(0, 10));
  const aging = useQuery({ queryKey: ['ar-aging', asOf], queryFn: () => arReportApi.aging(asOf) });
  const recon = useQuery({ queryKey: ['ar-recon'], queryFn: arReportApi.reconciliation, enabled: hasPermission('LEDGER_VIEW') });
  const cols = ['current', 'days1to30', 'days31to60', 'days61to90', 'over90', 'total'] as const;
  return (
    <>
      <PageHeader title="Receivables reports" crumbs={[{ label: 'Receivables' }, { label: 'Reports' }]} />
      {recon.data && (
        <section className="mb-4 space-y-1 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5 text-sm">
          <h2 className="mb-2 font-semibold">Customer sub-ledger to general ledger</h2>
          <ReconLine r={recon.data.receivables} /><ReconLine r={recon.data.unappliedCollections} />
          <p className="text-xs text-slate-500">A difference means something posted to a control account outside the receivables module (e.g. a manual journal voucher).</p>
        </section>
      )}
      <ErrorAlert error={recon.error} />
      <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
        <div className="mb-3 flex items-center justify-between"><h2 className="font-semibold">Aging of receivables</h2>
          <label className="text-sm">As of <input type="date" value={asOf} onChange={(e) => setAsOf(e.target.value)} className="ml-2 rounded-md border border-slate-300 px-2 py-1" /></label></div>
        <ErrorAlert error={aging.error} />
        {aging.isPending ? <Spinner /> : aging.data && (
          <div className="overflow-x-auto"><table className="min-w-full text-sm">
            <thead className="border-b border-slate-200 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="py-1 pr-3">Customer</th><th className="py-1 text-right">Current</th>
              <th className="py-1 text-right">1–30</th><th className="py-1 text-right">31–60</th><th className="py-1 text-right">61–90</th><th className="py-1 text-right">&gt; 90</th><th className="py-1 text-right">Total</th></tr></thead>
            <tbody>{aging.data.rows.map((r) => (
              <tr key={r.customerId} className="border-t border-slate-100"><td className="py-1.5 pr-3">{r.customerName}</td>
                {cols.map((c) => <td key={c} className="py-1.5 text-right"><Money value={r[c]} blankZero /></td>)}</tr>))}
              {aging.data.rows.length === 0 && <tr><td colSpan={7} className="py-4 text-center text-slate-500">No open receivables.</td></tr>}</tbody>
            <tfoot className="border-t-2 border-slate-300 font-medium"><tr><td className="py-1.5">Total</td>
              {cols.map((c) => <td key={c} className="py-1.5 text-right"><Money value={aging.data.totals[c]} /></td>)}</tr></tfoot>
          </table></div>
        )}
      </section>
    </>
  );
}
