import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router';
import { ledgerApi } from '../../api/endpoints';
import type { TrialBalanceRow } from '../../api/types';
import { Badge } from '../../components/Badge';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Spinner } from '../../components/Spinner';
import { allPeriods, useFiscalYears } from '../../hooks/useAccounting';

export function TrialBalancePage() {
  const years = useFiscalYears();
  const periods = allPeriods(years.data).filter((p) => p.status !== 'FUTURE');
  const [periodId, setPeriodId] = useState('');
  // Default to the regular period containing today, else the latest period that is not in the future.
  const today = new Date().toISOString().slice(0, 10);
  const current = periods.find((p) => p.periodType === 'REGULAR' && p.startDate <= today && today <= p.endDate);
  const latest = [...periods].sort((a, b) => b.endDate.localeCompare(a.endDate) || b.periodNumber - a.periodNumber)[0];
  const selected = periodId || current?.id || latest?.id || '';
  const tb = useQuery({ queryKey: ['trial-balance', selected], queryFn: () => ledgerApi.trialBalance(selected), enabled: !!selected });
  const period = periods.find((p) => p.id === selected);

  return (
    <>
      <PageHeader title="Trial balance" crumbs={[{ label: 'General ledger' }, { label: 'Trial balance' }]} />
      <div className="mb-4 flex flex-wrap items-center gap-3">
        <label className="text-sm">Through period{' '}
          <select value={selected} onChange={(e) => setPeriodId(e.target.value)} className="ml-2 rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm">
            {periods.map((p) => <option key={p.id} value={p.id}>{p.name} ({p.status.replace('_', ' ').toLowerCase()})</option>)}
          </select>
        </label>
        {tb.data && <Badge tone={tb.data.balanced ? 'green' : 'red'}>{tb.data.balanced ? 'BALANCED' : 'OUT OF BALANCE'}</Badge>}
        {tb.data?.placeholderAccountsUsed && <Badge tone="amber">USES PLACEHOLDER ACCOUNTS</Badge>}
      </div>
      <ErrorAlert error={years.error ?? tb.error} />
      {(years.isPending || tb.isPending) && selected ? <Spinner /> : tb.data && period && (
        <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs">
          <table className="min-w-full text-sm">
            <thead className="border-b border-slate-200 bg-slate-50/80 text-[11px] font-semibold uppercase tracking-wider text-slate-500">
              <tr><th className="px-3 py-2 text-left" rowSpan={2}>Account</th>
                <th className="px-3 py-1 text-center" colSpan={2}>Opening</th><th className="px-3 py-1 text-center" colSpan={2}>{period.name}</th>
                <th className="px-3 py-1 text-center" colSpan={2}>Closing</th></tr>
              <tr>{['Debit', 'Credit', 'Debit', 'Credit', 'Debit', 'Credit'].map((h, i) => <th key={i} className="px-3 py-1 text-right">{h}</th>)}</tr>
            </thead>
            <tbody>
              {tb.data.rows.map((r) => <Row key={r.code} row={r} from={period.startDate} to={period.endDate} />)}
              {tb.data.rows.length === 0 && <tr><td colSpan={7} className="px-4 py-6 text-center text-slate-500">No postings yet.</td></tr>}
            </tbody>
            <tfoot className="border-t-2 border-slate-300 font-semibold">
              <tr><td className="px-3 py-2">Totals</td>
                {[tb.data.totalOpeningDebit, tb.data.totalOpeningCredit, tb.data.totalPeriodDebit, tb.data.totalPeriodCredit,
                  tb.data.totalClosingDebit, tb.data.totalClosingCredit].map((t, i) => <td key={i} className="px-3 py-2 text-right"><Money value={t} /></td>)}
              </tr>
            </tfoot>
          </table>
        </div>
      )}
      <p className="mt-2 text-xs text-slate-500">Computed from posted journal lines. Revenue and expense accounts accumulate within the fiscal year.</p>
    </>
  );
}

function Row({ row, from, to }: { row: TrialBalanceRow; from: string; to: string }) {
  return (
    <tr className="border-t border-slate-100">
      <td className="px-3 py-2">
        {row.accountId ? (
          <Link className="text-brand-700 hover:underline" to={`/general-ledger/account-ledger?account=${row.accountId}&from=${from}&to=${to}`}>
            <span className="font-mono">{row.code}</span> {row.name}</Link>
        ) : <span className="italic text-amber-800">{row.name}</span>}
      </td>
      {[row.openingDebit, row.openingCredit, row.periodDebit, row.periodCredit, row.closingDebit, row.closingCredit].map((v, i) => (
        <td key={i} className="px-3 py-2 text-right"><Money value={v} blankZero /></td>
      ))}
    </tr>
  );
}
