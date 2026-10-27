import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { parallelRunApi, periodApi } from '../../api/endpoints';
import type { ParallelRunDetail, ParallelRunLine } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { Button } from '../../components/Button';
import { DocumentsPanel } from '../../components/DocumentsPanel';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Field } from '../../components/Field';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Pagination } from '../../components/Pagination';
import { Spinner } from '../../components/Spinner';
import { StatusBadge } from '../../components/StatusBadge';
import { formatDateTime } from '../../utils/format';

const TYPE_LABEL: Record<string, string> = {
  MATCHED: 'Agrees', DIFFERENT: 'Different', CAS_ONLY: 'Only in CAS', LEGACY_ONLY: 'Only in legacy', UNMAPPED: 'Legacy account not mapped',
};

export function ParallelRunsPage() {
  const { hasPermission } = useAuth();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const [page, setPage] = useState(0);
  const list = useQuery({ queryKey: ['parallel-runs', page], queryFn: () => parallelRunApi.list(page), placeholderData: keepPreviousData });
  const years = useQuery({ queryKey: ['fiscal-years'], queryFn: periodApi.fiscalYears });
  const periods = (years.data ?? []).flatMap((y) => y.periods ?? []).filter((p) => p.status !== 'FUTURE');
  const [form, setForm] = useState({ periodId: '', basis: 'CLOSING', legacySystem: '' });
  const [file, setFile] = useState<File | null>(null);
  const upload = useMutation({ mutationFn: () => parallelRunApi.upload(file!, form),
    onSuccess: (r) => { qc.invalidateQueries({ queryKey: ['parallel-runs'] }); navigate(`/migration/parallel-runs/${r.summary.id}`); } });
  return (
    <>
      <PageHeader title="Parallel run" crumbs={[{ label: 'Migration' }, { label: 'Parallel run' }]} />
      <p className="mb-4 text-sm text-slate-600">During the parallel run, each month's legacy trial balance is compared with CAS account by account. Every
        difference needs a written explanation, and a second person signs the comparison off only when none is left unexplained.</p>
      {hasPermission('MIGRATION_PREPARE') && (
        <section className="mb-4 space-y-3 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
          <h2 className="font-semibold">Compare a legacy trial balance</h2>
          <ErrorAlert error={upload.error} />
          <div className="grid gap-3 md:grid-cols-3">
            <div className="space-y-1.5"><label htmlFor="prperiod" className="block text-sm font-medium text-slate-700">CAS period</label>
              <select id="prperiod" value={form.periodId} onChange={(e) => setForm({ ...form, periodId: e.target.value })}
                className="block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm"><option value="">Select…</option>
                {periods.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}</select></div>
            <div className="space-y-1.5"><label htmlFor="prbasis" className="block text-sm font-medium text-slate-700">Compare</label>
              <select id="prbasis" value={form.basis} onChange={(e) => setForm({ ...form, basis: e.target.value })}
                className="block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm">
                <option value="CLOSING">Closing balances</option><option value="PERIOD">The period's movements</option></select></div>
            <Field label="Legacy system" value={form.legacySystem} maxLength={100} onChange={(e) => setForm({ ...form, legacySystem: e.target.value })} />
            <div className="space-y-1 md:col-span-3"><label htmlFor="prfile" className="block text-sm font-medium text-slate-700">Legacy trial balance (CSV)</label>
              <input id="prfile" type="file" accept=".csv" onChange={(e) => setFile(e.target.files?.[0] ?? null)} className="text-sm" /></div>
          </div>
          <div className="flex flex-wrap items-center gap-3">
            <Button busy={upload.isPending} disabled={!file || !form.periodId || !form.legacySystem.trim()} onClick={() => upload.mutate()}>Compare</Button>
            <button type="button" className="text-sm text-brand-700 hover:underline" onClick={() => parallelRunApi.template()}>Download the empty template</button>
          </div>
        </section>
      )}
      <ErrorAlert error={list.error} />
      {list.isPending ? <Spinner /> : (
        <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs"><table className="min-w-full text-sm">
          <thead className="border-b border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="px-3 py-2">Period</th><th className="px-3 py-2">Legacy system</th>
            <th className="px-3 py-2">Basis</th><th className="px-3 py-2">Status</th><th className="px-3 py-2">Differences (unexplained)</th>
            <th className="px-3 py-2">Uploaded</th><th className="px-3 py-2">Signed off</th></tr></thead>
          <tbody>{(list.data?.items ?? []).map((r) => {
            const diffs = Object.entries(r.lineCounts).filter(([k]) => k !== 'MATCHED').reduce((t, [, n]) => t + n, 0);
            return (
              <tr key={r.id} className="border-t border-slate-100">
                <td className="px-3 py-2"><Link className="text-brand-700 hover:underline" to={`/migration/parallel-runs/${r.id}`}>{r.periodName}</Link></td>
                <td className="px-3 py-2">{r.legacySystem}</td><td className="px-3 py-2">{r.basis === 'CLOSING' ? 'Closing' : 'Movements'}</td>
                <td className="px-3 py-2"><StatusBadge status={r.status} /></td><td className="px-3 py-2">{diffs} ({r.unexplained})</td>
                <td className="px-3 py-2">{r.uploadedBy}, {formatDateTime(r.uploadedAt)}</td>
                <td className="px-3 py-2">{r.signedOffBy ? `${r.signedOffBy}, ${formatDateTime(r.signedOffAt)}` : '—'}</td></tr>);
          })}
          {list.data?.items.length === 0 && <tr><td colSpan={7} className="px-4 py-6 text-center text-slate-500">No comparisons yet.</td></tr>}</tbody>
        </table></div>
      )}
      {list.data && <Pagination page={list.data.page} totalPages={list.data.totalPages} totalItems={list.data.totalItems} onPage={setPage} />}
    </>
  );
}

function ExplainCell({ runId, line, canExplain, onDone }: { runId: string; line: ParallelRunLine; canExplain: boolean;
  onDone: (d: ParallelRunDetail) => void }) {
  const [text, setText] = useState('');
  const save = useMutation({ mutationFn: () => parallelRunApi.explain(runId, line.id, text), onSuccess: (d) => { setText(''); onDone(d); } });
  if (line.lineType === 'MATCHED') return null;
  return (
    <div className="space-y-1">
      {line.explanation && <p>{line.explanation} <span className="text-xs text-slate-500">({line.explainedBy}, {formatDateTime(line.explainedAt)})</span></p>}
      {!line.explanation && !canExplain && <p className="text-red-700">Not explained</p>}
      {canExplain && <div className="flex flex-wrap gap-2">
        <input aria-label={`Explanation for ${line.accountCode ?? line.legacyCodes}`} value={text} onChange={(e) => setText(e.target.value)}
          placeholder={line.explanation ? 'Replace the explanation' : 'Why does it differ?'} className="w-72 rounded-md border border-slate-300 px-2 py-1" />
        <Button variant="secondary" busy={save.isPending} disabled={text.trim().length < 10} onClick={() => save.mutate()}>Save</Button>
        <ErrorAlert error={save.error} /></div>}
    </div>
  );
}

export function ParallelRunPage() {
  const { id = '' } = useParams();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['parallel-run', id], queryFn: () => parallelRunApi.get(id) });
  const [note, setNote] = useState('');
  const [reason, setReason] = useState('');
  const [showMatched, setShowMatched] = useState(false);
  const done = (d: ParallelRunDetail) => { qc.setQueryData(['parallel-run', id], d); qc.invalidateQueries({ queryKey: ['parallel-runs'] }); };
  const recompare = useMutation({ mutationFn: () => parallelRunApi.recompare(q.data!.summary), onSuccess: done });
  const signOff = useMutation({ mutationFn: () => parallelRunApi.signOff(q.data!.summary, note), onSuccess: done });
  const cancel = useMutation({ mutationFn: () => parallelRunApi.cancel(q.data!.summary, reason), onSuccess: done });
  if (q.isPending) return <Spinner />;
  if (!q.data) return <ErrorAlert error={q.error} />;
  const { summary: s, availableActions: actions } = q.data;
  const lines = q.data.lines.filter((l) => showMatched || l.lineType !== 'MATCHED');
  return (
    <>
      <PageHeader title={`Parallel run: ${s.periodName}`} crumbs={[{ label: 'Migration' }, { label: 'Parallel run', to: '/migration/parallel-runs' },
        { label: s.periodName }]} />
      <ErrorAlert error={recompare.error ?? signOff.error ?? cancel.error} />
      <section className="mb-4 grid gap-3 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5 text-sm md:grid-cols-4">
        <div><div className="text-xs text-slate-500">Status</div><StatusBadge status={s.status} /></div>
        <div><div className="text-xs text-slate-500">Legacy system</div>{s.legacySystem}</div>
        <div><div className="text-xs text-slate-500">Compared</div>{s.basis === 'CLOSING' ? 'Closing balances' : 'Period movements'}, {formatDateTime(s.comparedAt)}</div>
        <div><div className="text-xs text-slate-500">Uploaded</div>{s.uploadedBy}, {formatDateTime(s.uploadedAt)}</div>
        <div><div className="text-xs text-slate-500">Lines</div>{Object.entries(s.lineCounts).filter(([, n]) => n > 0)
          .map(([k, n]) => `${TYPE_LABEL[k]} ${n}`).join(' · ')}</div>
        <div><div className="text-xs text-slate-500">Unexplained</div><span className={s.unexplained ? 'font-semibold text-red-700' : 'text-green-800'}>{s.unexplained}</span></div>
        <div><div className="text-xs text-slate-500">Net of the legacy trial balance</div><Money value={s.legacyTotal} />
          {Number(s.legacyTotal) !== 0 && <span className="ml-1 text-xs text-amber-800">(the legacy trial balance does not balance)</span>}</div>
        <div className="break-all"><div className="text-xs text-slate-500">File SHA-256</div><span className="font-mono text-xs">{s.fileSha256}</span></div>
        {s.signedOffBy && <div className="md:col-span-4">Signed off by {s.signedOffBy}, {formatDateTime(s.signedOffAt)}: {s.signOffNote}</div>}
        {s.cancelReason && <div className="md:col-span-4">Cancelled: {s.cancelReason}</div>}
      </section>
      <div className="mb-4 flex flex-wrap items-end gap-3">
        {actions.includes('RECOMPARE') && <Button variant="secondary" busy={recompare.isPending} onClick={() => recompare.mutate()}>Compare again with CAS</Button>}
        {actions.includes('SIGN_OFF') && <>
          <Field label="Sign-off note" value={note} onChange={(e) => setNote(e.target.value)} />
          <Button busy={signOff.isPending} disabled={note.trim().length < 5} onClick={() => signOff.mutate()}>Sign off</Button></>}
        {actions.includes('CANCEL') && <>
          <Field label="Cancellation reason" value={reason} onChange={(e) => setReason(e.target.value)} />
          <Button variant="secondary" busy={cancel.isPending} disabled={reason.trim().length < 5} onClick={() => cancel.mutate()}>Cancel comparison</Button></>}
      </div>
      <section className="mb-4 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
        <div className="mb-3 flex items-center justify-between"><h2 className="font-semibold">Accounts</h2>
          <label className="text-sm"><input type="checkbox" className="mr-1" checked={showMatched} onChange={(e) => setShowMatched(e.target.checked)} />
            Show accounts that agree</label></div>
        <div className="overflow-x-auto"><table className="min-w-full text-sm">
          <thead className="border-b border-slate-200 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="py-1 pr-3">CAS account</th><th className="py-1 pr-3">Legacy accounts</th>
            <th className="py-1 pr-3">Result</th><th className="py-1 text-right">Legacy</th><th className="py-1 text-right">CAS</th><th className="py-1 text-right">Difference</th>
            <th className="py-1 pl-3">Explanation</th></tr></thead>
          <tbody>{lines.map((l) => (
            <tr key={l.id} className="border-t border-slate-100 align-top">
              <td className="py-1.5 pr-3">{l.accountCode ? `${l.accountCode} ${l.accountName}` : '—'}</td><td className="py-1.5 pr-3">{l.legacyCodes ?? '—'}</td>
              <td className="py-1.5 pr-3">{TYPE_LABEL[l.lineType]}</td><td className="py-1.5 text-right"><Money value={l.legacyAmount} /></td>
              <td className="py-1.5 text-right"><Money value={l.casAmount} /></td><td className="py-1.5 text-right"><Money value={l.difference} blankZero /></td>
              <td className="py-1.5 pl-3"><ExplainCell runId={id} line={l} canExplain={actions.includes('EXPLAIN')} onDone={done} /></td></tr>))}
            {lines.length === 0 && <tr><td colSpan={7} className="py-4 text-center text-slate-500">Every account agrees.</td></tr>}</tbody>
        </table></div>
        <p className="mt-2 text-xs text-slate-500">Amounts are balances with debits positive. Difference = legacy − CAS.</p>
      </section>
      <DocumentsPanel documents={q.data.documents} canUpload={false} onUpload={() => undefined} uploading={false} error={null} />
    </>
  );
}
