import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { accountApi } from '../../api/endpoints';
import type { SubsidiaryAccount } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Field } from '../../components/Field';
import { PageHeader } from '../../components/PageHeader';
import { Pagination } from '../../components/Pagination';
import { Select } from '../../components/Select';
import { Spinner } from '../../components/Spinner';

const TYPES: Record<string, string> = { EMPLOYEE: 'Employee', OTHER: 'Other', SUPPLIER: 'Supplier', CUSTOMER: 'Customer' };
/** Supplier and customer subsidiaries come with their supplier or customer; only these two are kept here. */
const MANAGED = ['EMPLOYEE', 'OTHER'];

export function SubsidiaryAccountsPage() {
  const { hasPermission } = useAuth();
  const canManage = hasPermission('ACCOUNT_ADMINISTER');
  const qc = useQueryClient();
  const [slType, setSlType] = useState('');
  const [q, setQ] = useState('');
  const [page, setPage] = useState(0);
  const [form, setForm] = useState({ slType: 'EMPLOYEE', code: '', name: '' });
  const [editing, setEditing] = useState<{ s: SubsidiaryAccount; name: string } | null>(null);
  const list = useQuery({
    queryKey: ['subsidiary-accounts', slType, q, page],
    queryFn: () => accountApi.subsidiaryAccounts(slType || undefined, q, page, 25),
    placeholderData: keepPreviousData,
  });
  const refresh = () => qc.invalidateQueries({ queryKey: ['subsidiary-accounts'] });
  const create = useMutation({
    mutationFn: () => accountApi.createSubsidiaryAccount({ slType: form.slType, code: form.code.trim().toUpperCase(), name: form.name.trim() }),
    onSuccess: () => { setForm({ ...form, code: '', name: '' }); refresh(); },
  });
  const update = useMutation({
    mutationFn: (v: { s: SubsidiaryAccount; name: string; status: string }) => accountApi.updateSubsidiaryAccount(v.s, v.name, v.status),
    onSuccess: () => { setEditing(null); refresh(); },
  });

  return (
    <>
      <PageHeader title="Subsidiary accounts" crumbs={[{ label: 'General ledger' }, { label: 'Subsidiary accounts' }]} />
      <p className="mb-4 max-w-3xl text-sm text-slate-600">
        The individual employees, suppliers, customers and other parties that a control account is broken down into. A voucher line on
        an account marked SL in the chart of accounts must name one. Supplier and customer subsidiaries are created with the supplier or
        customer; employees and other parties are added here.
      </p>

      {canManage && (
        <section className="mb-4 rounded-xl border border-slate-200/80 bg-white p-5 shadow-xs">
          <h2 className="mb-3 font-semibold">Add a subsidiary account</h2>
          <ErrorAlert error={create.error} />
          <form className="grid gap-3 md:grid-cols-[12rem_14rem_1fr_auto] md:items-end"
            onSubmit={(e) => { e.preventDefault(); create.mutate(); }}>
            <label className="text-sm font-medium text-slate-700">Type
              <Select value={form.slType} onChange={(e) => setForm({ ...form, slType: e.target.value })}
                className="block w-full rounded-lg border border-slate-300 px-3 py-2 text-sm shadow-xs">
                {MANAGED.map((t) => <option key={t} value={t}>{TYPES[t]}</option>)}
              </Select>
            </label>
            <Field label="Code" hint="e.g. the employee number" value={form.code} maxLength={40}
              onChange={(e) => setForm({ ...form, code: e.target.value.toUpperCase() })} />
            <Field label="Name" value={form.name} maxLength={200} onChange={(e) => setForm({ ...form, name: e.target.value })} />
            <Button type="submit" busy={create.isPending} disabled={!form.code.trim() || !form.name.trim()}>Add</Button>
          </form>
        </section>
      )}

      <div className="mb-4 flex flex-wrap gap-3">
        <input aria-label="Search subsidiary accounts" placeholder="Search code or name" value={q}
          onChange={(e) => { setQ(e.target.value); setPage(0); }}
          className="w-72 rounded-lg border border-slate-300 px-3 py-2 text-sm shadow-xs" />
        <Select aria-label="Type filter" value={slType} onChange={(e) => { setSlType(e.target.value); setPage(0); }}
          className="rounded-lg border border-slate-300 px-3 py-2 text-sm shadow-xs">
          <option value="">All types</option>
          {Object.entries(TYPES).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
        </Select>
      </div>
      <ErrorAlert error={list.error ?? update.error} />
      {list.isPending ? <Spinner /> : list.data && (
        <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs">
          <table className="min-w-full text-left text-sm">
            <thead className="border-b border-slate-200 bg-slate-50/80 text-[11px] font-semibold uppercase tracking-wider text-slate-500">
              <tr><th className="px-4 py-2">Type</th><th className="px-4 py-2">Code</th><th className="px-4 py-2">Name</th>
                <th className="px-4 py-2">Status</th><th className="px-4 py-2" /></tr>
            </thead>
            <tbody>
              {list.data.items.map((s) => {
                const managed = canManage && MANAGED.includes(s.slType);
                const isEditing = editing?.s.id === s.id;
                return (
                  <tr key={s.id} className="border-t border-slate-100">
                    <td className="px-4 py-2">{TYPES[s.slType] ?? s.slType}</td>
                    <td className="px-4 py-2 font-mono text-xs">{s.code}</td>
                    <td className="px-4 py-2">
                      {isEditing ? (
                        <input aria-label={`Name of ${s.code}`} value={editing.name} maxLength={200}
                          onChange={(e) => setEditing({ ...editing, name: e.target.value })}
                          className="w-full rounded-lg border border-slate-300 px-3 py-1 text-sm shadow-xs" />
                      ) : s.name}
                    </td>
                    <td className="px-4 py-2"><Badge tone={s.status === 'ACTIVE' ? 'green' : 'slate'}>{s.status}</Badge></td>
                    <td className="space-x-3 whitespace-nowrap px-4 py-2 text-right">
                      {managed && !isEditing && <>
                        <button type="button" className="text-brand-700 hover:underline" onClick={() => setEditing({ s, name: s.name })}>Rename</button>
                        <button type="button" className="text-brand-700 hover:underline" disabled={update.isPending}
                          onClick={() => update.mutate({ s, name: s.name, status: s.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE' })}>
                          {s.status === 'ACTIVE' ? 'Deactivate' : 'Activate'}</button>
                      </>}
                      {isEditing && <>
                        <button type="button" className="text-brand-700 hover:underline" disabled={!editing.name.trim() || update.isPending}
                          onClick={() => update.mutate({ s, name: editing.name.trim(), status: s.status })}>Save</button>
                        <button type="button" className="text-slate-600 hover:underline" onClick={() => setEditing(null)}>Cancel</button>
                      </>}
                      {!managed && MANAGED.indexOf(s.slType) < 0 && (
                        <span className="text-xs text-slate-500">Kept on the {s.slType === 'SUPPLIER' ? 'supplier' : 'customer'} page</span>)}
                    </td>
                  </tr>
                );
              })}
              {list.data.items.length === 0 && (
                <tr><td colSpan={5} className="px-4 py-6 text-center text-slate-500">No subsidiary accounts match.</td></tr>)}
            </tbody>
          </table>
        </div>
      )}
      {list.data && <Pagination page={list.data.page} totalPages={list.data.totalPages} totalItems={list.data.totalItems} onPage={setPage} />}
    </>
  );
}
