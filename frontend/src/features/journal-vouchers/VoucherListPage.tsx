import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router';
import { voucherApi } from '../../api/endpoints';
import { useAuth } from '../../auth/AuthContext';
import { ErrorAlert } from '../../components/ErrorAlert';
import { PageHeader } from '../../components/PageHeader';
import { Pagination } from '../../components/Pagination';
import { Spinner } from '../../components/Spinner';
import { VoucherTable } from './VoucherTable';

const STATUSES = ['', 'DRAFT', 'RETURNED', 'SUBMITTED', 'APPROVED', 'POSTED', 'REJECTED', 'CANCELLED'];

export function VoucherListPage() {
  const { hasPermission } = useAuth();
  const [status, setStatus] = useState('');
  const [q, setQ] = useState('');
  const [mine, setMine] = useState(false);
  const [page, setPage] = useState(0);
  const [sort, setSort] = useState('');
  const onSort = (next: string) => { setSort(next); setPage(0); };
  const list = useQuery({
    queryKey: ['vouchers', status, q, mine, page, sort],
    queryFn: () => voucherApi.list({ status, q, mine, page, size: 25, sort: sort || undefined }),
    placeholderData: keepPreviousData,
  });
  return (
    <>
      <PageHeader title="Journal entry vouchers" crumbs={[{ label: 'Accounting' }, { label: 'Journal vouchers' }]}
        actions={hasPermission('JOURNAL_CREATE') && (
          <Link to="/journal-vouchers/new" className="rounded-md bg-brand-700 px-4 py-2 text-sm font-medium text-white hover:bg-brand-900">New voucher</Link>
        )} />
      <div className="mb-4 flex flex-wrap items-center gap-3">
        <input aria-label="Search vouchers" placeholder="Search number or description" value={q}
          onChange={(e) => { setQ(e.target.value); setPage(0); }} className="w-72 rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm" />
        <select aria-label="Status" value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }}
          className="rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm">
          {STATUSES.map((s) => <option key={s} value={s}>{s || 'All statuses'}</option>)}
        </select>
        {hasPermission('JOURNAL_CREATE') && (
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={mine} onChange={(e) => { setMine(e.target.checked); setPage(0); }} /> Prepared by me
          </label>
        )}
      </div>
      <ErrorAlert error={list.error} />
      {list.isPending ? <Spinner /> : list.data && <VoucherTable vouchers={list.data.items} empty="No vouchers match." sort={sort} onSort={onSort} />}
      {list.data && <Pagination page={list.data.page} totalPages={list.data.totalPages} totalItems={list.data.totalItems} onPage={setPage} />}
    </>
  );
}
