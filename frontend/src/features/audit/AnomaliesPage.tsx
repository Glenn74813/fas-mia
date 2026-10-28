import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Select } from '../../components/Select';
import { useState } from 'react';
import { Link } from 'react-router';
import { anomalyApi } from '../../api/endpoints';
import type { AnomalyFinding, AnomalySeverity } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { ActionDialog, type ActionSpec } from '../../components/ActionDialog';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Pagination } from '../../components/Pagination';
import { Spinner } from '../../components/Spinner';
import { formatDateTime } from '../../utils/format';

const RULES: Record<string, string> = {
  DUPLICATE_INVOICE: 'Duplicate invoice',
  BANK_CHANGE_PAYMENT: 'Payment after bank change',
  NEAR_THRESHOLD: 'Just below a threshold',
  BACKDATED_ENTRY: 'Backdated entry',
  BENFORD: "Benford's law",
  OFF_HOURS_POSTING: 'Off-hours posting',
  ROUND_AMOUNT: 'Round amount',
};

const SEVERITY_TONE = { HIGH: 'red', MEDIUM: 'amber', LOW: 'slate' } as const;

/** The page that shows the document a finding points at, when there is one. */
function documentLink(f: AnomalyFinding): string | null {
  if (!f.documentId) return null;
  switch (f.documentType) {
    case 'AP_INVOICE': return `/payables/invoices/${f.documentId}`;
    case 'DISBURSEMENT_VOUCHER': return `/payables/disbursements/${f.documentId}`;
    case 'JOURNAL_ENTRY': return `/general-ledger/entries/${f.documentId}`;
    case 'JOURNAL_VOUCHER': return `/journal-vouchers/${f.documentId}`;
    default: return null;
  }
}

type Decision = 'EXPLAINED' | 'ESCALATED';

export function AnomaliesPage() {
  const { hasPermission, user } = useAuth();
  const canReview = hasPermission('ANOMALY_REVIEW');
  const qc = useQueryClient();
  const [status, setStatus] = useState('OPEN');
  const [severity, setSeverity] = useState('');
  const [rule, setRule] = useState('');
  const [page, setPage] = useState(0);
  const [reviewing, setReviewing] = useState<{ finding: AnomalyFinding; decision: Decision } | null>(null);

  const summary = useQuery({ queryKey: ['anomalies', 'summary'], queryFn: anomalyApi.summary });
  const findings = useQuery({
    queryKey: ['anomalies', status, severity, rule, page],
    queryFn: () => anomalyApi.list({ status: status || undefined, severity: severity || undefined, rule: rule || undefined, page, size: 25 }),
    placeholderData: keepPreviousData,
  });
  const run = useMutation({
    mutationFn: anomalyApi.run,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['anomalies'] }),
  });
  const review = useMutation({
    mutationFn: (note: string) => anomalyApi.review(reviewing!.finding.id,
      { version: reviewing!.finding.version, status: reviewing!.decision, note }),
    onSuccess: () => {
      setReviewing(null);
      qc.invalidateQueries({ queryKey: ['anomalies'] });
    },
  });

  const spec: ActionSpec | null = reviewing && (reviewing.decision === 'EXPLAINED'
    ? { title: 'Mark as explained', text: 'Record why this is not a problem, for example the supporting document you checked.',
      confirm: 'Mark as explained', commentRequired: true, commentLabel: 'Explanation', minComment: 10 }
    : { title: 'Escalate', text: 'Record what needs following up and with whom. The finding stays on record as escalated.',
      confirm: 'Escalate', commentRequired: true, commentLabel: 'Reason for escalating', minComment: 10, danger: true });

  const select = 'rounded-lg border border-slate-300 px-3 py-2 text-sm shadow-xs';
  return (
    <>
      <PageHeader title="Anomalies" crumbs={[{ label: 'Audit', to: '/audit' }, { label: 'Anomalies' }]}
        actions={canReview && <Button variant="secondary" busy={run.isPending} onClick={() => run.mutate()}>Run detection now</Button>} />
      <p className="mb-4 max-w-3xl text-sm text-slate-600">Transactions the system flagged as unusual: possible duplicate invoices, payments
        soon after a supplier's bank details changed, amounts just below an approval threshold, backdated, off-hours or round-sum
        entries, and periods whose amounts deviate from Benford's law. Detection runs every hour. A finding is not proof of
        wrongdoing; each one is reviewed and explained or escalated by someone not involved in the document.</p>
      <ErrorAlert error={run.error} />
      {run.data && (
        <div role="status" className="mb-4 rounded-md border border-slate-200 bg-slate-50 p-3 text-sm">
          Detection finished: {run.data.high + run.data.medium + run.data.low === 0 ? 'no new findings.'
            : `${run.data.high} high, ${run.data.medium} medium and ${run.data.low} low severity finding(s) added.`}
        </div>
      )}
      {summary.data && (
        <div className="mb-4 grid grid-cols-1 gap-3 sm:grid-cols-3">
          {([['HIGH', summary.data.openHigh], ['MEDIUM', summary.data.openMedium], ['LOW', summary.data.openLow]] as [AnomalySeverity, number][])
            .map(([s, n]) => (
              <button key={s} type="button" onClick={() => { setStatus('OPEN'); setSeverity(s); setPage(0); }}
                className="rounded-xl border border-slate-200/80 bg-white p-4 text-left shadow-xs hover:border-brand-300">
                <span className="text-xs font-semibold uppercase tracking-wider text-slate-500">Open, {s.toLowerCase()} severity</span>
                <span className="mt-1.5 block text-2xl font-semibold tabular-nums text-slate-900">{n}</span>
              </button>
            ))}
        </div>
      )}
      <div className="mb-4 flex flex-wrap gap-3">
        <Select aria-label="Status" value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }} className={select}>
          <option value="OPEN">Open</option><option value="EXPLAINED">Explained</option><option value="ESCALATED">Escalated</option>
          <option value="">Any status</option>
        </Select>
        <Select aria-label="Severity" value={severity} onChange={(e) => { setSeverity(e.target.value); setPage(0); }} className={select}>
          <option value="">Any severity</option><option value="HIGH">High</option><option value="MEDIUM">Medium</option><option value="LOW">Low</option>
        </Select>
        <Select aria-label="Rule" value={rule} onChange={(e) => { setRule(e.target.value); setPage(0); }} className={select}>
          <option value="">Any rule</option>
          {Object.entries(RULES).map(([code, label]) => <option key={code} value={code}>{label}</option>)}
        </Select>
      </div>
      <ErrorAlert error={findings.error} />
      {findings.isPending ? <Spinner /> : findings.data && (
        <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs">
          <table className="min-w-full text-left text-sm">
            <thead className="border-b border-slate-200 bg-slate-50/80 text-[11px] font-semibold uppercase tracking-wider text-slate-500">
              <tr><th className="px-3 py-2">Severity</th><th className="px-3 py-2">Finding</th><th className="px-3 py-2">Document</th>
                <th className="px-3 py-2 text-right">Amount</th><th className="px-3 py-2">Detected</th><th className="px-3 py-2">Review</th></tr>
            </thead>
            <tbody>
              {findings.data.items.map((f) => {
                const link = documentLink(f);
                const involved = !!user && f.involvedUsers.includes(user.username);
                return (
                  <tr key={f.id} className="border-t border-slate-100 align-top">
                    <td className="px-3 py-2"><Badge tone={SEVERITY_TONE[f.severity]}>{f.severity}</Badge></td>
                    <td className="max-w-md px-3 py-2">
                      <span className="block font-medium text-slate-900">{f.title}</span>
                      <span className="block text-xs text-slate-500">{RULES[f.ruleCode] ?? f.ruleCode}</span>
                      <span className="mt-1.5 block text-slate-700">{f.detail}</span>
                      {f.involvedUsers.length > 0 && <span className="mt-1.5 block text-xs text-slate-500">Involved: {f.involvedUsers.join(', ')}</span>}
                    </td>
                    <td className="whitespace-nowrap px-3 py-2">{link
                      ? <Link to={link} className="text-brand-700 hover:underline">{f.reference ?? 'Open'}</Link> : f.reference ?? '—'}
                      {f.occurredOn && <span className="block text-xs text-slate-500">{f.occurredOn}</span>}</td>
                    <td className="whitespace-nowrap px-3 py-2 text-right">{f.amount ? <Money value={f.amount} /> : '—'}</td>
                    <td className="whitespace-nowrap px-3 py-2 text-xs">{formatDateTime(f.detectedAt)}</td>
                    <td className="px-3 py-2">
                      {f.status === 'OPEN' ? (canReview && (involved
                        ? <span className="text-xs text-slate-500">You are involved; another reviewer must review this.</span>
                        : <div className="flex gap-1">
                          <Button variant="secondary" onClick={() => { review.reset(); setReviewing({ finding: f, decision: 'EXPLAINED' }); }}>Explain</Button>
                          <Button variant="secondary" onClick={() => { review.reset(); setReviewing({ finding: f, decision: 'ESCALATED' }); }}>Escalate</Button>
                        </div>)) : (
                        <div className="max-w-xs">
                          <Badge tone={f.status === 'ESCALATED' ? 'red' : 'green'}>{f.status}</Badge>
                          <span className="mt-1.5 block text-xs text-slate-500">{f.reviewedBy}, {formatDateTime(f.reviewedAt)}</span>
                          <span className="mt-1.5 block text-xs text-slate-700">{f.reviewNote}</span>
                        </div>
                      )}
                    </td>
                  </tr>
                );
              })}
              {findings.data.items.length === 0 && <tr><td colSpan={6} className="px-4 py-6 text-center text-slate-500">No findings.</td></tr>}
            </tbody>
          </table>
        </div>
      )}
      {findings.data && <Pagination page={findings.data.page} totalPages={findings.data.totalPages} totalItems={findings.data.totalItems} onPage={setPage} />}
      <ActionDialog spec={spec} busy={review.isPending} error={review.error} onConfirm={(note) => review.mutate(note)}
        onClose={() => setReviewing(null)} />
    </>
  );
}
