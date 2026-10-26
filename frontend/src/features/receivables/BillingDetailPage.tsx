import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useParams } from 'react-router';
import { billingApi } from '../../api/endpoints';
import { ActionDialog, COMMON_ACTIONS } from '../../components/ActionDialog';
import { Button } from '../../components/Button';
import { DecisionHistory } from '../../components/DecisionHistory';
import { DocumentsPanel } from '../../components/DocumentsPanel';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Spinner } from '../../components/Spinner';
import { StatusBadge } from '../../components/StatusBadge';
import { KIND_LABEL } from './BillingsPage';

type Act = 'submit' | 'approve' | 'return' | 'reject' | 'cancel' | 'post';

export function BillingDetailPage() {
  const { id = '' } = useParams();
  const qc = useQueryClient();
  const query = useQuery({ queryKey: ['billing', id], queryFn: () => billingApi.get(id) });
  const [dialog, setDialog] = useState<Act | null>(null);
  const act = useMutation({
    mutationFn: ({ kind, comment }: { kind: Act; comment: string }) => billingApi.act(id, kind, query.data!.version, comment),
    onSuccess: (b) => { qc.setQueryData(['billing', id], b); qc.invalidateQueries({ queryKey: ['billings'] }); setDialog(null); },
    onError: () => qc.invalidateQueries({ queryKey: ['billing', id] }),
  });
  const upload = useMutation({ mutationFn: (f: File) => billingApi.upload(id, f), onSuccess: () => qc.invalidateQueries({ queryKey: ['billing', id] }) });
  if (query.isPending) return <Spinner />;
  if (query.error) return <ErrorAlert error={query.error} />;
  const b = query.data;
  const can = (a: string) => b.availableActions.includes(a);
  const open = (k: Act) => { act.reset(); setDialog(k); };
  const title = `${KIND_LABEL[b.kind]} ${b.documentNumber ?? '(draft)'}`;
  const postSpec = { ...COMMON_ACTIONS.post!, text: b.kind === 'CREDIT_MEMO'
    ? 'Records Dr revenue (and output VAT) / Cr accounts receivable and reduces the related invoice. A credit memo number is assigned.'
    : 'Records Dr accounts receivable / Cr revenue and output VAT in the general ledger. The document number is assigned now.' };
  return (
    <>
      <PageHeader title={title} crumbs={[{ label: 'Receivables' }, { label: 'Billing', to: '/receivables/billings' }, { label: b.documentNumber ?? 'Draft' }]}
        actions={<div className="flex flex-wrap gap-2">
          {can('EDIT') && <Link to={`/receivables/billings/${b.id}/edit`} className="rounded-md border border-slate-300 bg-white px-4 py-2 text-sm font-medium hover:bg-slate-100">Edit</Link>}
          {(['submit', 'approve', 'post', 'return', 'reject', 'cancel'] as Act[]).filter((a) => can(a.toUpperCase())).map((a) => (
            <Button key={a} variant={['return', 'reject', 'cancel'].includes(a) ? 'secondary' : 'primary'} onClick={() => open(a)}>
              {a.charAt(0).toUpperCase() + a.slice(1)}</Button>))}
        </div>} />
      <div className="space-y-4">
        {b.warnings.map((w) => <p key={w} role="note" className="rounded-md border border-amber-300 bg-amber-50 p-3 text-sm">{w}</p>)}
        <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
          <div className="mb-3 flex items-center gap-2"><StatusBadge status={b.status} />
            {b.currentStep && <span className="text-xs text-slate-500">awaiting {b.currentStep.toLowerCase()}</span>}</div>
          <dl className="grid gap-2 text-sm sm:grid-cols-4">
            <dt className="text-slate-500">Customer</dt><dd><Link className="text-brand-700 hover:underline" to={`/receivables/customers/${b.customerId}`}>
              {b.customerCode} — {b.customerName}</Link>{b.customerTin ? ` (TIN ${b.customerTin})` : ''}</dd>
            <dt className="text-slate-500">Date / due</dt><dd>{b.documentDate} / {b.dueDate} ({b.periodName})</dd>
            {b.contractNumber && <><dt className="text-slate-500">Contract</dt><dd>{b.contractNumber} · {b.billingPeriod}</dd></>}
            {b.relatedInvoiceId && <><dt className="text-slate-500">Related invoice</dt>
              <dd><Link className="text-brand-700 hover:underline" to={`/receivables/billings/${b.relatedInvoiceId}`}>{b.relatedInvoiceNumber}</Link></dd></>}
            <dt className="text-slate-500">Prepared by</dt><dd>{b.preparedBy} ({b.source.toLowerCase()})</dd>
            {b.postedEntryId && <><dt className="text-slate-500">Ledger entry</dt>
              <dd><Link className="text-brand-700 hover:underline" to={`/general-ledger/entries/${b.postedEntryId}`}>{b.postedEntryNumber}</Link></dd></>}
          </dl>
          <p className="mt-3 text-sm">{b.description}</p>
          <dl className="mt-4 grid max-w-md grid-cols-2 gap-1 text-sm">
            <dt>Net amount</dt><dd className="text-right"><Money value={b.netAmount} /></dd>
            <dt>Output VAT {b.vatCode ? `(${b.vatCode})` : ''}</dt><dd className="text-right"><Money value={b.vatAmount} /></dd>
            <dt className="font-semibold">Total</dt><dd className="text-right font-semibold"><Money value={b.grossAmount} /></dd>
            {b.kind !== 'CREDIT_MEMO' && b.status === 'POSTED' && <>
              <dt>Settled</dt><dd className="text-right"><Money value={b.settledAmount} /></dd>
              <dt>Open balance</dt><dd className="text-right"><Money value={b.openBalance} /></dd></>}
          </dl>
        </section>
        <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs">
          <table className="min-w-full text-sm">
            <thead className="border-b border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="px-3 py-2">#</th><th className="px-3 py-2">Revenue account</th>
              <th className="px-3 py-2">Cost centre</th><th className="px-3 py-2">Description</th><th className="px-3 py-2 text-right">Net</th></tr></thead>
            <tbody>{b.lines.map((l) => (
              <tr key={l.lineNo} className="border-t border-slate-100"><td className="px-3 py-2">{l.lineNo}</td>
                <td className="px-3 py-2"><span className="font-mono">{l.accountCode}</span> {l.accountName}</td><td className="px-3 py-2">{l.costCenterCode ?? '—'}</td>
                <td className="px-3 py-2">{l.description}</td><td className="px-3 py-2 text-right"><Money value={l.netAmount} /></td></tr>
            ))}</tbody>
          </table>
        </div>
        <DocumentsPanel documents={b.documents} canUpload={can('UPLOAD')} onUpload={(f) => upload.mutate(f)} uploading={upload.isPending}
          error={upload.error} requiredNote={b.kind === 'INVOICE' ? undefined : 'a justification must be attached before submission'} />
        <DecisionHistory decisions={b.decisions} />
      </div>
      <ActionDialog spec={dialog ? (dialog === 'post' ? postSpec : COMMON_ACTIONS[dialog]!) : null} busy={act.isPending} error={act.error}
        onClose={() => setDialog(null)} onConfirm={(comment) => act.mutate({ kind: dialog!, comment })} />
    </>
  );
}
