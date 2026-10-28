import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Select } from '../../components/Select';
import { Combobox } from '../../components/Combobox';
import { useMemo, useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { accountApi, allocationApi, periodApi } from '../../api/endpoints';
import type { AllocationCycle } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Spinner } from '../../components/Spinner';
import { formatDateTime } from '../../utils/format';
import { activeCostCenters, accountsFor } from '../../hooks/useAccounting';

interface RuleForm { description: string; senderAccountId: string; senderCostCenterId: string; receiverAccountId: string;
  keyType: 'PERCENT' | 'QUANTITY'; receivers: { costCenterId: string; weight: string }[] }
const blankRule = (): RuleForm => ({ description: '', senderAccountId: '', senderCostCenterId: '', receiverAccountId: '', keyType: 'PERCENT',
  receivers: [{ costCenterId: '', weight: '' }] });

export function AllocationCyclesPage() {
  const { hasPermission } = useAuth();
  const canManage = hasPermission('ALLOCATION_MANAGE');
  const canRun = canManage && hasPermission('JOURNAL_CREATE');
  const qc = useQueryClient();
  const cycles = useQuery({ queryKey: ['allocations'], queryFn: allocationApi.list });
  const chart = useQuery({ queryKey: ['accounts'], queryFn: accountApi.chart, enabled: canManage });
  const costCenters = useQuery({ queryKey: ['cost-centers'], queryFn: accountApi.costCenters });
  const years = useQuery({ queryKey: ['fiscal-years'], queryFn: periodApi.fiscalYears });
  const periods = useMemo(() => (years.data ?? []).flatMap((y) => y.periods).filter((p) => p.status !== 'FUTURE')
    .sort((a, b) => b.endDate.localeCompare(a.endDate)), [years.data]);
  const accounts = accountsFor(chart.data?.accounts, 'ALLOCATION');
  const refresh = () => qc.invalidateQueries({ queryKey: ['allocations'] });

  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [rules, setRules] = useState<RuleForm[]>([blankRule()]);
  const create = useMutation({
    mutationFn: () => allocationApi.create({ code, name, rules: rules.map((r) => ({ ...r, receiverAccountId: r.receiverAccountId || null,
      receivers: r.receivers.filter((x) => x.costCenterId).map((x) => ({ costCenterId: x.costCenterId, weight: x.weight })) })) }),
    onSuccess: () => { setCode(''); setName(''); setRules([blankRule()]); refresh(); },
  });
  const status = useMutation({ mutationFn: (a: { c: AllocationCycle; s: 'ACTIVE' | 'INACTIVE' }) => allocationApi.setStatus(a.c, a.s), onSuccess: refresh });

  const [selected, setSelected] = useState<string | null>(null);
  const [periodId, setPeriodId] = useState('');
  const preview = useQuery({ queryKey: ['allocations', 'preview', selected, periodId], queryFn: () => allocationApi.preview(selected!, periodId),
    enabled: !!selected && !!periodId });
  const runs = useQuery({ queryKey: ['allocations', 'runs', selected], queryFn: () => allocationApi.runs(selected!), enabled: !!selected });
  const run = useMutation({ mutationFn: () => allocationApi.run(selected!, periodId), onSuccess: refresh });

  const setRule = (i: number, patch: Partial<RuleForm>) => setRules(rules.map((r, j) => (j === i ? { ...r, ...patch } : r)));
  const submit = (e: FormEvent) => { e.preventDefault(); create.mutate(); };
  const select = 'rounded-lg border border-slate-300 px-2 py-1.5 text-sm shadow-xs';

  return (
    <>
      <PageHeader title="Overhead allocation" crumbs={[{ label: 'General ledger' }, { label: 'Overhead allocation' }]} />
      <p className="mb-4 max-w-3xl text-sm text-slate-600">A cycle moves the period's balance of an account in a sending cost centre to
        receiving cost centres, by fixed percentages or by quantities such as floor area or headcount. Running it creates a draft
        journal voucher dated the period's last day, reviewed and posted like any other. The cycles and keys are MIAA's to set (C-10).</p>
      <ErrorAlert error={cycles.error ?? status.error} />
      {cycles.isPending ? <Spinner /> : cycles.data && (
        <div className="mb-6 space-y-3">
          {cycles.data.map((c) => (
            <section key={c.id} className={`rounded-xl border bg-white p-4 shadow-xs ${selected === c.id ? 'border-brand-300' : 'border-slate-200/80'}`}>
              <div className="flex flex-wrap items-center gap-2">
                <h2 className="mr-auto text-sm font-semibold text-slate-900">{c.code} {c.name} <Badge tone={c.status === 'ACTIVE' ? 'green' : 'slate'}>{c.status}</Badge></h2>
                <Button variant="secondary" onClick={() => setSelected(selected === c.id ? null : c.id)}>{selected === c.id ? 'Close' : 'Run or preview'}</Button>
                {canManage && <Button variant="secondary" onClick={() => status.mutate({ c, s: c.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE' })}>
                  {c.status === 'ACTIVE' ? 'Deactivate' : 'Activate'}</Button>}
              </div>
              <ul className="mt-2 space-y-1 text-xs text-slate-600">
                {c.rules.map((r) => (
                  <li key={r.id}>{r.seq}. {r.senderAccountCode} / {r.senderCostCenterCode} → {r.receiverAccountCode ?? r.senderAccountCode} by {r.keyType === 'PERCENT'
                    ? 'percentage' : 'quantity'}: {r.receivers.map((x) => `${x.costCenterCode} ${x.weight}${r.keyType === 'PERCENT' ? '%' : ''}`).join(', ')}
                    {r.description && ` (${r.description})`}</li>
                ))}
              </ul>
              {selected === c.id && (
                <div className="mt-3 border-t border-slate-200 pt-3">
                  <label className="text-sm">Period <Select aria-label="Period" value={periodId} onChange={(e) => setPeriodId(e.target.value)} className={select}>
                    <option value="">Choose…</option>{periods.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}</Select></label>
                  <ErrorAlert error={preview.error ?? run.error} />
                  {preview.data && (
                    <div className="mt-2 overflow-x-auto">
                      <table className="min-w-full text-sm">
                        <thead className="text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="px-2 py-1 text-left">Account</th>
                          <th className="px-2 py-1 text-left">Cost centre</th><th className="px-2 py-1 text-right">Debit</th><th className="px-2 py-1 text-right">Credit</th></tr></thead>
                        <tbody>{preview.data.lines.map((l, i) => (
                          <tr key={i} className="border-t border-slate-100"><td className="px-2 py-1">{l.accountCode}</td><td className="px-2 py-1">{l.costCenterCode}</td>
                            <td className="px-2 py-1 text-right"><Money value={l.debit} blankZero /></td><td className="px-2 py-1 text-right"><Money value={l.credit} blankZero /></td></tr>))}
                        </tbody>
                      </table>
                      {preview.data.notes.map((n) => <p key={n} className="mt-1 text-xs text-slate-500">{n}</p>)}
                      {canRun && c.status === 'ACTIVE' && preview.data.lines.length > 0 &&
                        <Button className="mt-2" busy={run.isPending} onClick={() => run.mutate()}>Create draft voucher</Button>}
                    </div>
                  )}
                  {runs.data && runs.data.length > 0 && (
                    <p className="mt-3 text-xs text-slate-600">Runs: {runs.data.map((x) => (
                      <span key={x.id} className="mr-3"><Link to={`/journal-vouchers/${x.voucherId}`} className="text-brand-700 hover:underline">
                        {x.voucherNumber ?? 'draft'}</Link> {x.periodName} ({x.voucherStatus.toLowerCase()}, {x.runBy}, {formatDateTime(x.runAt)})</span>))}</p>
                  )}
                </div>
              )}
            </section>
          ))}
          {cycles.data.length === 0 && <p className="text-sm text-slate-500">No allocation cycles yet.</p>}
        </div>
      )}

      {canManage && (
        <form onSubmit={submit} className="max-w-4xl space-y-3 rounded-xl border border-slate-200/80 bg-white p-4 shadow-xs">
          <h2 className="text-base font-semibold text-slate-900">New cycle</h2>
          <div className="grid gap-3 sm:grid-cols-2">
            <label className="text-sm">Code<input value={code} maxLength={30} onChange={(e) => setCode(e.target.value.toUpperCase())} className={`mt-1.5 block w-full ${select}`} /></label>
            <label className="text-sm">Name<input value={name} maxLength={200} onChange={(e) => setName(e.target.value)} className={`mt-1.5 block w-full ${select}`} /></label>
          </div>
          {rules.map((r, i) => (
            <fieldset key={i} className="space-y-2 rounded-lg border border-slate-200 p-3">
              <legend className="px-1 text-sm font-medium">Rule {i + 1}</legend>
              <div className="grid gap-2 sm:grid-cols-3">
                <label className="text-sm">Sender account<Combobox value={r.senderAccountId} onChange={(v) => setRule(i, { senderAccountId: v })} className={`mt-1.5 block w-full ${select}`} placeholder="Choose…"
              options={accounts.map((a) => ({ value: a.id, label: `${a.code} — ${a.name}` }))} /></label>
                <label className="text-sm">Sender cost centre<Combobox value={r.senderCostCenterId} onChange={(v) => setRule(i, { senderCostCenterId: v })} className={`mt-1.5 block w-full ${select}`} placeholder="Choose…"
              options={activeCostCenters(costCenters.data).map((c) => ({ value: c.id, label: `${c.code} — ${c.name}` }))} /></label>
                <label className="text-sm">Key<Select value={r.keyType} onChange={(e) => setRule(i, { keyType: e.target.value as RuleForm['keyType'] })} className={`mt-1.5 block w-full ${select}`}>
                  <option value="PERCENT">Fixed percentages</option><option value="QUANTITY">Statistical quantities</option></Select></label>
              </div>
              {r.receivers.map((x, k) => (
                <div key={k} className="flex flex-wrap items-end gap-2">
                  <label className="text-sm">Receiver<Combobox value={x.costCenterId} onChange={(v) => setRule(i, { receivers: r.receivers.map((y, m) => (m === k ? { ...y, costCenterId: v } : y)) })}
                    className={`mt-1.5 block ${select}`} placeholder="Choose…"
              options={activeCostCenters(costCenters.data).map((c) => ({ value: c.id, label: `${c.code} — ${c.name}` }))} /></label>
                  <label className="text-sm">{r.keyType === 'PERCENT' ? 'Percent' : 'Quantity'}<input inputMode="decimal" value={x.weight}
                    onChange={(e) => setRule(i, { receivers: r.receivers.map((y, m) => (m === k ? { ...y, weight: e.target.value } : y)) })} className={`mt-1.5 block w-28 ${select}`} /></label>
                </div>
              ))}
              <button type="button" className="text-sm text-brand-700 hover:underline" onClick={() => setRule(i, { receivers: [...r.receivers, { costCenterId: '', weight: '' }] })}>
                Add receiver</button>
            </fieldset>
          ))}
          <button type="button" className="mr-4 text-sm text-brand-700 hover:underline" onClick={() => setRules([...rules, blankRule()])}>Add rule</button>
          <ErrorAlert error={create.error} />
          <Button type="submit" busy={create.isPending} disabled={!code || !name}>Create cycle</Button>
        </form>
      )}
    </>
  );
}
