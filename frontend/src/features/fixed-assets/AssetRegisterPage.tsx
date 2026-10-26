import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router';
import { assetApi } from '../../api/endpoints';
import { useAuth } from '../../auth/AuthContext';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Pagination } from '../../components/Pagination';
import { Spinner } from '../../components/Spinner';
import { StatusBadge } from '../../components/StatusBadge';

export function AssetRegisterPage() {
  const { hasPermission } = useAuth();
  const [status, setStatus] = useState('');
  const [categoryId, setCategory] = useState('');
  const [q, setQ] = useState('');
  const categories = useQuery({ queryKey: ['asset-categories'], queryFn: assetApi.categories });
  const [page, setPage] = useState(0);
  const list = useQuery({ queryKey: ['assets', status, categoryId, q, page], queryFn: () => assetApi.list({ status, categoryId, q, page }),
    placeholderData: keepPreviousData });
  return (
    <>
      <PageHeader title="Asset register" crumbs={[{ label: 'Fixed assets' }, { label: 'Register' }]}
        actions={hasPermission('FA_MANAGE') && (
          <Link to="/fixed-assets/new" className="rounded-md bg-brand-700 px-4 py-2 text-sm font-medium text-white hover:bg-brand-900">Register asset</Link>
        )} />
      <div className="mb-4 flex flex-wrap gap-3">
        <input aria-label="Search assets" placeholder="Number, description or serial" value={q} onChange={(e) => { setQ(e.target.value); setPage(0); }}
          className="w-72 rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm" />
        <select aria-label="Category" value={categoryId} onChange={(e) => { setCategory(e.target.value); setPage(0); }} className="rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm">
          <option value="">All categories</option>{(categories.data ?? []).map((c) => <option key={c.id} value={c.id}>{c.code}</option>)}</select>
        <select aria-label="Status" value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }} className="rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm">
          {['', 'DRAFT', 'ACTIVE', 'FULLY_DEPRECIATED', 'DISPOSED', 'CANCELLED'].map((s) => <option key={s} value={s}>{s ? s.replace('_', ' ') : 'All statuses'}</option>)}</select>
      </div>
      <ErrorAlert error={list.error} />
      {list.isPending ? <Spinner /> : (
        <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs">
          <table className="min-w-full text-sm">
            <thead className="border-b border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="px-3 py-2">Asset</th><th className="px-3 py-2">Description</th>
              <th className="px-3 py-2">Category</th><th className="px-3 py-2">Location / custodian</th><th className="px-3 py-2">In service</th>
              <th className="px-3 py-2">Status</th><th className="px-3 py-2 text-right">Cost</th><th className="px-3 py-2 text-right">Accum. depr.</th>
              <th className="px-3 py-2 text-right">Net book value</th></tr></thead>
            <tbody>{(list.data?.items ?? []).map((a) => (
              <tr key={a.id} className="border-t border-slate-100">
                <td className="whitespace-nowrap px-3 py-2"><Link className="text-brand-700 hover:underline" to={`/fixed-assets/${a.id}`}>{a.assetNumber ?? '(draft)'}</Link></td>
                <td className="px-3 py-2">{a.description}</td><td className="px-3 py-2">{a.categoryCode}</td>
                <td className="px-3 py-2">{[a.location, a.custodian].filter(Boolean).join(' / ') || '—'}</td><td className="px-3 py-2">{a.inServiceDate}</td>
                <td className="px-3 py-2"><StatusBadge status={a.status} /></td><td className="px-3 py-2 text-right"><Money value={a.cost} /></td>
                <td className="px-3 py-2 text-right"><Money value={a.accumulatedDepreciation} blankZero /></td>
                <td className="px-3 py-2 text-right"><Money value={a.netBookValue} /></td></tr>))}
              {list.data?.items.length === 0 && <tr><td colSpan={9} className="px-4 py-6 text-center text-slate-500">No assets.</td></tr>}</tbody>
          </table>
        </div>
      )}
      {list.data && <Pagination page={list.data.page} totalPages={list.data.totalPages} totalItems={list.data.totalItems} onPage={setPage} />}
    </>
  );
}
