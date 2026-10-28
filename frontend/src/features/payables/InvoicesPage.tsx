import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Select } from '../../components/Select';
import { useState } from 'react';
import { Link } from 'react-router';
import { invoiceApi } from '../../api/endpoints';
import { useAuth } from '../../auth/AuthContext';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Pagination } from '../../components/Pagination';
import { SortHeader } from '../../components/SortHeader';
import { Spinner } from '../../components/Spinner';
import { StatusBadge } from '../../components/StatusBadge';

export function InvoicesPage() {
  const { hasPermission } = useAuth();
  const [status, setStatus] = useState('');
  const [q, setQ] = useState('');
  const [page, setPage] = useState(0);
  const [sort, setSort] = useState('');
  const onSort = (next: string) => { setSort(next); setPage(0); };
  const list = useQuery({ queryKey: ['invoices', status, q, page, sort],
    queryFn: () => invoiceApi.list({ status, q, page, size: 25, sort: sort || undefined }),
    placeholderData: keepPreviousData });
  return (
    <>
      <PageHeader title="Supplier invoices" crumbs={[{ label: 'Payables' }, { label: 'Invoices' }]}
        actions={hasPermission('AP_INVOICE_PREPARE') && (
          <Link to="/payables/invoices/new" className="rounded-md bg-brand-700 px-4 py-2 text-sm font-medium text-white hover:bg-brand-900">Register invoice</Link>
        )} />
      <div className="mb-4 flex flex-wrap gap-3">
        <input aria-label="Search invoices" placeholder="Invoice number or description" value={q}
          onChange={(e) => { setQ(e.target.value); setPage(0); }} className="w-72 rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm" />
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
              <tr><th className="px-3 py-2">Invoice</th><th className="px-3 py-2">Supplier</th><SortHeader label="Date" field="invoiceDate" sort={sort} onSort={onSort} descFirst />
                <SortHeader label="Due" field="dueDate" sort={sort} onSort={onSort} /><th className="px-3 py-2">Status</th><SortHeader label="Payable" field="amount" sort={sort} onSort={onSort} descFirst align="right" />
                <th className="px-3 py-2 text-right">Open</th></tr>
            </thead>
            <tbody>
              {list.data.items.map((i) => (
                <tr key={i.id} className="border-t border-slate-100">
                  <td className="whitespace-nowrap px-3 py-2"><Link className="text-brand-700 hover:underline" to={`/payables/invoices/${i.id}`}>{i.invoiceNumber}</Link></td>
                  <td className="px-3 py-2">{i.supplierName}</td><td className="whitespace-nowrap px-3 py-2">{i.invoiceDate}</td>
                  <td className="whitespace-nowrap px-3 py-2">{i.dueDate}</td><td className="px-3 py-2"><StatusBadge status={i.status} /></td>
                  <td className="px-3 py-2 text-right"><Money value={i.payableAmount} /></td>
                  <td className="px-3 py-2 text-right"><Money value={i.status === 'POSTED' ? i.openBalance : null} blankZero /></td>
                </tr>
              ))}
              {list.data.items.length === 0 && <tr><td colSpan={7} className="px-4 py-6 text-center text-slate-500">No invoices.</td></tr>}
            </tbody>
          </table>
        </div>
      )}
      {list.data && <Pagination page={list.data.page} totalPages={list.data.totalPages} totalItems={list.data.totalItems} onPage={setPage} />}
    </>
  );
}
