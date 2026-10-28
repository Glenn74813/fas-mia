import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Select } from '../../components/Select';
import { useState } from 'react';
import { useParams } from 'react-router';
import { ticketApi } from '../../api/endpoints';
import type { TicketView } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { ActionDialog, type ActionSpec } from '../../components/ActionDialog';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { DocumentsPanel } from '../../components/DocumentsPanel';
import { ErrorAlert } from '../../components/ErrorAlert';
import { PageHeader } from '../../components/PageHeader';
import { Spinner } from '../../components/Spinner';
import { StatusBadge } from '../../components/StatusBadge';
import { formatDateTime } from '../../utils/format';
import { SEVERITIES, TICKET_TYPES, useTicketCategories } from './TicketsPage';

const SPECS: Record<string, ActionSpec & { label: string }> = {
  ACKNOWLEDGE: { label: 'Acknowledge', title: 'Acknowledge the ticket?', text: 'Counts as the first response.', confirm: 'Acknowledge' },
  START: { label: 'Start work', title: 'Start work?', text: 'The ticket moves to In progress.', confirm: 'Start' },
  SUBMIT_FOR_APPROVAL: { label: 'Submit for approval', title: 'Submit the change for approval?', text: 'The SLA clock pauses while it awaits a decision.',
    confirm: 'Submit', commentRequired: true, commentLabel: 'Proposed change and impact' },
  VALIDATE_STAGING: { label: 'Validate on staging', title: 'Record the staging validation?', text: 'Confirms the change works on staging before release. The assignee cannot validate their own change.',
    confirm: 'Record validation', commentRequired: true, commentLabel: 'What was tested and the result' },
  APPROVE: { label: 'Approve', title: 'Approve the change?', text: 'Work can start once approved.', confirm: 'Approve', commentRequired: true, commentLabel: 'Reason' },
  REJECT: { label: 'Reject', title: 'Reject the change?', text: 'The ticket is closed as rejected.', confirm: 'Reject', commentRequired: true, commentLabel: 'Reason', danger: true },
  HOLD: { label: 'Put on hold', title: 'Put on hold?', text: 'The resolution clock pauses until the ticket resumes.', confirm: 'Hold', commentRequired: true, commentLabel: 'Waiting for' },
  RESUME: { label: 'Resume', title: 'Resume the ticket?', text: 'The deadline is extended by the time spent on hold.', confirm: 'Resume' },
  RESOLVE: { label: 'Resolve', title: 'Mark resolved?', text: 'The requester confirms it or reopens it within the reopen period; otherwise it closes automatically as unconfirmed.',
    confirm: 'Resolve', commentRequired: true, commentLabel: 'Resolution' },
  REOPEN: { label: 'Reopen', title: 'Reopen the ticket?', text: 'Support continues work.', confirm: 'Reopen', commentRequired: true, commentLabel: 'Why the resolution did not work' },
  CLOSE: { label: 'Confirm and close', title: 'Confirm the resolution and close the ticket?', text: 'Closed tickets cannot be reopened.', confirm: 'Close ticket' },
  CANCEL: { label: 'Cancel', title: 'Cancel the ticket?', text: 'Support stops work on it.', confirm: 'Cancel ticket', commentRequired: true, commentLabel: 'Reason', danger: true },
};

function minutes(m: number | null) {
  if (m === null) return '—';
  const a = Math.abs(m);
  const text = a >= 1440 ? `${Math.floor(a / 1440)}d ${Math.floor((a % 1440) / 60)}h` : a >= 60 ? `${Math.floor(a / 60)}h ${a % 60}m` : `${a}m`;
  return m < 0 ? `${text} overdue` : `${text} left`;
}

export function TicketDetailPage() {
  const { id = '' } = useParams();
  const { hasPermission } = useAuth();
  const qc = useQueryClient();
  const query = useQuery({ queryKey: ['ticket', id], queryFn: () => ticketApi.get(id), refetchInterval: 60_000 });
  const [dialog, setDialog] = useState<string | null>(null);
  const [comment, setComment] = useState('');
  const [internal, setInternal] = useState(false);
  const [assignee, setAssignee] = useState('');
  const [extra, setExtra] = useState({ rollbackPlan: '', releaseNotes: '', releaseReference: '', satisfaction: '' });
  const categories = useTicketCategories();
  const attach = useMutation({ mutationFn: (file: File) => ticketApi.attach(id, file), onSuccess: (t) => qc.setQueryData(['ticket', id], t) });
  const remove = useMutation({ mutationFn: (doc: string) => ticketApi.removeDocument(doc), onSuccess: () => qc.invalidateQueries({ queryKey: ['ticket', id] }) });
  const done = (t: TicketView) => { qc.setQueryData(['ticket', id], t); qc.invalidateQueries({ queryKey: ['tickets'] }); setDialog(null); };
  const act = useMutation({ mutationFn: ({ action, extra }: { action: string; extra?: Parameters<typeof ticketApi.act>[2] }) =>
    ticketApi.act(query.data!, action, extra), onSuccess: done, onError: () => qc.invalidateQueries({ queryKey: ['ticket', id] }) });
  if (query.isPending) return <Spinner />;
  if (query.error) return <ErrorAlert error={query.error} />;
  const t = query.data;
  const can = (a: string) => t.availableActions.includes(a);
  const s = t.sla;
  const change = t.change !== null;
  const openForFiles = !['CLOSED', 'REJECTED', 'CANCELLED'].includes(t.status) && (can('COMMENT') || can('CLOSE'));
  const classify = (field: 'severity' | 'categoryId', value: string) => {
    const reason = window.prompt('Reason for the change');
    if (reason) act.mutate({ action: 'CLASSIFY', extra: { [field]: value, comment: reason } });
  };
  const dialogExtra = () => {
    if (dialog === 'SUBMIT_FOR_APPROVAL') return { rollbackPlan: extra.rollbackPlan.trim() };
    if (dialog === 'RESOLVE' && change) return { releaseNotes: extra.releaseNotes.trim(), releaseReference: extra.releaseReference.trim() || undefined };
    if (dialog === 'CLOSE') return { satisfaction: extra.satisfaction ? Number(extra.satisfaction) : undefined };
    return {};
  };
  const extraMissing = (dialog === 'SUBMIT_FOR_APPROVAL' && extra.rollbackPlan.trim().length < 10)
    || (dialog === 'RESOLVE' && change && extra.releaseNotes.trim().length < 10);
  return (
    <>
      <PageHeader title={`${t.ticketNumber} — ${t.title}`} crumbs={[{ label: 'Support' }, { label: 'Tickets', to: '/tickets' }, { label: t.ticketNumber }]}
        actions={<div className="flex flex-wrap gap-2">{Object.keys(SPECS).filter(can).map((a) => (
          <Button key={a} variant={['REJECT', 'CANCEL', 'HOLD'].includes(a) ? 'secondary' : 'primary'} onClick={() => { act.reset(); setDialog(a); }}>{SPECS[a]!.label}</Button>))}</div>} />
      <div className="grid gap-4 lg:grid-cols-3">
        <section className="space-y-3 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5 lg:col-span-2">
          <div className="flex items-center gap-2"><StatusBadge status={t.status} /><span className="text-sm text-slate-500">{TICKET_TYPES[t.ticketType]} · {t.priority}
            {t.severity ? ` · ${t.severity}` : ''}{t.category ? ` · ${t.category}` : ''}
            {t.module ? ` · ${t.module}` : ''} · raised by {t.reporter} {formatDateTime(t.createdAt)}</span></div>
          {t.closedAt && t.closureConfirmed !== null && (
            <p className="text-sm">{t.closureConfirmed ? <Badge tone="green">CONFIRMED BY REQUESTER</Badge> : <Badge tone="amber">CLOSED UNCONFIRMED</Badge>}
              {t.satisfaction && <span className="ml-2 text-slate-600">Satisfaction {t.satisfaction}/5</span>}</p>
          )}
          <p className="whitespace-pre-wrap text-sm">{t.description}</p>
          <h2 className="pt-2 font-semibold">History</h2>
          <ol className="space-y-2 text-sm">{t.events.map((e) => (
            <li key={e.seq} className={`rounded-md border p-2 ${e.internal ? 'border-amber-200 bg-amber-50' : 'border-slate-100'}`}>
              <span className="text-xs text-slate-500">{formatDateTime(e.occurredAt)} · {e.actor} · {e.eventType.toLowerCase()}
                {e.toValue ? ` ${e.fromValue ? `${e.fromValue.replace('_', ' ').toLowerCase()} → ` : ''}${e.toValue.replace('_', ' ').toLowerCase()}` : ''}
                {e.internal ? ' · internal' : ''}</span>
              {e.comment && <p className="whitespace-pre-wrap">{e.comment}</p>}</li>))}</ol>
          {can('COMMENT') && (
            <div className="space-y-2">
              <textarea aria-label="Comment" value={comment} onChange={(e) => setComment(e.target.value)} rows={3} maxLength={4000}
                className="block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm" />
              <div className="flex items-center gap-3">
                {hasPermission('TICKET_WORK') && <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={internal}
                  onChange={(e) => setInternal(e.target.checked)} />Internal note (hidden from the requester)</label>}
                <Button variant="secondary" busy={act.isPending && act.variables?.action === 'COMMENT'} disabled={!comment.trim()}
                  onClick={() => act.mutate({ action: 'COMMENT', extra: { comment, internal } }, { onSuccess: () => { setComment(''); setInternal(false); } })}>Add comment</Button>
              </div>
            </div>
          )}
          <ErrorAlert error={!dialog ? act.error : null} />
        </section>
        <aside className="space-y-4">
          {t.change && (
            <section className="space-y-2 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5 text-sm" aria-label="Change control">
              <h2 className="font-semibold">Change control</h2>
              <div><p className="text-xs uppercase text-slate-500">Rollback plan</p><p className="whitespace-pre-wrap">{t.change.rollbackPlan ?? 'Given when submitted for approval.'}</p></div>
              <div><p className="text-xs uppercase text-slate-500">Staging validation</p>
                {t.change.stagingValidatedAt ? <p><Badge tone="green">VALIDATED</Badge> by {t.change.stagingValidatedBy} {formatDateTime(t.change.stagingValidatedAt)}
                  <span className="block whitespace-pre-wrap text-slate-600">{t.change.stagingValidationNote}</span></p>
                  : <p className="text-slate-500">Not yet validated. The requester or an approver validates it, never the assignee.</p>}</div>
              <div><p className="text-xs uppercase text-slate-500">Release notes</p>
                <p className="whitespace-pre-wrap">{t.change.releaseNotes ? `${t.change.releaseReference ? `${t.change.releaseReference}: ` : ''}${t.change.releaseNotes}` : 'Given at release.'}</p></div>
            </section>
          )}
          <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5 text-sm">
            <h2 className="mb-2 font-semibold">Service level {s.paused && <Badge tone="amber">PAUSED</Badge>}</h2>
            {s.policy ? <>
              <p className="mb-2 text-xs text-slate-500">{s.policy}</p>
              <dl className="grid grid-cols-2 gap-1">
                <dt>Response due</dt><dd>{formatDateTime(s.responseDueAt)}</dd>
                <dt>Responded</dt><dd>{s.firstResponseAt ? formatDateTime(s.firstResponseAt) : minutes(s.responseMinutesLeft)}
                  {s.responseBreached && <span className="ml-1"><Badge tone="red">BREACHED</Badge></span>}</dd>
                <dt>Resolution due</dt><dd>{formatDateTime(s.resolutionDueAt)}</dd>
                <dt>Remaining</dt><dd>{t.resolvedAt ? `Resolved ${formatDateTime(t.resolvedAt)}` : minutes(s.resolutionMinutesLeft)}
                  {s.resolutionBreached && <span className="ml-1"><Badge tone="red">BREACHED</Badge></span>}</dd>
              </dl></> : <p className="text-slate-500">No SLA policy is configured for this type and priority in the {t.contractPhase} phase.</p>}
          </section>
          {can('ASSIGN') && (
            <section className="space-y-2 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5 text-sm">
              <p>Assignee: <span className="font-medium">{t.assignee ?? 'unassigned'}</span></p>
              <div className="flex gap-2"><input aria-label="Assign to username" placeholder="username" value={assignee} onChange={(e) => setAssignee(e.target.value)}
                className="w-40 rounded-md border border-slate-300 px-2 py-1" />
                <Button variant="secondary" disabled={!assignee.trim()} onClick={() => act.mutate({ action: 'ASSIGN', extra: { assignee } }, { onSuccess: () => setAssignee('') })}>Assign</Button></div>
              {can('PRIORITY') && <label className="block">Priority
                <Select value={t.priority} onChange={(e) => { const p = e.target.value; const reason = window.prompt('Reason for the priority change');
                  if (reason) act.mutate({ action: 'PRIORITY', extra: { priority: p, comment: reason } }); }} className="ml-2 rounded-md border border-slate-300 px-2 py-1">
                  {['P1', 'P2', 'P3', 'P4'].map((p) => <option key={p}>{p}</option>)}</Select></label>}
              {can('CLASSIFY') && <>
                <label className="block">Severity
                  <Select value={t.severity ?? ''} onChange={(e) => classify('severity', e.target.value)} className="ml-2 rounded-md border border-slate-300 px-2 py-1">
                    {!t.severity && <option value="">—</option>}{Object.keys(SEVERITIES).map((k) => <option key={k}>{k}</option>)}</Select></label>
                <label className="block">Category
                  <Select value={t.categoryId ?? ''} onChange={(e) => classify('categoryId', e.target.value)} className="ml-2 w-48 rounded-md border border-slate-300 px-2 py-1">
                    {!t.categoryId && <option value="">—</option>}
                    {(categories.data ?? []).filter((c) => c.status === 'ACTIVE' || c.id === t.categoryId).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}</Select></label>
              </>}
            </section>
          )}
        </aside>
      </div>
      <div className="mt-4">
        <DocumentsPanel documents={t.documents} canUpload={openForFiles} uploading={attach.isPending} error={attach.error ?? remove.error}
          onUpload={(f) => attach.mutate(f)} onRemove={(d) => remove.mutate(d)} requiredNote="screenshots or files; no passwords or account numbers" />
      </div>
      <ActionDialog spec={dialog ? SPECS[dialog]! : null} busy={act.isPending} error={act.error} onClose={() => setDialog(null)} confirmDisabled={extraMissing}
        onConfirm={(c) => act.mutate({ action: dialog!, extra: { comment: c, ...dialogExtra() } },
          { onSuccess: () => setExtra({ rollbackPlan: '', releaseNotes: '', releaseReference: '', satisfaction: '' }) })}>
        {dialog === 'SUBMIT_FOR_APPROVAL' && <label className="block text-sm">Rollback plan
          <textarea value={extra.rollbackPlan} onChange={(e) => setExtra({ ...extra, rollbackPlan: e.target.value })} rows={3} maxLength={4000}
            className="mt-1.5 block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs" /></label>}
        {dialog === 'RESOLVE' && change && <>
          <label className="block text-sm">Release notes
            <textarea value={extra.releaseNotes} onChange={(e) => setExtra({ ...extra, releaseNotes: e.target.value })} rows={3} maxLength={4000}
              className="mt-1.5 block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs" /></label>
          <label className="block text-sm">Release reference (optional)
            <input value={extra.releaseReference} onChange={(e) => setExtra({ ...extra, releaseReference: e.target.value })} maxLength={60}
              className="mt-1.5 block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs" /></label>
        </>}
        {dialog === 'CLOSE' && <label className="block text-sm">Satisfaction (optional)
          <Select value={extra.satisfaction} onChange={(e) => setExtra({ ...extra, satisfaction: e.target.value })}
            className="mt-1.5 block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs">
            <option value="">No rating</option>{[5, 4, 3, 2, 1].map((n) => <option key={n} value={n}>{n} of 5</option>)}</Select></label>}
      </ActionDialog>
    </>
  );
}
