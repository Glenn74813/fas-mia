import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Select } from '../../components/Select';
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

type Kind = 'submit' | 'approve' | 'return' | 'reject' | 'cancel' | 'post' | 'instruct' | 'void' | 'confirm' | 'approveCheck';

const SPECS: Record<Kind, ActionSpec> = {
  ...(COMMON_ACTIONS as Record<'submit' | 'approve' | 'return' | 'reject' | 'cancel' | 'post', ActionSpec>),
  post: { title: 'Post the payment?', text: 'Records Dr accounts payable / Cr cash in bank in the general ledger.', confirm: 'Post' },
  instruct: { title: 'Issue payment', text: 'Prepare the check from the register (a second person approves it before release), or record the ADA / bank transfer reference.', confirm: 'Issue payment' },
  approveCheck: { title: 'Approve the check?', text: 'Confirms the prepared check matches the approved voucher (payee, amount, bank). It can then be released.', confirm: 'Approve check' },
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
      if (kind === 'approveCheck') return dvApi.approveCheck(id, v);
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
  const approveLabel = dv.currentStep === 'BUDGET' ? 'Certify budget' : dv.currentStep === 'CERTIFY' ? 'Certify' : 'Approve';
  const buttons: [Kind, string, boolean][] = [['submit', 'Submit', can('SUBMIT')], ['approve', approveLabel, can('APPROVE')],
    ['instruct', 'Issue payment', can('INSTRUCT')], ['approveCheck', 'Approve check', can('APPROVE_CHECK')],
    ['confirm', 'Confirm release', can('CONFIRM')], ['post', 'Post', can('POST')],
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
        {dv.budget.length > 0 && <BudgetPanel dv={dv} />}
        {dv.check && (
          <section aria-label="Check" className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5 text-sm">
            <h2 className="mb-3 font-semibold">Check</h2>
            <dl className="grid gap-2 sm:grid-cols-4">
              <dt className="text-slate-500">Check</dt><dd>{dv.check.checkNumber} · {dv.check.bankAccountCode}</dd>
              <dt className="text-slate-500">Status</dt><dd><StatusBadge status={dv.check.status} /></dd>
              <dt className="text-slate-500">Prepared by</dt><dd>{dv.check.preparedBy}</dd>
              <dt className="text-slate-500">Approved by</dt><dd>{dv.check.approvedBy ? `${dv.check.approvedBy} · ${formatDateTime(dv.check.approvedAt)}`
                : dv.check.status === 'PREPARED' ? 'Awaiting a second person' : '—'}</dd>
            </dl>
          </section>
        )}
        <DocumentsPanel documents={dv.documents} canUpload={can('UPLOAD')} onUpload={(f) => upload.mutate(f)} uploading={upload.isPending}
          error={upload.error} requiredNote="required before submission" />
        <DecisionHistory decisions={dv.decisions} />
      </div>
      <ActionDialog spec={dialog ? SPECS[dialog] : null} busy={act.isPending} error={act.error} onClose={() => setDialog(null)}
        onConfirm={(comment) => act.mutate({ kind: dialog!, comment })}
        confirmDisabled={dialog === 'instruct' && (!payment.bankAccountId || !payment.reference)}>
        {dialog === 'instruct' && (
          <div className="grid gap-3 text-sm sm:grid-cols-2">
            <label>Mode<Select value={payment.mode} onChange={(e) => setPayment({ ...payment, mode: e.target.value })}
              className="mt-1.5 block w-full rounded-md border border-slate-300 px-2 py-2"><option value="CHECK">Check</option><option value="ADA">ADA / bank transfer</option></Select></label>
            <label>Bank account<Select value={payment.bankAccountId} onChange={(e) => setPayment({ ...payment, bankAccountId: e.target.value })}
              className="mt-1.5 block w-full rounded-md border border-slate-300 px-2 py-2"><option value="">Select…</option>
              {(banks.data ?? []).filter((b) => b.status === 'ACTIVE').map((b) => <option key={b.id} value={b.id}>{b.code} ({b.bankName} ****{b.accountNumberLast4})</option>)}</Select></label>
            <label>{payment.mode === 'CHECK' ? 'Check number' : 'ADA reference'}<input value={payment.reference}
              onChange={(e) => setPayment({ ...payment, reference: e.target.value.replace(/[^A-Za-z0-9-]/g, '') })}
              className="mt-1.5 block w-full rounded-md border border-slate-300 px-2 py-2" /></label>
            <label>Payment date<input type="date" value={payment.paymentDate} onChange={(e) => setPayment({ ...payment, paymentDate: e.target.value })}
              className="mt-1.5 block w-full rounded-md border border-slate-300 px-2 py-2" /></label>
          </div>
        )}
        {dialog === 'confirm' && (
          <label className="block text-sm">Released on<input type="date" value={releasedOn} onChange={(e) => setReleasedOn(e.target.value)}
            className="mt-1.5 block rounded-md border border-slate-300 px-2 py-2" /></label>
        )}
      </ActionDialog>
    </>
  );
}

const STATUS_NOTE: Record<string, string> = {
  NO_ALLOCATION: 'No allocation in the approved budget',
  NO_BUDGET: 'No approved budget for the fiscal year',
};

/** The Corporate Operating Budget position of what this DV pays, for the Budget Division's certification. */
function BudgetPanel({ dv }: { dv: DvView }) {
  return (
    <section aria-label="Budget" className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs">
      <div className="px-4 pt-4">
        <h2 className="font-semibold">Budget (Corporate Operating Budget)</h2>
        <p className="mt-1 text-xs text-slate-500">This voucher's share of each budget line it pays. The invoices already reserved
          or used these amounts when they were approved, so they are included in Used.</p>
      </div>
      <table className="mt-3 min-w-full text-sm">
        <thead className="border-y border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500">
          <tr><th className="px-3 py-2">Account / cost centre</th><th className="px-3 py-2 text-right">This DV</th>
            <th className="px-3 py-2 text-right">Allotment</th><th className="px-3 py-2 text-right">Used</th>
            <th className="px-3 py-2 text-right">Available</th></tr>
        </thead>
        <tbody>{dv.budget.map((b) => (
          <tr key={b.accountCode + (b.costCenterCode ?? '')} className="border-t border-slate-100">
            <td className="px-3 py-2">{b.accountCode} {b.accountName}{b.costCenterCode && ` / ${b.costCenterCode}`}</td>
            <td className="px-3 py-2 text-right"><Money value={b.charged} /></td>
            {b.status === 'ALLOCATED' ? <>
              <td className="px-3 py-2 text-right"><Money value={b.allotment} /></td>
              <td className="px-3 py-2 text-right"><Money value={b.used} /></td>
              <td className={`px-3 py-2 text-right font-medium ${b.available!.startsWith('-') ? 'text-red-700' : ''}`}><Money value={b.available} /></td>
            </> : <td colSpan={3} className="px-3 py-2 text-right text-amber-800">{STATUS_NOTE[b.status]}</td>}
          </tr>))}
        </tbody>
      </table>
    </section>
  );
}
