import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Select } from '../../components/Select';
import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { settingsApi, supplierApi } from '../../api/endpoints';
import type { Supplier } from '../../api/types';
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

const EMPTY_FORM = { code: '', name: '', tin: '', address: '', zipCode: '', foreignAddress: '', payeeType: '', vatRegistered: false, defaultEwtCodeId: '',
  lastName: '', firstName: '', middleName: '' };

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
  const [form, setForm] = useState(EMPTY_FORM);
  const [editing, setEditing] = useState<Supplier | null>(null);
  const openNew = () => { setEditing(null); setForm(EMPTY_FORM); setOpen(true); };
  const openEdit = (s: Supplier) => {
    setEditing(s);
    setForm({ code: s.code, name: s.name, tin: s.tin ?? '', address: s.address ?? '', zipCode: s.zipCode ?? '', foreignAddress: s.foreignAddress ?? '',
      payeeType: s.payeeType ?? '', vatRegistered: s.vatRegistered, defaultEwtCodeId: s.defaultEwtCodeId ?? '',
      lastName: s.lastName ?? '', firstName: s.firstName ?? '', middleName: s.middleName ?? '' });
    setOpen(true);
  };
  const save = useMutation({
    mutationFn: () => {
      const details = { name: form.name, tin: form.tin || null, address: form.address || null, zipCode: form.zipCode || null,
        foreignAddress: form.foreignAddress || null, payeeType: form.payeeType || null, vatRegistered: form.vatRegistered, defaultEwtCodeId: form.defaultEwtCodeId || null,
        lastName: form.lastName || null, firstName: form.firstName || null, middleName: form.middleName || null };
      return editing ? supplierApi.update(editing.id, { ...details, version: editing.version, status: editing.status })
        : supplierApi.create({ ...details, code: form.code });
    },
    onSuccess: () => { setOpen(false); qc.invalidateQueries({ queryKey: ['suppliers'] }); },
  });
  const rows = list.data?.items ?? [];
  return (
    <>
      <PageHeader title="Suppliers" crumbs={[{ label: 'Payables' }, { label: 'Suppliers' }]}
        actions={hasPermission('SUPPLIER_MANAGE') && <Button onClick={openNew}>New supplier</Button>} />
      {open && (
        <section className="mb-4 space-y-3 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
          {editing && <h2 className="font-semibold">Edit {editing.code}</h2>}
          <ErrorAlert error={save.error} />
          <div className="grid gap-3 md:grid-cols-3">
            <Field label="Code" disabled={!!editing} value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value })} />
            <Field label="Registered name" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
            <Field label="TIN" hint="000-000-000-00000" value={form.tin} onChange={(e) => setForm({ ...form, tin: e.target.value })} />
            <Field label="Registered address" value={form.address} onChange={(e) => setForm({ ...form, address: e.target.value })} />
            <Field label="ZIP code" hint="4 digits; printed on BIR Form 2307" maxLength={4} value={form.zipCode} onChange={(e) => setForm({ ...form, zipCode: e.target.value })} />
            <Field label="Foreign address, if any" value={form.foreignAddress} onChange={(e) => setForm({ ...form, foreignAddress: e.target.value })} />
            <div className="space-y-1.5">
              <label htmlFor="payee-type" className="block text-sm font-medium text-slate-700">Supplier type</label>
              <Select id="payee-type" value={form.payeeType} onChange={(e) => setForm({ ...form, payeeType: e.target.value })}
                className="block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm">
                <option value="">Not set</option>
                <option value="INDIVIDUAL">Individual (WI tax codes)</option>
                <option value="CORPORATION">Corporation (WC tax codes)</option>
              </Select>
            </div>
            {form.payeeType === 'INDIVIDUAL' && <>
              <Field label="Last name" hint="BIR alphalists list individuals by name" maxLength={30} value={form.lastName}
                onChange={(e) => setForm({ ...form, lastName: e.target.value })} />
              <Field label="First name" maxLength={30} value={form.firstName} onChange={(e) => setForm({ ...form, firstName: e.target.value })} />
              <Field label="Middle name" maxLength={30} value={form.middleName} onChange={(e) => setForm({ ...form, middleName: e.target.value })} />
            </>}
            <div className="space-y-1.5">
              <label htmlFor="ewt" className="block text-sm font-medium text-slate-700">Default withholding tax</label>
              <Select id="ewt" value={form.defaultEwtCodeId} onChange={(e) => setForm({ ...form, defaultEwtCodeId: e.target.value })}
                className="block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm">
                <option value="">None</option>
                {(taxCodes.data ?? []).filter((t) => t.taxType === 'EWT' && t.status === 'ACTIVE').map((t) => <option key={t.id} value={t.id}>{t.code} ({t.rate}){t.atc ? ` ${t.atc}` : ''}</option>)}
              </Select>
            </div>
            <label className="flex items-center gap-2 self-end pb-2 text-sm">
              <input type="checkbox" checked={form.vatRegistered} onChange={(e) => setForm({ ...form, vatRegistered: e.target.checked })} />
              VAT-registered
            </label>
          </div>
          <div className="flex gap-2">
            <Button busy={save.isPending} disabled={!form.code || !form.name} onClick={() => save.mutate()}>Save supplier</Button>
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
                  <td className="whitespace-nowrap px-3 py-2 text-right">{hasPermission('SUPPLIER_MANAGE') && <button type="button" className="mr-3 text-xs text-brand-700 hover:underline" onClick={() => openEdit(s)}>Edit</button>}<Link className="text-xs text-brand-700 hover:underline" to={`/payables/suppliers/${s.id}/bank`}>Bank details</Link></td>
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
