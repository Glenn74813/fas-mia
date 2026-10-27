import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { customerApi } from '../../api/endpoints';
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
import { isValidAmount } from '../../utils/money';

export const CUSTOMER_TYPES = ['CONCESSIONAIRE', 'AIRLINE', 'GOVERNMENT', 'OTHER'];

const blankForm = { code: '', name: '', customerType: 'CONCESSIONAIRE', tin: '', address: '', email: '', creditLimit: '', paymentTermsDays: '30' };

export function CustomersPage() {
  const { hasPermission } = useAuth();
  const qc = useQueryClient();
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState('');
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState('');
  useEffect(() => { const t = setTimeout(() => { setSearch(q.trim()); setPage(0); }, 300); return () => clearTimeout(t); }, [q]);
  const [sort, setSort] = useState('');
  const onSort = (next: string) => { setSort(next); setPage(0); };
  const list = useQuery({ queryKey: ['customers', search, page, sort], queryFn: () => customerApi.list(search, page, 25, sort || undefined),
    placeholderData: keepPreviousData });
  const [form, setForm] = useState(blankForm);
  const create = useMutation({
    mutationFn: () => customerApi.create({ code: form.code, name: form.name, customerType: form.customerType,
      tin: form.tin || null, address: form.address || null, email: form.email || null, creditLimit: form.creditLimit || null,
      paymentTermsDays: Number(form.paymentTermsDays) }),
    onSuccess: () => { setOpen(false); setForm(blankForm); qc.invalidateQueries({ queryKey: ['customers'] }); },
  });
  const rows = list.data?.items ?? [];
  const valid = form.code && form.name && /^\d{1,3}$/.test(form.paymentTermsDays) && (!form.creditLimit || isValidAmount(form.creditLimit));
  return (
    <>
      <PageHeader title="Customers" crumbs={[{ label: 'Receivables' }, { label: 'Customers' }]}
        actions={hasPermission('CUSTOMER_MANAGE') && <Button onClick={() => setOpen(true)}>New customer</Button>} />
      {open && (
        <section className="mb-4 space-y-3 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
          <ErrorAlert error={create.error} />
          <div className="grid gap-3 md:grid-cols-3">
            <Field label="Code" value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value })} />
            <Field label="Registered name" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
            <div className="space-y-1">
              <label htmlFor="ctype" className="block text-sm font-medium text-slate-700">Type</label>
              <select id="ctype" value={form.customerType} onChange={(e) => setForm({ ...form, customerType: e.target.value })}
                className="block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm">
                {CUSTOMER_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
              </select>
            </div>
            <Field label="TIN" hint="000-000-000-00000" value={form.tin} onChange={(e) => setForm({ ...form, tin: e.target.value })} />
            <Field label="Address" value={form.address} onChange={(e) => setForm({ ...form, address: e.target.value })} />
            <Field label="Billing e-mail" type="email" value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} />
            <Field label="Credit limit" hint="Leave blank for none" inputMode="decimal" value={form.creditLimit}
              onChange={(e) => setForm({ ...form, creditLimit: e.target.value.replace(/[^\d.]/g, '') })} />
            <Field label="Payment terms (days)" inputMode="numeric" value={form.paymentTermsDays}
              onChange={(e) => setForm({ ...form, paymentTermsDays: e.target.value.replace(/\D/g, '') })} />
          </div>
          <div className="flex gap-2">
            <Button busy={create.isPending} disabled={!valid} onClick={() => create.mutate()}>Save customer</Button>
            <Button variant="secondary" onClick={() => setOpen(false)}>Cancel</Button>
          </div>
        </section>
      )}
      <input aria-label="Search customers" placeholder="Search code, name or TIN" value={q} onChange={(e) => setQ(e.target.value)}
        className="mb-3 w-72 rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm" />
      <ErrorAlert error={list.error} />
      {list.isPending ? <Spinner /> : (
        <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs">
          <table className="min-w-full text-sm">
            <thead className="border-b border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500">
              <tr><SortHeader label="Code" field="code" sort={sort} onSort={onSort} /><SortHeader label="Name" field="name" sort={sort} onSort={onSort} /><th className="px-3 py-2">Type</th>
                <th className="px-3 py-2">TIN</th><SortHeader label="Status" field="status" sort={sort} onSort={onSort} /><th className="px-3 py-2 text-right">Open receivables</th>
                <th className="px-3 py-2 text-right">Unapplied</th></tr>
            </thead>
            <tbody>
              {rows.map((c) => (
                <tr key={c.id} className="border-t border-slate-100">
                  <td className="px-3 py-2 font-mono"><Link className="text-brand-700 hover:underline" to={`/receivables/customers/${c.id}`}>{c.code}</Link></td>
                  <td className="px-3 py-2">{c.name}</td><td className="px-3 py-2">{c.customerType}</td>
                  <td className="px-3 py-2">{c.tin ?? '—'}</td><td className="px-3 py-2"><StatusBadge status={c.status} /></td>
                  <td className="px-3 py-2 text-right"><Money value={c.openBalance} blankZero /></td>
                  <td className="px-3 py-2 text-right"><Money value={c.unappliedCollections} blankZero /></td>
                </tr>
              ))}
              {rows.length === 0 && <tr><td colSpan={7} className="px-4 py-6 text-center text-slate-500">No customers.</td></tr>}
            </tbody>
          </table>
        </div>
      )}
      {list.data && <Pagination page={list.data.page} totalPages={list.data.totalPages} totalItems={list.data.totalItems} onPage={setPage} />}
    </>
  );
}
