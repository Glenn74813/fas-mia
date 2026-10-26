import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useParams } from 'react-router';
import { invoiceApi } from '../../api/endpoints';
import { ActionDialog, COMMON_ACTIONS } from '../../components/ActionDialog';
import { Button } from '../../components/Button';
import { DecisionHistory } from '../../components/DecisionHistory';
import { DocumentsPanel } from '../../components/DocumentsPanel';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Spinner } from '../../components/Spinner';
import { StatusBadge } from '../../components/StatusBadge';

type Act = 'submit' | 'approve' | 'return' | 'reject' | 'cancel' | 'post';

export function InvoiceDetailPage() {
  const { id = '' } = useParams();
  const qc = useQueryClient();
  const query = useQuery({ queryKey: ['invoice', id], queryFn: () => invoiceApi.get(id) });
  const [dialog, setDialog] = useState<Act | null>(null);
  const act = useMutation({
    mutationFn: ({ kind, comment }: { kind: Act; comment: string }) => invoiceApi.act(id, kind, query.data!.version, comment),
    onSuccess: (inv) => { qc.setQueryData(['invoice', id], inv); qc.invalidateQueries({ queryKey: ['invoices'] }); setDialog(null); },
    onError: () => qc.invalidateQueries({ queryKey: ['invoice', id] }),
  });
  const upload = useMutation({ mutationFn: (f: File) => invoiceApi.upload(id, f), onSuccess: () => qc.invalidateQueries({ queryKey: ['invoice', id] }) });
  if (query.isPending) return <Spinner />;
  if (query.error) return <ErrorAlert error={query.error} />;
  const inv = query.data;
  const can = (a: string) => inv.availableActions.includes(a);
  const open = (k: Act) => { act.reset(); setDialog(k); };
  return (
    <>
      <PageHeader title={`Invoice ${inv.invoiceNumber}`} crumbs={[{ label: 'Payables' }, { label: 'Invoices', to: '/payables/invoices' }, { label: inv.invoiceNumber }]}
        actions={<div className="flex flex-wrap gap-2">
          {can('EDIT') && <Link to={`/payables/invoices/${inv.id}/edit`} className="rounded-md border border-slate-300 bg-white px-4 py-2 text-sm font-medium hover:bg-slate-100">Edit</Link>}
          {(['submit', 'approve', 'post', 'return', 'reject', 'cancel'] as Act[]).filter((a) => can(a.toUpperCase())).map((a) => (
            <Button key={a} variant={['return', 'reject', 'cancel'].includes(a) ? 'secondary' : 'primary'} onClick={() => open(a)}>
              {a.charAt(0).toUpperCase() + a.slice(1)}</Button>))}
        </div>} />
      <div className="space-y-4">
        {inv.possibleDuplicates.length > 0 && (
          <p role="note" className="rounded-md border border-amber-300 bg-amber-50 p-3 text-sm">Possible duplicate: same supplier and amount within
            7 days — {inv.possibleDuplicates.join('; ')}. Check before approving.</p>
        )}
        <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
          <div className="mb-3 flex items-center gap-2"><StatusBadge status={inv.status} />
            {inv.currentStep && <span className="text-xs text-slate-500">awaiting {inv.currentStep.toLowerCase()}</span>}</div>
          <dl className="grid gap-2 text-sm sm:grid-cols-4">
            <dt className="text-slate-500">Supplier</dt><dd>{inv.supplierCode} — {inv.supplierName}{inv.supplierTin ? ` (TIN ${inv.supplierTin})` : ''}</dd>
            <dt className="text-slate-500">Invoice / due</dt><dd>{inv.invoiceDate} / {inv.dueDate}</dd>
            <dt className="text-slate-500">Accounting date</dt><dd>{inv.accountingDate} ({inv.periodName})</dd>
            <dt className="text-slate-500">Prepared by</dt><dd>{inv.preparedBy}</dd>
            {inv.postedEntryId && <><dt className="text-slate-500">Ledger entry</dt>
              <dd><Link className="text-brand-700 hover:underline" to={`/general-ledger/entries/${inv.postedEntryId}`}>{inv.postedEntryNumber}</Link></dd></>}
          </dl>
          <p className="mt-3 text-sm">{inv.description}</p>
          <dl className="mt-4 grid max-w-md grid-cols-2 gap-1 text-sm">
            <dt>Net amount</dt><dd className="text-right"><Money value={inv.netAmount} /></dd>
            <dt>Input VAT {inv.vatCode ? `(${inv.vatCode})` : ''}</dt><dd className="text-right"><Money value={inv.vatAmount} /></dd>
            <dt>Less withholding tax</dt><dd className="text-right">({<Money value={inv.ewtAmount} />})</dd>
            <dt className="font-semibold">Payable to supplier</dt><dd className="text-right font-semibold"><Money value={inv.payableAmount} /></dd>
            <dt>Paid</dt><dd className="text-right"><Money value={inv.paidAmount} /></dd>
            <dt>Open balance</dt><dd className="text-right"><Money value={inv.openBalance} /></dd>
          </dl>
        </section>
        <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs">
          <table className="min-w-full text-sm">
            <thead className="border-b border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="px-3 py-2">#</th><th className="px-3 py-2">Account</th>
              <th className="px-3 py-2">Cost centre</th><th className="px-3 py-2">Description</th><th className="px-3 py-2">EWT</th>
              <th className="px-3 py-2 text-right">Net</th><th className="px-3 py-2 text-right">Withheld</th></tr></thead>
            <tbody>{inv.lines.map((l) => (
              <tr key={l.lineNo} className="border-t border-slate-100"><td className="px-3 py-2">{l.lineNo}</td>
                <td className="px-3 py-2"><span className="font-mono">{l.accountCode}</span> {l.accountName}</td><td className="px-3 py-2">{l.costCenterCode ?? '—'}</td>
                <td className="px-3 py-2">{l.description ?? ''}</td><td className="px-3 py-2">{l.ewtCode ?? '—'}</td>
                <td className="px-3 py-2 text-right"><Money value={l.netAmount} /></td><td className="px-3 py-2 text-right"><Money value={l.ewtAmount} blankZero /></td></tr>
            ))}</tbody>
          </table>
        </div>
        <DocumentsPanel documents={inv.documents} canUpload={can('UPLOAD')} onUpload={(f) => upload.mutate(f)} uploading={upload.isPending}
          error={upload.error} requiredNote="the supplier invoice must be attached before submission" />
        <DecisionHistory decisions={inv.decisions} />
      </div>
      <ActionDialog spec={dialog ? COMMON_ACTIONS[dialog]! : null} busy={act.isPending} error={act.error}
        onClose={() => setDialog(null)} onConfirm={(comment) => act.mutate({ kind: dialog!, comment })} />
    </>
  );
}
