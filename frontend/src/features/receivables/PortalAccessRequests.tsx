import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router';
import { portalAccessApi, voucherApi } from '../../api/endpoints';
import type { PortalLinkRequest } from '../../api/types';
import { ErrorAlert } from '../../components/ErrorAlert';
import { PageHeader } from '../../components/PageHeader';
import { Pagination } from '../../components/Pagination';
import { StatusBadge } from '../../components/StatusBadge';
import { formatDateTime } from '../../utils/format';

/** Link requests for one customer, or (without a customer) every pending one, with decide / withdraw / attach actions. */
export function PortalRequestList({ customerId, status }: { customerId?: string; status?: string }) {
  const qc = useQueryClient();
  const [page, setPage] = useState(0);
  const list = useQuery({ queryKey: ['portal-link-requests', customerId ?? 'all', status ?? '', page],
    queryFn: () => portalAccessApi.list({ customerId, status, page }), placeholderData: keepPreviousData });
  const [notes, setNotes] = useState<Record<string, string>>({});
  const refresh = () => { qc.invalidateQueries({ queryKey: ['portal-link-requests'] }); qc.invalidateQueries({ queryKey: ['portal-users'] }); };
  const decide = useMutation({ mutationFn: ({ r, approve }: { r: PortalLinkRequest; approve: boolean }) => portalAccessApi.decide(r.id, approve, notes[r.id] ?? ''),
    onSuccess: refresh });
  const cancel = useMutation({ mutationFn: (r: PortalLinkRequest) => portalAccessApi.cancel(r.id), onSuccess: refresh });
  const upload = useMutation({ mutationFn: ({ r, f }: { r: PortalLinkRequest; f: File }) => portalAccessApi.upload(r.id, f), onSuccess: refresh });
  const rows = list.data?.items ?? [];
  return (
    <div className="space-y-2">
      <ErrorAlert error={list.error ?? decide.error ?? cancel.error ?? upload.error} />
      {rows.map((r) => (
        <div key={r.id} className="rounded-md border border-slate-200 p-3 text-sm">
          <div className="flex flex-wrap items-center gap-2"><StatusBadge status={r.status} />
            <span className="font-mono">{r.username}</span> {r.displayName} ({r.email}) →
            {customerId ? ` ${r.customerCode}` : <Link className="text-brand-700 hover:underline" to={`/receivables/customers/${r.customerId}`}>{r.customerCode} {r.customerName}</Link>}</div>
          <p className="mt-1">Evidence: {r.evidence}</p>
          <p className="text-xs text-slate-500">Requested by {r.requestedBy} {formatDateTime(r.requestedAt)}
            {r.decidedBy ? ` · ${r.status.toLowerCase()} by ${r.decidedBy} ${formatDateTime(r.decidedAt)}: ${r.decisionNote}` : ''}</p>
          {r.documents.length > 0 && <p className="mt-1 text-xs">Attachments: {r.documents.map((d) => (
            <button key={d.id} className="mr-2 text-brand-700 hover:underline" onClick={() => voucherApi.download(d)}>{d.fileName}</button>))}</p>}
          <div className="mt-2 flex flex-wrap items-center gap-2">
            {r.availableActions.includes('UPLOAD') && <label className="text-xs">Attach authorisation letter
              <input type="file" accept=".pdf,.png,.jpg,.jpeg" className="ml-2 text-xs" onChange={(e) => { const f = e.target.files?.[0]; if (f) upload.mutate({ r, f }); }} /></label>}
            {r.availableActions.includes('APPROVE') && <>
              <input aria-label={`Decision note for ${r.username}`} placeholder="How the evidence was checked" value={notes[r.id] ?? ''}
                onChange={(e) => setNotes({ ...notes, [r.id]: e.target.value })} className="w-64 rounded-md border border-slate-300 px-2 py-1" />
              <button className="text-xs text-brand-700" disabled={(notes[r.id] ?? '').trim().length < 5} onClick={() => decide.mutate({ r, approve: true })}>Approve access</button>
              <button className="text-xs text-red-700" disabled={(notes[r.id] ?? '').trim().length < 5} onClick={() => decide.mutate({ r, approve: false })}>Reject</button></>}
            {r.availableActions.includes('CANCEL') && <button className="text-xs text-red-700" onClick={() => cancel.mutate(r)}>Withdraw request</button>}
          </div>
        </div>
      ))}
      {list.isSuccess && rows.length === 0 && <p className="text-sm text-slate-500">No requests.</p>}
      {list.data && list.data.totalPages > 1 && <Pagination page={list.data.page} totalPages={list.data.totalPages} totalItems={list.data.totalItems}
        onPage={setPage} />}
    </div>
  );
}

export function PortalAccessPage() {
  return (
    <>
      <PageHeader title="Portal access requests" crumbs={[{ label: 'Receivables' }, { label: 'Portal access' }]} />
      <p className="mb-4 text-sm text-slate-600">A concessionaire account sees a customer's records only after a second person approves the link, having checked the evidence
        of identity and authority (C-18). Removing access is immediate and is done from the customer's page.</p>
      <PortalRequestList status="PENDING" />
    </>
  );
}
