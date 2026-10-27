import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { bankApi, settingsApi } from '../../api/endpoints';
import { useAuth } from '../../auth/AuthContext';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Field } from '../../components/Field';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Pagination } from '../../components/Pagination';
import { Spinner } from '../../components/Spinner';
import { StatusBadge } from '../../components/StatusBadge';
import { formatDateTime } from '../../utils/format';
import { isValidAmount } from '../../utils/money';

export function BankStatementsPage() {
  const { hasPermission } = useAuth();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const banks = useQuery({ queryKey: ['bank-accounts'], queryFn: settingsApi.bankAccounts });
  const [bankFilter, setBankFilter] = useState('');
  const [page, setPage] = useState(0);
  const list = useQuery({ queryKey: ['statements', bankFilter, page], queryFn: () => bankApi.statements(bankFilter || undefined, page),
    placeholderData: keepPreviousData });
  const [form, setForm] = useState({ bankAccountId: '', format: 'CAMT053', openingBalance: '', closingBalance: '' });
  const [file, setFile] = useState<File | null>(null);
  const imp = useMutation({
    mutationFn: () => bankApi.importStatement(file!, form.format === 'CSV' ? form : { bankAccountId: form.bankAccountId, format: form.format }),
    onSuccess: () => { setFile(null); qc.invalidateQueries({ queryKey: ['statements'] }); },
  });
  const start = useMutation({ mutationFn: (id: string) => bankApi.startReconciliation(id),
    onSuccess: (r) => { qc.invalidateQueries({ queryKey: ['statements'] }); navigate(`/treasury/reconciliations/${r.id}`); } });
  const csv = form.format === 'CSV';
  const valid = form.bankAccountId && file && (!csv || (form.openingBalance && form.closingBalance
    && isValidAmount(form.openingBalance.replace('-', '')) && isValidAmount(form.closingBalance.replace('-', ''))));
  return (
    <>
      <PageHeader title="Bank statements" crumbs={[{ label: 'Treasury' }, { label: 'Bank statements' }]} />
      {hasPermission('BANK_STATEMENT_IMPORT') && (
        <section className="mb-4 space-y-3 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
          <h2 className="font-semibold">Import a statement</h2>
          <ErrorAlert error={imp.error} />
          {imp.data && <p role="status" className="text-sm text-green-800">Imported {imp.data.summary.lineCount} lines for
            {' '}{imp.data.summary.periodStart} to {imp.data.summary.periodEnd}.</p>}
          <div className="grid gap-3 md:grid-cols-4">
            <div className="space-y-1.5"><label htmlFor="sbank" className="block text-sm font-medium text-slate-700">Bank account</label>
              <select id="sbank" value={form.bankAccountId} onChange={(e) => setForm({ ...form, bankAccountId: e.target.value })}
                className="block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm"><option value="">Select…</option>
                {(banks.data ?? []).filter((b) => b.status === 'ACTIVE').map((b) => <option key={b.id} value={b.id}>{b.code} ****{b.accountNumberLast4}</option>)}</select></div>
            <div className="space-y-1.5"><label htmlFor="sfmt" className="block text-sm font-medium text-slate-700">Format</label>
              <select id="sfmt" value={form.format} onChange={(e) => setForm({ ...form, format: e.target.value })}
                className="block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm">
                <option value="CAMT053">ISO 20022 camt.053 (XML)</option><option value="MT940">SWIFT MT940</option><option value="CSV">CSV (CAS layout)</option></select></div>
            {csv && <Field label="Opening balance" inputMode="decimal" value={form.openingBalance} onChange={(e) => setForm({ ...form, openingBalance: e.target.value })} />}
            {csv && <Field label="Closing balance" inputMode="decimal" value={form.closingBalance} onChange={(e) => setForm({ ...form, closingBalance: e.target.value })} />}
            <div className="space-y-1 md:col-span-4"><label htmlFor="sfile" className="block text-sm font-medium text-slate-700">Statement file</label>
              <input id="sfile" type="file" accept=".xml,.txt,.sta,.mt940,.csv" onChange={(e) => setFile(e.target.files?.[0] ?? null)} className="text-sm" /></div>
          </div>
          {csv && <p className="text-xs text-slate-500">CSV header: date,description,reference,amount (signed) or date,description,reference,debit,credit; optional balance column; dates yyyy-MM-dd.</p>}
          <Button busy={imp.isPending} disabled={!valid} onClick={() => imp.mutate()}>Import</Button>
          <p className="text-xs text-slate-500">Bank-specific variants of these formats must be confirmed with MIAA's banks (clarification C-11).</p>
        </section>
      )}
      <select aria-label="Filter bank account" value={bankFilter} onChange={(e) => { setBankFilter(e.target.value); setPage(0); }} className="mb-3 rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm">
        <option value="">All bank accounts</option>{(banks.data ?? []).map((b) => <option key={b.id} value={b.id}>{b.code}</option>)}</select>
      <ErrorAlert error={list.error ?? start.error} />
      {list.isPending ? <Spinner /> : (
        <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs">
          <table className="min-w-full text-sm">
            <thead className="border-b border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="px-3 py-2">Account</th><th className="px-3 py-2">Period</th>
              <th className="px-3 py-2">Format</th><th className="px-3 py-2 text-right">Opening</th><th className="px-3 py-2 text-right">Closing</th>
              <th className="px-3 py-2">Lines (unmatched)</th><th className="px-3 py-2">Imported</th><th className="px-3 py-2">Reconciliation</th></tr></thead>
            <tbody>{(list.data?.items ?? []).map((s) => (
              <tr key={s.id} className="border-t border-slate-100"><td className="px-3 py-2">{s.bankAccountCode}</td>
                <td className="whitespace-nowrap px-3 py-2">{s.periodStart} – {s.periodEnd}</td><td className="px-3 py-2">{s.format}</td>
                <td className="px-3 py-2 text-right"><Money value={s.openingBalance} /></td><td className="px-3 py-2 text-right"><Money value={s.closingBalance} /></td>
                <td className="px-3 py-2">{s.lineCount} ({s.unmatchedLines})</td><td className="px-3 py-2">{formatDateTime(s.importedAt)} · {s.importedBy}</td>
                <td className="px-3 py-2">{s.reconciliationId
                  ? <Link className="text-brand-700 hover:underline" to={`/treasury/reconciliations/${s.reconciliationId}`}><StatusBadge status={s.reconciliationStatus!} /></Link>
                  : hasPermission('BANK_RECONCILIATION_PREPARE')
                    ? <Button variant="secondary" busy={start.isPending} onClick={() => start.mutate(s.id)}>Reconcile</Button> : '—'}</td></tr>))}
              {list.data?.items.length === 0 && <tr><td colSpan={8} className="px-4 py-6 text-center text-slate-500">No statements imported.</td></tr>}</tbody>
          </table>
        </div>
      )}
      {list.data && <Pagination page={list.data.page} totalPages={list.data.totalPages} totalItems={list.data.totalItems} onPage={setPage} />}
    </>
  );
}
