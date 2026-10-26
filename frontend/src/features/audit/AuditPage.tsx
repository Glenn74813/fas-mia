import { keepPreviousData, useMutation, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { auditApi } from '../../api/endpoints';
import { useAuth } from '../../auth/AuthContext';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { PageHeader } from '../../components/PageHeader';
import { Pagination } from '../../components/Pagination';
import { Spinner } from '../../components/Spinner';
import { formatDateTime } from '../../utils/format';

type Trail = 'audit' | 'security';

export function AuditPage() {
  const { hasPermission } = useAuth();
  const canAudit = hasPermission('AUDIT_LOG_VIEW');
  const [trail, setTrail] = useState<Trail>(canAudit ? 'audit' : 'security');
  const [action, setAction] = useState('');
  const [page, setPage] = useState(0);
  const events = useQuery({
    queryKey: ['events', trail, action, page],
    queryFn: () => (trail === 'audit' ? auditApi.auditEvents : auditApi.securityEvents)({ action: action || undefined, page, size: 50 }),
    placeholderData: keepPreviousData,
  });
  const verify = useMutation({ mutationFn: auditApi.verify });

  return (
    <>
      <PageHeader title="Audit trail" crumbs={[{ label: 'Audit' }]}
        actions={canAudit && <Button variant="secondary" busy={verify.isPending} onClick={() => verify.mutate()}>Verify integrity</Button>} />
      <ErrorAlert error={verify.error} />
      {verify.data && (
        <div role="status" className={`mb-4 rounded-md border p-3 text-sm ${verify.data.audit.intact && verify.data.security.intact ? 'border-emerald-300 bg-emerald-50' : 'border-red-400 bg-red-50'}`}>
          Audit chain: {verify.data.audit.intact ? 'intact' : `BROKEN at #${verify.data.audit.firstInvalidSequence}`} ({verify.data.audit.rowsChecked} rows).
          {' '}Security chain: {verify.data.security.intact ? 'intact' : `BROKEN at #${verify.data.security.firstInvalidSequence}`} ({verify.data.security.rowsChecked} rows).
        </div>
      )}
      <div className="mb-4 flex flex-wrap gap-3">
        <div role="tablist" aria-label="Event trail" className="flex gap-1">
          {canAudit && <Button role="tab" aria-selected={trail === 'audit'} variant={trail === 'audit' ? 'primary' : 'secondary'}
            onClick={() => { setTrail('audit'); setPage(0); }}>Business &amp; admin</Button>}
          {hasPermission('SECURITY_EVENT_VIEW') && <Button role="tab" aria-selected={trail === 'security'} variant={trail === 'security' ? 'primary' : 'secondary'}
            onClick={() => { setTrail('security'); setPage(0); }}>Security</Button>}
        </div>
        <input aria-label="Filter by action" placeholder="Action, e.g. USER_CREATED" value={action}
          onChange={(e) => { setAction(e.target.value.toUpperCase().replace(/[^A-Z_]/g, '')); setPage(0); }}
          className="w-64 rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm" />
      </div>
      <ErrorAlert error={events.error} />
      {events.isPending ? <Spinner /> : events.data && (
        <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs">
          <table className="min-w-full text-left text-sm">
            <thead className="border-b border-slate-200 bg-slate-50/80 text-[11px] font-semibold uppercase tracking-wider text-slate-500">
              <tr><th className="px-3 py-2">#</th><th className="px-3 py-2">Time</th><th className="px-3 py-2">Actor</th>
                <th className="px-3 py-2">Action</th><th className="px-3 py-2">Resource</th><th className="px-3 py-2">Outcome</th><th className="px-3 py-2">Details</th></tr>
            </thead>
            <tbody>
              {events.data.items.map((e) => (
                <tr key={e.eventId} className="border-t border-slate-100 align-top">
                  <td className="px-3 py-2 text-slate-500">{e.sequence}</td>
                  <td className="whitespace-nowrap px-3 py-2">{formatDateTime(e.occurredAt)}</td>
                  <td className="px-3 py-2">{e.actorUsername ?? '—'}{e.actorRoles && <span className="block text-xs text-slate-500">{e.actorRoles.split(',').join(', ')}</span>}</td>
                  <td className="px-3 py-2 font-mono text-xs">{e.action}</td>
                  <td className="px-3 py-2 text-xs">{e.resourceType}{e.resourceId ? ` ${e.resourceId}` : ''}</td>
                  <td className="px-3 py-2"><Badge tone={e.outcome === 'SUCCESS' ? 'green' : 'red'}>{e.outcome}</Badge>
                    {e.severity && <span className="ml-1"><Badge tone={e.severity === 'HIGH' ? 'red' : 'slate'}>{e.severity}</Badge></span>}</td>
                  <td className="max-w-xs break-words px-3 py-2 font-mono text-xs">{JSON.stringify(e.details)}</td>
                </tr>
              ))}
              {events.data.items.length === 0 && <tr><td colSpan={7} className="px-4 py-6 text-center text-slate-500">No events.</td></tr>}
            </tbody>
          </table>
        </div>
      )}
      {events.data && <Pagination page={events.data.page} totalPages={events.data.totalPages} totalItems={events.data.totalItems} onPage={setPage} />}
    </>
  );
}
