import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { recurringApi } from '../../api/endpoints';
import type { LineInput } from '../../api/types';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Field } from '../../components/Field';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Pagination } from '../../components/Pagination';
import { Spinner } from '../../components/Spinner';
import { StatusBadge } from '../../components/StatusBadge';
import { emptyLine, LinesEditor, normaliseLines } from './LinesEditor';

export function RecurringJournalsPage() {
  const qc = useQueryClient();
  const [page, setPage] = useState(0);
  const list = useQuery({ queryKey: ['recurring', page], queryFn: () => recurringApi.list(page), placeholderData: keepPreviousData });
  const [creating, setCreating] = useState(false);
  const [form, setForm] = useState({ name: '', description: '', frequency: 'MONTHLY', dayOfMonth: '25',
    firstRunDate: new Date().toISOString().slice(0, 10), endDate: '' });
  const [lines, setLines] = useState<LineInput[]>([emptyLine(), emptyLine()]);
  const invalidate = () => qc.invalidateQueries({ queryKey: ['recurring'] });
  const create = useMutation({
    mutationFn: () => recurringApi.create({ ...form, dayOfMonth: Number(form.dayOfMonth), endDate: form.endDate || null,
      lines: normaliseLines(lines) }),
    onSuccess: () => { setCreating(false); invalidate(); },
  });
  const runDue = useMutation({ mutationFn: recurringApi.runDue, onSuccess: () => { invalidate(); qc.invalidateQueries({ queryKey: ['vouchers'] }); } });
  const toggle = useMutation({ mutationFn: (args: [Parameters<typeof recurringApi.setActive>[0], boolean]) => recurringApi.setActive(...args),
    onSuccess: invalidate });

  return (
    <>
      <PageHeader title="Recurring journals" crumbs={[{ label: 'Accounting' }, { label: 'Recurring journals' }]}
        actions={<>
          <Button variant="secondary" busy={runDue.isPending} onClick={() => runDue.mutate()}>Generate due vouchers</Button>
          <Button onClick={() => setCreating(true)}>New template</Button>
        </>} />
      <p className="mb-4 text-sm text-slate-600">Templates generate <strong>draft</strong> vouchers owned by the template owner;
        each one still needs submission, review, approval and posting. Generation also runs daily at 01:00 (Manila).</p>
      <ErrorAlert error={list.error ?? runDue.error ?? toggle.error} />
      {runDue.data && <p role="status" className="mb-3 text-sm">{runDue.data.generated} voucher(s) generated.
        {runDue.data.skipped.length > 0 && ` Skipped: ${runDue.data.skipped.join('; ')}`}</p>}
      {creating && (
        <section className="mb-4 space-y-3 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
          <ErrorAlert error={create.error} />
          <div className="grid gap-3 md:grid-cols-3">
            <Field label="Name" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
            <Field label="Voucher description" value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} />
            <div className="space-y-1.5">
              <label htmlFor="freq" className="block text-sm font-medium text-slate-700">Frequency</label>
              <select id="freq" value={form.frequency} onChange={(e) => setForm({ ...form, frequency: e.target.value })}
                className="block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm">
                <option value="MONTHLY">Monthly</option><option value="QUARTERLY">Quarterly</option><option value="ANNUALLY">Annually</option>
              </select>
            </div>
            <Field label="Day of month (1-28)" value={form.dayOfMonth} onChange={(e) => setForm({ ...form, dayOfMonth: e.target.value.replace(/\D/g, '') })} />
            <Field label="First run" type="date" value={form.firstRunDate} onChange={(e) => setForm({ ...form, firstRunDate: e.target.value })} />
            <Field label="End date (optional)" type="date" value={form.endDate} onChange={(e) => setForm({ ...form, endDate: e.target.value })} />
          </div>
          <LinesEditor lines={lines} onChange={setLines} />
          <div className="flex gap-2">
            <Button busy={create.isPending} disabled={!form.name || !form.description} onClick={() => create.mutate()}>Save template</Button>
            <Button variant="secondary" onClick={() => setCreating(false)}>Cancel</Button>
          </div>
        </section>
      )}
      {list.isPending ? <Spinner /> : (
        <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs">
          <table className="min-w-full text-sm">
            <thead className="border-b border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500">
              <tr><th className="px-3 py-2">Name</th><th className="px-3 py-2">Frequency</th><th className="px-3 py-2">Next run</th>
                <th className="px-3 py-2">Owner</th><th className="px-3 py-2">Status</th><th className="px-3 py-2 text-right">Amount</th><th /></tr>
            </thead>
            <tbody>
              {(list.data?.items ?? []).map((r) => (
                <tr key={r.id} className="border-t border-slate-100">
                  <td className="px-3 py-2">{r.name}</td><td className="px-3 py-2">{r.frequency} (day {r.dayOfMonth})</td>
                  <td className="px-3 py-2">{r.nextRunDate}</td><td className="px-3 py-2">{r.owner}</td>
                  <td className="px-3 py-2"><StatusBadge status={r.status} /></td>
                  <td className="px-3 py-2 text-right"><Money value={r.total} /></td>
                  <td className="px-3 py-2 text-right">
                    {r.status !== 'ENDED' && <button type="button" className="text-brand-700 hover:underline"
                      onClick={() => toggle.mutate([r, r.status !== 'ACTIVE'])}>{r.status === 'ACTIVE' ? 'Pause' : 'Resume'}</button>}
                  </td>
                </tr>
              ))}
              {list.data?.items.length === 0 && <tr><td colSpan={7} className="px-4 py-6 text-center text-slate-500">No templates.</td></tr>}
            </tbody>
          </table>
        </div>
      )}
      {list.data && <Pagination page={list.data.page} totalPages={list.data.totalPages} totalItems={list.data.totalItems} onPage={setPage} />}
    </>
  );
}
