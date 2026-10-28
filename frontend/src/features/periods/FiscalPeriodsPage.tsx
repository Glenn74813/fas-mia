import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Combobox } from '../../components/Combobox';
import { useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import { periodApi } from '../../api/endpoints';
import type { CloseChecklist, FiscalYear, Period } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { PageHeader } from '../../components/PageHeader';
import { Spinner } from '../../components/Spinner';
import { StatusBadge } from '../../components/StatusBadge';
import { accountsFor, useChart, useFiscalYears } from '../../hooks/useAccounting';
import { formatDateTime } from '../../utils/format';

export function FiscalPeriodsPage() {
  const { hasPermission } = useAuth();
  const qc = useQueryClient();
  const years = useFiscalYears();
  const pending = useQuery({ queryKey: ['reopen-requests'], queryFn: periodApi.pendingReopens,
    enabled: hasPermission('PERIOD_REOPEN') || hasPermission('PERIOD_CLOSE') });
  const [newYear, setNewYear] = useState(String(new Date().getFullYear() + 1));
  const [error, setError] = useState<unknown>(null);
  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ['fiscal-years'] });
    qc.invalidateQueries({ queryKey: ['reopen-requests'] });
  };
  const run = async (fn: () => Promise<unknown>) => {
    setError(null);
    try {
      await fn();
      invalidate();
    } catch (e) {
      setError(e);
      invalidate();
    }
  };
  const createYear = useMutation({ mutationFn: () => periodApi.createFiscalYear(Number(newYear)), onSuccess: invalidate });

  return (
    <>
      <PageHeader title="Fiscal periods" crumbs={[{ label: 'General ledger' }, { label: 'Fiscal periods' }]}
        actions={hasPermission('PERIOD_OPEN') && (
          <div className="flex items-end gap-2">
            <input aria-label="Fiscal year to create" value={newYear} onChange={(e) => setNewYear(e.target.value.replace(/\D/g, ''))}
              className="w-24 rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm" />
            <Button busy={createYear.isPending} onClick={() => createYear.mutate()}>Create fiscal year</Button>
          </div>
        )} />
      <ErrorAlert error={error ?? createYear.error} />
      {pending.data && pending.data.length > 0 && (
        <section className="mb-4 rounded-lg border border-amber-300 bg-amber-50 p-4">
          <h2 className="mb-2 font-semibold">Pending reopen requests</h2>
          <ul className="space-y-2 text-sm">
            {pending.data.map((r) => (
              <li key={r.id} className="flex flex-wrap items-center justify-between gap-2">
                <span><strong>{r.periodName}</strong> — requested by {r.requestedBy} {formatDateTime(r.requestedAt)}: “{r.reason}”</span>
                {hasPermission('PERIOD_REOPEN') && (
                  <span className="flex gap-2">
                    <Button onClick={() => run(() => periodApi.decideReopen(r.id, true, 'Approved'))}>Approve</Button>
                    <Button variant="secondary" onClick={() => run(() => periodApi.decideReopen(r.id, false, 'Rejected'))}>Reject</Button>
                  </span>
                )}
              </li>
            ))}
          </ul>
          <p className="mt-2 text-xs text-slate-600">The requester cannot decide their own request.</p>
        </section>
      )}
      {years.isPending ? <Spinner /> : years.data?.map((fy) => <YearCard key={fy.id} fy={fy} run={run} />)}
      {years.data?.length === 0 && <p className="text-sm text-slate-500">No fiscal years defined.</p>}
    </>
  );
}

function YearCard({ fy, run }: { fy: FiscalYear; run: (fn: () => Promise<unknown>) => Promise<void> }) {
  const { hasPermission } = useAuth();
  const navigate = useNavigate();
  const chart = useChart();
  const [checklist, setChecklist] = useState<CloseChecklist | null>(null);
  const [waived, setWaived] = useState<string[]>([]);
  const [justification, setJustification] = useState('');
  const [reopenFor, setReopenFor] = useState<Period | null>(null);
  const [reason, setReason] = useState('');
  const [surplus, setSurplus] = useState('');
  const closeDialog = useRef<HTMLDialogElement>(null);
  const reopenDialog = useRef<HTMLDialogElement>(null);
  const equity = accountsFor(chart.data?.accounts, 'EQUITY');

  const openChecklist = async (p: Period) => {
    setChecklist(await periodApi.checklist(p.id));
    setWaived([]);
    setJustification('');
    closeDialog.current?.showModal();
  };
  const failingWaivable = checklist?.checks.filter((c) => c.outcome === 'FAIL' && c.waivable) ?? [];
  const blocking = checklist?.checks.filter((c) => c.outcome === 'FAIL' && !c.waivable) ?? [];

  return (
    <section className="mb-4 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-lg font-semibold">Fiscal year {fy.fiscalYear} <StatusBadge status={fy.status} /></h2>
        {fy.status === 'OPEN' && hasPermission('PERIOD_CLOSE') && (
          <div className="flex flex-wrap items-center gap-2 text-sm">
            <Combobox aria-label="Accumulated surplus account" value={surplus} onChange={(v) => setSurplus(v)}
              className="rounded-md border border-slate-300 px-2 py-2" placeholder="Surplus account for closing…"
              options={equity.map((a) => ({ value: a.id, label: `${a.code} — ${a.name}` }))} />
            <Button variant="secondary" disabled={!surplus} onClick={() => run(async () => {
              const v = await periodApi.closingVoucher(fy.id, surplus);
              navigate(`/journal-vouchers/${v.id}`);
            })}>Generate closing voucher</Button>
            <Button variant="secondary" onClick={() => run(() => periodApi.closeFiscalYear(fy.id, fy.version))}>Close year</Button>
          </div>
        )}
      </div>
      <div className="overflow-x-auto">
        <table className="min-w-full text-sm">
          <thead className="border-b border-slate-200 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500">
            <tr><th className="py-1 pr-3">#</th><th className="py-1 pr-3">Period</th><th className="py-1 pr-3">Dates</th><th className="py-1 pr-3">Status</th><th /></tr>
          </thead>
          <tbody>
            {fy.periods.map((p) => (
              <tr key={p.id} className="border-t border-slate-100">
                <td className="py-1.5 pr-3 text-slate-500">{p.periodNumber}</td>
                <td className="py-1.5 pr-3">{p.name} {p.periodType === 'ADJUSTMENT' && <Badge>ADJUSTMENT</Badge>}</td>
                <td className="py-1.5 pr-3 text-xs">{p.startDate} – {p.endDate}</td>
                <td className="py-1.5 pr-3"><StatusBadge status={p.status} /></td>
                <td className="space-x-2 py-1.5 text-right">
                  {p.status === 'FUTURE' && hasPermission('PERIOD_OPEN') && fy.status === 'OPEN' &&
                    <button type="button" className="text-brand-700 hover:underline" onClick={() => run(() => periodApi.open(p))}>Open</button>}
                  {p.status === 'OPEN' && hasPermission('PERIOD_CLOSE') &&
                    <button type="button" className="text-brand-700 hover:underline" onClick={() => run(() => periodApi.softClose(p))}>Soft-close</button>}
                  {(p.status === 'OPEN' || p.status === 'SOFT_CLOSED') && (
                    <button type="button" className="text-brand-700 hover:underline" onClick={() => openChecklist(p)}>
                      {hasPermission('PERIOD_CLOSE') ? 'Close…' : 'Checklist'}</button>
                  )}
                  {p.status === 'CLOSED' && hasPermission('PERIOD_CLOSE') && fy.status === 'OPEN' &&
                    <button type="button" className="text-brand-700 hover:underline"
                      onClick={() => { setReopenFor(p); setReason(''); reopenDialog.current?.showModal(); }}>Request reopen</button>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <dialog ref={closeDialog} aria-labelledby={`close-${fy.id}`} className="m-auto w-full max-w-2xl rounded-lg p-0 backdrop:bg-black/40">
        {checklist && (
          <div className="space-y-4 p-6">
            <h2 id={`close-${fy.id}`} className="text-lg font-semibold">Close {checklist.period.name}</h2>
            <ul className="space-y-2 text-sm">
              {checklist.checks.map((c) => (
                <li key={c.code} className="flex items-start gap-2">
                  <Badge tone={c.outcome === 'PASS' ? 'green' : c.outcome === 'FAIL' ? 'red' : 'slate'}>{c.outcome.replace('_', ' ')}</Badge>
                  <span className="flex-1">{c.description}{c.detail && <span className="block text-xs text-slate-500">{c.detail}</span>}</span>
                  {c.outcome === 'FAIL' && c.waivable && hasPermission('PERIOD_CLOSE') && (
                    <label className="text-xs"><input type="checkbox" checked={waived.includes(c.code)}
                      onChange={(e) => setWaived(e.target.checked ? [...waived, c.code] : waived.filter((w) => w !== c.code))} /> waive</label>
                  )}
                </li>
              ))}
            </ul>
            {failingWaivable.length > 0 && hasPermission('PERIOD_CLOSE') && (
              <label className="block text-sm">Justification for waivers (recorded in the audit trail)
                <textarea value={justification} onChange={(e) => setJustification(e.target.value)} rows={2}
                  className="mt-1.5 block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs" /></label>
            )}
            {blocking.length > 0 && <p className="text-sm text-red-700">Checks that cannot be waived are failing; resolve them first.</p>}
            <div className="flex justify-end gap-2">
              <Button variant="secondary" onClick={() => closeDialog.current?.close()}>Close dialog</Button>
              {hasPermission('PERIOD_CLOSE') && (
                <Button disabled={blocking.length > 0 || failingWaivable.some((c) => !waived.includes(c.code))
                  || (waived.length > 0 && justification.trim().length < 10)}
                  onClick={() => run(async () => {
                    await periodApi.close(checklist.period, waived, justification);
                    closeDialog.current?.close();
                  })}>Close period</Button>
              )}
            </div>
          </div>
        )}
      </dialog>

      <dialog ref={reopenDialog} aria-labelledby={`reopen-${fy.id}`} className="m-auto w-full max-w-lg rounded-lg p-0 backdrop:bg-black/40">
        {reopenFor && (
          <div className="space-y-4 p-6">
            <h2 id={`reopen-${fy.id}`} className="text-lg font-semibold">Request reopening of {reopenFor.name}</h2>
            <p className="text-sm text-slate-700">A different user with reopen authority must approve the request.</p>
            <label className="block text-sm">Reason (at least 10 characters)
              <textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={3}
                className="mt-1.5 block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs" /></label>
            <div className="flex justify-end gap-2">
              <Button variant="secondary" onClick={() => reopenDialog.current?.close()}>Cancel</Button>
              <Button disabled={reason.trim().length < 10} onClick={() => run(async () => {
                await periodApi.requestReopen(reopenFor.id, reason);
                reopenDialog.current?.close();
              })}>Submit request</Button>
            </div>
          </div>
        )}
      </dialog>
    </section>
  );
}
