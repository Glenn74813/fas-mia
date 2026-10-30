import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Select } from '../../components/Select';
import { useState } from 'react';
import { portalApi, voucherApi } from '../../api/endpoints';
import type { PortalInquiry } from '../../api/types';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Field } from '../../components/Field';
import { Money } from '../../components/Money';
import { Pagination } from '../../components/Pagination';
import { Spinner } from '../../components/Spinner';
import { StatusBadge } from '../../components/StatusBadge';
import { formatDateTime } from '../../utils/format';

const METHOD: Record<string, string> = { BANK_TRANSFER: 'Bank transfer', CHECK: 'Check', CASH: 'Cash' };
const EMPTY = { kind: 'QUESTION', subject: '', message: '', billingId: '', paymentDate: '', paymentAmount: '', paymentMethod: 'BANK_TRANSFER',
  paymentReference: '' };

/** The concessionaire's questions and payment references. A payment reference only tells Finance; it records no payment. */
export function PortalInquiries() {
  const qc = useQueryClient();
  const [page, setPage] = useState(0);
  const list = useQuery({ queryKey: ['portal-inquiries', page], queryFn: () => portalApi.inquiries(page), placeholderData: keepPreviousData });
  const billings = useQuery({ queryKey: ['portal-billings', 0], queryFn: () => portalApi.billings(0) });
  const [form, setForm] = useState(EMPTY);
  const [open, setOpen] = useState(false);
  const [file, setFile] = useState<File | null>(null);
  const payment = form.kind === 'PAYMENT_REFERENCE';
  const submit = useMutation({
    mutationFn: async () => {
      const created = await portalApi.submitInquiry({ kind: form.kind, subject: form.subject.trim(), message: form.message.trim(),
        billingId: form.billingId || undefined,
        ...(payment ? { paymentDate: form.paymentDate, paymentAmount: form.paymentAmount.trim(), paymentMethod: form.paymentMethod,
          paymentReference: form.paymentReference.trim() } : {}) });
      if (file) await portalApi.attachToInquiry(created.id, file);
      return created;
    },
    onSuccess: () => { setForm(EMPTY); setFile(null); setOpen(false); void qc.invalidateQueries({ queryKey: ['portal-inquiries'] }); },
  });
  const attach = useMutation({ mutationFn: ({ i, f }: { i: PortalInquiry; f: File }) => portalApi.attachToInquiry(i.id, f),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['portal-inquiries'] }) });
  const ready = form.subject.trim() && form.message.trim()
    && (!payment || (form.paymentDate && /^\d+(\.\d{1,2})?$/.test(form.paymentAmount.trim()) && form.paymentReference.trim()));
  const select = 'block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm';
  return (
    <section className="space-y-4 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
      <div className="flex items-center justify-between">
        <h2 className="font-semibold">Inquiries and payment references</h2>
        {!open && <Button variant="secondary" onClick={() => setOpen(true)}>New inquiry</Button>}
      </div>
      {open && (
        <form className="grid gap-3 rounded-md bg-slate-50 p-4 sm:grid-cols-2" onSubmit={(e) => { e.preventDefault(); if (ready) submit.mutate(); }}>
          <label className="text-sm font-medium text-slate-700">Type
            <Select value={form.kind} onChange={(e) => setForm({ ...form, kind: e.target.value })} className={select}>
              <option value="QUESTION">A question about my account</option><option value="PAYMENT_REFERENCE">I have made a payment</option></Select></label>
          <label className="text-sm font-medium text-slate-700">Billing (optional)
            <Select value={form.billingId} onChange={(e) => setForm({ ...form, billingId: e.target.value })} className={select}>
              <option value="">None</option>{(billings.data?.items ?? []).map((b) => <option key={b.id} value={b.id}>{b.documentNumber}</option>)}</Select></label>
          <div className="sm:col-span-2"><Field label="Subject" maxLength={200} value={form.subject} onChange={(e) => setForm({ ...form, subject: e.target.value })} /></div>
          {payment && <>
            <Field label="Payment date" type="date" max={new Date().toISOString().slice(0, 10)} value={form.paymentDate}
              onChange={(e) => setForm({ ...form, paymentDate: e.target.value })} />
            <Field label="Amount paid (₱)" inputMode="decimal" value={form.paymentAmount} placeholder="0.00"
              onChange={(e) => setForm({ ...form, paymentAmount: e.target.value.replace(/[^\d.]/g, '') })} />
            <label className="text-sm font-medium text-slate-700">Paid by
              <Select value={form.paymentMethod} onChange={(e) => setForm({ ...form, paymentMethod: e.target.value })} className={select}>
                {Object.entries(METHOD).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</Select></label>
            <Field label="Bank or check reference" maxLength={60} value={form.paymentReference}
              onChange={(e) => setForm({ ...form, paymentReference: e.target.value })} /></>}
          <label className="text-sm font-medium text-slate-700 sm:col-span-2">Message
            <textarea rows={4} maxLength={4000} value={form.message} onChange={(e) => setForm({ ...form, message: e.target.value })} className={select} /></label>
          <label className="text-sm text-slate-700 sm:col-span-2">{payment ? 'Proof of payment' : 'Attachment'} (optional, PDF or image)
            <input type="file" accept=".pdf,.png,.jpg,.jpeg" className="ml-2 text-xs" onChange={(e) => setFile(e.target.files?.[0] ?? null)} /></label>
          {payment && <p className="text-xs text-slate-600 sm:col-span-2">This tells AVTECH Finance about your payment. Your balance changes only
            when Finance has checked the payment and posted the official receipt.</p>}
          <div className="flex gap-2 sm:col-span-2">
            <Button type="submit" busy={submit.isPending} disabled={!ready}>Send</Button>
            <Button type="button" variant="secondary" onClick={() => { setOpen(false); setForm(EMPTY); }}>Cancel</Button>
          </div>
          <div className="sm:col-span-2"><ErrorAlert error={submit.error} /></div>
        </form>
      )}
      <ErrorAlert error={list.error ?? attach.error} />
      {list.isPending ? <Spinner /> : (
        <ul className="space-y-3">
          {(list.data?.items ?? []).map((i) => (
            <li key={i.id} className="rounded-md border border-slate-200 p-3 text-sm">
              <div className="flex flex-wrap items-center gap-2"><StatusBadge status={i.status} /><span className="font-mono">{i.reference}</span>
                <span className="font-medium">{i.subject}</span></div>
              <p className="text-xs text-slate-500">Sent {formatDateTime(i.createdAt)}{i.billingNumber ? ` · billing ${i.billingNumber}` : ''}</p>
              {i.kind === 'PAYMENT_REFERENCE' && <p className="mt-1">Payment of <Money value={i.paymentAmount!} /> on {i.paymentDate} by {METHOD[i.paymentMethod!]},
                reference {i.paymentReference}</p>}
              <p className="mt-1 whitespace-pre-wrap">{i.message}</p>
              {i.documents.length > 0 && <p className="mt-1 text-xs">Attachments: {i.documents.map((d) => (
                <button key={d.id} className="mr-2 text-brand-700 hover:underline" onClick={() => voucherApi.download(d)}>{d.fileName}</button>))}</p>}
              {i.response && <div className="mt-2 rounded bg-slate-50 p-2"><p className="text-xs text-slate-500">AVTECH Finance replied {formatDateTime(i.respondedAt)}</p>
                <p className="whitespace-pre-wrap">{i.response}</p></div>}
              {i.availableActions.includes('UPLOAD') && <label className="mt-2 block text-xs">Add a file
                <input type="file" accept=".pdf,.png,.jpg,.jpeg" className="ml-2 text-xs"
                  onChange={(e) => { const f = e.target.files?.[0]; if (f) attach.mutate({ i, f }); e.target.value = ''; }} /></label>}
            </li>
          ))}
          {list.data?.items.length === 0 && <li className="text-sm text-slate-500">You have not sent any inquiries.</li>}
        </ul>
      )}
      {list.data && <Pagination page={list.data.page} totalPages={list.data.totalPages} totalItems={list.data.totalItems} onPage={setPage} />}
    </section>
  );
}
