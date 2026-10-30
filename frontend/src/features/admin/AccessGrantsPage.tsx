import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router';
import { governanceApi } from '../../api/endpoints';
import type { RoleGrant } from '../../api/types';
import { ErrorAlert } from '../../components/ErrorAlert';
import { PageHeader } from '../../components/PageHeader';
import { Pagination } from '../../components/Pagination';
import { Spinner } from '../../components/Spinner';
import { StatusBadge } from '../../components/StatusBadge';
import { formatDateTime } from '../../utils/format';

/** Grants of roles carrying financial permissions, held for a second person's approval (C-19). */
export function AccessGrantsPage() {
  const qc = useQueryClient();
  const [status, setStatus] = useState('PENDING');
  const [page, setPage] = useState(0);
  const [notes, setNotes] = useState<Record<string, string>>({});
  const list = useQuery({ queryKey: ['role-grants', status, page], queryFn: () => governanceApi.grants(status || undefined, page),
    placeholderData: keepPreviousData });
  const refresh = () => { void qc.invalidateQueries({ queryKey: ['role-grants'] }); void qc.invalidateQueries({ queryKey: ['inbox'] }); };
  const decide = useMutation({ mutationFn: ({ g, action }: { g: RoleGrant; action: 'approve' | 'reject' }) =>
    governanceApi.decideGrant(g, action, (notes[g.id] ?? '').trim()), onSuccess: refresh });
  const cancel = useMutation({ mutationFn: (g: RoleGrant) => governanceApi.cancelGrant(g), onSuccess: refresh });
  return (
    <>
      <PageHeader title="Financial access grants" crumbs={[{ label: 'Administration' }, { label: 'Access grants' }]} />
      <p className="mb-4 text-sm text-slate-600">Adding a role that carries financial permissions waits here until a second person approves it.
        Neither the administrator who asked nor the user concerned can decide it. Approval rules are proposed pending AVTECH sign-off (C-19).</p>
      <div className="mb-3 flex gap-2 text-sm">
        {[['PENDING', 'Waiting'], ['APPROVED', 'Approved'], ['REJECTED', 'Rejected'], ['CANCELLED', 'Withdrawn'], ['', 'All']].map(([v, l]) => (
          <button key={v} aria-pressed={status === v} onClick={() => { setStatus(v!); setPage(0); }}
            className={`rounded-md px-3 py-1.5 ${status === v ? 'bg-brand-700 text-white' : 'border border-slate-300 bg-white'}`}>{l}</button>))}
      </div>
      <ErrorAlert error={list.error ?? decide.error ?? cancel.error} />
      {list.isPending ? <Spinner /> : (
        <ul className="space-y-3">
          {(list.data?.items ?? []).map((g) => (
            <li key={g.id} className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-4 text-sm">
              <div className="flex flex-wrap items-center gap-2"><StatusBadge status={g.status} />
                <Link className="font-medium text-brand-700 hover:underline" to={`/admin/users/${g.userId}`}>{g.displayName} ({g.username})</Link></div>
              <p className="mt-1">Adds <strong>{g.financialAdded.join(', ')}</strong> · roles now {g.rolesBefore.join(', ') || 'none'} → requested {g.rolesRequested.join(', ')}</p>
              <p className="text-xs text-slate-500">Requested by {g.requestedBy} {formatDateTime(g.requestedAt)}
                {g.decidedBy ? ` · ${g.status.toLowerCase()} by ${g.decidedBy} ${formatDateTime(g.decidedAt)}${g.decisionNote ? `: ${g.decisionNote}` : ''}` : ''}</p>
              {g.availableActions.length > 0 && (
                <div className="mt-2 flex flex-wrap items-center gap-2">
                  {g.availableActions.includes('APPROVE') && <>
                    <input aria-label={`Note for ${g.username}`} placeholder="Basis for the decision (required to reject)" value={notes[g.id] ?? ''}
                      onChange={(e) => setNotes({ ...notes, [g.id]: e.target.value })} className="w-80 rounded-md border border-slate-300 px-2 py-1" />
                    <button className="text-brand-700 hover:underline" disabled={decide.isPending} onClick={() => decide.mutate({ g, action: 'approve' })}>Approve</button>
                    <button className="text-red-700 hover:underline disabled:text-slate-400" disabled={decide.isPending || (notes[g.id] ?? '').trim().length < 5}
                      onClick={() => decide.mutate({ g, action: 'reject' })}>Reject</button></>}
                  {g.availableActions.includes('CANCEL') && <button className="text-slate-700 hover:underline" disabled={cancel.isPending}
                    onClick={() => cancel.mutate(g)}>Withdraw</button>}
                </div>
              )}
            </li>
          ))}
          {list.data?.items.length === 0 && <li className="rounded-xl border border-slate-200/80 bg-white shadow-xs px-4 py-6 text-center text-slate-500">No grants.</li>}
        </ul>
      )}
      {list.data && <Pagination page={list.data.page} totalPages={list.data.totalPages} totalItems={list.data.totalItems} onPage={setPage} />}
    </>
  );
}
