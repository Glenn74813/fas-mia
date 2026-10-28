import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Combobox } from '../../components/Combobox';
import { useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { ledgerApi } from '../../api/endpoints';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Pagination } from '../../components/Pagination';
import { Spinner } from '../../components/Spinner';
import { postableAccounts, useChart } from '../../hooks/useAccounting';

export function AccountLedgerPage() {
  const [params, setParams] = useSearchParams();
  const chart = useChart();
  const year = new Date().getFullYear();
  const account = params.get('account') ?? '';
  const from = params.get('from') ?? `${year}-01-01`;
  const to = params.get('to') ?? `${year}-12-31`;
  const [page, setPage] = useState(0);
  const ledger = useQuery({
    queryKey: ['ledger', account, from, to, page],
    queryFn: () => ledgerApi.accountLedger(account, from, to, page),
    enabled: !!account,
    placeholderData: keepPreviousData,
  });
  const update = (patch: Record<string, string>) => {
    setPage(0);
    setParams({ account, from, to, ...patch });
  };

  return (
    <>
      <PageHeader title="Account ledger" crumbs={[{ label: 'General ledger' }, { label: 'Account ledger' }]} />
      <div className="mb-4 flex flex-wrap items-end gap-3 text-sm">
        <label>Account
          <Combobox value={account} onChange={(v) => update({ account: v })} className="ml-2 w-80 rounded-lg border border-slate-300 px-3 py-2 shadow-xs" placeholder="Select…"
              options={postableAccounts(chart.data?.accounts).map((a) => ({ value: a.id, label: `${a.code} — ${a.name}` }))} />
        </label>
        <label>From <input type="date" value={from} onChange={(e) => update({ from: e.target.value })} className="ml-1 rounded-md border border-slate-300 px-2 py-2" /></label>
        <label>To <input type="date" value={to} onChange={(e) => update({ to: e.target.value })} className="ml-1 rounded-md border border-slate-300 px-2 py-2" /></label>
      </div>
      <ErrorAlert error={ledger.error} />
      {!account ? <p className="text-sm text-slate-500">Choose an account.</p> : ledger.isPending ? <Spinner /> : ledger.data && (
        <>
          <dl className="mb-3 grid grid-cols-2 gap-2 rounded-xl border border-slate-200/80 bg-white shadow-xs p-4 text-sm sm:grid-cols-4">
            <dt className="text-slate-500">Opening balance</dt><dd className="text-right"><Money value={ledger.data.openingBalance} /></dd>
            <dt className="text-slate-500">Closing balance</dt><dd className="text-right"><Money value={ledger.data.closingBalance} /></dd>
            <dt className="text-slate-500">Debits</dt><dd className="text-right"><Money value={ledger.data.periodDebit} /></dd>
            <dt className="text-slate-500">Credits</dt><dd className="text-right"><Money value={ledger.data.periodCredit} /></dd>
          </dl>
          <p className="mb-2 text-xs text-slate-500">Balances are shown in the account's normal-balance sign ({ledger.data.balanceSign.toLowerCase()}); a negative figure means the account is on the opposite side.</p>
          <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs">
            <table className="min-w-full text-sm">
              <thead className="border-b border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500">
                <tr><th className="px-3 py-2">Date</th><th className="px-3 py-2">Entry</th><th className="px-3 py-2">Description</th>
                  <th className="px-3 py-2">CC / SL</th><th className="px-3 py-2 text-right">Debit</th><th className="px-3 py-2 text-right">Credit</th>
                  <th className="px-3 py-2 text-right">Balance</th></tr>
              </thead>
              <tbody>
                {ledger.data.lines.map((l) => (
                  <tr key={l.lineId} className="border-t border-slate-100">
                    <td className="whitespace-nowrap px-3 py-2">{l.accountingDate}</td>
                    <td className="px-3 py-2"><Link className="font-mono text-brand-700 hover:underline" to={`/general-ledger/entries/${l.entryId}`}>{l.entryNumber}</Link></td>
                    <td className="px-3 py-2">{l.description}</td>
                    <td className="px-3 py-2 text-xs">{[l.costCenterCode, l.subsidiaryCode].filter(Boolean).join(' / ') || '—'}</td>
                    <td className="px-3 py-2 text-right"><Money value={l.debit} blankZero /></td>
                    <td className="px-3 py-2 text-right"><Money value={l.credit} blankZero /></td>
                    <td className="px-3 py-2 text-right"><Money value={l.runningBalance} /></td>
                  </tr>
                ))}
                {ledger.data.lines.length === 0 && <tr><td colSpan={7} className="px-4 py-6 text-center text-slate-500">No postings in this range.</td></tr>}
              </tbody>
            </table>
          </div>
          <Pagination page={ledger.data.page} totalItems={ledger.data.totalLines}
            totalPages={Math.ceil(ledger.data.totalLines / ledger.data.size)} onPage={setPage} />
        </>
      )}
    </>
  );
}
