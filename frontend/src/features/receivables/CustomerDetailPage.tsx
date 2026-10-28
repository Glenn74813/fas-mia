import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Combobox } from '../../components/Combobox';
import { useState } from 'react';
import { useParams } from 'react-router';
import { customerApi, portalAccessApi, settingsApi } from '../../api/endpoints';
import { useAuth } from '../../auth/AuthContext';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Field } from '../../components/Field';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Spinner } from '../../components/Spinner';
import { StatusBadge } from '../../components/StatusBadge';
import { postableAccounts, useChart, useCostCenters } from '../../hooks/useAccounting';
import { isValidAmount } from '../../utils/money';
import { PortalRequestList } from './PortalAccessRequests';
import { StatementTable } from './StatementTable';

export function CustomerDetailPage() {
  const { id = '' } = useParams();
  const { hasPermission } = useAuth();
  const customer = useQuery({ queryKey: ['customer', id], queryFn: () => customerApi.get(id) });
  if (customer.isPending) return <Spinner />;
  if (customer.error) return <ErrorAlert error={customer.error} />;
  const c = customer.data;
  return (
    <>
      <PageHeader title={`${c.code} — ${c.name}`} crumbs={[{ label: 'Receivables' }, { label: 'Customers', to: '/receivables/customers' }, { label: c.code }]} />
      <div className="space-y-4">
        <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
          <div className="mb-3"><StatusBadge status={c.status} /></div>
          <dl className="grid gap-2 text-sm sm:grid-cols-4">
            <dt className="text-slate-500">Type</dt><dd>{c.customerType}</dd>
            <dt className="text-slate-500">TIN</dt><dd>{c.tin ?? '—'}</dd>
            <dt className="text-slate-500">Address</dt><dd>{c.address ?? '—'}</dd>
            <dt className="text-slate-500">Billing e-mail</dt><dd>{c.email ?? '—'}</dd>
            <dt className="text-slate-500">Payment terms</dt><dd>{c.paymentTermsDays} days</dd>
            <dt className="text-slate-500">Credit limit</dt><dd>{c.creditLimit ? <Money value={c.creditLimit} /> : 'None'}</dd>
            <dt className="text-slate-500">Open receivables</dt><dd><Money value={c.openBalance} /></dd>
            <dt className="text-slate-500">Unapplied collections</dt><dd><Money value={c.unappliedCollections} /></dd>
          </dl>
        </section>
        <Contracts customerId={id} canManage={hasPermission('CUSTOMER_MANAGE')} />
        <PortalUsers customerId={id} canManage={hasPermission('CUSTOMER_MANAGE')} />
        <CustomerStatement customerId={id} />
      </div>
    </>
  );
}

function Contracts({ customerId, canManage }: { customerId: string; canManage: boolean }) {
  const qc = useQueryClient();
  const list = useQuery({ queryKey: ['contracts', customerId], queryFn: () => customerApi.contracts(customerId) });
  const chart = useChart();
  const costCenters = useCostCenters();
  const taxCodes = useQuery({ queryKey: ['tax-codes'], queryFn: settingsApi.taxCodes });
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState({ contractNumber: '', description: '', startDate: '', endDate: '', monthlyAmount: '',
    revenueAccountId: '', costCenterId: '', vatCodeId: '' });
  const refresh = () => qc.invalidateQueries({ queryKey: ['contracts', customerId] });
  const create = useMutation({
    mutationFn: () => customerApi.createContract(customerId, { ...form, endDate: form.endDate || null,
      costCenterId: form.costCenterId || null, vatCodeId: form.vatCodeId || null }),
    onSuccess: () => { setOpen(false); refresh(); },
  });
  const status = useMutation({ mutationFn: ({ c, s }: { c: Parameters<typeof customerApi.setContractStatus>[0]; s: string }) =>
    customerApi.setContractStatus(c, s), onSuccess: refresh });
  const revenue = postableAccounts(chart.data?.accounts).filter((a) => a.accountType === 'REVENUE');
  const vat = (taxCodes.data ?? []).filter((t) => t.taxType === 'VAT_OUTPUT' && t.status === 'ACTIVE');
  const valid = form.contractNumber && form.description && form.startDate && form.revenueAccountId && isValidAmount(form.monthlyAmount) && form.monthlyAmount;
  return (
    <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
      <div className="mb-3 flex items-center justify-between"><h2 className="font-semibold">Contracts</h2>
        {canManage && !open && <Button variant="secondary" onClick={() => setOpen(true)}>Add contract</Button>}</div>
      {open && (
        <div className="mb-4 space-y-3 rounded-md border border-slate-200 p-4">
          <ErrorAlert error={create.error} />
          <div className="grid gap-3 md:grid-cols-4">
            <Field label="Contract number" value={form.contractNumber} onChange={(e) => setForm({ ...form, contractNumber: e.target.value })} />
            <div className="md:col-span-3"><Field label="Description" value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} /></div>
            <Field label="Start date" type="date" value={form.startDate} onChange={(e) => setForm({ ...form, startDate: e.target.value })} />
            <Field label="End date" type="date" value={form.endDate} onChange={(e) => setForm({ ...form, endDate: e.target.value })} />
            <Field label="Monthly amount (net of VAT)" inputMode="decimal" value={form.monthlyAmount}
              onChange={(e) => setForm({ ...form, monthlyAmount: e.target.value.replace(/[^\d.]/g, '') })} />
            <div className="space-y-1.5"><label htmlFor="kvat" className="block text-sm font-medium text-slate-700">Output VAT</label>
              <select id="kvat" value={form.vatCodeId} onChange={(e) => setForm({ ...form, vatCodeId: e.target.value })}
                className="block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm"><option value="">No VAT</option>
                {vat.map((t) => <option key={t.id} value={t.id}>{t.code} ({t.rate})</option>)}</select></div>
            <div className="space-y-1 md:col-span-2"><label htmlFor="krev" className="block text-sm font-medium text-slate-700">Revenue account</label>
              <Combobox id="krev" value={form.revenueAccountId} onChange={(v) => setForm({ ...form, revenueAccountId: v })}
                className="block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm" placeholder="Select…"
              options={revenue.map((a) => ({ value: a.id, label: `${a.code} — ${a.name}` }))} /></div>
            <div className="space-y-1.5"><label htmlFor="kcc" className="block text-sm font-medium text-slate-700">Cost centre</label>
              <select id="kcc" value={form.costCenterId} onChange={(e) => setForm({ ...form, costCenterId: e.target.value })}
                className="block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm"><option value="">—</option>
                {(costCenters.data ?? []).map((cc) => <option key={cc.id} value={cc.id}>{cc.code}</option>)}</select></div>
          </div>
          <p className="text-xs text-slate-500">Contract billing charges the full monthly amount for every month the contract covers.
            Proration, escalation and percentage rent await MIAA's billing rules (clarification C-28).</p>
          <div className="flex gap-2"><Button busy={create.isPending} disabled={!valid} onClick={() => create.mutate()}>Save contract</Button>
            <Button variant="secondary" onClick={() => setOpen(false)}>Cancel</Button></div>
        </div>
      )}
      <ErrorAlert error={list.error ?? status.error} />
      <table className="min-w-full text-sm">
        <thead className="border-b border-slate-200 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="py-1 pr-3">Contract</th><th className="py-1 pr-3">Description</th>
          <th className="py-1 pr-3">Term</th><th className="py-1 pr-3">Revenue</th><th className="py-1 pr-3">VAT</th><th className="py-1 text-right">Monthly</th>
          <th className="py-1 pl-3">Status</th><th /></tr></thead>
        <tbody>{(list.data ?? []).map((k) => (
          <tr key={k.id} className="border-t border-slate-100"><td className="py-1.5 pr-3 font-mono">{k.contractNumber}</td><td className="py-1.5 pr-3">{k.description}</td>
            <td className="py-1.5 pr-3 whitespace-nowrap">{k.startDate} – {k.endDate ?? 'open'}</td><td className="py-1.5 pr-3 font-mono">{k.revenueAccountCode}</td>
            <td className="py-1.5 pr-3">{k.vatCode ?? '—'}</td><td className="py-1.5 text-right"><Money value={k.monthlyAmount} /></td>
            <td className="py-1.5 pl-3"><StatusBadge status={k.status} /></td>
            <td className="py-1.5 pl-3 text-right">{canManage && k.status === 'ACTIVE' && (
              <button className="text-xs text-brand-700" onClick={() => status.mutate({ c: k, s: 'SUSPENDED' })}>Suspend</button>)}
              {canManage && k.status === 'SUSPENDED' && (
                <button className="text-xs text-brand-700" onClick={() => status.mutate({ c: k, s: 'ACTIVE' })}>Reactivate</button>)}</td></tr>
        ))}
          {list.data?.length === 0 && <tr><td colSpan={8} className="py-4 text-center text-slate-500">No contracts.</td></tr>}</tbody>
      </table>
    </section>
  );
}

function PortalUsers({ customerId, canManage }: { customerId: string; canManage: boolean }) {
  const qc = useQueryClient();
  const list = useQuery({ queryKey: ['portal-users', customerId], queryFn: () => customerApi.portalUsers(customerId) });
  const [username, setUsername] = useState('');
  const [evidence, setEvidence] = useState('');
  const request = useMutation({ mutationFn: () => portalAccessApi.request(customerId, username, evidence),
    onSuccess: () => { setUsername(''); setEvidence(''); qc.invalidateQueries({ queryKey: ['portal-link-requests'] }); } });
  const unlink = useMutation({ mutationFn: (userId: string) => customerApi.unlinkPortalUser(customerId, userId),
    onSuccess: (rows) => qc.setQueryData(['portal-users', customerId], rows) });
  return (
    <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
      <h2 className="mb-1 font-semibold">Portal access</h2>
      <p className="mb-3 text-sm text-slate-600">Linked concessionaire portal accounts see this customer's posted billings, receipts and statement — nothing else.
        A link takes effect only after a second person approves the request. Accounts are created by the system administrator as type CONCESSIONAIRE.</p>
      <ErrorAlert error={request.error ?? unlink.error ?? list.error} />
      <ul className="mb-3 space-y-1 text-sm">
        {(list.data ?? []).map((u) => (
          <li key={u.userId} className="flex items-center gap-3"><span className="font-mono">{u.username}</span> {u.displayName}
            <StatusBadge status={u.status} />
            {canManage && <button className="text-xs text-red-700" onClick={() => unlink.mutate(u.userId)}>Remove access</button>}</li>
        ))}
        {list.data?.length === 0 && <li className="text-slate-500">No portal accounts linked.</li>}
      </ul>
      <h3 className="mb-2 text-sm font-medium">Link requests</h3>
      <PortalRequestList customerId={customerId} />
      {canManage && (
        <div className="mt-3 grid gap-2 md:grid-cols-3">
          <Field label="Portal username" value={username} onChange={(e) => setUsername(e.target.value)} />
          <div className="md:col-span-2"><Field label="Evidence of identity and authority" hint="e.g. authorisation letter reference and how the signatory was confirmed"
            value={evidence} maxLength={1000} onChange={(e) => setEvidence(e.target.value)} /></div>
          <div><Button variant="secondary" busy={request.isPending} disabled={!username.trim() || evidence.trim().length < 10}
            onClick={() => request.mutate()}>Request link</Button></div>
        </div>
      )}
    </section>
  );
}

function CustomerStatement({ customerId }: { customerId: string }) {
  const year = new Date().getFullYear();
  const [from, setFrom] = useState(`${year}-01-01`);
  const [to, setTo] = useState(new Date().toISOString().slice(0, 10));
  const st = useQuery({ queryKey: ['statement', customerId, from, to], queryFn: () => customerApi.statement(customerId, from, to),
    enabled: !!from && !!to });
  return (
    <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2"><h2 className="font-semibold">Statement of account</h2>
        <span className="flex gap-2 text-sm">
          <label>From <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} className="ml-1 rounded-md border border-slate-300 px-2 py-1" /></label>
          <label>To <input type="date" value={to} onChange={(e) => setTo(e.target.value)} className="ml-1 rounded-md border border-slate-300 px-2 py-1" /></label>
        </span></div>
      <ErrorAlert error={st.error} />
      {st.isPending ? <Spinner /> : st.data && <StatementTable statement={st.data} />}
    </section>
  );
}
