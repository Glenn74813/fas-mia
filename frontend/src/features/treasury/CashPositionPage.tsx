import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { settingsApi, treasuryApi } from '../../api/endpoints';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Spinner } from '../../components/Spinner';
import { StatusBadge } from '../../components/StatusBadge';

export function CashPositionPage() {
  const [asOf, setAsOf] = useState(new Date().toISOString().slice(0, 10));
  const [bank, setBank] = useState('');
  const [status, setStatus] = useState('');
  const pos = useQuery({ queryKey: ['cash-position', asOf], queryFn: () => treasuryApi.cashPosition(asOf) });
  const banks = useQuery({ queryKey: ['bank-accounts'], queryFn: settingsApi.bankAccounts });
  const checks = useQuery({ queryKey: ['checks', bank, status, asOf], queryFn: () => treasuryApi.checks({ bankAccountId: bank || undefined, status, asOf }) });
  return (
    <>
      <PageHeader title="Cash position and checks" crumbs={[{ label: 'Treasury' }, { label: 'Cash position' }]} />
      <label className="mb-4 block text-sm">As of <input type="date" value={asOf} onChange={(e) => setAsOf(e.target.value)} className="ml-1 rounded-md border border-slate-300 px-2 py-1" /></label>
      <ErrorAlert error={pos.error} />
      {pos.isPending ? <Spinner /> : pos.data && (
        <section className="mb-4 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5 text-sm">
          <table className="min-w-full">
            <thead className="border-b border-slate-200 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="py-1 pr-3">Account</th><th className="py-1 pr-3">Ledger account</th>
              <th className="py-1 pr-3">Last reconciled</th><th className="py-1 text-right">Book balance</th></tr></thead>
            <tbody>
              {pos.data.banks.map((b) => (
                <tr key={b.bankAccountId} className="border-t border-slate-100"><td className="py-1.5 pr-3">{b.code} — {b.bankName} ****{b.last4}</td>
                  <td className="py-1.5 pr-3 font-mono">{b.glAccountCode}</td><td className="py-1.5 pr-3">{b.lastReconciledStatementEnd ?? 'never'}</td>
                  <td className="py-1.5 text-right"><Money value={b.bookBalance} /></td></tr>))}
              <tr className="border-t border-slate-100"><td colSpan={3} className="py-1.5">Cash on hand (collecting officers)</td>
                <td className="py-1.5 text-right"><Money value={pos.data.cashOnHandBook} /></td></tr>
            </tbody>
            <tfoot className="border-t-2 border-slate-300 font-medium"><tr><td colSpan={3} className="py-1.5">Total in banks</td>
              <td className="py-1.5 text-right"><Money value={pos.data.totalBank} /></td></tr></tfoot>
          </table>
          <p className="mt-2 text-xs text-slate-500">Posted collections not yet deposited: <Money value={pos.data.undepositedCollections} />. Balances are per the books;
            the bank's figures appear on the reconciliations.</p>
        </section>
      )}
      <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
        <div className="mb-3 flex flex-wrap items-center gap-3"><h2 className="font-semibold">Check register</h2>
          <select aria-label="Bank account" value={bank} onChange={(e) => setBank(e.target.value)} className="rounded-md border border-slate-300 px-2 py-1 text-sm">
            <option value="">All accounts</option>{(banks.data ?? []).map((b) => <option key={b.id} value={b.id}>{b.code}</option>)}</select>
          <select aria-label="Check status" value={status} onChange={(e) => setStatus(e.target.value)} className="rounded-md border border-slate-300 px-2 py-1 text-sm">
            {['', 'ISSUED', 'RELEASED', 'CLEARED', 'VOIDED'].map((s) => <option key={s} value={s}>{s || 'All statuses'}</option>)}</select></div>
        <ErrorAlert error={checks.error} />
        <div className="overflow-x-auto"><table className="min-w-full text-sm">
          <thead className="border-b border-slate-200 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="py-1 pr-3">Check</th><th className="py-1 pr-3">Account</th>
            <th className="py-1 pr-3">DV</th><th className="py-1 pr-3">Payee</th><th className="py-1 pr-3">Issued</th><th className="py-1 pr-3">Age (days)</th>
            <th className="py-1 pr-3">Status</th><th className="py-1 text-right">Amount</th></tr></thead>
          <tbody>{(checks.data ?? []).map((c) => (
            <tr key={c.id} className="border-t border-slate-100"><td className="py-1.5 pr-3 font-mono">{c.checkNumber}</td><td className="py-1.5 pr-3">{c.bankAccountCode}</td>
              <td className="py-1.5 pr-3">{c.dvNumber}</td><td className="py-1.5 pr-3">{c.payee}</td><td className="py-1.5 pr-3">{c.issueDate}</td>
              <td className="py-1.5 pr-3">{c.ageDays}</td><td className="py-1.5 pr-3"><StatusBadge status={c.status} /></td>
              <td className="py-1.5 text-right"><Money value={c.amount} /></td></tr>))}
            {checks.data?.length === 0 && <tr><td colSpan={8} className="py-4 text-center text-slate-500">No checks.</td></tr>}</tbody>
        </table></div>
        <p className="mt-2 text-xs text-slate-500">Checks become CLEARED when matched on an approved bank reconciliation. The stale-check period is MIAA policy and is not applied automatically.</p>
      </section>
    </>
  );
}
