import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { dataExportApi } from '../../api/endpoints';
import type { DataExport } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { ActionDialog, type ActionSpec } from '../../components/ActionDialog';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { PageHeader } from '../../components/PageHeader';
import { Pagination } from '../../components/Pagination';
import { Spinner } from '../../components/Spinner';
import { formatDateTime } from '../../utils/format';

const TONE = { REQUESTED: 'amber', APPROVED: 'blue', RUNNING: 'blue', DONE: 'green', FAILED: 'red', REJECTED: 'red', EXPIRED: 'slate' } as const;

function size(bytes: number | null): string {
  if (bytes == null) return '';
  if (bytes >= 1e9) return `${(bytes / 1e9).toFixed(1)} GB`;
  if (bytes >= 1e6) return `${(bytes / 1e6).toFixed(1)} MB`;
  return `${Math.max(1, Math.round(bytes / 1e3))} KB`;
}

export function DataExportsPage() {
  const { user } = useAuth();
  const qc = useQueryClient();
  const [page, setPage] = useState(0);
  const [purpose, setPurpose] = useState('');
  const [deciding, setDeciding] = useState<{ exp: DataExport; approve: boolean } | null>(null);
  const exports = useQuery({
    queryKey: ['data-exports', page],
    queryFn: () => dataExportApi.list(page),
    placeholderData: keepPreviousData,
    // Poll while an export is being prepared.
    refetchInterval: (q) => (q.state.data?.items.some((e) => e.status === 'APPROVED' || e.status === 'RUNNING') ? 5000 : false),
  });
  const refresh = () => qc.invalidateQueries({ queryKey: ['data-exports'] });
  const request = useMutation({
    mutationFn: () => dataExportApi.request(purpose),
    onSuccess: () => { setPurpose(''); refresh(); },
  });
  const decide = useMutation({
    mutationFn: (note: string) => dataExportApi.decide(deciding!.exp.id,
      { approve: deciding!.approve, note: note || undefined, version: deciding!.exp.version }),
    onSuccess: () => { setDeciding(null); refresh(); },
  });
  const download = useMutation({ mutationFn: dataExportApi.download });

  const submit = (e: FormEvent) => {
    e.preventDefault();
    request.mutate();
  };
  const spec: ActionSpec | null = deciding && (deciding.approve
    ? { title: 'Approve the export', text: `${deciding.exp.requestedBy} will be able to download every table and document `
      + 'in the system, except sign-in secrets. Approve only if the purpose justifies it.', confirm: 'Approve' }
    : { title: 'Reject the export', text: 'The requester will see your reason.', confirm: 'Reject', commentRequired: true,
      commentLabel: 'Reason', danger: true });

  return (
    <>
      <PageHeader title="Data export" crumbs={[{ label: 'Administration' }, { label: 'Data export' }]} />
      <p className="mb-4 max-w-3xl text-sm text-slate-600">A full copy of MIAA's data in open formats: every table as CSV with a data
        dictionary, every supporting document, the audit-chain check and a manifest of SHA-256 checksums, in one ZIP. Sign-in
        secrets are left out. Two people are needed: one asks, with the purpose, and another approves. Only the person who
        asked can download the file, for 7 days. Every step is recorded in the audit trail.</p>
      <form onSubmit={submit} className="mb-6 max-w-3xl space-y-2 rounded-xl border border-slate-200/80 bg-white p-4 shadow-xs">
        <label className="block text-sm font-medium text-slate-800" htmlFor="export-purpose">Purpose</label>
        <textarea id="export-purpose" rows={2} maxLength={500} value={purpose} onChange={(e) => setPurpose(e.target.value)}
          placeholder="For example: year-end copy for the Records Office"
          className="block w-full rounded-lg border border-slate-300 px-3 py-2 text-sm shadow-xs" />
        <ErrorAlert error={request.error} />
        <Button type="submit" busy={request.isPending} disabled={purpose.trim().length < 10}>Request full export</Button>
      </form>
      <ErrorAlert error={exports.error ?? download.error} />
      {exports.isPending ? <Spinner /> : exports.data && (
        <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs">
          <table className="min-w-full text-left text-sm">
            <thead className="border-b border-slate-200 bg-slate-50/80 text-[11px] font-semibold uppercase tracking-wider text-slate-500">
              <tr><th className="px-3 py-2">Requested</th><th className="px-3 py-2">Purpose</th><th className="px-3 py-2">Status</th>
                <th className="px-3 py-2">Decision</th><th className="px-3 py-2">File</th><th className="px-3 py-2" /></tr>
            </thead>
            <tbody>
              {exports.data.items.map((e) => {
                const mine = e.requestedBy === user?.username;
                return (
                  <tr key={e.id} className="border-t border-slate-100 align-top">
                    <td className="whitespace-nowrap px-3 py-2">{formatDateTime(e.requestedAt)}<span className="block text-xs text-slate-500">{e.requestedBy}</span></td>
                    <td className="max-w-sm px-3 py-2"><span className="block text-xs font-medium text-slate-500">
                      {e.kind === 'ARCHIVE' ? `Archive of fiscal year ${e.fiscalYear}` : 'Full export'}</span>{e.purpose}</td>
                    <td className="px-3 py-2"><Badge tone={TONE[e.status]}>{e.status}</Badge>
                      {e.error && <span className="mt-1 block text-xs text-red-700">{e.error}</span>}</td>
                    <td className="max-w-xs px-3 py-2 text-xs">{e.decidedBy ? <>{e.decidedBy}, {formatDateTime(e.decidedAt)}
                      {e.decisionNote && <span className="block text-slate-600">{e.decisionNote}</span>}</> : '—'}</td>
                    <td className="px-3 py-2 text-xs">{e.fileName && <>{e.fileName} ({size(e.sizeBytes)})
                      <span className="block font-mono text-[11px] text-slate-500" title="SHA-256 of the ZIP">{e.sha256?.slice(0, 16)}…</span>
                      {e.expiresAt && e.status === 'DONE' && <span className="block text-slate-500">Until {formatDateTime(e.expiresAt)}</span>}</>}</td>
                    <td className="whitespace-nowrap px-3 py-2 text-right">
                      {e.status === 'REQUESTED' && !mine && <div className="flex justify-end gap-1">
                        <Button variant="secondary" onClick={() => { decide.reset(); setDeciding({ exp: e, approve: true }); }}>Approve</Button>
                        <Button variant="secondary" onClick={() => { decide.reset(); setDeciding({ exp: e, approve: false }); }}>Reject</Button>
                      </div>}
                      {e.status === 'REQUESTED' && mine && <span className="text-xs text-slate-500">Waiting for another approver</span>}
                      {e.status === 'DONE' && mine && <Button variant="secondary" busy={download.isPending} onClick={() => download.mutate(e)}>Download</Button>}
                    </td>
                  </tr>
                );
              })}
              {exports.data.items.length === 0 && <tr><td colSpan={6} className="px-4 py-6 text-center text-slate-500">No exports yet.</td></tr>}
            </tbody>
          </table>
        </div>
      )}
      {exports.data && <Pagination page={exports.data.page} totalPages={exports.data.totalPages} totalItems={exports.data.totalItems} onPage={setPage} />}
      <ActionDialog spec={spec} busy={decide.isPending} error={decide.error} onConfirm={(note) => decide.mutate(note)} onClose={() => setDeciding(null)} />
    </>
  );
}
