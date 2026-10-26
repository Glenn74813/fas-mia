import { useQuery } from '@tanstack/react-query';
import { Fragment, useState } from 'react';
import { treasuryApi } from '../../api/endpoints';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Spinner } from '../../components/Spinner';
import { StatusBadge } from '../../components/StatusBadge';

export function CollectionReportPage() {
  const [date, setDate] = useState(new Date().toISOString().slice(0, 10));
  const [collector, setCollector] = useState('');
  const dcr = useQuery({ queryKey: ['dcr', date, collector], queryFn: () => treasuryApi.dcr(date, collector || undefined), enabled: !!date });
  return (
    <>
      <PageHeader title="Daily collection report" crumbs={[{ label: 'Treasury' }, { label: 'Daily collections' }]}
        actions={<Button variant="secondary" onClick={() => window.print()}>Print</Button>} />
      <div className="mb-4 flex flex-wrap gap-3 text-sm print:hidden">
        <label>Date <input type="date" value={date} onChange={(e) => setDate(e.target.value)} className="ml-1 rounded-md border border-slate-300 px-2 py-1" /></label>
        <label>Collector <input value={collector} placeholder="all" onChange={(e) => setCollector(e.target.value)} className="ml-1 w-40 rounded-md border border-slate-300 px-2 py-1" /></label>
      </div>
      <ErrorAlert error={dcr.error} />
      {dcr.isPending ? <Spinner /> : dcr.data && (
        <div className="space-y-4">
          <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5 text-sm">
            <p>Receipts {dcr.data.firstNumber ?? '—'} to {dcr.data.lastNumber ?? '—'} · {dcr.data.lines.length} issued, {dcr.data.cancelled} cancelled</p>
            <dl className="mt-3 grid max-w-xl grid-cols-2 gap-1">
              {dcr.data.totals.map((t) => <Fragment key={t.paymentMethod}><dt>{t.paymentMethod.replace('_', ' ')} ({t.count})</dt>
                <dd className="text-right"><Money value={t.amount} /></dd></Fragment>)}
              <dt className="font-semibold">Total collected</dt><dd className="text-right font-semibold"><Money value={dcr.data.totalCollected} /></dd>
              <dt>Tax withheld (certificates)</dt><dd className="text-right"><Money value={dcr.data.totalTaxWithheld} /></dd>
              <dt>Not yet deposited</dt><dd className="text-right"><Money value={dcr.data.undeposited} /></dd>
            </dl>
            <p className="mt-3 text-xs text-amber-800">{dcr.data.note}</p>
          </section>
          <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs">
            <table className="min-w-full text-sm">
              <thead className="border-b border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="px-3 py-2">Receipt</th><th className="px-3 py-2">Customer</th>
                <th className="px-3 py-2">Method</th><th className="px-3 py-2">Collector</th><th className="px-3 py-2">Status</th><th className="px-3 py-2">Deposit</th>
                <th className="px-3 py-2 text-right">Amount</th><th className="px-3 py-2 text-right">Tax withheld</th></tr></thead>
              <tbody>{dcr.data.lines.map((l) => (
                <tr key={l.receiptNumber} className="border-t border-slate-100"><td className="px-3 py-2 font-mono">{l.receiptNumber}</td>
                  <td className="px-3 py-2">{l.customerName}</td><td className="px-3 py-2">{l.paymentMethod.replace('_', ' ')} {l.paymentReference ?? ''}</td>
                  <td className="px-3 py-2">{l.recordedBy}</td><td className="px-3 py-2"><StatusBadge status={l.status} />
                    {l.cancelReason && <span className="ml-1 text-xs text-slate-500">{l.cancelReason}</span>}</td>
                  <td className="px-3 py-2">{l.depositNumber ?? '—'}</td><td className="px-3 py-2 text-right"><Money value={l.amount} /></td>
                  <td className="px-3 py-2 text-right"><Money value={l.taxWithheld} blankZero /></td></tr>))}
                {dcr.data.lines.length === 0 && <tr><td colSpan={8} className="px-4 py-6 text-center text-slate-500">No receipts on this date.</td></tr>}</tbody>
            </table>
          </div>
        </div>
      )}
    </>
  );
}
