import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { Link, useParams } from 'react-router';
import { dvApi, settingsApi } from '../../api/endpoints';
import type { DvView } from '../../api/types';
import { ActionDialog, COMMON_ACTIONS, type ActionSpec } from '../../components/ActionDialog';
import { Button } from '../../components/Button';
import { DecisionHistory } from '../../components/DecisionHistory';
import { DocumentsPanel } from '../../components/DocumentsPanel';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Spinner } from '../../components/Spinner';
import { StatusBadge } from '../../components/StatusBadge';
import { formatDateTime } from '../../utils/format';

type Kind = 'submit' | 'approve' | 'return' | 'reject' | 'cancel' | 'post' | 'instruct' | 'void' | 'confirm';

const SPECS: Record<Kind, ActionSpec> = {
  ...(COMMON_ACTIONS as Record<'submit' | 'approve' | 'return' | 'reject' | 'cancel' | 'post', ActionSpec>),
  post: { title: 'Post the payment?', text: 'Records Dr accounts payable / Cr cash in bank in the general ledger.', confirm: 'Post' },
  instruct: { title: 'Issue payment', text: 'Record the check from the register, or the ADA / bank transfer reference.', confirm: 'Issue payment' },
  void: { title: 'Void the issued payment?', text: 'The check is marked VOIDED in the register and the DV returns to Approved.',
    confirm: 'Void', commentRequired: true, commentLabel: 'Reason', danger: true },
  confirm: { title: 'Confirm payment release?', text: 'Confirms the check was released or the bank transfer executed. Accounting then posts it.', confirm: 'Confirm release' },
};

export function DisbursementDetailPage() {
  const { id = '' } = useParams();
  const qc = useQueryClient();
  const query = useQuery({ queryKey: ['dv', id], queryFn: () => dvApi.get(id) });
  const banks = useQuery({ queryKey: ['bank-accounts'], queryFn: settingsApi.bankAccounts });
  const [dialog, setDialog] = useState<Kind | null>(null);
  const today = new Date().toISOString().slice(0, 10);
  const [payment, setPayment] = useState({ mode: 'CHECK', bankAccountId: '', reference: '', paymentDate: today });
  const [releasedOn, setReleasedOn] = useState(today);
  // One key per payment instruction: a retry after a lost response cannot issue the check twice.
  const instructKey = useRef<string | null>(null);
  const act = useMutation({
    mutationFn: ({ kind, comment }: { kind: Kind; comment: string }): Promise<DvView> => {
      const v = query.data!.version;
      if (kind === 'instruct') {
        instructKey.current ??= crypto.randomUUID();
        return dvApi.instruct(id, { version: v, ...payment }, instructKey.current);
      }
      if (kind === 'void') return dvApi.voidPayment(id, v, comment);
      if (kind === 'confirm') return dvApi.confirm(id, v, releasedOn);
      return dvApi.act(id, kind, v, comment);
    },
    onSuccess: (dv) => { instructKey.current = null; qc.setQueryData(['dv', id], dv); qc.invalidateQueries({ queryKey: ['dvs'] }); setDialog(null); },
    onError: () => qc.invalidateQueries({ queryKey: ['dv', id] }),
  });
  const upload = useMutation({ mutationFn: (f: File) => dvApi.upload(id, f), onSuccess: () => qc.invalidateQueries({ queryKey: ['dv', id] }) });
  if (query.isPending) return <Spinner />;
  if (query.error) return <ErrorAlert error={query.error} />;
  const dv = query.data;
  const can = (a: string) => dv.availableActions.includes(a);
  const buttons: [Kind, string, boolean][] = [['submit', 'Submit', can('SUBMIT')], ['approve', dv.currentStep === 'CERTIFY' ? 'Certify' : 'Approve', can('APPROVE')],
    ['instruct', 'Issue payment', can('INSTRUCT')], ['confirm', 'Confirm release', can('CONFIRM')], ['post', 'Post', can('POST')],
    ['void', 'Void payment', can('VOID')], ['return', 'Return', can('RETURN')], ['reject', 'Reject', can('REJECT')], ['cancel', 'Cancel', can('CANCEL')]];
  return (
    <>
      <PageHeader title={dv.dvNumber ?? 'Draft disbursement voucher'}
        crumbs={[{ label: 'Payables' }, { label: 'Disbursement vouchers', to: '/payables/disbursements' }, { label: dv.dvNumber ?? 'Draft' }]}
        actions={<div className="flex flex-wrap gap-2">{buttons.filter(([, , show]) => show).map(([k, label]) => (
          <Button key={k} variant={['void', 'return', 'reject', 'cancel'].includes(k) ? 'secondary' : 'primary'}
            onClick={() => { act.reset(); setDialog(k); }}>{label}</Button>))}</div>} />
      <div className="space-y-4">
        <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
          <div className="mb-3 flex items-center gap-2"><StatusBadge status={dv.status} />
            {dv.currentStep && <span className="text-xs text-slate-500">awaiting {dv.currentStep.toLowerCase()}</span>}</div>
          <dl className="grid gap-2 text-sm sm:grid-cols-4">
            <dt className="text-slate-500">Payee</dt><dd>{dv.supplierCode} — {dv.supplierName}</dd>
            <dt className="text-slate-500">Amount</dt><dd className="font-semibold"><Money value={dv.amount} /></dd>
            <dt className="text-slate-500">Prepared by</dt><dd>{dv.preparedBy}</dd>
            <dt className="text-slate-500">Payment</dt><dd>{dv.paymentMode ? `${dv.paymentMode} ${dv.paymentReference} · ${dv.bankAccountCode} · ${dv.paymentDate}` : '—'}</dd>
            {dv.instructedBy && <><dt className="text-slate-500">Issued by</dt><dd>{dv.instructedBy} · {formatDateTime(dv.instructedAt)}</dd></>}
            {dv.confirmedBy && <><dt className="text-slate-500">Release confirmed</dt><dd>{dv.confirmedBy} · {formatDateTime(dv.confirmedAt)}</dd></>}
            {dv.postedEntryId && <><dt className="text-slate-500">Ledger entry</dt>
              <dd><Link className="text-brand-700 hover:underline" to={`/general-ledger/entries/${dv.postedEntryId}`}>{dv.postedEntryNumber}</Link></dd></>}
          </dl>
          <p className="mt-3 text-sm">{dv.particulars}</p>
        </section>
        <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs">
          <table className="min-w-full text-sm">
            <thead className="border-b border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="px-3 py-2">Invoice</th><th className="px-3 py-2">Due</th>
              <th className="px-3 py-2 text-right">Invoice payable</th><th className="px-3 py-2 text-right">Paid by this DV</th></tr></thead>
            <tbody>{dv.lines.map((l) => (
              <tr key={l.invoiceId} className="border-t border-slate-100"><td className="px-3 py-2"><Link className="text-brand-700 hover:underline"
                to={`/payables/invoices/${l.invoiceId}`}>{l.invoiceNumber}</Link></td><td className="px-3 py-2">{l.dueDate}</td>
                <td className="px-3 py-2 text-right"><Money value={l.invoicePayable} /></td><td className="px-3 py-2 text-right"><Money value={l.amount} /></td></tr>))}</tbody>
          </table>
        </div>
        <DocumentsPanel documents={dv.documents} canUpload={can('UPLOAD')} onUpload={(f) => upload.mutate(f)} uploading={upload.isPending}
          error={upload.error} requiredNote="required before submission" />
        <DecisionHistory decisions={dv.decisions} />
      </div>
      <ActionDialog spec={dialog ? SPECS[dialog] : null} busy={act.isPending} error={act.error} onClose={() => setDialog(null)}
        onConfirm={(comment) => act.mutate({ kind: dialog!, comment })}
        confirmDisabled={dialog === 'instruct' && (!payment.bankAccountId || !payment.reference)}>
        {dialog === 'instruct' && (
          <div className="grid gap-3 text-sm sm:grid-cols-2">
            <label>Mode<select value={payment.mode} onChange={(e) => setPayment({ ...payment, mode: e.target.value })}
              className="mt-1 block w-full rounded-md border border-slate-300 px-2 py-2"><option value="CHECK">Check</option><option value="ADA">ADA / bank transfer</option></select></label>
            <label>Bank account<select value={payment.bankAccountId} onChange={(e) => setPayment({ ...payment, bankAccountId: e.target.value })}
              className="mt-1 block w-full rounded-md border border-slate-300 px-2 py-2"><option value="">Select…</option>
              {(banks.data ?? []).filter((b) => b.status === 'ACTIVE').map((b) => <option key={b.id} value={b.id}>{b.code} ({b.bankName} ****{b.accountNumberLast4})</option>)}</select></label>
            <label>{payment.mode === 'CHECK' ? 'Check number' : 'ADA reference'}<input value={payment.reference}
              onChange={(e) => setPayment({ ...payment, reference: e.target.value.replace(/[^A-Za-z0-9-]/g, '') })}
              className="mt-1 block w-full rounded-md border border-slate-300 px-2 py-2" /></label>
            <label>Payment date<input type="date" value={payment.paymentDate} onChange={(e) => setPayment({ ...payment, paymentDate: e.target.value })}
              className="mt-1 block w-full rounded-md border border-slate-300 px-2 py-2" /></label>
          </div>
        )}
        {dialog === 'confirm' && (
          <label className="block text-sm">Released on<input type="date" value={releasedOn} onChange={(e) => setReleasedOn(e.target.value)}
            className="mt-1 block rounded-md border border-slate-300 px-2 py-2" /></label>
        )}
      </ActionDialog>
    </>
  );
}
