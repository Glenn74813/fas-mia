import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { settingsApi, supplierApi } from '../../api/endpoints';
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

export function SuppliersPage() {
  const { hasPermission } = useAuth();
  const qc = useQueryClient();
  const taxCodes = useQuery({ queryKey: ['tax-codes'], queryFn: settingsApi.taxCodes });
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState('');
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState('');
  useEffect(() => { const t = setTimeout(() => { setSearch(q.trim()); setPage(0); }, 300); return () => clearTimeout(t); }, [q]);
  const [sort, setSort] = useState('');
  const onSort = (next: string) => { setSort(next); setPage(0); };
  const list = useQuery({ queryKey: ['suppliers', search, page, sort], queryFn: () => supplierApi.list(search, page, 25, sort || undefined),
    placeholderData: keepPreviousData });
  const [form, setForm] = useState({ code: '', name: '', tin: '', address: '', vatRegistered: false, defaultEwtCodeId: '' });
  const create = useMutation({
    mutationFn: () => supplierApi.create({ ...form, tin: form.tin || null, address: form.address || null,
      defaultEwtCodeId: form.defaultEwtCodeId || null }),
    onSuccess: () => { setOpen(false); qc.invalidateQueries({ queryKey: ['suppliers'] }); },
  });
  const rows = list.data?.items ?? [];
  return (
    <>
      <PageHeader title="Suppliers" crumbs={[{ label: 'Payables' }, { label: 'Suppliers' }]}
        actions={hasPermission('SUPPLIER_MANAGE') && <Button onClick={() => setOpen(true)}>New supplier</Button>} />
      {open && (
        <section className="mb-4 space-y-3 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
          <ErrorAlert error={create.error} />
          <div className="grid gap-3 md:grid-cols-3">
            <Field label="Code" value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value })} />
            <Field label="Registered name" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
            <Field label="TIN" hint="000-000-000-00000" value={form.tin} onChange={(e) => setForm({ ...form, tin: e.target.value })} />
            <Field label="Address" value={form.address} onChange={(e) => setForm({ ...form, address: e.target.value })} />
            <div className="space-y-1">
              <label htmlFor="ewt" className="block text-sm font-medium text-slate-700">Default withholding tax</label>
              <select id="ewt" value={form.defaultEwtCodeId} onChange={(e) => setForm({ ...form, defaultEwtCodeId: e.target.value })}
                className="block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm">
                <option value="">None</option>
                {(taxCodes.data ?? []).filter((t) => t.taxType === 'EWT').map((t) => <option key={t.id} value={t.id}>{t.code} ({t.rate})</option>)}
              </select>
            </div>
            <label className="flex items-center gap-2 self-end pb-2 text-sm">
              <input type="checkbox" checked={form.vatRegistered} onChange={(e) => setForm({ ...form, vatRegistered: e.target.checked })} />
              VAT-registered
            </label>
          </div>
          <div className="flex gap-2">
            <Button busy={create.isPending} disabled={!form.code || !form.name} onClick={() => create.mutate()}>Save supplier</Button>
            <Button variant="secondary" onClick={() => setOpen(false)}>Cancel</Button>
          </div>
        </section>
      )}
      <input aria-label="Search suppliers" placeholder="Search code, name or TIN" value={q} onChange={(e) => setQ(e.target.value)}
        className="mb-3 w-72 rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm" />
      <ErrorAlert error={list.error} />
      {list.isPending ? <Spinner /> : (
        <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs">
          <table className="min-w-full text-sm">
            <thead className="border-b border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500">
              <tr><SortHeader label="Code" field="code" sort={sort} onSort={onSort} /><SortHeader label="Name" field="name" sort={sort} onSort={onSort} /><th className="px-3 py-2">TIN</th>
                <th className="px-3 py-2">VAT</th><th className="px-3 py-2">Default EWT</th><SortHeader label="Status" field="status" sort={sort} onSort={onSort} />
                <th className="px-3 py-2 text-right">Open payables</th><th /></tr>
            </thead>
            <tbody>
              {rows.map((s) => (
                <tr key={s.id} className="border-t border-slate-100">
                  <td className="px-3 py-2 font-mono">{s.code}</td><td className="px-3 py-2">{s.name}</td>
                  <td className="px-3 py-2">{s.tin ?? '—'}</td><td className="px-3 py-2">{s.vatRegistered ? 'Yes' : 'No'}</td>
                  <td className="px-3 py-2">{s.defaultEwtCode ?? '—'}</td><td className="px-3 py-2"><StatusBadge status={s.status} /></td>
                  <td className="px-3 py-2 text-right"><Money value={s.openBalance} blankZero /></td>
                  <td className="px-3 py-2 text-right"><Link className="text-xs text-brand-700 hover:underline" to={`/payables/suppliers/${s.id}/bank`}>Bank details</Link></td>
                </tr>
              ))}
              {rows.length === 0 && <tr><td colSpan={8} className="px-4 py-6 text-center text-slate-500">No suppliers.</td></tr>}
            </tbody>
          </table>
        </div>
      )}
      {list.data && <Pagination page={list.data.page} totalPages={list.data.totalPages} totalItems={list.data.totalItems} onPage={setPage} />}
    </>
  );
}
