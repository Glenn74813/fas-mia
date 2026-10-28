import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Select } from '../../components/Select';
import { useState } from 'react';
import { Link } from 'react-router';
import { billingApi } from '../../api/endpoints';
import { useAuth } from '../../auth/AuthContext';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Field } from '../../components/Field';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Pagination } from '../../components/Pagination';
import { SortHeader } from '../../components/SortHeader';
import { Spinner } from '../../components/Spinner';
import { StatusBadge } from '../../components/StatusBadge';

export const KIND_LABEL: Record<string, string> = { INVOICE: 'Invoice', CREDIT_MEMO: 'Credit memo', DEBIT_MEMO: 'Debit memo' };

export function BillingsPage() {
  const { hasPermission } = useAuth();
  const [kind, setKind] = useState('');
  const [status, setStatus] = useState('');
  const [q, setQ] = useState('');
  const [page, setPage] = useState(0);
  const [sort, setSort] = useState('');
  const onSort = (next: string) => { setSort(next); setPage(0); };
  const list = useQuery({ queryKey: ['billings', kind, status, q, page, sort], placeholderData: keepPreviousData,
    queryFn: () => billingApi.list({ kind, status, q, page, size: 25, sort: sort || undefined }) });
  const prepare = hasPermission('AR_BILLING_PREPARE');
  return (
    <>
      <PageHeader title="Billing" crumbs={[{ label: 'Receivables' }, { label: 'Billing' }]}
        actions={prepare && <div className="flex gap-2">
          <Link to="/receivables/billings/new" className="rounded-md bg-brand-700 px-4 py-2 text-sm font-medium text-white hover:bg-brand-900">New invoice or memo</Link>
        </div>} />
      {prepare && <BillingRun />}
      <div className="mb-4 flex flex-wrap gap-3">
        <input aria-label="Search billing" placeholder="Number or description" value={q}
          onChange={(e) => { setQ(e.target.value); setPage(0); }} className="w-72 rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm" />
        <Select aria-label="Kind" value={kind} onChange={(e) => { setKind(e.target.value); setPage(0); }}
          className="rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm">
          {['', 'INVOICE', 'CREDIT_MEMO', 'DEBIT_MEMO'].map((k) => <option key={k} value={k}>{k ? KIND_LABEL[k] : 'All kinds'}</option>)}
        </Select>
        <Select aria-label="Status" value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }}
          className="rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm">
          {['', 'DRAFT', 'RETURNED', 'SUBMITTED', 'APPROVED', 'POSTED', 'REJECTED', 'CANCELLED'].map((s) => <option key={s} value={s}>{s || 'All statuses'}</option>)}
        </Select>
      </div>
      <ErrorAlert error={list.error} />
      {list.isPending ? <Spinner /> : list.data && (
        <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs">
          <table className="min-w-full text-sm">
            <thead className="border-b border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500">
              <tr><th className="px-3 py-2">Number</th><th className="px-3 py-2">Kind</th><th className="px-3 py-2">Customer</th>
                <SortHeader label="Date" field="documentDate" sort={sort} onSort={onSort} descFirst /><SortHeader label="Due" field="dueDate" sort={sort} onSort={onSort} /><th className="px-3 py-2">Status</th>
                <SortHeader label="Amount" field="amount" sort={sort} onSort={onSort} descFirst align="right" /><th className="px-3 py-2 text-right">Open</th></tr>
            </thead>
            <tbody>
              {list.data.items.map((b) => (
                <tr key={b.id} className="border-t border-slate-100">
                  <td className="whitespace-nowrap px-3 py-2"><Link className="text-brand-700 hover:underline" to={`/receivables/billings/${b.id}`}>
                    {b.documentNumber ?? '(unnumbered draft)'}</Link></td>
                  <td className="px-3 py-2">{KIND_LABEL[b.kind]}{b.billingPeriod ? ` · ${b.billingPeriod}` : ''}</td>
                  <td className="px-3 py-2">{b.customerName}</td><td className="whitespace-nowrap px-3 py-2">{b.documentDate}</td>
                  <td className="whitespace-nowrap px-3 py-2">{b.dueDate}</td><td className="px-3 py-2"><StatusBadge status={b.status} /></td>
                  <td className="px-3 py-2 text-right"><Money value={b.grossAmount} /></td>
                  <td className="px-3 py-2 text-right"><Money value={b.openBalance} blankZero /></td>
                </tr>
              ))}
              {list.data.items.length === 0 && <tr><td colSpan={8} className="px-4 py-6 text-center text-slate-500">No billing documents.</td></tr>}
            </tbody>
          </table>
        </div>
      )}
      {list.data && <Pagination page={list.data.page} totalPages={list.data.totalPages} totalItems={list.data.totalItems} onPage={setPage} />}
    </>
  );
}

/** Generates draft invoices for every active contract covering a month; safe to re-run. */
function BillingRun() {
  const qc = useQueryClient();
  const now = new Date();
  const [open, setOpen] = useState(false);
  const [period, setPeriod] = useState(now.toISOString().slice(0, 7));
  const [date, setDate] = useState(now.toISOString().slice(0, 10));
  const run = useMutation({ mutationFn: () => billingApi.run(period, date), onSuccess: () => qc.invalidateQueries({ queryKey: ['billings'] }) });
  if (!open) return <button className="mb-3 text-sm text-brand-700 hover:underline" onClick={() => setOpen(true)}>Run contract billing…</button>;
  return (
    <section className="mb-4 space-y-3 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
      <h2 className="font-semibold">Contract billing run</h2>
      <p className="text-sm text-slate-600">Creates a draft invoice for each active contract covering the month that has not been billed yet.
        Drafts still go through review and approval before posting.</p>
      <div className="flex flex-wrap items-end gap-3">
        <Field label="Billing month" type="month" value={period} onChange={(e) => setPeriod(e.target.value)} />
        <Field label="Invoice date" type="date" value={date} onChange={(e) => setDate(e.target.value)} />
        <Button busy={run.isPending} disabled={!/^\d{4}-\d{2}$/.test(period) || !date} onClick={() => run.mutate()}>Generate drafts</Button>
        <Button variant="secondary" onClick={() => { setOpen(false); run.reset(); }}>Close</Button>
      </div>
      <ErrorAlert error={run.error} />
      {run.data && (
        <div className="text-sm" role="status">
          <p>{run.data.created.length} draft invoice(s) created for {run.data.billingPeriod}.</p>
          {run.data.skipped.length > 0 && <ul className="mt-1 list-disc pl-5 text-slate-600">{run.data.skipped.map((s) => <li key={s}>{s}</li>)}</ul>}
        </div>
      )}
    </section>
  );
}
