import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Select } from '../../components/Select';
import { useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { closeApi } from '../../api/endpoints';
import type { CloseFrequency, CloseTask, CloseTaskItem } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Field } from '../../components/Field';
import { PageHeader } from '../../components/PageHeader';
import { Spinner } from '../../components/Spinner';
import { useFiscalYears } from '../../hooks/useAccounting';
import { formatDateTime } from '../../utils/format';

const FREQUENCIES: { code: CloseFrequency; label: string }[] = [
  { code: 'DAY_END', label: 'Day end' }, { code: 'MONTH_END', label: 'Month end' },
  { code: 'QUARTER_END', label: 'Quarter end' }, { code: 'YEAR_END', label: 'Year end' },
];

const isoDay = (d: Date) => d.toISOString().slice(0, 10);
const addDays = (iso: string, n: number) => { const d = new Date(`${iso}T00:00:00Z`); d.setUTCDate(d.getUTCDate() + n); return isoDay(d); };

/** Close checklists (automatic checks plus owned tasks), the closing schedule and the close-task templates (MS §5.4). */
export function CloseCalendarPage() {
  const { hasPermission } = useAuth();
  const [params, setParams] = useSearchParams();
  const frequency = (params.get('frequency') as CloseFrequency | null) ?? 'DAY_END';
  const scope = params.get('scope') ?? (frequency === 'DAY_END' ? isoDay(new Date()) : '');
  const choose = (f: CloseFrequency, s: string) => setParams({ frequency: f, scope: s }, { replace: true });
  return (
    <>
      <PageHeader title="Close calendar" crumbs={[{ label: 'General ledger' }, { label: 'Close calendar' }]} />
      <div className="mb-3 flex flex-wrap gap-2" role="tablist" aria-label="Checklist type">
        {FREQUENCIES.map((f) => (
          <button key={f.code} role="tab" aria-selected={f.code === frequency} onClick={() => choose(f.code, f.code === 'DAY_END' ? isoDay(new Date()) : '')}
            className={`rounded-md px-3 py-1.5 text-sm ${f.code === frequency ? 'bg-brand-700 text-white' : 'bg-slate-100 text-slate-700'}`}>{f.label}</button>
        ))}
      </div>
      <ScopePicker frequency={frequency} scope={scope} onChange={(s) => choose(frequency, s)} />
      {scope && <ChecklistView frequency={frequency} scope={scope} />}
      <Schedule />
      {hasPermission('CLOSE_TASK_MANAGE') && <TaskAdmin />}
    </>
  );
}

function ScopePicker({ frequency, scope, onChange }: { frequency: CloseFrequency; scope: string; onChange: (s: string) => void }) {
  const years = useFiscalYears();
  const select = (label: string, options: { value: string; label: string }[]) => (
    <label className="text-sm font-medium text-slate-700">{label}
      <Select value={scope} onChange={(e) => onChange(e.target.value)} className="block w-72 rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm">
        <option value="">Choose…</option>
        {options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
      </Select></label>
  );
  const fys = years.data ?? [];
  return (
    <div className="mb-4">
      {frequency === 'DAY_END' && <Field label="Day" type="date" value={scope} onChange={(e) => onChange(e.target.value)} />}
      {frequency === 'MONTH_END' && select('Period', fys.flatMap((y) => y.periods.filter((p) => p.periodType === 'REGULAR')
        .map((p) => ({ value: p.id, label: `${p.name} (${p.status.toLowerCase().replace('_', ' ')})` }))))}
      {frequency === 'QUARTER_END' && select('Quarter', fys.flatMap((y) => [1, 2, 3, 4].map((q) => ({ value: `${y.id}:Q${q}`, label: `Q${q} ${y.fiscalYear}` }))))}
      {frequency === 'YEAR_END' && select('Fiscal year', fys.map((y) => ({ value: y.id, label: `${y.fiscalYear} (${y.status.toLowerCase()})` })))}
    </div>
  );
}

function ChecklistView({ frequency, scope }: { frequency: CloseFrequency; scope: string }) {
  const checklist = useQuery({ queryKey: ['close-checklist', frequency, scope], queryFn: () => closeApi.checklist(frequency, scope) });
  if (checklist.isPending) return <Spinner />;
  if (checklist.error) return <ErrorAlert error={checklist.error} />;
  const c = checklist.data;
  return (
    <section className="mb-4 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5" aria-label="Checklist">
      <h2 className="mb-1 font-semibold">{c.label}</h2>
      <p className="mb-3 text-xs text-slate-500">Ends {c.scopeEnd}. Automatic checks are computed now; tasks are ticked off by their owners.</p>
      <h3 className="mb-1 text-sm font-semibold">Automatic checks</h3>
      <ul className="mb-4 space-y-2 text-sm">
        {c.checks.map((k) => (
          <li key={k.code}>
            <div className="flex items-center gap-2"><Badge tone={k.outcome === 'PASS' ? 'green' : k.outcome === 'FAIL' ? 'red' : 'slate'}>
              {k.outcome === 'NOT_AVAILABLE' ? 'N/A' : k.outcome}</Badge><span>{k.description}</span>
              {k.detail && <span className="text-xs text-slate-500">{k.detail}</span>}</div>
            {k.exceptions.length > 0 && (
              <ul className="ml-14 list-disc text-xs text-slate-600">{k.exceptions.map((e) => <li key={e}>{e}</li>)}</ul>
            )}
          </li>
        ))}
      </ul>
      {frequency === 'MONTH_END' && <p className="mb-3 text-xs text-slate-500">Close the period, with any waivers, on <Link className="text-brand-700 hover:underline"
        to="/fiscal-periods">Fiscal periods</Link>.</p>}
      <h3 className="mb-1 text-sm font-semibold">Tasks</h3>
      <ul className="space-y-2 text-sm">
        {c.tasks.map((t) => <TaskRow key={t.taskId} task={t} frequency={frequency} scope={c.scopeKey} />)}
        {c.tasks.length === 0 && <li className="text-slate-500">No active tasks for this checklist.</li>}
      </ul>
    </section>
  );
}

function TaskRow({ task, frequency, scope }: { task: CloseTaskItem; frequency: CloseFrequency; scope: string }) {
  const qc = useQueryClient();
  const [outcome, setOutcome] = useState<'DONE' | 'NOT_APPLICABLE'>('DONE');
  const [note, setNote] = useState('');
  const complete = useMutation({ mutationFn: () => closeApi.complete(task.taskId, scope, outcome, note.trim()),
    onSuccess: (data) => {
      qc.setQueryData(['close-checklist', frequency, scope], data);
      void qc.invalidateQueries({ queryKey: ['close-schedule'] });
      void qc.invalidateQueries({ queryKey: ['inbox'] });
    } });
  const tone = task.status === 'OPEN' ? (task.overdue ? 'red' : 'amber') : 'green';
  return (
    <li className="rounded-md border border-slate-200 p-3" aria-label={task.name}>
      <div className="flex flex-wrap items-center gap-2"><Badge tone={tone}>{task.status === 'OPEN' ? (task.overdue ? 'OVERDUE' : 'OPEN')
        : task.status.replace('_', ' ')}</Badge><strong>{task.name}</strong>
        <span className="text-xs text-slate-500">due {task.dueDate} · owner: {task.responsiblePermission}</span></div>
      {task.instructions && <p className="text-xs text-slate-600">{task.instructions}</p>}
      {task.completedBy && <p className="text-xs text-slate-500">{task.note} — {task.completedBy} {formatDateTime(task.completedAt)}</p>}
      {task.canComplete && (
        <div className="mt-2 flex flex-wrap items-end gap-2">
          <label className="text-sm font-medium text-slate-700">Outcome
            <Select value={outcome} onChange={(e) => setOutcome(e.target.value as 'DONE' | 'NOT_APPLICABLE')}
              className="block rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm">
              <option value="DONE">Done</option><option value="NOT_APPLICABLE">Not applicable</option></Select></label>
          <div className="w-96"><Field label="Note" value={note} maxLength={1000} onChange={(e) => setNote(e.target.value)} /></div>
          <Button variant="secondary" disabled={note.trim().length < 3} busy={complete.isPending} onClick={() => complete.mutate()}>Complete task</Button>
        </div>
      )}
      <ErrorAlert error={complete.error} />
    </li>
  );
}

function Schedule() {
  const today = isoDay(new Date());
  const [from, setFrom] = useState(addDays(today, -7));
  const [to, setTo] = useState(addDays(today, 30));
  const schedule = useQuery({ queryKey: ['close-schedule', from, to], queryFn: () => closeApi.schedule(from, to), enabled: !!from && !!to });
  const label = (f: CloseFrequency) => FREQUENCIES.find((x) => x.code === f)?.label ?? f;
  return (
    <section className="mb-4 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5" aria-label="Closing schedule">
      <h2 className="mb-2 font-semibold">Closing schedule</h2>
      <div className="mb-3 flex flex-wrap gap-3">
        <Field label="Due from" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
        <Field label="Due to" type="date" value={to} onChange={(e) => setTo(e.target.value)} />
      </div>
      <ErrorAlert error={schedule.error} />
      <p className="mb-2 text-xs text-slate-500">Day-end tasks fall on weekdays; public holidays are not modelled.</p>
      <div className="max-h-96 overflow-y-auto">
        <table className="w-full text-sm">
          <thead><tr className="text-left text-xs uppercase text-slate-500"><th className="py-1">Due</th><th className="py-1">Task</th>
            <th className="py-1">For</th><th className="py-1">Status</th></tr></thead>
          <tbody>
            {(schedule.data ?? []).map((s) => (
              <tr key={`${s.taskId}-${s.scopeKey}`} className="border-t border-slate-100">
                <td className="py-1">{s.dueDate}</td><td className="py-1">{s.taskName}</td>
                <td className="py-1"><Link className="text-brand-700 hover:underline" to={`/close?frequency=${s.frequency}&scope=${encodeURIComponent(s.scopeKey)}`}>
                  {label(s.frequency)}: {s.scopeLabel}</Link></td>
                <td className="py-1"><Badge tone={s.status === 'OPEN' ? (s.overdue ? 'red' : 'amber') : 'green'}>
                  {s.status === 'OPEN' ? (s.overdue ? 'OVERDUE' : 'OPEN') : s.status.replace('_', ' ')}</Badge></td>
              </tr>
            ))}
            {schedule.data?.length === 0 && <tr><td colSpan={4} className="py-2 text-slate-500">Nothing falls due in this range.</td></tr>}
          </tbody>
        </table>
      </div>
    </section>
  );
}

const EMPTY = { code: '', name: '', frequency: 'MONTH_END' as CloseFrequency, dueOffsetDays: 5, responsiblePermission: 'PERIOD_CLOSE',
  instructions: '', sortOrder: 100 };

function TaskAdmin() {
  const qc = useQueryClient();
  const tasks = useQuery({ queryKey: ['close-tasks'], queryFn: closeApi.tasks });
  const [editing, setEditing] = useState<CloseTask | null>(null);
  const [form, setForm] = useState(EMPTY);
  const done = () => { setEditing(null); setForm(EMPTY); void qc.invalidateQueries({ queryKey: ['close-tasks'] });
    void qc.invalidateQueries({ queryKey: ['close-checklist'] }); void qc.invalidateQueries({ queryKey: ['close-schedule'] }); };
  const save = useMutation({ mutationFn: (status?: 'ACTIVE' | 'INACTIVE') => editing
    ? closeApi.updateTask(editing.id, { ...form, version: editing.version, status: status ?? editing.status })
    : closeApi.createTask(form), onSuccess: done });
  const edit = (t: CloseTask) => { setEditing(t); setForm({ code: t.code, name: t.name, frequency: t.frequency, dueOffsetDays: t.dueOffsetDays,
    responsiblePermission: t.responsiblePermission, instructions: t.instructions ?? '', sortOrder: t.sortOrder }); };
  return (
    <section className="mb-4 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5" aria-label="Close tasks">
      <h2 className="mb-2 font-semibold">Close tasks</h2>
      <ErrorAlert error={tasks.error} />
      <table className="mb-3 w-full text-sm">
        <thead><tr className="text-left text-xs uppercase text-slate-500"><th className="py-1">Code</th><th className="py-1">Task</th>
          <th className="py-1">Checklist</th><th className="py-1">Due after (days)</th><th className="py-1">Owner</th><th className="py-1">Status</th><th /></tr></thead>
        <tbody>
          {(tasks.data ?? []).map((t) => (
            <tr key={t.id} className="border-t border-slate-100">
              <td className="py-1">{t.code}</td><td className="py-1">{t.name}</td>
              <td className="py-1">{FREQUENCIES.find((f) => f.code === t.frequency)?.label}</td><td className="py-1">{t.dueOffsetDays}</td>
              <td className="py-1">{t.responsiblePermission}</td><td className="py-1">{t.status.toLowerCase()}</td>
              <td className="py-1 text-right"><button className="text-brand-700 hover:underline" onClick={() => edit(t)}>Edit</button></td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className="grid gap-3 border-t border-slate-100 pt-3 md:grid-cols-3">
        <Field label="Task code" value={form.code} maxLength={30} disabled={!!editing} onChange={(e) => setForm({ ...form, code: e.target.value })} />
        <div className="md:col-span-2"><Field label="Task name" value={form.name} maxLength={200} onChange={(e) => setForm({ ...form, name: e.target.value })} /></div>
        <label className="text-sm font-medium text-slate-700">Checklist
          <Select value={form.frequency} onChange={(e) => setForm({ ...form, frequency: e.target.value as CloseFrequency })}
            className="mt-1.5 block w-full rounded-lg border border-slate-300 px-3 shadow-xs text-sm">
            {FREQUENCIES.map((f) => <option key={f.code} value={f.code}>{f.label}</option>)}</Select></label>
        <Field label="Due after (days)" type="number" min={0} max={90} value={String(form.dueOffsetDays)}
          onChange={(e) => setForm({ ...form, dueOffsetDays: Number(e.target.value) })} />
        <Field label="Responsible permission" value={form.responsiblePermission} maxLength={64}
          onChange={(e) => setForm({ ...form, responsiblePermission: e.target.value.toUpperCase() })} />
        <div className="md:col-span-2"><Field label="Instructions" value={form.instructions} maxLength={1000}
          onChange={(e) => setForm({ ...form, instructions: e.target.value })} /></div>
        <Field label="Order" type="number" value={String(form.sortOrder)} onChange={(e) => setForm({ ...form, sortOrder: Number(e.target.value) })} />
        <div className="flex flex-wrap items-center gap-2 md:col-span-3">
          <Button busy={save.isPending} disabled={form.code.trim().length < 2 || form.name.trim().length === 0} onClick={() => save.mutate(undefined)}>
            {editing ? 'Save task' : 'Add task'}</Button>
          {editing && <Button variant="secondary" onClick={() => save.mutate(editing.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE')}>
            {editing.status === 'ACTIVE' ? 'Deactivate' : 'Reactivate'}</Button>}
          {editing && <Button variant="secondary" onClick={() => { setEditing(null); setForm(EMPTY); }}>Cancel</Button>}
          <span className="text-xs text-slate-500">The owner is a permission code (for example PERIOD_CLOSE or DEPOSIT_PREPARE).</span>
        </div>
        <div className="md:col-span-3"><ErrorAlert error={save.error} /></div>
      </div>
    </section>
  );
}
