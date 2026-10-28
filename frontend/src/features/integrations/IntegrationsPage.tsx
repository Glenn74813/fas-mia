import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Select } from '../../components/Select';
import { useState } from 'react';
import { integrationApi } from '../../api/endpoints';
import type { Transmission } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Field } from '../../components/Field';
import { PageHeader } from '../../components/PageHeader';
import { Pagination } from '../../components/Pagination';
import { Spinner } from '../../components/Spinner';
import { StatusBadge } from '../../components/StatusBadge';
import { formatDateTime } from '../../utils/format';

export function IntegrationsPage() {
  const { hasPermission } = useAuth();
  const manage = hasPermission('INTEGRATION_MANAGE');
  const qc = useQueryClient();
  const [channel, setChannel] = useState('');
  const [status, setStatus] = useState('');
  const [open, setOpen] = useState<string | null>(null);
  const today = new Date().toISOString().slice(0, 10);
  const [range, setRange] = useState({ from: `${today.slice(0, 7)}-01`, to: today });
  const channels = useQuery({ queryKey: ['integration-channels'], queryFn: integrationApi.channels });
  const [page, setPage] = useState(0);
  const list = useQuery({ queryKey: ['transmissions', channel, status, page], queryFn: () => integrationApi.transmissions(channel, status, page),
    placeholderData: keepPreviousData });
  const refresh = () => { qc.invalidateQueries({ queryKey: ['transmissions'] }); qc.invalidateQueries({ queryKey: ['integration-channels'] }); };
  const queue = useMutation({ mutationFn: () => integrationApi.queuePosted(range.from, range.to), onSuccess: refresh });
  const eis = channels.data?.find((c) => c.channel === 'BIR_EIS');
  return (
    <>
      <PageHeader title="Integrations" crumbs={[{ label: 'Administration' }, { label: 'Integrations' }]} />
      <ErrorAlert error={channels.error} />
      <div className="mb-4 grid gap-4 md:grid-cols-2">
        {(channels.data ?? []).map((c) => (
          <section key={c.channel} className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5 text-sm">
            <h2 className="font-semibold">{c.channel} {c.simulated && <Badge tone="amber">SIMULATED</Badge>}</h2>
            <p className="mt-1 text-slate-600">{c.mode}</p>
            <p className={`mt-1.5 ${c.availability === 'ENABLED' ? 'text-green-800' : 'text-amber-800'}`}>{c.availability}</p>
            <p className="mt-2 text-xs text-slate-500">{Object.entries(c.counts).map(([k, v]) => `${k.toLowerCase()} ${v}`).join(' · ') || 'No transmissions'}</p>
          </section>
        ))}
      </div>
      {manage && eis?.availability === 'ENABLED' && (
        <section className="mb-4 flex flex-wrap items-end gap-3 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5 text-sm">
          <span className="font-medium">Queue posted billings and receipts for BIR EIS</span>
          <Field label="From" type="date" value={range.from} onChange={(e) => setRange({ ...range, from: e.target.value })} />
          <Field label="To" type="date" value={range.to} onChange={(e) => setRange({ ...range, to: e.target.value })} />
          <Button variant="secondary" busy={queue.isPending} onClick={() => queue.mutate()}>Queue</Button>
          {queue.data && <span role="status">{queue.data.queued} document(s) queued.</span>}
          <p className="w-full text-xs text-slate-500">Documents are sent in CAS's canonical format, which is not the BIR EIS schema; production transmission awaits BIR's specification and Permit to Transmit (C-06).</p>
        </section>
      )}
      <ErrorAlert error={queue.error} />
      <div className="mb-3 flex gap-3">
        <Select aria-label="Channel" value={channel} onChange={(e) => { setChannel(e.target.value); setPage(0); }} className="rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm">
          <option value="">All channels</option>{(channels.data ?? []).map((c) => <option key={c.channel}>{c.channel}</option>)}</Select>
        <Select aria-label="Status" value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }} className="rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm">
          {['', 'PENDING', 'SENDING', 'SENT', 'ACKNOWLEDGED', 'REJECTED', 'DEAD', 'CANCELLED'].map((s) => <option key={s} value={s}>{s || 'All statuses'}</option>)}</Select>
      </div>
      <ErrorAlert error={list.error} />
      {list.isPending ? <Spinner /> : (
        <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs"><table className="min-w-full text-sm">
          <thead className="border-b border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="px-3 py-2">Queued</th><th className="px-3 py-2">Channel</th>
            <th className="px-3 py-2">Document</th><th className="px-3 py-2">Status</th><th className="px-3 py-2">Attempts</th><th className="px-3 py-2">Reference / error</th><th /></tr></thead>
          <tbody>{(list.data?.items ?? []).map((t) => (
            <Row key={t.id} t={t} open={open === t.id} onToggle={() => setOpen(open === t.id ? null : t.id)} manage={manage} onChanged={refresh} />))}
            {list.data?.items.length === 0 && <tr><td colSpan={7} className="px-4 py-6 text-center text-slate-500">No transmissions.</td></tr>}</tbody>
        </table></div>
      )}
      {list.data && <Pagination page={list.data.page} totalPages={list.data.totalPages} totalItems={list.data.totalItems} onPage={setPage} />}
    </>
  );
}

function Row({ t, open, onToggle, manage, onChanged }: { t: Transmission; open: boolean; onToggle: () => void; manage: boolean; onChanged: () => void }) {
  const detail = useQuery({ queryKey: ['transmission', t.id], queryFn: () => integrationApi.transmission(t.id), enabled: open });
  const retry = useMutation({ mutationFn: () => integrationApi.retry(t.id), onSuccess: onChanged });
  const cancel = useMutation({ mutationFn: () => integrationApi.cancel(t.id, 'Cancelled from the integrations page'), onSuccess: onChanged });
  return (
    <>
      <tr className="border-t border-slate-100">
        <td className="px-3 py-2">{formatDateTime(t.createdAt)}</td><td className="px-3 py-2">{t.channel}{t.simulated ? ' (simulated)' : ''}</td>
        <td className="px-3 py-2">{t.documentType}</td><td className="px-3 py-2"><StatusBadge status={t.status} /></td>
        <td className="px-3 py-2">{t.attempts}</td><td className="px-3 py-2">{t.externalReference ?? t.lastError ?? '—'}</td>
        <td className="px-3 py-2 text-right whitespace-nowrap">
          <button className="text-xs text-brand-700" onClick={onToggle}>{open ? 'Hide' : 'Details'}</button>
          {manage && ['DEAD', 'REJECTED'].includes(t.status) && <button className="ml-3 text-xs text-brand-700" onClick={() => retry.mutate()}>Retry</button>}
          {manage && ['PENDING', 'DEAD', 'REJECTED'].includes(t.status) && <button className="ml-3 text-xs text-red-700" onClick={() => cancel.mutate()}>Cancel</button>}
        </td></tr>
      {open && (
        <tr><td colSpan={7} className="bg-slate-50 px-3 py-3 text-xs">
          <ErrorAlert error={detail.error ?? retry.error ?? cancel.error} />
          {detail.data && <>
            <p className="mb-1">Key {detail.data.idempotencyKey} · payload SHA-256 {detail.data.payloadSha256}</p>
            <ul className="mb-2 space-y-1">{detail.data.attemptLog.map((a) => <li key={a.attemptNo}>#{a.attemptNo} {formatDateTime(a.startedAt)} — {a.outcome}
              {a.httpStatus ? ` (HTTP ${a.httpStatus})` : ''} {a.responseExcerpt ?? ''}</li>)}</ul>
            <pre className="max-h-64 overflow-auto rounded bg-white p-2">{JSON.stringify(detail.data.payload, null, 2)}</pre></>}
        </td></tr>
      )}
    </>
  );
}
