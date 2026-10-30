import { keepPreviousData, useMutation, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router';
import { ApiError } from '../../api/client';
import { portalApi } from '../../api/endpoints';
import { useAuth } from '../../auth/AuthContext';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { ModuleHelpButton } from '../../components/ModuleHelpButton';
import { Money } from '../../components/Money';
import { Pagination } from '../../components/Pagination';
import { Spinner } from '../../components/Spinner';
import { KIND_LABEL } from '../receivables/BillingsPage';
import { StatementTable } from '../receivables/StatementTable';
import { PortalInquiries } from './PortalInquiries';

type Tab = 'billings' | 'payments' | 'statement' | 'inquiries';
const TAB_LABEL: Record<Tab, string> = { billings: 'Billings', payments: 'Payments', statement: 'Statement of account', inquiries: 'Inquiries' };

/** Concessionaire landing page: own account summary, posted billings, payments and statement. */
export function PortalHomePage() {
  const { user } = useAuth();
  const account = useQuery({ queryKey: ['portal-account'], queryFn: portalApi.account });
  const [tab, setTab] = useState<Tab>('billings');
  if (account.isPending) return <Spinner />;
  if (account.error instanceof ApiError && account.error.status === 403) {
    return (
      <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-6">
        <h1 className="text-xl font-semibold">Welcome, {user?.displayName}</h1>
        <p className="mt-2 text-sm text-slate-600">Your sign-in is active, but your portal account is not yet linked to a
          concessionaire account. Please contact AVTECH Finance.</p>
      </section>
    );
  }
  if (account.error) return <ErrorAlert error={account.error} />;
  const a = account.data;
  return (
    <div className="space-y-4">
      <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-6">
        <div className="flex items-center gap-1.5">
          <h1 className="text-xl font-semibold">{a.customerName}</h1>
          <ModuleHelpButton />
        </div>
        <p className="text-sm text-slate-500">Account {a.customerCode}{a.tin ? ` · TIN ${a.tin}` : ''} · terms {a.paymentTermsDays} days</p>
        <dl className="mt-4 grid gap-4 sm:grid-cols-3">
          <div><dt className="text-xs uppercase text-slate-500">Outstanding balance</dt><dd className="text-2xl font-semibold"><Money value={a.openBalance} /></dd></div>
          <div><dt className="text-xs uppercase text-slate-500">Overdue</dt><dd className={`text-2xl font-semibold ${a.overdueBalance !== '0.00' ? 'text-red-700' : ''}`}>
            <Money value={a.overdueBalance} /></dd></div>
          <div><dt className="text-xs uppercase text-slate-500">Unapplied payments</dt><dd className="text-2xl font-semibold"><Money value={a.unappliedCollections} /></dd></div>
        </dl>
        <p className="mt-3 text-xs text-slate-500">As of {a.asOf}. Only posted documents are shown; payments appear once AVTECH Finance has posted them.</p>
      </section>
      <nav aria-label="Portal sections" className="flex gap-2">
        {(Object.keys(TAB_LABEL) as Tab[]).map((t) => (
          <button key={t} onClick={() => setTab(t)} aria-current={tab === t ? 'page' : undefined}
            className={`rounded-md px-4 py-2 text-sm font-medium ${tab === t ? 'bg-brand-700 text-white' : 'border border-slate-300 bg-white'}`}>
            {TAB_LABEL[t]}</button>
        ))}
      </nav>
      {tab === 'billings' && <Billings />}
      {tab === 'payments' && <Payments />}
      {tab === 'statement' && <PortalStatement />}
      {tab === 'inquiries' && <PortalInquiries />}
    </div>
  );
}

function Billings() {
  const [page, setPage] = useState(0);
  const list = useQuery({ queryKey: ['portal-billings', page], queryFn: () => portalApi.billings(page), placeholderData: keepPreviousData });
  return (
    <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs">
      <ErrorAlert error={list.error} />
      {list.isPending ? <Spinner /> : list.data && (
        <table className="min-w-full text-sm">
          <thead className="border-b border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="px-3 py-2">Number</th><th className="px-3 py-2">Kind</th>
            <th className="px-3 py-2">Date</th><th className="px-3 py-2">Due</th><th className="px-3 py-2 text-right">Amount</th><th className="px-3 py-2 text-right">Balance</th></tr></thead>
          <tbody>{list.data.items.map((b) => (
            <tr key={b.id} className="border-t border-slate-100">
              <td className="px-3 py-2"><Link className="text-brand-700 hover:underline" to={`/portal/billings/${b.id}`}>{b.documentNumber}</Link></td>
              <td className="px-3 py-2">{KIND_LABEL[b.kind]}{b.billingPeriod ? ` · ${b.billingPeriod}` : ''}</td>
              <td className="px-3 py-2">{b.documentDate}</td><td className="px-3 py-2">{b.kind === 'CREDIT_MEMO' ? '—' : b.dueDate}</td>
              <td className="px-3 py-2 text-right"><Money value={b.grossAmount} /></td><td className="px-3 py-2 text-right"><Money value={b.openBalance} blankZero /></td></tr>))}
            {list.data.items.length === 0 && <tr><td colSpan={6} className="px-4 py-6 text-center text-slate-500">No billings yet.</td></tr>}</tbody>
        </table>
      )}
      {list.data && <div className="px-3"><Pagination page={list.data.page} totalPages={list.data.totalPages} totalItems={list.data.totalItems} onPage={setPage} /></div>}
    </section>
  );
}

function Payments() {
  const [page, setPage] = useState(0);
  const list = useQuery({ queryKey: ['portal-receipts', page], queryFn: () => portalApi.receipts(page), placeholderData: keepPreviousData });
  return (
    <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs">
      <ErrorAlert error={list.error} />
      {list.isPending ? <Spinner /> : list.data && (
        <table className="min-w-full text-sm">
          <thead className="border-b border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="px-3 py-2">Receipt</th><th className="px-3 py-2">Date</th>
            <th className="px-3 py-2">Method</th><th className="px-3 py-2 text-right">Amount</th><th className="px-3 py-2 text-right">Tax withheld</th>
            <th className="px-3 py-2 text-right">Unapplied</th></tr></thead>
          <tbody>{list.data.items.map((r) => (
            <tr key={r.id} className="border-t border-slate-100"><td className="px-3 py-2 font-mono">{r.receiptNumber}</td><td className="px-3 py-2">{r.receiptDate}</td>
              <td className="px-3 py-2">{r.paymentMethod.replace('_', ' ')}</td><td className="px-3 py-2 text-right"><Money value={r.amount} /></td>
              <td className="px-3 py-2 text-right"><Money value={r.taxWithheld} blankZero /></td><td className="px-3 py-2 text-right"><Money value={r.unappliedAmount} blankZero /></td></tr>))}
            {list.data.items.length === 0 && <tr><td colSpan={6} className="px-4 py-6 text-center text-slate-500">No posted payments yet.</td></tr>}</tbody>
        </table>
      )}
      {list.data && <div className="px-3"><Pagination page={list.data.page} totalPages={list.data.totalPages} totalItems={list.data.totalItems} onPage={setPage} /></div>}
    </section>
  );
}

function PortalStatement() {
  const [from, setFrom] = useState(`${new Date().getFullYear()}-01-01`);
  const [to, setTo] = useState(new Date().toISOString().slice(0, 10));
  const st = useQuery({ queryKey: ['portal-statement', from, to], queryFn: () => portalApi.statement(from, to), enabled: !!from && !!to });
  const pdf = useMutation({ mutationFn: () => portalApi.statementPdf(from, to) });
  return (
    <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
      <div className="mb-3 flex flex-wrap gap-2 text-sm">
        <label>From <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} className="ml-1 rounded-md border border-slate-300 px-2 py-1" /></label>
        <label>To <input type="date" value={to} onChange={(e) => setTo(e.target.value)} className="ml-1 rounded-md border border-slate-300 px-2 py-1" /></label>
        <Button variant="secondary" busy={pdf.isPending} disabled={!from || !to} onClick={() => pdf.mutate()}>Download PDF</Button>
      </div>
      <ErrorAlert error={st.error ?? pdf.error} />
      {st.isPending ? <Spinner /> : st.data && <StatementTable statement={st.data} />}
    </section>
  );
}
