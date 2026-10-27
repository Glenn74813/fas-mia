import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { dataExportApi, retentionApi } from '../../api/endpoints';
import type { LegalHold, YearRetention } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { ActionDialog, type ActionSpec } from '../../components/ActionDialog';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { PageHeader } from '../../components/PageHeader';
import { Spinner } from '../../components/Spinner';
import { formatDateTime } from '../../utils/format';

export function RecordsRetentionPage() {
  const { hasPermission } = useAuth();
  const canHold = hasPermission('RECORDS_RETENTION_MANAGE');
  const canArchive = hasPermission('DATA_EXPORT');
  const qc = useQueryClient();
  const schedule = useQuery({ queryKey: ['records-retention'], queryFn: retentionApi.schedule });
  const refresh = () => qc.invalidateQueries({ queryKey: ['records-retention'] });

  const [archiving, setArchiving] = useState<YearRetention | null>(null);
  const [releasing, setReleasing] = useState<LegalHold | null>(null);
  const [holdYear, setHoldYear] = useState('');
  const [reason, setReason] = useState('');
  const [reference, setReference] = useState('');

  const archive = useMutation({
    mutationFn: (purpose: string) => dataExportApi.request(purpose, archiving!.fiscalYearId),
    onSuccess: () => { setArchiving(null); refresh(); },
  });
  const place = useMutation({
    mutationFn: () => retentionApi.hold({ fiscalYearId: holdYear || undefined, reason, reference: reference || undefined }),
    onSuccess: () => { setReason(''); setReference(''); refresh(); },
  });
  const release = useMutation({
    mutationFn: (note: string) => retentionApi.release(releasing!.id, { note, version: releasing!.version }),
    onSuccess: () => { setReleasing(null); refresh(); },
  });

  const submitHold = (e: FormEvent) => {
    e.preventDefault();
    place.mutate();
  };
  const archiveSpec: ActionSpec | null = archiving && {
    title: `Archive fiscal year ${archiving.fiscalYear}`,
    text: 'The archive holds the year\'s transactions, their supporting documents, the year\'s audit trail and the reference data '
      + 'needed to read them. Another holder of the data export permission must approve it; you can then download it from '
      + 'Data export. Its checksum is recorded in the audit trail.',
    confirm: 'Request archive', commentRequired: true, commentLabel: 'Purpose', minComment: 10,
  };
  const releaseSpec: ActionSpec | null = releasing && {
    title: 'Release the legal hold', text: `Hold: ${releasing.reason}`, confirm: 'Release', commentRequired: true,
    commentLabel: 'Why it is released', minComment: 10,
  };

  const input = 'block w-full rounded-lg border border-slate-300 px-3 py-2 text-sm shadow-xs';
  return (
    <>
      <PageHeader title="Records retention" crumbs={[{ label: 'Administration' }, { label: 'Records retention' }]} />
      <ErrorAlert error={schedule.error} />
      {schedule.isPending ? <Spinner /> : schedule.data && (
        <>
          <p className="mb-2 max-w-3xl text-sm text-slate-600">Each fiscal year's records are kept at least {schedule.data.retentionYears} years
            after the year ends. A closed year can be archived: a sealed copy of its records, documents and audit trail.
            A legal hold records that a year's records must not be disposed of.</p>
          <p className="mb-4 max-w-3xl rounded-md border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900">{schedule.data.disposal}</p>
          <div className="mb-6 overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs">
            <table className="min-w-full text-left text-sm">
              <thead className="border-b border-slate-200 bg-slate-50/80 text-[11px] font-semibold uppercase tracking-wider text-slate-500">
                <tr><th className="px-3 py-2">Fiscal year</th><th className="px-3 py-2">Status</th><th className="px-3 py-2">Keep until</th>
                  <th className="px-3 py-2">Hold</th><th className="px-3 py-2">Archives</th><th className="px-3 py-2" /></tr>
              </thead>
              <tbody>
                {schedule.data.years.map((y) => (
                  <tr key={y.fiscalYearId} className="border-t border-slate-100 align-top">
                    <td className="px-3 py-2 font-medium">{y.fiscalYear}<span className="block text-xs font-normal text-slate-500">{y.start} to {y.end}</span></td>
                    <td className="px-3 py-2"><Badge tone={y.status === 'CLOSED' ? 'slate' : 'blue'}>{y.status}</Badge>
                      {y.closedAt && <span className="block text-xs text-slate-500">Closed {formatDateTime(y.closedAt)}</span>}</td>
                    <td className="whitespace-nowrap px-3 py-2">{y.retainUntil}{y.retentionElapsed && <span className="block text-xs text-slate-500">Retention period over</span>}</td>
                    <td className="px-3 py-2">{y.onHold ? <Badge tone="amber">ON HOLD</Badge> : '—'}</td>
                    <td className="px-3 py-2 text-xs">{y.archives.length === 0 ? '—' : y.archives.map((a) => (
                      <span key={a.exportId} className="block">{a.fileName} <span className="text-slate-500">{formatDateTime(a.finishedAt)}</span>
                        <span className="block font-mono text-[11px] text-slate-500" title="SHA-256, also in the audit trail">{a.sha256.slice(0, 16)}…</span></span>))}</td>
                    <td className="whitespace-nowrap px-3 py-2 text-right">
                      {canArchive && y.status === 'CLOSED' && <Button variant="secondary" onClick={() => { archive.reset(); setArchiving(y); }}>Archive</Button>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {archive.isSuccess && <p role="status" className="mb-4 text-sm text-slate-700">Archive requested. It needs another approver;
            follow it under <Link to="/admin/data-exports" className="text-brand-700 hover:underline">Data export</Link>.</p>}

          <h2 className="mb-2 text-base font-semibold text-slate-900">Legal holds</h2>
          {canHold && (
            <form onSubmit={submitHold} className="mb-4 grid max-w-3xl gap-3 rounded-xl border border-slate-200/80 bg-white p-4 shadow-xs sm:grid-cols-2">
              <label className="text-sm">Records
                <select value={holdYear} onChange={(e) => setHoldYear(e.target.value)} className={`mt-1 ${input}`}>
                  <option value="">Every fiscal year</option>
                  {schedule.data.years.map((y) => <option key={y.fiscalYearId} value={y.fiscalYearId}>Fiscal year {y.fiscalYear}</option>)}
                </select></label>
              <label className="text-sm">Reference (optional)
                <input value={reference} maxLength={100} onChange={(e) => setReference(e.target.value)} placeholder="Case or memo number" className={`mt-1 ${input}`} /></label>
              <label className="text-sm sm:col-span-2">Reason
                <textarea rows={2} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} className={`mt-1 ${input}`} /></label>
              <div className="sm:col-span-2"><ErrorAlert error={place.error} />
                <Button type="submit" busy={place.isPending} disabled={reason.trim().length < 10}>Place hold</Button></div>
            </form>
          )}
          <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs">
            <table className="min-w-full text-left text-sm">
              <thead className="border-b border-slate-200 bg-slate-50/80 text-[11px] font-semibold uppercase tracking-wider text-slate-500">
                <tr><th className="px-3 py-2">Records</th><th className="px-3 py-2">Reason</th><th className="px-3 py-2">Placed</th>
                  <th className="px-3 py-2">Released</th><th className="px-3 py-2" /></tr>
              </thead>
              <tbody>
                {schedule.data.holds.map((h) => (
                  <tr key={h.id} className="border-t border-slate-100 align-top">
                    <td className="whitespace-nowrap px-3 py-2">{h.fiscalYear ? `Fiscal year ${h.fiscalYear}` : 'Every fiscal year'}</td>
                    <td className="max-w-sm px-3 py-2">{h.reason}{h.reference && <span className="block text-xs text-slate-500">{h.reference}</span>}</td>
                    <td className="whitespace-nowrap px-3 py-2 text-xs">{h.placedBy}<span className="block text-slate-500">{formatDateTime(h.placedAt)}</span></td>
                    <td className="max-w-xs px-3 py-2 text-xs">{h.releasedAt ? <>{h.releasedBy}, {formatDateTime(h.releasedAt)}
                      <span className="block text-slate-600">{h.releaseNote}</span></> : <Badge tone="amber">ACTIVE</Badge>}</td>
                    <td className="px-3 py-2 text-right">{canHold && !h.releasedAt &&
                      <Button variant="secondary" onClick={() => { release.reset(); setReleasing(h); }}>Release</Button>}</td>
                  </tr>
                ))}
                {schedule.data.holds.length === 0 && <tr><td colSpan={5} className="px-4 py-6 text-center text-slate-500">No legal holds.</td></tr>}
              </tbody>
            </table>
          </div>
        </>
      )}
      <ActionDialog spec={archiveSpec} busy={archive.isPending} error={archive.error} onConfirm={(p) => archive.mutate(p)} onClose={() => setArchiving(null)} />
      <ActionDialog spec={releaseSpec} busy={release.isPending} error={release.error} onConfirm={(n) => release.mutate(n)} onClose={() => setReleasing(null)} />
    </>
  );
}
