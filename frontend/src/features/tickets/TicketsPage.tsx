import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Select } from '../../components/Select';
import { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { ticketApi } from '../../api/endpoints';
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

export const TICKET_TYPES: Record<string, string> = { INCIDENT: 'Incident', SERVICE_REQUEST: 'Service request', CHANGE_REQUEST: 'Change request',
  ENHANCEMENT: 'Enhancement' };

/** Business impact, separate from the priority that drives the SLA. [PLACEHOLDER] definitions until AVTECH agrees them (C-34). */
export const SEVERITIES: Record<string, string> = { S1: 'S1 — finance operations stopped', S2: 'S2 — a key function impaired',
  S3: 'S3 — limited impact, workaround exists', S4: 'S4 — minimal or none' };

export function useTicketCategories() {
  return useQuery({ queryKey: ['ticket-categories'], queryFn: ticketApi.categories, staleTime: 300_000 });
}

export function TicketsPage() {
  const { hasPermission } = useAuth();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const staff = hasPermission('TICKET_VIEW_ALL') || hasPermission('TICKET_WORK');
  const [all, setAll] = useState(staff);
  const [status, setStatus] = useState('');
  const [type, setType] = useState('');
  const [severity, setSeverity] = useState('');
  const [categoryId, setCategoryId] = useState('');
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState({ ticketType: 'INCIDENT', priority: 'P3', severity: 'S3', categoryId: '', title: '', description: '', module: '' });
  const [page, setPage] = useState(0);
  const categories = useTicketCategories();
  const active = (categories.data ?? []).filter((c) => c.status === 'ACTIVE');
  const list = useQuery({ queryKey: ['tickets', all, status, type, severity, categoryId, page],
    queryFn: () => ticketApi.list(all, status, type, page, severity, categoryId), placeholderData: keepPreviousData });
  const create = useMutation({ mutationFn: () => ticketApi.create({ ...form, module: form.module || null }),
    onSuccess: (t) => { qc.invalidateQueries({ queryKey: ['tickets'] }); navigate(`/tickets/${t.id}`); } });
  return (
    <>
      <PageHeader title="Support tickets" crumbs={[{ label: 'Support' }, { label: 'Tickets' }]}
        actions={hasPermission('TICKET_CREATE') && <Button onClick={() => setOpen(true)}>New ticket</Button>} />
      {open && (
        <section className="mb-4 space-y-3 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
          <ErrorAlert error={create.error} />
          <div className="grid gap-3 md:grid-cols-3">
            <label className="block text-sm">Type
              <Select value={form.ticketType} onChange={(e) => setForm({ ...form, ticketType: e.target.value })} className="mt-1.5 block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs">
                {Object.entries(TICKET_TYPES).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</Select></label>
            <label className="block text-sm">Priority
              <Select value={form.priority} onChange={(e) => setForm({ ...form, priority: e.target.value })} className="mt-1.5 block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs">
                <option value="P1">P1 — critical, operations stopped</option><option value="P2">P2 — high, major function impaired</option>
                <option value="P3">P3 — medium, workaround exists</option><option value="P4">P4 — low, cosmetic or question</option></Select></label>
            <label className="block text-sm">Severity
              <Select value={form.severity} onChange={(e) => setForm({ ...form, severity: e.target.value })} className="mt-1.5 block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs">
                {Object.entries(SEVERITIES).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</Select></label>
            <label className="block text-sm">Category
              <Select value={form.categoryId} onChange={(e) => setForm({ ...form, categoryId: e.target.value })} className="mt-1.5 block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs">
                <option value="">Choose…</option>{active.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}</Select></label>
            <Field label="Module (optional)" value={form.module} onChange={(e) => setForm({ ...form, module: e.target.value })} />
            <div className="md:col-span-3"><Field label="Title" value={form.title} maxLength={200} onChange={(e) => setForm({ ...form, title: e.target.value })} /></div>
            <label className="block text-sm md:col-span-3">Description
              <textarea value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} rows={5} maxLength={4000}
                className="mt-1.5 block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs" /></label>
          </div>
          <p className="text-xs text-slate-500">Do not put passwords, account numbers or other secrets in a ticket.</p>
          <div className="flex gap-2"><Button busy={create.isPending} disabled={!form.title.trim() || !form.description.trim() || !form.categoryId} onClick={() => create.mutate()}>Submit ticket</Button>
            <Button variant="secondary" onClick={() => setOpen(false)}>Cancel</Button></div>
        </section>
      )}
      <div className="mb-3 flex flex-wrap items-center gap-3 text-sm">
        {staff && <label className="flex items-center gap-2"><input type="checkbox" checked={all} onChange={(e) => { setAll(e.target.checked); setPage(0); }} />All tickets</label>}
        <Select aria-label="Filter by type" value={type} onChange={(e) => { setType(e.target.value); setPage(0); }} className="rounded-lg border border-slate-300 px-3 py-2 shadow-xs">
          <option value="">All types</option>{Object.entries(TICKET_TYPES).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</Select>
        <Select aria-label="Filter by status" value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }} className="rounded-lg border border-slate-300 px-3 py-2 shadow-xs">
          {['', 'NEW', 'ACKNOWLEDGED', 'PENDING_APPROVAL', 'APPROVED', 'IN_PROGRESS', 'ON_HOLD', 'RESOLVED', 'CLOSED', 'REJECTED', 'CANCELLED']
            .map((s) => <option key={s} value={s}>{s ? s.replace('_', ' ') : 'All statuses'}</option>)}</Select>
        <Select aria-label="Filter by severity" value={severity} onChange={(e) => { setSeverity(e.target.value); setPage(0); }} className="rounded-lg border border-slate-300 px-3 py-2 shadow-xs">
          <option value="">All severities</option>{Object.keys(SEVERITIES).map((k) => <option key={k} value={k}>{k}</option>)}</Select>
        <Select aria-label="Filter by category" value={categoryId} onChange={(e) => { setCategoryId(e.target.value); setPage(0); }} className="rounded-lg border border-slate-300 px-3 py-2 shadow-xs">
          <option value="">All categories</option>{(categories.data ?? []).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}</Select>
      </div>
      <ErrorAlert error={list.error} />
      {list.isPending ? <Spinner /> : (
        <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs"><table className="min-w-full text-sm">
          <thead className="border-b border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="px-3 py-2">Ticket</th><th className="px-3 py-2">Type</th>
            <th className="px-3 py-2">Priority</th><th className="px-3 py-2">Severity</th><th className="px-3 py-2">Title</th><th className="px-3 py-2">Status</th><th className="px-3 py-2">Requester</th>
            <th className="px-3 py-2">Assignee</th><th className="px-3 py-2">Resolve by</th></tr></thead>
          <tbody>{(list.data?.items ?? []).map((t) => (
            <tr key={t.id} className="border-t border-slate-100"><td className="px-3 py-2 font-mono"><Link className="text-brand-700 hover:underline" to={`/tickets/${t.id}`}>{t.ticketNumber}</Link></td>
              <td className="px-3 py-2">{TICKET_TYPES[t.ticketType]}</td><td className="px-3 py-2">{t.priority}</td><td className="px-3 py-2">{t.severity ?? '—'}</td>
              <td className="px-3 py-2">{t.title}{t.category && <span className="block text-xs text-slate-500">{t.category}</span>}</td>
              <td className="px-3 py-2"><StatusBadge status={t.status} /></td><td className="px-3 py-2">{t.reporter}</td><td className="px-3 py-2">{t.assignee ?? '—'}</td>
              <td className="px-3 py-2">{t.resolutionDueAt ? formatDateTime(t.resolutionDueAt) : 'No SLA'}{t.breached && <span className="ml-1"><Badge tone="red">BREACHED</Badge></span>}
                {!t.breached && t.escalationLevel > 0 && <span className="ml-1"><Badge tone="amber">AT RISK</Badge></span>}</td></tr>))}
            {list.data?.items.length === 0 && <tr><td colSpan={9} className="px-4 py-6 text-center text-slate-500">No tickets.</td></tr>}</tbody>
        </table></div>
      )}
      {list.data && <Pagination page={list.data.page} totalPages={list.data.totalPages} totalItems={list.data.totalItems} onPage={setPage} />}
    </>
  );
}
