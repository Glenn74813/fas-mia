import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Select } from '../../components/Select';
import { useState } from 'react';
import { Link } from 'react-router';
import { reportingApi } from '../../api/endpoints';
import { useAuth } from '../../auth/AuthContext';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Spinner } from '../../components/Spinner';
import { StatusBadge } from '../../components/StatusBadge';
import { allPeriods, useFiscalYears } from '../../hooks/useAccounting';

/** Lines shown in regular weight; headers, totals, opening and closing balances are bold. */
const PLAIN = new Set(['LINE', 'SURPLUS', 'MOVEMENT']);

const STATEMENT_TYPES: Record<string, string> = { SFP: 'Financial position', SFPERF: 'Financial performance', SCF: 'Cash flows',
  SCE: 'Changes in equity' };

export function FinancialStatementsPage() {
  const { hasPermission } = useAuth();
  const qc = useQueryClient();
  const templates = useQuery({ queryKey: ['report-templates'], queryFn: reportingApi.templates });
  const years = useFiscalYears();
  const periods = allPeriods(years.data).filter((p) => p.status !== 'FUTURE').sort((a, b) => b.endDate.localeCompare(a.endDate));
  const active = (templates.data ?? []).filter((t) => t.status === 'ACTIVE');
  const [template, setTemplate] = useState('');
  const [periodId, setPeriodId] = useState('');
  const [comparative, setComparative] = useState(true);
  const chosenTemplate = template || active[0]?.code || '';
  const chosenPeriod = periodId || periods[0]?.id || '';
  const st = useQuery({ queryKey: ['statement', chosenTemplate, chosenPeriod, comparative], enabled: !!chosenTemplate && !!chosenPeriod,
    queryFn: () => reportingApi.statement(chosenTemplate, chosenPeriod, comparative) });
  const exp = useMutation({ mutationFn: (format: string) => reportingApi.requestJob('FINANCIAL_STATEMENT', format,
    { template: chosenTemplate, periodId: chosenPeriod, comparative: String(comparative) }), onSuccess: () => qc.invalidateQueries({ queryKey: ['report-jobs'] }) });
  const activate = useMutation({ mutationFn: reportingApi.activate, onSuccess: () => qc.invalidateQueries({ queryKey: ['report-templates'] }) });
  const s = st.data;
  return (
    <>
      <PageHeader title="Financial statements" crumbs={[{ label: 'Reports' }, { label: 'Financial statements' }]} />
      <div className="mb-4 flex flex-wrap items-end gap-3 text-sm print:hidden">
        <label>Statement
          <Select value={chosenTemplate} onChange={(e) => setTemplate(e.target.value)} className="ml-2 rounded-md border border-slate-300 px-2 py-1">
            {active.map((t) => <option key={t.id} value={t.code}>{t.name} (v{t.version})</option>)}</Select></label>
        <label>Period
          <Select value={chosenPeriod} onChange={(e) => setPeriodId(e.target.value)} className="ml-2 rounded-md border border-slate-300 px-2 py-1">
            {periods.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}</Select></label>
        <label className="flex items-center gap-2"><input type="checkbox" checked={comparative} onChange={(e) => setComparative(e.target.checked)} />Prior-year comparative</label>
        {hasPermission('FINANCIAL_REPORT_EXPORT') && (
          <span className="flex gap-2">{['XLSX', 'PDF', 'CSV'].map((f) => (
            <Button key={f} variant="secondary" busy={exp.isPending && exp.variables === f} disabled={!s} onClick={() => exp.mutate(f)}>Export {f}</Button>))}</span>
        )}
      </div>
      {exp.isSuccess && <p role="status" className="mb-3 text-sm text-green-800">Export queued — it will appear under <Link className="underline" to="/reports/jobs">My reports</Link>.</p>}
      <ErrorAlert error={st.error ?? exp.error ?? templates.error} />
      {active.length === 0 && templates.isSuccess && <p className="text-sm text-slate-600">No active statement template. An administrator defines and a second administrator activates one.</p>}
      {st.isFetching && !s ? <Spinner /> : s && (
        <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-6">
          {s.placeholderData && <p className="mb-3 rounded-md border border-amber-300 bg-amber-50 p-2 text-sm">PLACEHOLDER data or template — not an official statement.</p>}
          <h2 className="text-lg font-semibold">{s.title}</h2>
          <p className="text-sm text-slate-500">As of / for the period ended {s.periodEnd} · template {s.templateCode} v{s.templateVersion}
            {s.balanced !== null && <span className="ml-2">{s.balanced ? <Badge tone="green">BALANCED</Badge> : <Badge tone="red">OUT OF BALANCE</Badge>}</span>}</p>
          <table className="mt-4 min-w-full text-sm">
            <thead className="border-b border-slate-200 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="py-1" /><th className="py-1 text-right">{s.periodName}</th>
              {s.comparativePeriodName && <th className="py-1 text-right">{s.comparativePeriodName}</th>}</tr></thead>
            <tbody>{s.lines.map((l) => (
              <tr key={l.code} className={PLAIN.has(l.type) ? '' : 'font-semibold'}>
                <td className={`py-1 ${l.type === 'HEADER' ? 'pt-3' : ''}`} style={{ paddingLeft: `${l.indent * 1.25}rem` }}>{l.label}</td>
                <td className={`py-1 text-right ${l.type === 'TOTAL' ? 'border-t border-slate-300' : ''}`}>{l.current !== null && <Money value={l.current} />}</td>
                {s.comparativePeriodName && <td className={`py-1 text-right ${l.type === 'TOTAL' ? 'border-t border-slate-300' : ''}`}>{l.comparative !== null && <Money value={l.comparative} />}</td>}</tr>))}</tbody>
          </table>
          {s.unmapped.length > 0 && (
            <div className="mt-4 rounded-md border border-red-300 bg-red-50 p-3 text-sm">
              <p className="font-medium">Accounts with balances not shown on this statement:</p>
              <ul className="mt-1 list-disc pl-5">{s.unmapped.map((u) => <li key={u.accountCode}>{u.accountCode} {u.accountName} ({u.fsLineCode ?? 'no line code'}): <Money value={u.balance} /></li>)}</ul>
            </div>
          )}
          {s.unclassified.length > 0 && (
            <div className="mt-4 rounded-md border border-red-300 bg-red-50 p-3 text-sm">
              <p className="font-medium">Movements not classified by any line of this statement:</p>
              <ul className="mt-1 list-disc pl-5">{s.unclassified.map((u) => <li key={u.fsLineCodes.join() + u.counterpartCode}>
                {u.fsLineCodes.join(', ')} against {u.counterpartCode}: <Money value={u.amount} /></li>)}</ul>
            </div>
          )}
          <ul className="mt-4 space-y-1 text-xs text-slate-500">{s.notes.map((n) => <li key={n}>{n}</li>)}</ul>
        </section>
      )}
      <section className="mt-6 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5 print:hidden">
        <h2 className="mb-2 font-semibold">Statement templates</h2>
        <p className="mb-3 text-xs text-slate-500">Templates are versioned configuration mapping the chart's statement line codes to presentation lines (C-07).
          A new version is defined through the API and becomes effective only when a second administrator activates it.</p>
        <ErrorAlert error={activate.error} />
        <table className="min-w-full text-sm"><thead className="border-b border-slate-200 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="py-1 pr-3">Code</th><th className="py-1 pr-3">Statement</th><th className="py-1 pr-3">Name</th>
          <th className="py-1 pr-3">Version</th><th className="py-1 pr-3">Status</th><th className="py-1 pr-3">Defined / activated</th><th /></tr></thead>
          <tbody>{(templates.data ?? []).map((t) => (
            <tr key={t.id} className="border-t border-slate-100"><td className="py-1.5 pr-3 font-mono">{t.code}</td>
              <td className="py-1.5 pr-3">{STATEMENT_TYPES[t.statementType] ?? t.statementType}</td><td className="py-1.5 pr-3">{t.name}
              {t.placeholder && <span className="ml-2"><Badge tone="amber">PLACEHOLDER</Badge></span>}</td><td className="py-1.5 pr-3">{t.version}</td>
              <td className="py-1.5 pr-3"><StatusBadge status={t.status} /></td><td className="py-1.5 pr-3">{t.createdBy} / {t.activatedBy ?? '—'}</td>
              <td className="py-1.5">{t.availableActions.includes('ACTIVATE') && <button className="text-xs text-brand-700" onClick={() => activate.mutate(t.id)}>Activate</button>}</td></tr>))}</tbody></table>
      </section>
    </>
  );
}
