import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useParams } from 'react-router';
import { treasuryApi } from '../../api/endpoints';
import type { DepositView } from '../../api/types';
import { ActionDialog, type ActionSpec } from '../../components/ActionDialog';
import { Button } from '../../components/Button';
import { DocumentsPanel } from '../../components/DocumentsPanel';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Spinner } from '../../components/Spinner';
import { StatusBadge } from '../../components/StatusBadge';

type Kind = 'post' | 'cancel';
const SPECS: Record<Kind, ActionSpec> = {
  post: { title: 'Post the deposit?', text: 'Records Dr cash in bank / Cr cash on hand in the general ledger.', confirm: 'Post' },
  cancel: { title: 'Cancel this deposit?', text: 'Its collections become available for another deposit.', confirm: 'Cancel deposit',
    commentRequired: true, commentLabel: 'Reason', danger: true },
};

export function DepositDetailPage() {
  const { id = '' } = useParams();
  const qc = useQueryClient();
  const query = useQuery({ queryKey: ['deposit', id], queryFn: () => treasuryApi.deposit(id) });
  const [dialog, setDialog] = useState<Kind | null>(null);
  const act = useMutation({
    mutationFn: ({ kind, comment }: { kind: Kind; comment: string }): Promise<DepositView> =>
      kind === 'post' ? treasuryApi.postDeposit(id, query.data!.version) : treasuryApi.cancelDeposit(id, query.data!.version, comment),
    onSuccess: (d) => { qc.setQueryData(['deposit', id], d); qc.invalidateQueries({ queryKey: ['deposits'] }); setDialog(null); },
    onError: () => qc.invalidateQueries({ queryKey: ['deposit', id] }),
  });
  const upload = useMutation({ mutationFn: (f: File) => treasuryApi.uploadDeposit(id, f), onSuccess: () => qc.invalidateQueries({ queryKey: ['deposit', id] }) });
  if (query.isPending) return <Spinner />;
  if (query.error) return <ErrorAlert error={query.error} />;
  const d = query.data;
  const can = (a: string) => d.availableActions.includes(a);
  return (
    <>
      <PageHeader title={`Deposit ${d.depositNumber}`} crumbs={[{ label: 'Treasury' }, { label: 'Deposits', to: '/treasury/deposits' }, { label: d.depositNumber }]}
        actions={<div className="flex gap-2">
          {can('POST') && <Button onClick={() => { act.reset(); setDialog('post'); }}>Post</Button>}
          {can('CANCEL') && <Button variant="secondary" onClick={() => { act.reset(); setDialog('cancel'); }}>Cancel</Button>}
        </div>} />
      <div className="space-y-4">
        <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
          <div className="mb-3"><StatusBadge status={d.status} /></div>
          <dl className="grid gap-2 text-sm sm:grid-cols-4">
            <dt className="text-slate-500">Bank account</dt><dd>{d.bankAccountCode} — {d.bankName}</dd>
            <dt className="text-slate-500">Date</dt><dd>{d.depositDate}</dd>
            <dt className="text-slate-500">Slip number</dt><dd>{d.bankReference ?? '—'}</dd>
            <dt className="text-slate-500">Prepared by</dt><dd>{d.preparedBy}</dd>
            <dt className="text-slate-500">Amount</dt><dd className="font-semibold"><Money value={d.amount} /></dd>
            {d.postedEntryId && <><dt className="text-slate-500">Ledger entry</dt>
              <dd><Link className="text-brand-700 hover:underline" to={`/general-ledger/entries/${d.postedEntryId}`}>{d.postedEntryNumber}</Link></dd></>}
            {d.cancelReason && <><dt className="text-slate-500">Cancelled</dt><dd>{d.cancelReason}</dd></>}
          </dl>
        </section>
        <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs">
          <table className="min-w-full text-sm">
            <thead className="border-b border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="px-3 py-2">Receipt</th><th className="px-3 py-2">Date</th>
              <th className="px-3 py-2">Customer</th><th className="px-3 py-2">Method</th><th className="px-3 py-2 text-right">Amount</th></tr></thead>
            <tbody>{d.items.map((i) => (
              <tr key={i.receiptId} className="border-t border-slate-100"><td className="px-3 py-2">
                <Link className="text-brand-700 hover:underline" to={`/receivables/receipts/${i.receiptId}`}>{i.receiptNumber}</Link></td>
                <td className="px-3 py-2">{i.receiptDate}</td><td className="px-3 py-2">{i.customerName}</td>
                <td className="px-3 py-2">{i.paymentMethod.replace('_', ' ')} {i.paymentReference ?? ''}</td>
                <td className="px-3 py-2 text-right"><Money value={i.amount} /></td></tr>))}</tbody>
          </table>
        </div>
        <DocumentsPanel documents={d.documents} canUpload={can('UPLOAD')} onUpload={(f) => upload.mutate(f)} uploading={upload.isPending}
          error={upload.error} requiredNote="the validated deposit slip" />
      </div>
      <ActionDialog spec={dialog ? SPECS[dialog] : null} busy={act.isPending} error={act.error} onClose={() => setDialog(null)}
        onConfirm={(comment) => act.mutate({ kind: dialog!, comment })} />
    </>
  );
}
