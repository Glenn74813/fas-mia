import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router';
import { inquiryApi, voucherApi } from '../../api/endpoints';
import type { PortalInquiry } from '../../api/types';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Pagination } from '../../components/Pagination';
import { Spinner } from '../../components/Spinner';
import { StatusBadge } from '../../components/StatusBadge';
import { formatDateTime } from '../../utils/format';

/** Concessionaires' portal inquiries for Finance to answer. A payment reference is information only: the cashier records the receipt. */
export function InquiriesPage() {
  const qc = useQueryClient();
  const [status, setStatus] = useState('OPEN');
  const [page, setPage] = useState(0);
  const list = useQuery({ queryKey: ['portal-inquiries-staff', status, page], queryFn: () => inquiryApi.list(status || undefined, page),
    placeholderData: keepPreviousData });
  const [replies, setReplies] = useState<Record<string, string>>({});
  const refresh = () => qc.invalidateQueries({ queryKey: ['portal-inquiries-staff'] });
  const respond = useMutation({ mutationFn: (i: PortalInquiry) => inquiryApi.respond(i, (replies[i.id] ?? '').trim()),
    onSuccess: (_, i) => { setReplies({ ...replies, [i.id]: '' }); void refresh(); } });
  const close = useMutation({ mutationFn: (i: PortalInquiry) => inquiryApi.close(i), onSuccess: refresh });
  return (
    <>
      <PageHeader title="Portal inquiries" crumbs={[{ label: 'Receivables' }, { label: 'Inquiries' }]} />
      <div className="mb-3 flex gap-2 text-sm">
        {[['OPEN', 'Open'], ['ANSWERED', 'Answered'], ['CLOSED', 'Closed'], ['', 'All']].map(([v, l]) => (
          <button key={v} onClick={() => { setStatus(v!); setPage(0); }} aria-pressed={status === v}
            className={`rounded-md px-3 py-1.5 ${status === v ? 'bg-brand-700 text-white' : 'border border-slate-300 bg-white'}`}>{l}</button>))}
      </div>
      <ErrorAlert error={list.error ?? respond.error ?? close.error} />
      {list.isPending ? <Spinner /> : (
        <ul className="space-y-3">
          {(list.data?.items ?? []).map((i) => (
            <li key={i.id} className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-4 text-sm">
              <div className="flex flex-wrap items-center gap-2"><StatusBadge status={i.status} /><span className="font-mono">{i.reference}</span>
                <span className="font-medium">{i.subject}</span>
                <Link className="text-brand-700 hover:underline" to={`/receivables/customers/${i.customerId}`}>{i.customerCode} {i.customerName}</Link></div>
              <p className="text-xs text-slate-500">From {i.submittedBy} {formatDateTime(i.createdAt)}{i.billingNumber ? ` · billing ${i.billingNumber}` : ''}</p>
              {i.kind === 'PAYMENT_REFERENCE' && <p className="mt-1 rounded bg-amber-50 p-2">Reported payment of <Money value={i.paymentAmount!} /> on {i.paymentDate}
                ({i.paymentMethod?.replace('_', ' ').toLowerCase()}, reference {i.paymentReference}). Nothing has been recorded: check it against the bank
                and record the receipt in Collections.</p>}
              <p className="mt-1 whitespace-pre-wrap">{i.message}</p>
              {i.documents.length > 0 && <p className="mt-1 text-xs">Attachments: {i.documents.map((d) => (
                <button key={d.id} className="mr-2 text-brand-700 hover:underline" onClick={() => voucherApi.download(d)}>{d.fileName}</button>))}</p>}
              {i.response && <div className="mt-2 rounded bg-slate-50 p-2"><p className="text-xs text-slate-500">Answered by {i.respondedBy} {formatDateTime(i.respondedAt)}</p>
                <p className="whitespace-pre-wrap">{i.response}</p></div>}
              {(i.availableActions.includes('RESPOND') || i.availableActions.includes('CLOSE')) && (
                <div className="mt-2 flex flex-wrap items-end gap-2">
                  {i.availableActions.includes('RESPOND') && <>
                    <textarea aria-label={`Reply to ${i.reference}`} rows={2} maxLength={4000} placeholder={i.response ? 'Send a further reply' : 'Reply to the concessionaire'}
                      value={replies[i.id] ?? ''} onChange={(e) => setReplies({ ...replies, [i.id]: e.target.value })}
                      className="min-w-72 flex-1 rounded-md border border-slate-300 px-2 py-1" />
                    <button className="text-brand-700 hover:underline disabled:text-slate-400" disabled={(replies[i.id] ?? '').trim().length < 2 || respond.isPending}
                      onClick={() => respond.mutate(i)}>Send reply</button></>}
                  {i.availableActions.includes('CLOSE') && <button className="text-slate-700 hover:underline" disabled={close.isPending}
                    onClick={() => close.mutate(i)}>Close</button>}
                </div>
              )}
            </li>
          ))}
          {list.data?.items.length === 0 && <li className="rounded-xl border border-slate-200/80 bg-white shadow-xs px-4 py-6 text-center text-slate-500">No inquiries.</li>}
        </ul>
      )}
      {list.data && <Pagination page={list.data.page} totalPages={list.data.totalPages} totalItems={list.data.totalItems} onPage={setPage} />}
      <p className="mt-2 text-xs text-slate-500">The concessionaire is e-mailed when you reply.</p>
    </>
  );
}
