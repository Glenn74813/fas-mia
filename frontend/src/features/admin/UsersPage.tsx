import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Select } from '../../components/Select';
import { useState } from 'react';
import { Link } from 'react-router';
import { userApi } from '../../api/endpoints';
import { useAuth } from '../../auth/AuthContext';
import { Badge } from '../../components/Badge';
import { ErrorAlert } from '../../components/ErrorAlert';
import { PageHeader } from '../../components/PageHeader';
import { Pagination } from '../../components/Pagination';
import { SortHeader } from '../../components/SortHeader';
import { Spinner } from '../../components/Spinner';
import { formatDateTime } from '../../utils/format';


export function UsersPage() {
  const { hasPermission } = useAuth();
  const [q, setQ] = useState('');
  const [status, setStatus] = useState('');
  const [sort, setSort] = useState('username');
  const onSort = (next: string) => { setSort(next); setPage(0); };
  const [page, setPage] = useState(0);
  const query = useQuery({
    queryKey: ['users', q, status, sort, page],
    queryFn: () => userApi.list({ q, status, sort, page, size: 25 }),
    placeholderData: keepPreviousData,
  });

  return (
    <>
      <PageHeader title="Users" crumbs={[{ label: 'Administration' }, { label: 'Users' }]}
        actions={hasPermission('USER_ADMINISTER') && (
          <Link to="/admin/users/new" className="rounded-md bg-brand-700 px-4 py-2 text-sm font-medium text-white hover:bg-brand-900">New user</Link>
        )} />
      <div className="mb-4 flex flex-wrap gap-3">
        <input aria-label="Search users" placeholder="Search name, username or e-mail" value={q}
          onChange={(e) => { setQ(e.target.value); setPage(0); }}
          className="w-72 rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm" />
        <Select aria-label="Status filter" value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }}
          className="rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm">
          <option value="">All statuses</option><option value="ACTIVE">Active</option><option value="INACTIVE">Inactive</option>
        </Select>
      </div>
      <ErrorAlert error={query.error} />
      {query.isPending ? <Spinner /> : query.data && (
        <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs">
          <table className="min-w-full text-left text-sm">
            <thead className="border-b border-slate-200 bg-slate-50/80 text-[11px] font-semibold uppercase tracking-wider text-slate-500">
              <tr><SortHeader label="Username" field="username" sort={sort} onSort={onSort} /><SortHeader label="Name" field="displayName" sort={sort} onSort={onSort} /><th className="px-4 py-2">Type</th>
                <th className="px-4 py-2">Status</th><SortHeader label="Last sign-in" field="lastLoginAt" sort={sort} onSort={onSort} descFirst /></tr>
            </thead>
            <tbody>
              {query.data.items.map((u) => (
                <tr key={u.id} className="border-t border-slate-100">
                  <td className="px-4 py-2"><Link className="text-brand-700 hover:underline" to={`/admin/users/${u.id}`}>{u.username}</Link></td>
                  <td className="px-4 py-2">{u.displayName}</td>
                  <td className="px-4 py-2">{u.userType}</td>
                  <td className="space-x-1 px-4 py-2">
                    <Badge tone={u.status === 'ACTIVE' ? 'green' : 'slate'}>{u.status}</Badge>
                    {u.locked && <Badge tone="red">LOCKED</Badge>}
                  </td>
                  <td className="px-4 py-2">{formatDateTime(u.lastLoginAt)}</td>
                </tr>
              ))}
              {query.data.items.length === 0 && <tr><td colSpan={5} className="px-4 py-6 text-center text-slate-500">No users match.</td></tr>}
            </tbody>
          </table>
        </div>
      )}
      {query.data && <Pagination page={query.data.page} totalPages={query.data.totalPages}
        totalItems={query.data.totalItems} onPage={setPage} />}
    </>
  );
}
