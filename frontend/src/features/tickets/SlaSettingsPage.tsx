import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Select } from '../../components/Select';
import { useState } from 'react';
import { ticketApi } from '../../api/endpoints';
import { useAuth } from '../../auth/AuthContext';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Field } from '../../components/Field';
import { PageHeader } from '../../components/PageHeader';
import type { TicketCategory } from '../../api/types';
import { TICKET_TYPES, useTicketCategories } from './TicketsPage';

export function SlaSettingsPage() {
  const { hasPermission } = useAuth();
  const admin = hasPermission('TICKET_ADMIN');
  const qc = useQueryClient();
  const policies = useQuery({ queryKey: ['sla-policies'], queryFn: ticketApi.policies });
  const calendar = useQuery({ queryKey: ['business-calendar'], queryFn: ticketApi.calendar });
  const today = new Date().toISOString().slice(0, 10);
  const [range, setRange] = useState({ from: `${today.slice(0, 7)}-01`, to: today });
  const summary = useQuery({ queryKey: ['sla-summary', range], queryFn: () => ticketApi.summary(range.from, range.to),
    enabled: hasPermission('TICKET_VIEW_ALL') || hasPermission('TICKET_WORK') });
  const [p, setP] = useState({ contractPhase: '', ticketType: 'INCIDENT', priority: 'P1', responseMinutes: '', resolutionMinutes: '', businessHours: true });
  const [h, setH] = useState({ date: '', name: '' });
  const create = useMutation({ mutationFn: () => ticketApi.createPolicy({ ...p, contractPhase: p.contractPhase || calendar.data!.contractPhase,
    responseMinutes: Number(p.responseMinutes), resolutionMinutes: Number(p.resolutionMinutes) }), onSuccess: () => qc.invalidateQueries({ queryKey: ['sla-policies'] }) });
  const addHoliday = useMutation({ mutationFn: () => ticketApi.addHoliday(h.date, h.name), onSuccess: (c) => { qc.setQueryData(['business-calendar'], c); setH({ date: '', name: '' }); } });
  const removeHoliday = useMutation({ mutationFn: (d: string) => ticketApi.removeHoliday(d), onSuccess: (c) => qc.setQueryData(['business-calendar'], c) });
  const active = (policies.data ?? []).filter((x) => x.status === 'ACTIVE');
  return (
    <>
      <PageHeader title="Service levels" crumbs={[{ label: 'Support' }, { label: 'Service levels' }]} />
      <ErrorAlert error={policies.error ?? calendar.error} />
      {calendar.data && <p className="mb-3 text-sm text-slate-600">Current contract phase <span className="font-mono">{calendar.data.contractPhase}</span> · business hours
        {' '}{calendar.data.start}–{calendar.data.end} {calendar.data.zone}, {calendar.data.workdays.map((d) => d.slice(0, 3).toLowerCase()).join(' ')}.
        SLA values are contract configuration (C-15).</p>}
      <section className="mb-4 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5 text-sm">
        <h2 className="mb-2 font-semibold">Active SLA policies</h2>
        <table className="min-w-full"><thead className="border-b border-slate-200 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="py-1 pr-3">Phase</th><th className="py-1 pr-3">Type</th>
          <th className="py-1 pr-3">Priority</th><th className="py-1 pr-3">Respond</th><th className="py-1 pr-3">Resolve</th><th className="py-1 pr-3">Clock</th><th /></tr></thead>
          <tbody>{active.map((x) => (
            <tr key={x.id} className="border-t border-slate-100"><td className="py-1.5 pr-3">{x.contractPhase}</td><td className="py-1.5 pr-3">{TICKET_TYPES[x.ticketType]}</td>
              <td className="py-1.5 pr-3">{x.priority}</td><td className="py-1.5 pr-3">{x.responseMinutes} min</td><td className="py-1.5 pr-3">{x.resolutionMinutes} min</td>
              <td className="py-1.5 pr-3">{x.businessHours ? 'Business hours' : '24x7'}</td><td className="py-1.5">{x.placeholder && <Badge tone="amber">PLACEHOLDER</Badge>}</td></tr>))}
            {active.length === 0 && <tr><td colSpan={7} className="py-3 text-center text-slate-500">No SLA policies configured; tickets carry no deadlines.</td></tr>}</tbody></table>
        {admin && (
          <div className="mt-4 space-y-2 rounded-md border border-slate-200 p-4">
            <ErrorAlert error={create.error} />
            <div className="flex flex-wrap items-end gap-3">
              <Field label="Contract phase" placeholder={calendar.data?.contractPhase} value={p.contractPhase} onChange={(e) => setP({ ...p, contractPhase: e.target.value.toUpperCase() })} />
              <label>Type <Select value={p.ticketType} onChange={(e) => setP({ ...p, ticketType: e.target.value })} className="ml-1 rounded-md border border-slate-300 px-2 py-1">
                {Object.entries(TICKET_TYPES).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</Select></label>
              <label>Priority <Select value={p.priority} onChange={(e) => setP({ ...p, priority: e.target.value })} className="ml-1 rounded-md border border-slate-300 px-2 py-1">
                {['P1', 'P2', 'P3', 'P4'].map((x) => <option key={x}>{x}</option>)}</Select></label>
              <Field label="Respond within (min)" inputMode="numeric" value={p.responseMinutes} onChange={(e) => setP({ ...p, responseMinutes: e.target.value.replace(/\D/g, '') })} />
              <Field label="Resolve within (min)" inputMode="numeric" value={p.resolutionMinutes} onChange={(e) => setP({ ...p, resolutionMinutes: e.target.value.replace(/\D/g, '') })} />
              <label className="flex items-center gap-2"><input type="checkbox" checked={p.businessHours} onChange={(e) => setP({ ...p, businessHours: e.target.checked })} />Business hours</label>
              <Button variant="secondary" busy={create.isPending} disabled={!p.responseMinutes || !p.resolutionMinutes} onClick={() => create.mutate()}>Set policy</Button>
            </div>
            <p className="text-xs text-slate-500">Setting a policy replaces the active one for the same phase, type and priority; new tickets use it.</p>
          </div>
        )}
      </section>
      <section className="mb-4 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5 text-sm">
        <h2 className="mb-2 font-semibold">Holidays (not counted on business-hours clocks)</h2>
        <ul className="mb-2">{(calendar.data?.holidays ?? []).map((x) => <li key={x.date}>{x.date} {x.name}
          {admin && <button className="ml-2 text-xs text-red-700" onClick={() => removeHoliday.mutate(x.date)}>Remove</button>}</li>)}
          {calendar.data?.holidays.length === 0 && <li className="text-slate-500">None entered. Enter the holidays AVTECH observes.</li>}</ul>
        {admin && <div className="flex flex-wrap items-end gap-3"><Field label="Date" type="date" value={h.date} onChange={(e) => setH({ ...h, date: e.target.value })} />
          <Field label="Holiday name" value={h.name} onChange={(e) => setH({ ...h, name: e.target.value })} />
          <Button variant="secondary" disabled={!h.date || !h.name} onClick={() => addHoliday.mutate()}>Add holiday</Button></div>}
        <ErrorAlert error={addHoliday.error ?? removeHoliday.error} />
      </section>
      {summary.data && (
        <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5 text-sm">
          <div className="mb-2 flex flex-wrap items-end gap-3"><h2 className="font-semibold">SLA compliance</h2>
            <Field label="From" type="date" value={range.from} onChange={(e) => setRange({ ...range, from: e.target.value })} />
            <Field label="To" type="date" value={range.to} onChange={(e) => setRange({ ...range, to: e.target.value })} /></div>
          <table className="min-w-full"><thead className="border-b border-slate-200 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="py-1 pr-3">Type</th><th className="py-1 pr-3">Priority</th>
            <th className="py-1 pr-3">Total</th><th className="py-1 pr-3">Responded in time</th><th className="py-1 pr-3">Response breached</th>
            <th className="py-1 pr-3">Resolved in time</th><th className="py-1 pr-3">Resolution breached</th><th className="py-1 pr-3">Open</th></tr></thead>
            <tbody>{summary.data.map((r, i) => (
              <tr key={i} className="border-t border-slate-100"><td className="py-1.5 pr-3">{TICKET_TYPES[r.ticketType as string]}</td><td className="py-1.5 pr-3">{r.priority}</td>
                <td className="py-1.5 pr-3">{r.total}</td><td className="py-1.5 pr-3">{r.respondedInTime}</td><td className="py-1.5 pr-3">{r.responseBreached}</td>
                <td className="py-1.5 pr-3">{r.resolvedInTime}</td><td className="py-1.5 pr-3">{r.resolutionBreached}</td><td className="py-1.5 pr-3">{r.open}</td></tr>))}
              {summary.data.length === 0 && <tr><td colSpan={8} className="py-3 text-center text-slate-500">No tickets in the range.</td></tr>}</tbody></table>
        </section>
      )}
      <CategoriesSection admin={admin} />
    </>
  );
}

function CategoriesSection({ admin }: { admin: boolean }) {
  const qc = useQueryClient();
  const categories = useTicketCategories();
  const [form, setForm] = useState({ code: '', name: '', sortOrder: '100' });
  const refresh = () => qc.invalidateQueries({ queryKey: ['ticket-categories'] });
  const create = useMutation({ mutationFn: () => ticketApi.createCategory({ code: form.code.trim(), name: form.name.trim(), sortOrder: Number(form.sortOrder) }),
    onSuccess: () => { setForm({ code: '', name: '', sortOrder: '100' }); void refresh(); } });
  const toggle = useMutation({ mutationFn: (c: TicketCategory) => ticketApi.updateCategory(c.id, { name: c.name, sortOrder: c.sortOrder,
    status: c.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE' }), onSuccess: () => void refresh() });
  return (
    <section className="mt-4 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5 text-sm" aria-label="Ticket categories">
      <h2 className="mb-2 font-semibold">Ticket categories</h2>
      <ErrorAlert error={categories.error ?? toggle.error} />
      <ul className="mb-3 divide-y divide-slate-100">
        {(categories.data ?? []).map((c) => (
          <li key={c.id} className="flex items-center justify-between py-1.5">
            <span><span className="font-mono text-xs">{c.code}</span> {c.name} {c.status === 'INACTIVE' && <Badge>INACTIVE</Badge>}</span>
            {admin && <button className="text-xs text-brand-700 hover:underline" disabled={toggle.isPending} onClick={() => toggle.mutate(c)}>
              {c.status === 'ACTIVE' ? 'Deactivate' : 'Reactivate'}</button>}
          </li>
        ))}
      </ul>
      {admin && (
        <div className="flex flex-wrap items-end gap-2">
          <Field label="Category code" value={form.code} maxLength={30} onChange={(e) => setForm({ ...form, code: e.target.value.toUpperCase() })} />
          <Field label="Category name" value={form.name} maxLength={120} onChange={(e) => setForm({ ...form, name: e.target.value })} />
          <Field label="Order" type="number" value={form.sortOrder} onChange={(e) => setForm({ ...form, sortOrder: e.target.value })} />
          <Button variant="secondary" busy={create.isPending} disabled={form.code.trim().length < 2 || !form.name.trim()} onClick={() => create.mutate()}>Add category</Button>
          <ErrorAlert error={create.error} />
        </div>
      )}
    </section>
  );
}
