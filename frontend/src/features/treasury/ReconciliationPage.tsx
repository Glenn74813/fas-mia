import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useParams } from 'react-router';
import { bankApi } from '../../api/endpoints';
import type { ReconciliationView } from '../../api/types';
import { ActionDialog, COMMON_ACTIONS } from '../../components/ActionDialog';
import { Button } from '../../components/Button';
import { DecisionHistory } from '../../components/DecisionHistory';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Spinner } from '../../components/Spinner';
import { StatusBadge } from '../../components/StatusBadge';
import { fromCents, sumCents } from '../../utils/money';

type Act = 'submit' | 'approve' | 'return';

export function ReconciliationPage() {
  const { id = '' } = useParams();
  const qc = useQueryClient();
  const query = useQuery({ queryKey: ['reconciliation', id], queryFn: () => bankApi.reconciliation(id) });
  const [selS, setSelS] = useState<Set<string>>(new Set());
  const [selL, setSelL] = useState<Set<string>>(new Set());
  const [dialog, setDialog] = useState<Act | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const done = (r: ReconciliationView) => { qc.setQueryData(['reconciliation', id], r); qc.invalidateQueries({ queryKey: ['statements'] }); };
  const v = () => query.data!.version;
  const auto = useMutation({ mutationFn: () => bankApi.autoMatch(id, v()),
    onSuccess: (x) => { done(x.reconciliation); setNote(`${x.matched} item(s) matched automatically.`); } });
  const match = useMutation({ mutationFn: () => bankApi.match(id, v(), [...selS], [...selL]),
    onSuccess: (r) => { done(r); setSelS(new Set()); setSelL(new Set()); setNote(null); } });
  const unmatch = useMutation({ mutationFn: (m: string) => bankApi.unmatch(id, v(), m), onSuccess: done });
  const act = useMutation({ mutationFn: ({ kind, comment }: { kind: Act; comment: string }) => bankApi.act(id, kind, v(), comment),
    onSuccess: (r) => { done(r); setDialog(null); }, onError: () => qc.invalidateQueries({ queryKey: ['reconciliation', id] }) });
  if (query.isPending) return <Spinner />;
  if (query.error) return <ErrorAlert error={query.error} />;
  const r = query.data;
  const can = (a: string) => r.availableActions.includes(a);
  const f = r.figures;
  const toggle = (set: Set<string>, setter: (s: Set<string>) => void, key: string) => {
    const n = new Set(set); if (n.has(key)) n.delete(key); else n.add(key); setter(n);
  };
  const sTotal = sumCents(r.unmatchedStatementLines.filter((l) => selS.has(l.id)).map((l) => l.amount));
  const lTotal = sumCents(r.unmatchedLedgerLines.filter((l) => selL.has(l.journalLineId)).map((l) => l.amount));
  const canMatch = can('MATCH');
  return (
    <>
      <PageHeader title={`Bank reconciliation ${r.bankAccountCode} · ${r.asOfDate}`}
        crumbs={[{ label: 'Treasury' }, { label: 'Bank statements', to: '/treasury/statements' }, { label: r.asOfDate }]}
        actions={<div className="flex flex-wrap gap-2">
          {can('AUTO_MATCH') && <Button variant="secondary" busy={auto.isPending} onClick={() => auto.mutate()}>Auto-match</Button>}
          {can('SUBMIT') && <Button onClick={() => { act.reset(); setDialog('submit'); }}>Submit</Button>}
          {can('APPROVE') && <Button onClick={() => { act.reset(); setDialog('approve'); }}>Approve</Button>}
          {can('RETURN') && <Button variant="secondary" onClick={() => { act.reset(); setDialog('return'); }}>Return</Button>}
        </div>} />
      <div className="space-y-4">
        <ErrorAlert error={auto.error ?? match.error ?? unmatch.error} />
        {note && <p role="status" className="text-sm text-green-800">{note}</p>}
        {r.warnings.map((w) => <p key={w} role="note" className="rounded-md border border-amber-300 bg-amber-50 p-3 text-sm">{w}</p>)}
        <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
          <div className="mb-3 flex items-center gap-2"><StatusBadge status={r.status} />
            <span className="text-xs text-slate-500">Statement {r.periodStart} – {r.asOfDate} · prepared by {r.preparedBy}
              {r.approvedBy ? ` · approved by ${r.approvedBy}` : ''}</span></div>
          <div className="grid gap-6 text-sm md:grid-cols-2">
            <dl className="grid grid-cols-2 gap-1">
              <dt>Balance per bank</dt><dd className="text-right"><Money value={f.bankBalance} /></dd>
              <dt>Add: deposits in transit</dt><dd className="text-right"><Money value={f.depositsInTransit} /></dd>
              <dt>Less: outstanding checks</dt><dd className="text-right">(<Money value={f.outstandingChecks} />)</dd>
              <dt className="font-semibold">Adjusted bank balance</dt><dd className="text-right font-semibold"><Money value={f.adjustedBank} /></dd>
            </dl>
            <dl className="grid grid-cols-2 gap-1">
              <dt>Balance per books</dt><dd className="text-right"><Money value={f.bookBalance} /></dd>
              <dt>Add: unrecorded bank credits</dt><dd className="text-right"><Money value={f.unrecordedCredits} /></dd>
              <dt>Less: unrecorded bank debits</dt><dd className="text-right">(<Money value={f.unrecordedDebits} />)</dd>
              <dt className="font-semibold">Adjusted book balance</dt><dd className="text-right font-semibold"><Money value={f.adjustedBook} /></dd>
            </dl>
          </div>
          <p className={`mt-3 text-sm font-semibold ${f.difference === '0.00' ? 'text-green-800' : 'text-red-700'}`}>Difference: <Money value={f.difference} /></p>
          <p className="mt-1 text-xs text-slate-500">Book unrecorded bank items (charges, interest) with a journal voucher, then match them here.</p>
        </section>
        <div className="grid gap-4 lg:grid-cols-2">
          <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-4">
            <h2 className="mb-2 font-semibold">Unmatched bank lines</h2>
            <table className="min-w-full text-sm"><tbody>
              {r.unmatchedStatementLines.map((l) => (
                <tr key={l.id} className="border-t border-slate-100">
                  {canMatch && <td className="py-1 pr-2"><input type="checkbox" aria-label={`Bank line ${l.valueDate} ${l.amount}`} checked={selS.has(l.id)}
                    onChange={() => toggle(selS, setSelS, l.id)} /></td>}
                  <td className="whitespace-nowrap py-1 pr-2">{l.valueDate}</td><td className="py-1 pr-2">{l.description}{l.bankReference ? ` · ${l.bankReference}` : ''}</td>
                  <td className="py-1 text-right"><Money value={l.amount} /></td></tr>))}
              {r.unmatchedStatementLines.length === 0 && <tr><td className="py-3 text-center text-slate-500">None.</td></tr>}
            </tbody></table>
          </section>
          <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-4">
            <h2 className="mb-2 font-semibold">Unmatched book entries</h2>
            <table className="min-w-full text-sm"><tbody>
              {r.unmatchedLedgerLines.map((l) => (
                <tr key={l.journalLineId} className="border-t border-slate-100">
                  {canMatch && <td className="py-1 pr-2"><input type="checkbox" aria-label={`Book line ${l.entryNumber} ${l.amount}`} checked={selL.has(l.journalLineId)}
                    onChange={() => toggle(selL, setSelL, l.journalLineId)} /></td>}
                  <td className="whitespace-nowrap py-1 pr-2">{l.accountingDate}</td>
                  <td className="py-1 pr-2">{l.entryNumber} {l.description}{l.reference ? ` · ${l.reference}` : ''}</td>
                  <td className="py-1 text-right"><Money value={l.amount} /></td></tr>))}
              {r.unmatchedLedgerLines.length === 0 && <tr><td className="py-3 text-center text-slate-500">None.</td></tr>}
            </tbody></table>
          </section>
        </div>
        {canMatch && (
          <div className="flex items-center gap-3 text-sm">
            <span>Selected: bank {fromCents(sTotal)} · books {fromCents(lTotal)}</span>
            <Button busy={match.isPending} disabled={selS.size === 0 || selL.size === 0 || sTotal !== lTotal} onClick={() => match.mutate()}>Match selected</Button>
          </div>
        )}
        <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-4">
          <h2 className="mb-2 font-semibold">Matches</h2>
          <table className="min-w-full text-sm">
            <thead className="border-b border-slate-200 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="py-1 pr-2">Bank</th><th className="py-1 pr-2">Books</th>
              <th className="py-1 pr-2">How</th><th className="py-1 text-right">Amount</th><th /></tr></thead>
            <tbody>{r.matches.map((m) => (
              <tr key={m.id} className="border-t border-slate-100 align-top">
                <td className="py-1 pr-2">{m.statementLines.map((l) => <div key={l.id}>{l.valueDate} {l.description}</div>)}</td>
                <td className="py-1 pr-2">{m.ledgerLines.map((l) => <div key={l.journalLineId}>
                  {l.entryNumber} {l.reference ?? ''}</div>)}</td>
                <td className="py-1 pr-2">{m.method.toLowerCase()} · {m.rule.toLowerCase().replace('_', ' ')}</td>
                <td className="py-1 text-right"><Money value={fromCents(sumCents(m.statementLines.map((l) => l.amount)))} /></td>
                <td className="py-1 pl-2 text-right">{can('UNMATCH') && !m.locked && (
                  <button className="text-xs text-red-700" onClick={() => unmatch.mutate(m.id)}>Undo</button>)}</td></tr>))}
              {r.matches.length === 0 && <tr><td colSpan={5} className="py-3 text-center text-slate-500">No matches yet.</td></tr>}</tbody>
          </table>
        </section>
        <DecisionHistory decisions={r.decisions} />
      </div>
      <ActionDialog spec={dialog ? { ...COMMON_ACTIONS[dialog]!, ...(dialog === 'submit'
        ? { text: 'The figures are frozen and sent to the approver. Matches made in this reconciliation can no longer be undone.' } : {}) } : null}
        busy={act.isPending} error={act.error} onClose={() => setDialog(null)} onConfirm={(comment) => act.mutate({ kind: dialog!, comment })} />
    </>
  );
}
