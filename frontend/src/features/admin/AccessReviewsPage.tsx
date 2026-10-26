import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { governanceApi } from '../../api/endpoints';
import type { AccessReviewItem } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Field } from '../../components/Field';
import { PageHeader } from '../../components/PageHeader';
import { Spinner } from '../../components/Spinner';
import { StatusBadge } from '../../components/StatusBadge';
import { formatDateTime } from '../../utils/format';

/** Periodic access review cycles (FR-RBAC-08). */
export function AccessReviewsPage() {
  const { hasPermission } = useAuth();
  const navigate = useNavigate();
  const cycles = useQuery({ queryKey: ['access-review-cycles'], queryFn: governanceApi.cycles });
  const [form, setForm] = useState({ name: '', dueDate: '' });
  const start = useMutation({ mutationFn: () => governanceApi.startCycle(form.name.trim(), form.dueDate),
    onSuccess: (c) => navigate(`/admin/access-reviews/${c.id}`) });
  const open = (cycles.data ?? []).some((c) => c.status === 'OPEN');
  return (
    <>
      <PageHeader title="Access reviews" crumbs={[{ label: 'Administration' }, { label: 'Access reviews' }]} />
      <p className="mb-4 text-sm text-slate-600">A review cycle lists every active internal user with their roles. Reviewers confirm or ask for
        changes (never for their own access), user administrators apply the changes, and the cycle closes when all are done. How often
        cycles run is MIAA's decision (C-08, C-19).</p>
      {hasPermission('ACCESS_REVIEW_MANAGE') && !open && (
        <section className="mb-4 flex flex-wrap items-end gap-3 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
          <Field label="Cycle name" value={form.name} maxLength={200} placeholder="e.g. 2026 Q4 access review" onChange={(e) => setForm({ ...form, name: e.target.value })} />
          <Field label="Due date" type="date" value={form.dueDate} onChange={(e) => setForm({ ...form, dueDate: e.target.value })} />
          <Button busy={start.isPending} disabled={!form.name.trim() || !form.dueDate} onClick={() => start.mutate()}>Start review cycle</Button>
          <div className="w-full"><ErrorAlert error={start.error} /></div>
        </section>
      )}
      <ErrorAlert error={cycles.error} />
      {cycles.isPending ? <Spinner /> : (
        <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs"><table className="min-w-full text-sm">
          <thead className="border-b border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="px-3 py-2">Cycle</th><th className="px-3 py-2">Status</th>
            <th className="px-3 py-2">Due</th><th className="px-3 py-2">Reviewed</th><th className="px-3 py-2">Changes to apply</th><th className="px-3 py-2">Started</th></tr></thead>
          <tbody>{(cycles.data ?? []).map((c) => (
            <tr key={c.id} className="border-t border-slate-100">
              <td className="px-3 py-2"><Link className="text-brand-700 hover:underline" to={`/admin/access-reviews/${c.id}`}>{c.name}</Link></td>
              <td className="px-3 py-2"><StatusBadge status={c.status} /></td><td className="px-3 py-2">{c.dueDate}</td>
              <td className="px-3 py-2">{c.reviewed} of {c.total}</td><td className="px-3 py-2">{c.actionsOpen}</td>
              <td className="px-3 py-2">{c.startedBy}, {formatDateTime(c.startedAt)}</td></tr>))}
            {cycles.data?.length === 0 && <tr><td colSpan={6} className="px-4 py-6 text-center text-slate-500">No review cycles yet.</td></tr>}</tbody>
        </table></div>
      )}
    </>
  );
}

export function AccessReviewCyclePage() {
  const { id = '' } = useParams();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['access-review-cycle', id], queryFn: () => governanceApi.cycle(id) });
  const [notes, setNotes] = useState<Record<string, string>>({});
  const [filter, setFilter] = useState<'todo' | 'all'>('todo');
  const refresh = () => { void qc.invalidateQueries({ queryKey: ['access-review-cycle', id] }); void qc.invalidateQueries({ queryKey: ['inbox'] }); };
  const review = useMutation({ mutationFn: ({ i, outcome }: { i: AccessReviewItem; outcome: string }) =>
    governanceApi.review(i, outcome, (notes[i.id] ?? '').trim()), onSuccess: refresh });
  const act = useMutation({ mutationFn: (i: AccessReviewItem) => governanceApi.recordAction(i, (notes[i.id] ?? '').trim()), onSuccess: refresh });
  const close = useMutation({ mutationFn: () => governanceApi.closeCycle(q.data!.summary), onSuccess: refresh });
  if (q.isPending) return <Spinner />;
  if (q.error) return <ErrorAlert error={q.error} />;
  const { summary: s, items } = q.data;
  const shown = filter === 'all' ? items : items.filter((i) => i.availableActions.length > 0);
  return (
    <>
      <PageHeader title={s.name} crumbs={[{ label: 'Administration' }, { label: 'Access reviews', to: '/admin/access-reviews' }, { label: s.name }]}
        actions={q.data.availableActions.includes('CLOSE') && <Button busy={close.isPending} onClick={() => close.mutate()}>Close cycle</Button>} />
      <p className="mb-3 text-sm"><StatusBadge status={s.status} /> Due {s.dueDate} · {s.reviewed} of {s.total} reviewed · {s.actionsOpen} change(s) to apply</p>
      <ErrorAlert error={review.error ?? act.error ?? close.error} />
      <div className="mb-3 flex gap-2 text-sm">
        {([['todo', 'Waiting for me'], ['all', 'Everyone']] as const).map(([v, l]) => (
          <button key={v} aria-pressed={filter === v} onClick={() => setFilter(v)}
            className={`rounded-md px-3 py-1.5 ${filter === v ? 'bg-brand-700 text-white' : 'border border-slate-300 bg-white'}`}>{l}</button>))}
      </div>
      <ul className="space-y-2">
        {shown.map((i) => (
          <li key={i.id} className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-3 text-sm">
            <div className="flex flex-wrap items-center gap-2">
              <Link className="font-medium text-brand-700 hover:underline" to={`/admin/users/${i.userId}`}>{i.displayName} ({i.username})</Link>
              {i.financial && <Badge tone="amber">Financial</Badge>}
              {i.outcome && <Badge tone={i.outcome === 'RETAIN' ? 'green' : 'red'}>{i.outcome}</Badge>}
              {i.actionedAt && <Badge tone="green">Applied</Badge>}
            </div>
            <p className="mt-1">Roles at start: {i.rolesAtStart.join(', ') || 'none'}{i.rolesNow.join(',') !== i.rolesAtStart.join(',') ? ` · now: ${i.rolesNow.join(', ') || 'none'}` : ''}</p>
            {i.reviewer && <p className="text-xs text-slate-500">Reviewed by {i.reviewer} {formatDateTime(i.reviewedAt)}{i.notes ? `: ${i.notes}` : ''}</p>}
            {i.actionedBy && <p className="text-xs text-slate-500">Applied by {i.actionedBy} {formatDateTime(i.actionedAt)}{i.actionNote ? `: ${i.actionNote}` : ''}</p>}
            {i.availableActions.length > 0 && (
              <div className="mt-2 flex flex-wrap items-center gap-2">
                <input aria-label={`Note for ${i.username}`} value={notes[i.id] ?? ''} onChange={(e) => setNotes({ ...notes, [i.id]: e.target.value })}
                  placeholder={i.availableActions.includes('REVIEW') ? 'What should change (for modify or revoke)' : 'What was changed'}
                  className="w-80 rounded-md border border-slate-300 px-2 py-1" />
                {i.availableActions.includes('REVIEW') && <>
                  <button className="text-brand-700 hover:underline" disabled={review.isPending} onClick={() => review.mutate({ i, outcome: 'RETAIN' })}>Retain</button>
                  <button className="text-amber-800 hover:underline" disabled={review.isPending} onClick={() => review.mutate({ i, outcome: 'MODIFY' })}>Modify</button>
                  <button className="text-red-700 hover:underline" disabled={review.isPending} onClick={() => review.mutate({ i, outcome: 'REVOKE' })}>Revoke</button></>}
                {i.availableActions.includes('RECORD_ACTION') && <>
                  <Link className="text-brand-700 hover:underline" to={`/admin/users/${i.userId}`}>Change roles</Link>
                  <button className="text-brand-700 hover:underline" disabled={act.isPending} onClick={() => act.mutate(i)}>Record as applied</button></>}
              </div>
            )}
          </li>
        ))}
        {shown.length === 0 && <li className="rounded-xl border border-slate-200/80 bg-white shadow-xs px-4 py-6 text-center text-slate-500">Nothing waiting for you.</li>}
      </ul>
    </>
  );
}
