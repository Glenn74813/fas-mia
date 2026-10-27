import { useMutation, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { accountApi, bankApi, customerApi, reportingApi, supplierApi } from '../../api/endpoints';
import { useAuth } from '../../auth/AuthContext';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Field } from '../../components/Field';
import { SearchSelect } from '../../components/SearchSelect';
import { postableAccounts, useChart, useCostCenters, useFiscalYears } from '../../hooks/useAccounting';

type Param = 'asOf' | 'range' | 'date' | 'quarter' | 'fiscalYear' | 'audit' | 'account' | 'costCenter' | 'subsidiary' | 'customer' | 'supplier'
  | 'reconciliation' | 'variance';

interface Def { type: string; label: string; permission: string; params: Param[] }

const REPORTS: Def[] = [
  { type: 'GENERAL_LEDGER', label: 'General ledger', permission: 'LEDGER_VIEW', params: ['range', 'account', 'costCenter'] },
  { type: 'SUBSIDIARY_LEDGER', label: 'Subsidiary ledger', permission: 'LEDGER_VIEW', params: ['range', 'subsidiary'] },
  { type: 'BUDGET_VS_ACTUAL', label: 'Budget vs actual', permission: 'BUDGET_VIEW', params: ['fiscalYear'] },
  { type: 'VARIANCE_ANALYSIS', label: 'Variance analysis', permission: 'BUDGET_VIEW', params: ['fiscalYear', 'variance', 'costCenter'] },
  { type: 'AP_AGING', label: 'Accounts payable aging', permission: 'AP_VIEW', params: ['asOf', 'supplier'] },
  { type: 'CASH_DISBURSEMENT_JOURNAL', label: 'Cash Disbursement Journal', permission: 'AP_VIEW', params: ['range'] },
  { type: 'WITHHOLDING_SCHEDULE', label: 'Expanded withholding schedule', permission: 'AP_VIEW', params: ['quarter'] },
  { type: 'AR_AGING', label: 'Accounts receivable aging', permission: 'AR_VIEW', params: ['asOf', 'customer'] },
  { type: 'COLLECTION_SUMMARY', label: 'Collection summary', permission: 'AR_VIEW', params: ['range'] },
  { type: 'ABSTRACT_OF_BILLS', label: 'Abstract of bills', permission: 'AR_VIEW', params: ['range'] },
  { type: 'DAILY_COLLECTION_REPORT', label: 'Daily collection report', permission: 'TREASURY_VIEW', params: ['date'] },
  { type: 'BANK_RECONCILIATION_STATEMENT', label: 'Bank reconciliation statement', permission: 'TREASURY_VIEW', params: ['reconciliation'] },
  { type: 'PPE_SCHEDULE', label: 'PPE schedule', permission: 'FA_VIEW', params: ['range'] },
  { type: 'INVENTORY_STOCK', label: 'Inventory valuation', permission: 'INVENTORY_VIEW', params: [] },
  { type: 'AUDIT_TRAIL', label: 'Audit trail export', permission: 'AUDIT_LOG_VIEW', params: ['range', 'audit'] },
];

/** Relative periods for scheduled runs; the server resolves the tokens each time the schedule runs. */
const RELATIVE: Record<string, { label: string; from: string; to: string }> = {
  prevMonth: { label: 'The previous month', from: '{prevMonthStart}', to: '{prevMonthEnd}' },
  monthToDate: { label: 'Month to date', from: '{monthStart}', to: '{today}' },
};
const select = 'ml-1 rounded-md border border-slate-300 px-2 py-1';

/** Queues (or schedules) the reports the user's module permissions allow; the server checks them again. */
export function ManagementReportPanel({ onQueued }: { onQueued: () => void }) {
  const { hasPermission } = useAuth();
  const years = useFiscalYears();
  const allowed = REPORTS.filter((r) => hasPermission(r.permission) || (r.type === 'AUDIT_TRAIL' && hasPermission('SECURITY_EVENT_VIEW')));
  const today = new Date().toISOString().slice(0, 10);
  const [type, setType] = useState('');
  const [format, setFormat] = useState('XLSX');
  const [p, setP] = useState({ asOf: today, from: `${today.slice(0, 4)}-01-01`, to: today, date: today, year: today.slice(0, 4),
    quarter: String(Math.floor(new Date().getMonth() / 3) + 1), fiscalYearId: '', table: 'AUDIT', action: '', actor: '', accountId: '',
    costCenterId: '', slType: 'CUSTOMER', subsidiaryAccountId: '', customerId: '', supplierId: '', reconciliationId: '',
    throughPeriod: String(new Date().getMonth() + 1), thresholdPercent: '10' });
  const [schedule, setSchedule] = useState({ on: false, name: '', frequency: 'MONTHLY', runHour: '7', dayOfWeek: '1', dayOfMonth: '1', relative: 'prevMonth',
    email: true });
  const def = allowed.find((r) => r.type === type) ?? allowed[0];
  const has = (x: Param) => !!def?.params.includes(x);
  const chart = useChart(has('account'));
  const costCenters = useCostCenters();
  const recs = useQuery({ queryKey: ['bank-reconciliations', 'picker'], queryFn: () => bankApi.reconciliations(0, 200), enabled: has('reconciliation') });

  const parameters = (): Record<string, string> => {
    const out: Record<string, string> = {};
    const rel = RELATIVE[schedule.relative]!;
    if (has('asOf')) out.asOf = schedule.on ? '{today}' : p.asOf;
    if (has('range')) Object.assign(out, schedule.on ? { from: rel.from, to: rel.to } : { from: p.from, to: p.to });
    if (has('date')) out.date = schedule.on ? '{yesterday}' : p.date;
    if (has('quarter')) Object.assign(out, schedule.on ? { year: '{prevQuarterYear}', quarter: '{prevQuarter}' } : { year: p.year, quarter: p.quarter });
    if (has('fiscalYear')) out.fiscalYearId = p.fiscalYearId || years.data?.[0]?.id || '';
    if (has('account') && p.accountId) out.accountId = p.accountId;
    if (has('costCenter') && p.costCenterId) out.costCenterId = p.costCenterId;
    if (has('subsidiary')) Object.assign(out, p.subsidiaryAccountId ? { subsidiaryAccountId: p.subsidiaryAccountId } : { slType: p.slType });
    if (has('customer') && p.customerId) out.customerId = p.customerId;
    if (has('supplier') && p.supplierId) out.supplierId = p.supplierId;
    if (has('variance')) Object.assign(out, { throughPeriod: p.throughPeriod, thresholdPercent: p.thresholdPercent || '10' });
    if (has('reconciliation')) out.reconciliationId = p.reconciliationId || recs.data?.items[0]?.id || '';
    if (has('audit')) {
      out.table = hasPermission('AUDIT_LOG_VIEW') ? p.table : 'SECURITY';
      if (p.action.trim()) out.action = p.action.trim().toUpperCase();
      if (p.actor.trim()) out.actor = p.actor.trim();
    }
    return out;
  };
  const queue = useMutation({
    mutationFn: async () => {
      if (!schedule.on) return reportingApi.requestJob(def!.type, format, parameters());
      return reportingApi.createSchedule({ name: schedule.name.trim() || def!.label, reportType: def!.type, format, parameters: parameters(),
        frequency: schedule.frequency, runHour: Number(schedule.runHour),
        dayOfWeek: schedule.frequency === 'WEEKLY' ? Number(schedule.dayOfWeek) : undefined,
        dayOfMonth: schedule.frequency === 'MONTHLY' ? Number(schedule.dayOfMonth) : undefined, emailWhenReady: schedule.email });
    },
    onSuccess: onQueued,
  });
  if (!def) return null;
  const relativeAllowed = has('range') || has('asOf') || has('date') || has('quarter') || def.params.length === 0 || has('fiscalYear');
  return (
    <section className="mb-4 space-y-3 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5 text-sm">
      <h2 className="font-semibold">Request a report</h2>
      <div className="flex flex-wrap items-end gap-3">
        <label>Report <select value={def.type} onChange={(e) => setType(e.target.value)} className={select}>
          {allowed.map((r) => <option key={r.type} value={r.type}>{r.label}</option>)}</select></label>
        {!schedule.on && has('asOf') && <Field label="As of" type="date" value={p.asOf} onChange={(e) => setP({ ...p, asOf: e.target.value })} />}
        {!schedule.on && has('range') && <>
          <Field label="From" type="date" value={p.from} onChange={(e) => setP({ ...p, from: e.target.value })} />
          <Field label="To" type="date" value={p.to} onChange={(e) => setP({ ...p, to: e.target.value })} /></>}
        {!schedule.on && has('date') && <Field label="Collection date" type="date" value={p.date} onChange={(e) => setP({ ...p, date: e.target.value })} />}
        {!schedule.on && has('quarter') && <>
          <Field label="Year" value={p.year} inputMode="numeric" onChange={(e) => setP({ ...p, year: e.target.value.replace(/\D/g, '') })} />
          <label>Quarter <select value={p.quarter} onChange={(e) => setP({ ...p, quarter: e.target.value })} className={select}>
            {['1', '2', '3', '4'].map((q) => <option key={q} value={q}>Q{q}</option>)}</select></label></>}
        {has('fiscalYear') && (
          <label>Fiscal year <select value={p.fiscalYearId || years.data?.[0]?.id || ''} onChange={(e) => setP({ ...p, fiscalYearId: e.target.value })}
            className={select}>{(years.data ?? []).map((y) => <option key={y.id} value={y.id}>{y.fiscalYear}</option>)}</select></label>
        )}
        {has('variance') && <>
          <label>Through period <select value={p.throughPeriod} onChange={(e) => setP({ ...p, throughPeriod: e.target.value })} className={select}>
            {Array.from({ length: 13 }, (_, i) => String(i + 1)).map((n) => <option key={n} value={n}>{n === '13' ? '13 (adjustment)' : n}</option>)}
          </select></label>
          <div className="w-28"><Field label="Threshold %" inputMode="decimal" value={p.thresholdPercent}
            onChange={(e) => setP({ ...p, thresholdPercent: e.target.value.replace(/[^\d.]/g, '') })} /></div></>}
        {has('account') && <label>Account <select value={p.accountId} onChange={(e) => setP({ ...p, accountId: e.target.value })} className={select}>
          <option value="">All accounts</option>{postableAccounts(chart.data?.accounts).map((a) => <option key={a.id} value={a.id}>{a.code} {a.name}</option>)}</select></label>}
        {has('costCenter') && <label>Cost centre <select value={p.costCenterId} onChange={(e) => setP({ ...p, costCenterId: e.target.value })} className={select}>
          <option value="">All</option>{(costCenters.data ?? []).map((c) => <option key={c.id} value={c.id}>{c.code}</option>)}</select></label>}
        {has('subsidiary') && <>
          <label>Ledger <select value={p.slType} onChange={(e) => setP({ ...p, slType: e.target.value, subsidiaryAccountId: '' })} className={select}>
            {['CUSTOMER', 'SUPPLIER', 'EMPLOYEE', 'OTHER'].map((t) => <option key={t} value={t}>{t.charAt(0) + t.slice(1).toLowerCase()}</option>)}</select></label>
          <div className="w-80"><SearchSelect id="report-subsidiary" label="Subsidiary account" value={p.subsidiaryAccountId}
            queryKey={`subsidiary-accounts-${p.slType}`} placeholder="All of this type" search={(q) => accountApi.subsidiaryAccounts(p.slType, q)}
            optionLabel={(x) => `${x.code} ${x.name}`} onChange={(id) => setP({ ...p, subsidiaryAccountId: id })} /></div></>}
        {has('customer') && <div className="w-80"><SearchSelect id="report-customer" label="Customer" value={p.customerId} queryKey="customers"
          placeholder="All customers" search={(q) => customerApi.list(q, 0, 50)} optionLabel={(c) => `${c.code} ${c.name}`}
          onChange={(id) => setP({ ...p, customerId: id })} /></div>}
        {has('supplier') && <div className="w-80"><SearchSelect id="report-supplier" label="Supplier" value={p.supplierId} queryKey="suppliers"
          placeholder="All suppliers" search={(q) => supplierApi.list(q, 0, 50)} optionLabel={(s) => `${s.code} ${s.name}`}
          onChange={(id) => setP({ ...p, supplierId: id })} /></div>}
        {has('reconciliation') && <label>Reconciliation <select value={p.reconciliationId || recs.data?.items[0]?.id || ''}
          onChange={(e) => setP({ ...p, reconciliationId: e.target.value })} className={select}>
          {(recs.data?.items ?? []).map((r) => <option key={r.id} value={r.id}>{r.bankAccountCode} as of {r.asOfDate} ({r.status.toLowerCase()})</option>)}</select></label>}
        {has('audit') && <>
          {hasPermission('AUDIT_LOG_VIEW') && hasPermission('SECURITY_EVENT_VIEW') && (
            <label>Log <select value={p.table} onChange={(e) => setP({ ...p, table: e.target.value })} className={select}>
              <option value="AUDIT">Audit trail</option><option value="SECURITY">Security events</option></select></label>)}
          <Field label="Action (optional)" value={p.action} onChange={(e) => setP({ ...p, action: e.target.value })} />
          <Field label="Actor username (optional)" value={p.actor} onChange={(e) => setP({ ...p, actor: e.target.value })} /></>}
        <label>Format <select value={format} onChange={(e) => setFormat(e.target.value)} className={select}>
          {['XLSX', 'PDF', 'CSV'].map((f) => <option key={f}>{f}</option>)}</select></label>
      </div>

      {relativeAllowed && !has('reconciliation') && !has('variance') && (
        <div className="space-y-2 rounded-md bg-slate-50 p-3">
          <label className="font-medium"><input type="checkbox" className="mr-2" checked={schedule.on} onChange={(e) => setSchedule({ ...schedule, on: e.target.checked })} />
            Repeat on a schedule</label>
          {schedule.on && (
            <div className="flex flex-wrap items-end gap-3">
              <Field label="Schedule name" value={schedule.name} placeholder={def.label} onChange={(e) => setSchedule({ ...schedule, name: e.target.value })} />
              <label>Every <select value={schedule.frequency} onChange={(e) => setSchedule({ ...schedule, frequency: e.target.value })} className={select}>
                <option value="DAILY">Day</option><option value="WEEKLY">Week</option><option value="MONTHLY">Month</option></select></label>
              {schedule.frequency === 'WEEKLY' && <label>On <select value={schedule.dayOfWeek} onChange={(e) => setSchedule({ ...schedule, dayOfWeek: e.target.value })} className={select}>
                {['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'].map((d, i) => <option key={d} value={String(i + 1)}>{d}</option>)}</select></label>}
              {schedule.frequency === 'MONTHLY' && <label>On day <select value={schedule.dayOfMonth} onChange={(e) => setSchedule({ ...schedule, dayOfMonth: e.target.value })} className={select}>
                {Array.from({ length: 28 }, (_, i) => String(i + 1)).map((d) => <option key={d}>{d}</option>)}</select></label>}
              <label>At <select value={schedule.runHour} onChange={(e) => setSchedule({ ...schedule, runHour: e.target.value })} className={select}>
                {Array.from({ length: 24 }, (_, i) => String(i)).map((h) => <option key={h} value={h}>{h.padStart(2, '0')}:00</option>)}</select></label>
              <label className="flex h-9 items-center"><input type="checkbox" className="mr-2" checked={schedule.email}
                onChange={(e) => setSchedule({ ...schedule, email: e.target.checked })} />E-mail me when it is ready</label>
              {has('range') && <label>Covering <select value={schedule.relative} onChange={(e) => setSchedule({ ...schedule, relative: e.target.value })} className={select}>
                {Object.entries(RELATIVE).map(([k, v]) => <option key={k} value={k}>{v.label}</option>)}</select></label>}
            </div>
          )}
          {schedule.on && <p className="text-xs text-slate-500">Times are Manila time. {has('asOf') && 'Aging is as of the run date. '}
            {has('date') && 'The daily collection report covers the previous day. '}{has('quarter') && 'The schedule covers the previous quarter. '}
            The schedule stops by itself if you lose access to the report. The e-mail links to My reports; the report itself is never attached.</p>}
        </div>
      )}
      <Button variant="secondary" busy={queue.isPending} onClick={() => queue.mutate()}>{schedule.on ? 'Save schedule' : 'Queue report'}</Button>
      {def.type === 'VARIANCE_ANALYSIS' && <p className="text-xs text-slate-500">Budget to date is the allotment spread evenly over 12
        periods, against posted actuals to the end of the chosen period. A positive variance is favourable. The layout is proposed until MIAA
        confirms its budget definitions.</p>}
      {def.type === 'AUDIT_TRAIL' && <p className="text-xs text-slate-500">Each exported event carries its row hash, and the export records whether the
        whole hash chain verified intact. The export request is itself recorded in the audit trail.</p>}
      <ErrorAlert error={queue.error} />
      {queue.isSuccess && <p role="status" className="text-green-800">{schedule.on ? 'Schedule saved — see Scheduled reports below.'
        : 'Queued — it appears in the list below when ready.'}</p>}
    </section>
  );
}
