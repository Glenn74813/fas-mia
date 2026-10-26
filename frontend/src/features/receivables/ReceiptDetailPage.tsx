import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { Link, useParams } from 'react-router';
import { customerApi, receiptApi } from '../../api/endpoints';
import type { ReceiptView } from '../../api/types';
import { ActionDialog, type ActionSpec } from '../../components/ActionDialog';
import { Button } from '../../components/Button';
import { DocumentsPanel } from '../../components/DocumentsPanel';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Field } from '../../components/Field';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Spinner } from '../../components/Spinner';
import { StatusBadge } from '../../components/StatusBadge';
import { formatDateTime } from '../../utils/format';
import { fromCents, isValidAmount, sumCents, toCents } from '../../utils/money';
import { KIND_LABEL } from './BillingsPage';

type Kind = 'post' | 'cancel' | 'apply';

const SPECS: Record<Kind, ActionSpec> = {
  post: { title: 'Post the collection?', text: 'Records Dr cash on hand (and creditable withholding tax) / Cr accounts receivable and unapplied collections.', confirm: 'Post' },
  cancel: { title: 'Cancel this receipt?', text: 'The number stays used and the receipt is kept with your reason.', confirm: 'Cancel receipt',
    commentRequired: true, commentLabel: 'Reason', danger: true },
  apply: { title: 'Apply unapplied amount', text: 'Posts Dr unapplied collections / Cr accounts receivable for the amounts below.', confirm: 'Apply and post' },
};

export function ReceiptDetailPage() {
  const { id = '' } = useParams();
  const qc = useQueryClient();
  const query = useQuery({ queryKey: ['receipt', id], queryFn: () => receiptApi.get(id) });
  const [dialog, setDialog] = useState<Kind | null>(null);
  const [applyDate, setApplyDate] = useState(new Date().toISOString().slice(0, 10));
  const [amounts, setAmounts] = useState<Record<string, string>>({});
  const r = query.data;
  const open = useQuery({ queryKey: ['open-items', r?.customerId], enabled: dialog === 'apply' && !!r,
    queryFn: () => customerApi.openItems(r!.customerId) });
  const chosen = Object.entries(amounts).filter(([, v]) => v.trim() !== '');
  // One key per application: a retry after a lost response cannot apply the receipt twice.
  const applyKey = useRef<string | null>(null);
  const act = useMutation({
    mutationFn: ({ kind, comment }: { kind: Kind; comment: string }): Promise<ReceiptView> => {
      const v = query.data!.version;
      if (kind === 'post') return receiptApi.post(id, v);
      if (kind === 'cancel') return receiptApi.cancel(id, v, comment);
      applyKey.current ??= crypto.randomUUID();
      return receiptApi.apply(id, v, applyDate, chosen.map(([documentId, amount]) => ({ documentId, amount })), applyKey.current);
    },
    onSuccess: (x) => { applyKey.current = null; qc.setQueryData(['receipt', id], x); qc.invalidateQueries({ queryKey: ['receipts'] });
      qc.invalidateQueries({ queryKey: ['open-items'] }); setDialog(null); setAmounts({}); },
    onError: () => qc.invalidateQueries({ queryKey: ['receipt', id] }),
  });
  const upload = useMutation({ mutationFn: (f: File) => receiptApi.upload(id, f), onSuccess: () => qc.invalidateQueries({ queryKey: ['receipt', id] }) });
  if (query.isPending) return <Spinner />;
  if (query.error) return <ErrorAlert error={query.error} />;
  const rc = query.data;
  const can = (a: string) => rc.availableActions.includes(a);
  const show = (k: Kind) => { act.reset(); setDialog(k); };
  const applyTotal = sumCents(chosen.map(([, v]) => v));
  const applyValid = chosen.length > 0 && chosen.every(([, v]) => isValidAmount(v)) && applyTotal <= (toCents(rc.unappliedAmount) ?? 0n);
  return (
    <>
      <PageHeader title={`Receipt ${rc.receiptNumber}`} crumbs={[{ label: 'Receivables' }, { label: 'Collections', to: '/receivables/receipts' }, { label: rc.receiptNumber }]}
        actions={<div className="flex flex-wrap gap-2">
          {can('POST') && <Button onClick={() => show('post')}>Post</Button>}
          {can('APPLY') && <Button onClick={() => show('apply')}>Apply unapplied</Button>}
          {can('CANCEL') && <Button variant="secondary" onClick={() => show('cancel')}>Cancel</Button>}
        </div>} />
      <div className="space-y-4">
        <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
          <div className="mb-3"><StatusBadge status={rc.status} /></div>
          <dl className="grid gap-2 text-sm sm:grid-cols-4">
            <dt className="text-slate-500">Customer</dt><dd><Link className="text-brand-700 hover:underline" to={`/receivables/customers/${rc.customerId}`}>
              {rc.customerCode} — {rc.customerName}</Link></dd>
            <dt className="text-slate-500">Date</dt><dd>{rc.receiptDate}</dd>
            <dt className="text-slate-500">Method</dt><dd>{rc.paymentMethod.replace('_', ' ')} {rc.paymentReference ?? ''}</dd>
            <dt className="text-slate-500">Recorded by</dt><dd>{rc.recordedBy}</dd>
            {rc.postedEntryId && <><dt className="text-slate-500">Ledger entry</dt>
              <dd><Link className="text-brand-700 hover:underline" to={`/general-ledger/entries/${rc.postedEntryId}`}>{rc.postedEntryNumber}</Link></dd></>}
            {rc.cancelReason && <><dt className="text-slate-500">Cancelled</dt><dd>{rc.cancelReason}</dd></>}
            {rc.remarks && <><dt className="text-slate-500">Remarks</dt><dd>{rc.remarks}</dd></>}
          </dl>
          <dl className="mt-4 grid max-w-md grid-cols-2 gap-1 text-sm">
            <dt>Amount received</dt><dd className="text-right"><Money value={rc.amount} /></dd>
            <dt>Tax withheld</dt><dd className="text-right"><Money value={rc.taxWithheld} /></dd>
            <dt>Applied to documents</dt><dd className="text-right"><Money value={rc.allocatedAmount} /></dd>
            <dt className="font-semibold">Unapplied</dt><dd className="text-right font-semibold"><Money value={rc.unappliedAmount} /></dd>
          </dl>
        </section>
        <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
          <h2 className="mb-2 font-semibold">Applications</h2>
          <table className="min-w-full text-sm">
            <thead className="border-b border-slate-200 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="py-1 pr-3">Document</th><th className="py-1 pr-3">When</th>
              <th className="py-1 pr-3">Entry</th><th className="py-1 text-right">Amount</th></tr></thead>
            <tbody>{rc.allocations.map((a, i) => (
              <tr key={i} className="border-t border-slate-100"><td className="py-1.5 pr-3"><Link className="text-brand-700 hover:underline" to={`/receivables/billings/${a.documentId}`}>
                {KIND_LABEL[a.kind]} {a.documentNumber}</Link>{a.appliedLater ? ' (applied later)' : ''}</td>
                <td className="py-1.5 pr-3">{formatDateTime(a.createdAt)}</td><td className="py-1.5 pr-3">{a.postedEntryNumber ?? 'pending posting'}</td>
                <td className="py-1.5 text-right"><Money value={a.amount} /></td></tr>))}
              {rc.allocations.length === 0 && <tr><td colSpan={4} className="py-3 text-center text-slate-500">Not applied to any document.</td></tr>}</tbody>
          </table>
        </section>
        <DocumentsPanel documents={rc.documents} canUpload={can('UPLOAD')} onUpload={(f) => upload.mutate(f)} uploading={upload.isPending}
          error={upload.error} requiredNote="e.g. deposit slip, check image or withholding certificate" />
      </div>
      <ActionDialog spec={dialog ? SPECS[dialog] : null} busy={act.isPending} error={act.error} onClose={() => setDialog(null)}
        confirmDisabled={dialog === 'apply' && !applyValid} onConfirm={(comment) => act.mutate({ kind: dialog!, comment })}>
        {dialog === 'apply' && (
          <div className="space-y-2 text-sm">
            <Field label="Accounting date" type="date" value={applyDate} onChange={(e) => setApplyDate(e.target.value)} />
            {(open.data ?? []).map((o) => (
              <label key={o.id} className="flex items-center justify-between gap-2">{o.documentNumber} (available {o.available})
                <input aria-label={`Apply to ${o.documentNumber}`} inputMode="decimal" value={amounts[o.id] ?? ''} placeholder="0.00"
                  onChange={(e) => setAmounts({ ...amounts, [o.id]: e.target.value.replace(/[^\d.]/g, '') })}
                  className="w-28 rounded-md border border-slate-300 px-2 py-1 text-right" /></label>
            ))}
            {open.data?.length === 0 && <p className="text-slate-500">This customer has no open invoices.</p>}
            <p>Total {fromCents(applyTotal)} of {rc.unappliedAmount} unapplied.</p>
          </div>
        )}
      </ActionDialog>
    </>
  );
}
