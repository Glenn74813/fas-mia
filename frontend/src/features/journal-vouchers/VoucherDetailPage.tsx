import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { Link, useParams } from 'react-router';
import { voucherApi } from '../../api/endpoints';
import type { VoucherDetail } from '../../api/types';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Spinner } from '../../components/Spinner';
import { StatusBadge } from '../../components/StatusBadge';
import { formatDateTime } from '../../utils/format';

type DialogKind = 'submit' | 'approve' | 'return' | 'reject' | 'cancel' | 'post' | 'reverse';

const DIALOGS: Record<DialogKind, { title: string; confirm: string; commentRequired: boolean; danger?: boolean; text: string }> = {
  submit: { title: 'Submit for review?', confirm: 'Submit', commentRequired: false, text: 'The voucher will be locked and routed for review and approval.' },
  approve: { title: 'Approve this step?', confirm: 'Approve', commentRequired: false, text: 'Your approval is recorded with your name and the time.' },
  return: { title: 'Return to preparer?', confirm: 'Return', commentRequired: true, text: 'The preparer can correct and resubmit it. Explain what must change.' },
  reject: { title: 'Reject this voucher?', confirm: 'Reject', commentRequired: true, danger: true, text: 'Rejection is final; the voucher cannot be resubmitted.' },
  cancel: { title: 'Cancel this voucher?', confirm: 'Cancel voucher', commentRequired: false, danger: true, text: 'The draft is withdrawn and kept for the record.' },
  post: { title: 'Post to the general ledger?', confirm: 'Post', commentRequired: false, text: 'Posting creates an official, immutable journal entry with the next JEV number. It can only be corrected by a reversal.' },
  reverse: { title: 'Reverse this entry?', confirm: 'Create reversal', commentRequired: true, danger: true, text: 'A reversal voucher with debits and credits swapped is created and sent for approval. The original entry is not changed.' },
};

const DONE: Record<DialogKind, string> = {
  submit: 'Voucher submitted for review.', approve: 'Step approved.', return: 'Voucher returned to the preparer.',
  reject: 'Voucher rejected.', cancel: 'Voucher cancelled.', post: 'Voucher posted.', reverse: 'Reversal created.',
};

export function VoucherDetailPage() {
  const { id = '' } = useParams();
  const qc = useQueryClient();
  const query = useQuery({ queryKey: ['voucher', id], queryFn: () => voucherApi.get(id) });
  const [dialog, setDialog] = useState<DialogKind | null>(null);
  const [comment, setComment] = useState('');
  const [reversalDate, setReversalDate] = useState(new Date().toISOString().slice(0, 10));
  const [notice, setNotice] = useState<string | null>(null);
  const postKey = useRef<string | null>(null);
  const dialogRef = useRef<HTMLDialogElement>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  const refresh = (v: VoucherDetail) => {
    qc.setQueryData(['voucher', id], v);
    qc.invalidateQueries({ queryKey: ['vouchers'] });
  };

  const act = useMutation({
    mutationFn: async (kind: DialogKind): Promise<VoucherDetail> => {
      const v = query.data!;
      if (kind === 'post') {
        // One key per posting attempt, reused if the request is retried after a network failure.
        postKey.current ??= crypto.randomUUID();
        return voucherApi.post(v.id, v.version, postKey.current);
      }
      if (kind === 'reverse') return voucherApi.reverse(v.postedEntryId!, reversalDate, comment);
      return voucherApi.act(v.id, kind, v.version, comment);
    },
    onSuccess: (v, kind) => {
      postKey.current = null;
      dialogRef.current?.close();
      setComment('');
      if (kind === 'reverse') {
        setNotice(`Reversal voucher created and submitted for approval.`);
        qc.invalidateQueries({ queryKey: ['voucher', id] });
        qc.invalidateQueries({ queryKey: ['vouchers'] });
        return;
      }
      refresh(v);
      setNotice(kind === 'post' ? `Posted as ${v.voucherNumber}.` : DONE[kind]);
    },
    onError: () => qc.invalidateQueries({ queryKey: ['voucher', id] }),
  });

  const upload = useMutation({
    mutationFn: (file: File) => voucherApi.upload(id, file),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['voucher', id] }),
  });
  const remove = useMutation({
    mutationFn: (docId: string) => voucherApi.removeDocument(docId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['voucher', id] }),
  });

  if (query.isPending) return <Spinner />;
  if (query.error) return <ErrorAlert error={query.error} />;
  const v = query.data;
  const can = (a: string) => v.availableActions.includes(a as never);
  const open = (kind: DialogKind) => {
    setDialog(kind);
    setComment('');
    act.reset();
    setNotice(null);
    dialogRef.current?.showModal();
  };
  const cfg = dialog ? DIALOGS[dialog] : null;
  const commentOk = !cfg?.commentRequired || comment.trim().length >= (dialog === 'reverse' ? 10 : 5);

  return (
    <>
      <PageHeader title={v.voucherNumber ?? 'Draft voucher'}
        crumbs={[{ label: 'Accounting' }, { label: 'Journal vouchers', to: '/journal-vouchers' }, { label: v.voucherNumber ?? 'Draft' }]}
        actions={<div className="flex flex-wrap gap-2">
          {can('EDIT') && <Link to={`/journal-vouchers/${v.id}/edit`} className="rounded-md border border-slate-300 bg-white px-4 py-2 text-sm font-medium hover:bg-slate-100">Edit</Link>}
          {can('SUBMIT') && <Button onClick={() => open('submit')}>Submit</Button>}
          {can('APPROVE') && <Button onClick={() => open('approve')}>Approve step</Button>}
          {can('POST') && <Button onClick={() => open('post')}>Post</Button>}
          {can('RETURN') && <Button variant="secondary" onClick={() => open('return')}>Return</Button>}
          {can('REJECT') && <Button variant="secondary" onClick={() => open('reject')}>Reject</Button>}
          {can('CANCEL') && <Button variant="secondary" onClick={() => open('cancel')}>Cancel</Button>}
          {can('REVERSE') && <Button variant="secondary" onClick={() => open('reverse')}>Reverse</Button>}
        </div>} />

      <div className="space-y-4">
        {notice && <p role="status" className="rounded-md border border-emerald-300 bg-emerald-50 p-3 text-sm">{notice}</p>}
        <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
          <div className="mb-3 flex flex-wrap items-center gap-2">
            <StatusBadge status={v.status} /><Badge>{v.voucherType}</Badge>
            {!v.balanced && <Badge tone="red">NOT BALANCED</Badge>}
            {v.lines.some((l) => l.placeholderAccount) && <Badge tone="amber">PLACEHOLDER ACCOUNTS</Badge>}
          </div>
          <dl className="grid gap-2 text-sm sm:grid-cols-4">
            <dt className="text-slate-500">Accounting date</dt><dd>{v.accountingDate}</dd>
            <dt className="text-slate-500">Period</dt><dd>{v.periodName} <span className="text-xs text-slate-500">({v.periodStatus})</span></dd>
            <dt className="text-slate-500">Prepared by</dt><dd>{v.preparedBy}</dd>
            <dt className="text-slate-500">Reference</dt><dd>{v.supportingReference ?? '—'}</dd>
            <dt className="text-slate-500">Source</dt><dd>{v.sourceType}{v.sourceReference ? ` · ${v.sourceReference}` : ''}</dd>
            {v.reversalOfEntryId && <><dt className="text-slate-500">Reverses</dt>
              <dd><Link className="text-brand-700 hover:underline" to={`/general-ledger/entries/${v.reversalOfEntryId}`}>{v.reversalOfEntryNumber}</Link></dd></>}
            {v.postedEntryId && <><dt className="text-slate-500">Ledger entry</dt>
              <dd><Link className="text-brand-700 hover:underline" to={`/general-ledger/entries/${v.postedEntryId}`}>View posted entry</Link></dd></>}
          </dl>
          <p className="mt-3 text-sm">{v.description}</p>
        </section>

        <section aria-label="Approval progress" className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
          <h2 className="mb-3 font-semibold">Workflow</h2>
          <ol className="flex flex-wrap gap-3">
            {v.workflow.map((s) => (
              <li key={s.order} className={`rounded-md border px-3 py-2 text-sm ${s.state === 'DONE' ? 'border-emerald-300 bg-emerald-50' : s.state === 'CURRENT' ? 'border-blue-400 bg-blue-50' : 'border-slate-200'}`}>
                <span className="font-medium">{s.order}. {s.code}</span>
                <span className="block text-xs text-slate-600">{s.state === 'DONE' ? `${s.actor} · ${formatDateTime(s.decidedAt)}` : s.state === 'CURRENT' ? `awaiting ${s.permission}` : 'pending'}</span>
              </li>
            ))}
          </ol>
        </section>

        <section className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs">
          <table className="min-w-full text-sm">
            <thead className="border-b border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500">
              <tr><th className="px-3 py-2">#</th><th className="px-3 py-2">Account</th><th className="px-3 py-2">Cost centre</th>
                <th className="px-3 py-2">Subsidiary</th><th className="px-3 py-2">Description</th>
                <th className="px-3 py-2 text-right">Debit</th><th className="px-3 py-2 text-right">Credit</th></tr>
            </thead>
            <tbody>
              {v.lines.map((l) => (
                <tr key={l.id} className="border-t border-slate-100">
                  <td className="px-3 py-2 text-slate-500">{l.lineNo}</td>
                  <td className="px-3 py-2"><span className="font-mono">{l.accountCode}</span> {l.accountName}</td>
                  <td className="px-3 py-2">{l.costCenterCode ?? '—'}</td>
                  <td className="px-3 py-2">{l.subsidiaryCode ? `${l.subsidiaryCode} ${l.subsidiaryName}` : '—'}</td>
                  <td className="px-3 py-2">{l.description ?? ''}</td>
                  <td className="px-3 py-2 text-right"><Money value={l.debit} blankZero /></td>
                  <td className="px-3 py-2 text-right"><Money value={l.credit} blankZero /></td>
                </tr>
              ))}
            </tbody>
            <tfoot className="border-t-2 border-slate-300 font-medium">
              <tr><td colSpan={5} className="px-3 py-2 text-right">Totals</td>
                <td className="px-3 py-2 text-right"><Money value={v.totalDebit} /></td>
                <td className="px-3 py-2 text-right"><Money value={v.totalCredit} /></td></tr>
            </tfoot>
          </table>
        </section>

        <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
          <div className="mb-3 flex items-center justify-between">
            <h2 className="font-semibold">Supporting documents {v.requiresAttachment && <span className="text-xs font-normal text-slate-500">(at least one required)</span>}</h2>
            {can('UPLOAD') && (
              <>
                <input ref={fileRef} type="file" className="hidden" accept=".pdf,.png,.jpg,.jpeg,.csv,.xlsx,.docx" aria-label="Choose supporting document"
                  onChange={(e) => { const f = e.target.files?.[0]; if (f) upload.mutate(f); e.target.value = ''; }} />
                <Button variant="secondary" busy={upload.isPending} onClick={() => fileRef.current?.click()}>Attach file</Button>
              </>
            )}
          </div>
          <ErrorAlert error={upload.error ?? remove.error} />
          <ul className="divide-y divide-slate-100 text-sm">
            {v.documents.map((d) => (
              <li key={d.id} className={`flex flex-wrap items-center justify-between gap-2 py-2 ${d.removedAt ? 'text-slate-400 line-through' : ''}`}>
                <span>
                  <button type="button" className="text-brand-700 hover:underline disabled:no-underline" disabled={!!d.removedAt}
                    onClick={() => voucherApi.download(d)}>{d.fileName}</button>
                  <span className="ml-2 text-xs text-slate-500">{(d.sizeBytes / 1024).toFixed(1)} KB · {d.uploadedBy} · {formatDateTime(d.uploadedAt)}</span>
                  {d.scanStatus === 'NOT_SCANNED' && <span className="ml-2"><Badge tone="amber">NOT SCANNED</Badge></span>}
                </span>
                {can('UPLOAD') && !d.removedAt && (
                  <button type="button" className="text-xs text-red-700 hover:underline" onClick={() => remove.mutate(d.id)}>Remove</button>
                )}
              </li>
            ))}
            {v.documents.length === 0 && <li className="py-2 text-slate-500">No documents attached.</li>}
          </ul>
        </section>

        <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
          <h2 className="mb-3 font-semibold">Decision history</h2>
          <ul className="space-y-1 text-sm">
            {v.decisions.map((d) => (
              <li key={d.id}><span className="font-mono text-xs">{formatDateTime(d.decidedAt)}</span> — <strong>{d.action}</strong>
                {d.stepCode ? ` (${d.stepCode})` : ''} by {d.actorUsername}{d.comment ? `: “${d.comment}”` : ''}</li>
            ))}
            {v.decisions.length === 0 && <li className="text-slate-500">Not yet submitted.</li>}
          </ul>
        </section>
      </div>

      <dialog ref={dialogRef} aria-labelledby="voucher-dialog-title" className="m-auto w-full max-w-lg rounded-lg p-0 backdrop:bg-black/40"
        onClose={() => setDialog(null)}>
        {cfg && (
          <div className="space-y-4 p-6">
            <h2 id="voucher-dialog-title" className="text-lg font-semibold">{cfg.title}</h2>
            <p className="text-sm text-slate-700">{cfg.text}</p>
            {dialog === 'post' && <p className="text-sm">Amount: <strong><Money value={v.totalDebit} /></strong> · Period {v.periodName}</p>}
            {dialog === 'reverse' && (
              <label className="block text-sm">Reversal date
                <input type="date" value={reversalDate} onChange={(e) => setReversalDate(e.target.value)}
                  className="mt-1.5 block rounded-lg border border-slate-300 px-3 py-2 shadow-xs" /></label>
            )}
            <label className="block text-sm">{dialog === 'reverse' ? 'Reason' : 'Comment'}{cfg.commentRequired ? ' (required)' : ' (optional)'}
              <textarea value={comment} onChange={(e) => setComment(e.target.value)} rows={3} maxLength={1000}
                className="mt-1.5 block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs" /></label>
            <ErrorAlert error={act.error} />
            <div className="flex justify-end gap-2">
              <Button variant="secondary" onClick={() => dialogRef.current?.close()} disabled={act.isPending}>Close</Button>
              <Button variant={cfg.danger ? 'danger' : 'primary'} busy={act.isPending} disabled={!commentOk}
                onClick={() => act.mutate(dialog!)}>{cfg.confirm}</Button>
            </div>
          </div>
        )}
      </dialog>
    </>
  );
}
