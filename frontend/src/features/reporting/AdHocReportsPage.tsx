import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Select } from '../../components/Select';
import { useState } from 'react';
import { Link } from 'react-router';
import { adHocApi } from '../../api/endpoints';
import type { AdHocDefinition, AdHocFilter, AdHocSaved } from '../../api/types';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Spinner } from '../../components/Spinner';

const OPS: Record<string, { label: string; ops: string[] }> = {
  TEXT: { label: 'text', ops: ['EQ', 'NE', 'CONTAINS', 'STARTS_WITH'] },
  DATE: { label: 'date', ops: ['EQ', 'GE', 'LE', 'BETWEEN'] },
  AMOUNT: { label: 'amount', ops: ['EQ', 'GT', 'GE', 'LT', 'LE', 'BETWEEN'] },
  NUMBER: { label: 'number', ops: ['EQ', 'GT', 'GE', 'LT', 'LE', 'BETWEEN'] },
};
const OP_LABEL: Record<string, string> = { EQ: 'is', NE: 'is not', CONTAINS: 'contains', STARTS_WITH: 'starts with', GT: '>', GE: '≥ (from)',
  LT: '<', LE: '≤ (to)', BETWEEN: 'between' };

export function AdHocReportsPage() {
  const qc = useQueryClient();
  const datasets = useQuery({ queryKey: ['adhoc', 'datasets'], queryFn: adHocApi.datasets });
  const saved = useQuery({ queryKey: ['adhoc', 'saved'], queryFn: adHocApi.saved });
  const [dataset, setDataset] = useState('');
  const [columns, setColumns] = useState<string[]>([]);
  const [filters, setFilters] = useState<AdHocFilter[]>([]);
  const [groupBy, setGroupBy] = useState<string[]>([]);
  const [sort, setSort] = useState<{ column: string; descending: boolean } | null>(null);
  const [name, setName] = useState('');
  const [shared, setShared] = useState(false);
  const [current, setCurrent] = useState<AdHocSaved | null>(null);
  const ds = datasets.data?.find((d) => d.code === (dataset || datasets.data?.[0]?.code));
  const definition: AdHocDefinition = { columns, filters, groupBy, sort: sort ? [sort] : [] };
  const run = useMutation({ mutationFn: () => adHocApi.preview(ds!.code, definition) });
  const save = useMutation({
    mutationFn: () => adHocApi.save({ name, dataset: ds!.code, definition, shared, version: current?.version }, current?.mine ? current.id : undefined),
    onSuccess: (s) => { setCurrent(s); qc.invalidateQueries({ queryKey: ['adhoc', 'saved'] }); },
  });
  const exp = useMutation({ mutationFn: (format: 'CSV' | 'XLSX' | 'PDF') => adHocApi.export(current!, format) });
  const remove = useMutation({ mutationFn: (id: string) => adHocApi.remove(id), onSuccess: () => { setCurrent(null); qc.invalidateQueries({ queryKey: ['adhoc', 'saved'] }); } });

  const load = (s: AdHocSaved) => {
    setCurrent(s); setDataset(s.dataset); setColumns(s.definition.columns); setFilters(s.definition.filters ?? []);
    setGroupBy(s.definition.groupBy ?? []); setSort(s.definition.sort?.[0] ?? null); setName(s.name); setShared(s.shared); run.reset();
  };
  const reset = (code: string) => {
    setDataset(code); setColumns([]); setFilters([]); setGroupBy([]); setSort(null); setCurrent(null); setName(''); run.reset();
  };
  const toggle = (list: string[], key: string, set: (v: string[]) => void) => set(list.includes(key) ? list.filter((k) => k !== key) : [...list, key]);
  const select = 'rounded-lg border border-slate-300 px-2 py-1.5 text-sm shadow-xs';

  if (datasets.isPending) return <Spinner />;
  return (
    <>
      <PageHeader title="Ad hoc reports" crumbs={[{ label: 'Reports' }, { label: 'Ad hoc reports' }]} />
      <p className="mb-4 max-w-3xl text-sm text-slate-600">Build a report from an approved dataset: choose the columns, add conditions, group
        rows to total their amounts, and sort. Save it for later or share it with colleagues who may see the same records; export it as
        CSV, Excel or PDF through <Link to="/reports/jobs" className="text-brand-700 hover:underline">My reports</Link>.</p>
      <ErrorAlert error={datasets.error ?? saved.error} />
      {saved.data && saved.data.length > 0 && (
        <div className="mb-4 flex flex-wrap gap-2 text-sm">Saved:
          {saved.data.map((s) => <button key={s.id} type="button" onClick={() => load(s)}
            className={`rounded-full border px-3 py-0.5 ${current?.id === s.id ? 'border-brand-500 bg-brand-50' : 'border-slate-300'}`}>
            {s.name}{!s.mine && <span className="text-slate-500"> · {s.owner}</span>}</button>)}
        </div>
      )}
      {ds && (
        <div className="mb-4 space-y-4 rounded-xl border border-slate-200/80 bg-white p-4 shadow-xs">
          <label className="block text-sm">Dataset <Select aria-label="Dataset" value={ds.code} onChange={(e) => reset(e.target.value)} className={select}>
            {datasets.data!.map((d) => <option key={d.code} value={d.code}>{d.label}</option>)}</Select></label>
          <fieldset className="text-sm"><legend className="mb-1 font-medium">Columns (in the order chosen)</legend>
            <div className="flex flex-wrap gap-x-4 gap-y-1">{ds.columns.map((c) => (
              <label key={c.key} className="flex items-center gap-1"><input type="checkbox" checked={columns.includes(c.key)}
                onChange={() => toggle(columns, c.key, setColumns)} />{c.label}</label>))}</div></fieldset>
          <fieldset className="text-sm"><legend className="mb-1 font-medium">Group by (amounts are totalled)</legend>
            <div className="flex flex-wrap gap-x-4 gap-y-1">{ds.columns.filter((c) => c.type !== 'AMOUNT').map((c) => (
              <label key={c.key} className="flex items-center gap-1"><input type="checkbox" checked={groupBy.includes(c.key)}
                onChange={() => { toggle(groupBy, c.key, setGroupBy); if (!columns.includes(c.key)) setColumns([...columns, c.key]); }} />{c.label}</label>))}</div></fieldset>
          <div className="space-y-2 text-sm">
            <p className="font-medium">Conditions</p>
            {filters.map((f, i) => {
              const col = ds.columns.find((c) => c.key === f.column);
              const set = (patch: Partial<AdHocFilter>) => setFilters(filters.map((x, j) => (j === i ? { ...x, ...patch } : x)));
              return (
                <div key={i} className="flex flex-wrap items-center gap-2">
                  <Select aria-label={`Condition ${i + 1} column`} value={f.column} onChange={(e) => set({ column: e.target.value,
                    op: OPS[ds.columns.find((c) => c.key === e.target.value)!.type]!.ops[0]! })} className={select}>
                    {ds.columns.map((c) => <option key={c.key} value={c.key}>{c.label}</option>)}</Select>
                  <Select aria-label={`Condition ${i + 1} test`} value={f.op} onChange={(e) => set({ op: e.target.value })} className={select}>
                    {OPS[col?.type ?? 'TEXT']!.ops.map((o) => <option key={o} value={o}>{OP_LABEL[o]}</option>)}</Select>
                  <input aria-label={`Condition ${i + 1} value`} type={col?.type === 'DATE' ? 'date' : 'text'} value={f.value}
                    onChange={(e) => set({ value: e.target.value })} className={select} />
                  {f.op === 'BETWEEN' && <><span>and</span><input aria-label={`Condition ${i + 1} second value`} type={col?.type === 'DATE' ? 'date' : 'text'}
                    value={f.value2 ?? ''} onChange={(e) => set({ value2: e.target.value })} className={select} /></>}
                  <button type="button" className="text-xs text-red-700 hover:underline" onClick={() => setFilters(filters.filter((_, j) => j !== i))}>Remove</button>
                </div>
              );
            })}
            <button type="button" className="text-brand-700 hover:underline" onClick={() => setFilters([...filters,
              { column: ds.columns[0]!.key, op: OPS[ds.columns[0]!.type]!.ops[0]!, value: '' }])}>Add condition</button>
          </div>
          <label className="block text-sm">Sort by <Select aria-label="Sort by" value={sort?.column ?? ''} onChange={(e) => setSort(e.target.value ? { column: e.target.value, descending: sort?.descending ?? false } : null)} className={select}>
            <option value="">First column</option>{columns.map((k) => <option key={k} value={k}>{ds.columns.find((c) => c.key === k)?.label}</option>)}</Select>
            {sort && <label className="ml-2"><input type="checkbox" checked={sort.descending} onChange={(e) => setSort({ ...sort, descending: e.target.checked })} /> highest first</label>}</label>
          <div className="flex flex-wrap items-center gap-2">
            <Button busy={run.isPending} disabled={columns.length === 0} onClick={() => run.mutate()}>Run</Button>
            <input aria-label="Report name" placeholder="Report name" value={name} maxLength={120} onChange={(e) => setName(e.target.value)} className={select} />
            <label className="text-sm"><input type="checkbox" checked={shared} onChange={(e) => setShared(e.target.checked)} /> Share</label>
            <Button variant="secondary" busy={save.isPending} disabled={name.trim().length < 3 || columns.length === 0} onClick={() => save.mutate()}>
              {current?.mine ? 'Save changes' : 'Save'}</Button>
            {current && <>{(['CSV', 'XLSX', 'PDF'] as const).map((f) => (
              <Button key={f} variant="secondary" busy={exp.isPending && exp.variables === f} onClick={() => exp.mutate(f)}>Export {f}</Button>))}
              {current.mine && <Button variant="secondary" onClick={() => remove.mutate(current.id)}>Delete</Button>}</>}
          </div>
          <ErrorAlert error={run.error ?? save.error ?? exp.error ?? remove.error} />
          {exp.isSuccess && <p role="status" className="text-sm">Export queued; download it from <Link to="/reports/jobs" className="text-brand-700 hover:underline">My reports</Link>.</p>}
        </div>
      )}
      {run.data && (
        <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs">
          <table className="min-w-full text-sm">
            <thead className="border-b border-slate-200 bg-slate-50/80 text-[11px] font-semibold uppercase tracking-wider text-slate-500">
              <tr>{run.data.columns.map((c) => <th key={c.key} className={`px-3 py-2 ${c.type === 'AMOUNT' || c.type === 'COUNT' ? 'text-right' : 'text-left'}`}>{c.label}</th>)}</tr>
            </thead>
            <tbody>
              {run.data.rows.map((r, i) => (
                <tr key={i} className="border-t border-slate-100">{r.map((v, j) => {
                  const t = run.data.columns[j]!.type;
                  return <td key={j} className={`px-3 py-1.5 ${t === 'AMOUNT' || t === 'COUNT' ? 'text-right' : ''}`}>
                    {t === 'AMOUNT' ? <Money value={v == null ? null : String(v)} /> : v ?? ''}</td>;
                })}</tr>
              ))}
              {run.data.rows.length === 0 && <tr><td colSpan={run.data.columns.length} className="px-4 py-6 text-center text-slate-500">No rows.</td></tr>}
            </tbody>
            {run.data.rows.length > 0 && <tfoot className="border-t-2 border-slate-300 font-medium"><tr>{run.data.totals.map((v, j) => (
              <td key={j} className="px-3 py-1.5 text-right">{j === 0 ? 'Total' : v == null ? '' : run.data.columns[j]!.type === 'AMOUNT' ? <Money value={String(v)} /> : v}</td>))}</tr></tfoot>}
          </table>
          {run.data.truncated && <p className="px-4 py-2 text-xs text-slate-500">Showing the first 200 rows; the export includes them all.</p>}
        </div>
      )}
    </>
  );
}
