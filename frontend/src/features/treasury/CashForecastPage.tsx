import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router';
import { treasuryApi } from '../../api/endpoints';
import type { CashForecastItem } from '../../api/types';
import { BalanceLine, StatTile } from '../../components/charts';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Spinner } from '../../components/Spinner';
import { formatPeso } from '../../utils/format';

const SOURCE = { RECEIVABLE: 'Collection due', VOUCHER: 'Approved DV', PAYABLE: 'Supplier invoice due' } as const;

function itemLink(i: CashForecastItem): string {
  if (i.source === 'RECEIVABLE') return `/receivables/billings/${i.id}`;
  if (i.source === 'VOUCHER') return `/payables/disbursements/${i.id}`;
  return `/payables/invoices/${i.id}`;
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
function shortDate(iso: string): string {
  return `${Number(iso.slice(8, 10))} ${MONTHS[Number(iso.slice(5, 7)) - 1]}`;
}

export function CashForecastPage() {
  const [granularity, setGranularity] = useState<'WEEK' | 'MONTH'>('WEEK');
  const [periods, setPeriods] = useState(13);
  const [includeBudget, setIncludeBudget] = useState(true);
  const [bucket, setBucket] = useState<number | null>(null);
  const f = useQuery({
    queryKey: ['cash-forecast', granularity, periods, includeBudget],
    queryFn: () => treasuryApi.cashForecast({ granularity, periods, includeBudget }),
    placeholderData: keepPreviousData,
  });
  const label = (start: string) => (granularity === 'WEEK' ? shortDate(start) : `${MONTHS[Number(start.slice(5, 7)) - 1]} ${start.slice(0, 4)}`);
  const items = f.data?.items.filter((i) => bucket === null || i.bucket === bucket) ?? [];
  const select = 'rounded-lg border border-slate-300 px-3 py-2 text-sm shadow-xs';

  return (
    <>
      <PageHeader title="Cash-flow forecast" crumbs={[{ label: 'Treasury' }, { label: 'Cash-flow forecast' }]} />
      <div className="mb-4 flex flex-wrap items-center gap-3 text-sm">
        <select aria-label="Group by" value={granularity} className={select} onChange={(e) => {
          const g = e.target.value as 'WEEK' | 'MONTH';
          setGranularity(g);
          setPeriods(g === 'WEEK' ? 13 : 12);
          setBucket(null);
        }}>
          <option value="WEEK">By week</option><option value="MONTH">By month</option>
        </select>
        <select aria-label="Horizon" value={periods} className={select} onChange={(e) => { setPeriods(Number(e.target.value)); setBucket(null); }}>
          {(granularity === 'WEEK' ? [4, 8, 13, 26, 52] : [3, 6, 12]).map((n) => (
            <option key={n} value={n}>{n} {granularity === 'WEEK' ? 'weeks' : 'months'}</option>))}
        </select>
        <label className="flex items-center gap-2">
          <input type="checkbox" checked={includeBudget} onChange={(e) => setIncludeBudget(e.target.checked)} />
          Include budgeted spending not yet committed
        </label>
      </div>
      <ErrorAlert error={f.error} />
      {f.isPending ? <Spinner /> : f.data && (
        <>
          <div className="mb-4 grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <StatTile label="Cash now" value={formatPeso(f.data.openingBalance)} hint="Banks and cash on hand, per the books" />
            <StatTile label="Lowest projected balance" value={formatPeso(f.data.lowestClosing)}
              hint={granularity === 'WEEK' ? `Week ending ${shortDate(f.data.lowestClosingOn)}`
                : `End of ${label(f.data.buckets.find((b) => b.end === f.data.lowestClosingOn)?.start ?? f.data.from)}`}
              tone={f.data.lowestClosing.startsWith('-') ? 'warning' : undefined} />
            <StatTile label="Overdue collections" value={formatPeso(f.data.overdueReceivables)} hint="Counted in the first period" />
            <StatTile label="Overdue payables" value={formatPeso(f.data.overduePayables)} hint="Counted in the first period" />
          </div>
          <section className="mb-4 rounded-xl border border-slate-200/80 bg-white p-5 shadow-xs">
            <BalanceLine title={`Projected closing balance, ${f.data.from} to ${f.data.to}`} valueName="closing balance"
              headers={[granularity === 'WEEK' ? 'Week from' : 'Month', 'Closing balance']}
              points={f.data.buckets.map((b) => ({ label: label(b.start), value: Number(b.closing), display: formatPeso(b.closing) }))} />
          </section>
          <div className="mb-4 overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs">
            <table className="min-w-full whitespace-nowrap text-right text-sm">
              <thead className="border-b border-slate-200 bg-slate-50/80 text-[11px] font-semibold uppercase tracking-wider text-slate-500">
                <tr><th className="px-2 py-2 text-left">{granularity === 'WEEK' ? 'Week' : 'Month'}</th><th className="px-2 py-2">Opening</th>
                  <th className="px-2 py-2">Collections</th><th className="px-2 py-2">Approved DVs</th><th className="px-2 py-2">Supplier invoices</th>
                  {f.data.includeBudget && <th className="px-2 py-2">Budgeted spending</th>}
                  <th className="px-2 py-2">Net</th><th className="px-2 py-2">Closing</th></tr>
              </thead>
              <tbody>
                {f.data.buckets.map((b, i) => (
                  <tr key={b.start} className={`border-t border-slate-100 ${bucket === i ? 'bg-brand-50' : ''}`}>
                    <td className="whitespace-nowrap px-2 py-1.5 text-left">
                      <button type="button" className="text-brand-700 hover:underline" aria-pressed={bucket === i}
                        onClick={() => setBucket(bucket === i ? null : i)}>
                        {granularity === 'WEEK' ? `${shortDate(b.start)} – ${shortDate(b.end)}` : label(b.start)}</button></td>
                    <td className="px-2 py-1.5"><Money value={b.opening} /></td>
                    <td className="px-2 py-1.5"><Money value={b.receivables} blankZero /></td>
                    <td className="px-2 py-1.5"><Money value={b.vouchers === '0.00' ? '0' : `-${b.vouchers}`} blankZero /></td>
                    <td className="px-2 py-1.5"><Money value={b.payables === '0.00' ? '0' : `-${b.payables}`} blankZero /></td>
                    {f.data.includeBudget && <td className="px-2 py-1.5"><Money value={b.budget === '0.00' ? '0' : `-${b.budget}`} blankZero /></td>}
                    <td className="px-2 py-1.5"><Money value={b.net} /></td>
                    <td className={`px-2 py-1.5 font-medium ${b.closing.startsWith('-') ? 'text-red-700' : ''}`}><Money value={b.closing} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <ul className="mb-4 list-disc space-y-1 pl-5 text-xs text-slate-600">
            {f.data.notes.map((n) => <li key={n}>{n}</li>)}
          </ul>
          <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs">
            <h2 className="border-b border-slate-200 px-4 py-3 text-sm font-semibold text-slate-800">
              {bucket === null || !f.data.buckets[bucket] ? 'Expected collections and payments' : `Expected in ${granularity === 'WEEK'
                ? `the week from ${shortDate(f.data.buckets[bucket].start)}` : label(f.data.buckets[bucket].start)}`}
              {bucket !== null && <button type="button" className="ml-3 text-xs font-normal text-brand-700 hover:underline"
                onClick={() => setBucket(null)}>Show all</button>}
            </h2>
            <div className="overflow-x-auto">
              <table className="min-w-full text-left text-sm">
                <thead className="text-[11px] font-semibold uppercase tracking-wider text-slate-500">
                  <tr><th className="px-3 py-2">Date</th><th className="px-3 py-2">Type</th><th className="px-3 py-2">Document</th>
                    <th className="px-3 py-2">Customer or supplier</th><th className="px-3 py-2 text-right">Amount</th></tr>
                </thead>
                <tbody>
                  {items.map((i) => (
                    <tr key={`${i.source}-${i.id}`} className="border-t border-slate-100">
                      <td className="whitespace-nowrap px-3 py-1.5">{i.date}{i.overdue && <span className="ml-1 text-xs font-medium text-amber-800">overdue</span>}</td>
                      <td className="px-3 py-1.5">{SOURCE[i.source]}</td>
                      <td className="px-3 py-1.5"><Link to={itemLink(i)} className="text-brand-700 hover:underline">{i.reference ?? 'Open'}</Link></td>
                      <td className="px-3 py-1.5">{i.party}</td>
                      <td className="whitespace-nowrap px-3 py-1.5 text-right"><Money value={i.source === 'RECEIVABLE' ? i.amount : `-${i.amount}`} /></td>
                    </tr>
                  ))}
                  {items.length === 0 && <tr><td colSpan={5} className="px-4 py-6 text-center text-slate-500">Nothing expected.</td></tr>}
                </tbody>
              </table>
            </div>
            {f.data.itemsTruncated && <p className="px-4 py-2 text-xs text-slate-500">Only the first 1,000 documents are listed; the totals above include all of them.</p>}
          </section>
        </>
      )}
    </>
  );
}
