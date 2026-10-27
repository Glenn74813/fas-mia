import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { reportingApi } from '../../api/endpoints';
import type { ReportJob, ReportSchedule } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { PageHeader } from '../../components/PageHeader';
import { Pagination } from '../../components/Pagination';
import { Spinner } from '../../components/Spinner';
import { StatusBadge } from '../../components/StatusBadge';
import { allPeriods, useFiscalYears } from '../../hooks/useAccounting';
import { ManagementReportPanel } from './ManagementReportPanel';
import { formatDateTime } from '../../utils/format';

export const REPORT_LABEL: Record<string, string> = {
  FINANCIAL_STATEMENT: 'Statement', TRIAL_BALANCE: 'Trial balance', BUDGET_VS_ACTUAL: 'Budget vs actual', AP_AGING: 'AP aging',
  AR_AGING: 'AR aging', PPE_SCHEDULE: 'PPE schedule', INVENTORY_STOCK: 'Inventory valuation', AUDIT_TRAIL: 'Audit trail export',
  CASH_DISBURSEMENT_JOURNAL: 'Cash Disbursement Journal', COLLECTION_SUMMARY: 'Collection summary', ABSTRACT_OF_BILLS: 'Abstract of bills', GENERAL_LEDGER: 'General ledger',
  SUBSIDIARY_LEDGER: 'Subsidiary ledger', DAILY_COLLECTION_REPORT: 'Daily collection report', BANK_RECONCILIATION_STATEMENT: 'Bank reconciliation statement',
  WITHHOLDING_SCHEDULE: 'Expanded withholding schedule', VARIANCE_ANALYSIS: 'Variance analysis',
};
const DAYS = ['', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];
const when = (s: ReportSchedule) => `${s.frequency === 'DAILY' ? 'Daily' : s.frequency === 'WEEKLY' ? `Every ${DAYS[s.dayOfWeek ?? 1]}`
  : `Monthly on day ${s.dayOfMonth}`} at ${String(s.runHour).padStart(2, '0')}:00`;

export function ReportJobsPage() {
  const { hasPermission } = useAuth();
  const qc = useQueryClient();
  const [page, setPage] = useState(0);
  const jobs = useQuery({ queryKey: ['report-jobs', page], queryFn: () => reportingApi.jobs(page), placeholderData: keepPreviousData,
    refetchInterval: (q) => (q.state.data?.items ?? []).some((j) => j.status === 'QUEUED' || j.status === 'RUNNING') ? 3000 : false });
  const years = useFiscalYears();
  const periods = allPeriods(years.data).filter((p) => p.status !== 'FUTURE').sort((a, b) => b.endDate.localeCompare(a.endDate));
  const [periodId, setPeriodId] = useState('');
  const [format, setFormat] = useState('XLSX');
  const tb = useMutation({ mutationFn: () => reportingApi.requestJob('TRIAL_BALANCE', format, { periodId: periodId || periods[0]!.id }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['report-jobs'] }) });
  const dl = useMutation({ mutationFn: (j: ReportJob) => reportingApi.download(j) });
  const schedules = useQuery({ queryKey: ['report-schedules'], queryFn: reportingApi.schedules });
  const remove = useMutation({ mutationFn: (id: string) => reportingApi.deleteSchedule(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['report-schedules'] }) });
  const email = useMutation({ mutationFn: ({ s, on }: { s: ReportSchedule; on: boolean }) => reportingApi.setScheduleEmail(s, on),
    onSettled: () => qc.invalidateQueries({ queryKey: ['report-schedules'] }) });
  return (
    <>
      <PageHeader title="My reports" crumbs={[{ label: 'Reports' }, { label: 'My reports' }]} />
      {hasPermission('FINANCIAL_REPORT_EXPORT') && (
        <section className="mb-4 flex flex-wrap items-end gap-3 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5 text-sm">
          <span className="flex h-9 items-center font-medium">Trial balance export</span>
          <label>Period <select value={periodId || periods[0]?.id || ''} onChange={(e) => setPeriodId(e.target.value)} className="ml-1 rounded-md border border-slate-300 px-2 py-1">
            {periods.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}</select></label>
          <label>Format <select value={format} onChange={(e) => setFormat(e.target.value)} className="ml-1 rounded-md border border-slate-300 px-2 py-1">
            {['XLSX', 'PDF', 'CSV'].map((f) => <option key={f}>{f}</option>)}</select></label>
          <Button variant="secondary" busy={tb.isPending} disabled={periods.length === 0} onClick={() => tb.mutate()}>Queue export</Button>
        </section>
      )}
      <ManagementReportPanel onQueued={() => { void qc.invalidateQueries({ queryKey: ['report-jobs'] });
        void qc.invalidateQueries({ queryKey: ['report-schedules'] }); }} />
      <ErrorAlert error={jobs.error ?? tb.error ?? dl.error ?? schedules.error ?? remove.error} />
      {(schedules.data ?? []).length > 0 && (
        <section className="mb-4 overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs" aria-label="Scheduled reports">
          <h2 className="px-4 pt-3 text-sm font-semibold">Scheduled reports</h2>
          <table className="min-w-full text-sm">
            <thead className="border-b border-slate-200 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="px-3 py-2">Name</th><th className="px-3 py-2">Report</th>
              <th className="px-3 py-2">Runs</th><th className="px-3 py-2">Next run</th><th className="px-3 py-2">Status</th>
              <th className="px-3 py-2">E-mail when ready</th><th /></tr></thead>
            <tbody>{schedules.data!.map((s) => (
              <tr key={s.id} className="border-t border-slate-100"><td className="px-3 py-2">{s.name}</td>
                <td className="px-3 py-2">{REPORT_LABEL[s.reportType] ?? s.reportType} ({s.format})</td><td className="px-3 py-2">{when(s)}</td>
                <td className="px-3 py-2">{s.status === 'ACTIVE' ? formatDateTime(s.nextRunAt) : '—'}</td>
                <td className="px-3 py-2"><StatusBadge status={s.status} />{s.statusReason && <span className="ml-2 text-xs text-amber-800">{s.statusReason}</span>}</td>
                <td className="px-3 py-2"><label className="inline-flex items-center gap-2">
                  <input type="checkbox" checked={s.emailWhenReady} disabled={email.isPending} aria-label={`E-mail me when “${s.name}” is ready`}
                    onChange={(e) => email.mutate({ s, on: e.target.checked })} />{s.emailWhenReady ? 'On' : 'Off'}</label></td>
                <td className="px-3 py-2 text-right"><button className="text-red-700 hover:underline" disabled={remove.isPending}
                  onClick={() => { if (window.confirm(`Delete the schedule “${s.name}”? Reports already produced are kept.`)) remove.mutate(s.id); }}>Delete</button></td></tr>))}
            </tbody></table>
        </section>
      )}
      {jobs.isPending ? <Spinner /> : (
        <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs"><table className="min-w-full text-sm">
          <thead className="border-b border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="px-3 py-2">Requested</th><th className="px-3 py-2">Report</th>
            <th className="px-3 py-2">Format</th><th className="px-3 py-2">Status</th><th className="px-3 py-2">File</th><th className="px-3 py-2">Available until</th><th /></tr></thead>
          <tbody>{(jobs.data?.items ?? []).map((j) => (
            <tr key={j.id} className="border-t border-slate-100"><td className="px-3 py-2">{formatDateTime(j.requestedAt)}</td>
              <td className="px-3 py-2">{REPORT_LABEL[j.reportType] ?? j.reportType}{j.reportType === 'FINANCIAL_STATEMENT' ? ` ${j.parameters.template ?? ''}` : ''}
                <span className="block text-xs text-slate-500">{Object.entries(j.parameters).filter(([k]) => k !== 'template' && k !== 'periodId')
                  .map(([k, v]) => `${k} ${v}`).join(' · ')}</span></td>
              <td className="px-3 py-2">{j.format}</td><td className="px-3 py-2"><StatusBadge status={j.status} />{j.error && <span className="ml-2 text-xs text-red-700">{j.error}</span>}</td>
              <td className="px-3 py-2">{j.fileName ?? '—'}{j.sizeBytes ? ` (${Math.ceil(j.sizeBytes / 1024)} KB)` : ''}</td>
              <td className="px-3 py-2">{j.expiresAt ? formatDateTime(j.expiresAt) : '—'}</td>
              <td className="px-3 py-2 text-right">{j.status === 'DONE' &&
                <button className="text-brand-700 hover:underline" onClick={() => dl.mutate(j)}>Download</button>}</td></tr>))}
            {jobs.data?.items.length === 0 && <tr><td colSpan={7} className="px-4 py-6 text-center text-slate-500">No reports requested yet.</td></tr>}</tbody>
        </table></div>
      )}
      {jobs.data && <Pagination page={jobs.data.page} totalPages={jobs.data.totalPages} totalItems={jobs.data.totalItems} onPage={setPage} />}
      <p className="mt-2 text-xs text-slate-500">Files are kept for the retention period and every download is recorded in the audit trail with the file's SHA-256.</p>
    </>
  );
}
