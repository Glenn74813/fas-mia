import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router';
import { roleApi } from '../../api/endpoints';
import { Badge } from '../../components/Badge';
import { ErrorAlert } from '../../components/ErrorAlert';
import { PageHeader } from '../../components/PageHeader';
import { Spinner } from '../../components/Spinner';

export function RolesPage() {
  const roles = useQuery({ queryKey: ['roles'], queryFn: roleApi.list });
  return (
    <>
      <PageHeader title="Roles & permissions" crumbs={[{ label: 'Administration' }, { label: 'Roles' }]} />
      <p className="mb-4 max-w-3xl text-sm text-slate-600">Seeded roles are proposals pending AVTECH sign-off of the
        role–permission matrix. Every change is checked against segregation-of-duties rules for all holders of the role.</p>
      <ErrorAlert error={roles.error} />
      {roles.isPending ? <Spinner /> : (
        <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs">
          <table className="min-w-full text-left text-sm">
            <thead className="border-b border-slate-200 bg-slate-50/80 text-[11px] font-semibold uppercase tracking-wider text-slate-500">
              <tr><th className="px-4 py-2">Code</th><th className="px-4 py-2">Name</th><th className="px-4 py-2">Permissions</th><th className="px-4 py-2">Active users</th></tr>
            </thead>
            <tbody>
              {roles.data?.map((r) => (
                <tr key={r.code} className="border-t border-slate-100">
                  <td className="px-4 py-2"><Link className="text-brand-700 hover:underline" to={`/admin/roles/${r.code}`}>{r.code}</Link>
                    {r.systemRole && <span className="ml-2"><Badge>SEEDED</Badge></span>}</td>
                  <td className="px-4 py-2">{r.name}</td>
                  <td className="px-4 py-2">{r.permissionCodes.length}</td>
                  <td className="px-4 py-2">{r.userCount}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
