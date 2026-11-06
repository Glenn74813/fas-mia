import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { assetApi } from '../../api/endpoints';
import type { AssetCategory, AssetCategoryPolicy } from '../../api/types';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Field } from '../../components/Field';
import { Select } from '../../components/Select';

const START = { NEXT_MONTH: 'Month after it is placed in service', SAME_MONTH: 'Month it is placed in service' };
const MODEL = { COST: 'Cost', REVALUATION: 'Revaluation (PAS 16)' };
const select = 'mt-1.5 block w-full rounded-lg border border-slate-300 px-3 py-2 text-sm shadow-xs';

function policyOf(c: AssetCategory): AssetCategoryPolicy {
  return { defaultUsefulLifeMonths: c.defaultUsefulLifeMonths, defaultResidualRate: c.defaultResidualRate, depreciationStart: c.depreciationStart,
    chargeDisposalMonth: c.chargeDisposalMonth, measurementModel: c.measurementModel, revaluationIntervalYears: c.revaluationIntervalYears };
}

/**
 * The depreciation and measurement rules of each asset class (C-30, C-07), set by the client: default life and residual
 * rate for new assets, when depreciation starts, whether the disposal month is charged, and cost or revaluation model.
 */
export function AssetPolicySection({ categories, manage }: { categories: AssetCategory[]; manage: boolean }) {
  const qc = useQueryClient();
  const [editing, setEditing] = useState<{ c: AssetCategory; p: AssetCategoryPolicy; life: string; residual: string; interval: string } | null>(null);
  const save = useMutation({
    mutationFn: () => assetApi.setCategoryPolicy(editing!.c, { ...editing!.p, defaultUsefulLifeMonths: Number(editing!.life),
      defaultResidualRate: editing!.residual.trim() || null,
      revaluationIntervalYears: editing!.p.measurementModel === 'REVALUATION' ? Number(editing!.interval) || null : null }),
    onSuccess: () => { setEditing(null); qc.invalidateQueries({ queryKey: ['asset-categories'] }); },
  });
  const open = (c: AssetCategory) => {
    save.reset();
    setEditing({ c, p: policyOf(c), life: String(c.defaultUsefulLifeMonths), residual: c.defaultResidualRate ?? '',
      interval: c.revaluationIntervalYears ? String(c.revaluationIntervalYears) : '' });
  };
  return (
    <section className="mb-4 rounded-xl border border-slate-200/80 bg-white p-5 shadow-xs">
      <h2 className="mb-1 font-semibold">Depreciation and measurement policy</h2>
      <p className="mb-3 max-w-3xl text-xs text-slate-500">The rules each asset class follows. Life and residual rate are defaults for assets registered
        afterwards; each asset keeps its own. When depreciation starts cannot change once the class has posted depreciation, and a class with
        posted revaluations cannot go back to cost.</p>
      <div className="overflow-x-auto">
        <table className="min-w-full text-sm">
          <thead className="border-b border-slate-200 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500">
            <tr><th className="py-1 pr-3">Class</th><th className="py-1 pr-3">Life (months)</th><th className="py-1 pr-3">Residual rate</th>
              <th className="py-1 pr-3">Depreciation starts</th><th className="py-1 pr-3">Disposal month</th><th className="py-1 pr-3">Measurement</th><th /></tr>
          </thead>
          <tbody>{categories.map((c) => (
            <tr key={c.id} className="border-t border-slate-100">
              <td className="py-1.5 pr-3"><span className="font-mono">{c.code}</span> <span className="text-slate-600">{c.name}</span></td>
              <td className="py-1.5 pr-3">{c.defaultUsefulLifeMonths}</td>
              <td className="py-1.5 pr-3">{c.defaultResidualRate ?? '—'}</td>
              <td className="py-1.5 pr-3">{START[c.depreciationStart]}</td>
              <td className="py-1.5 pr-3">{c.chargeDisposalMonth ? 'Charged' : 'Not charged'}</td>
              <td className="py-1.5 pr-3">{MODEL[c.measurementModel]}{c.revaluationIntervalYears ? `, every ${c.revaluationIntervalYears} year${c.revaluationIntervalYears === 1 ? '' : 's'}` : ''}</td>
              <td className="py-1.5 text-right">{manage && editing?.c.id !== c.id &&
                <button type="button" className="text-xs text-brand-700 hover:underline" onClick={() => open(c)}>Edit</button>}</td>
            </tr>))}
          </tbody>
        </table>
      </div>
      {editing && (
        <div className="mt-4 space-y-3 rounded-md border border-slate-200 p-4">
          <h3 className="text-sm font-semibold">Policy of {editing.c.code} {editing.c.name}</h3>
          <ErrorAlert error={save.error} />
          <div className="grid gap-3 md:grid-cols-3 md:items-end">
            <Field label="Default life (months)" inputMode="numeric" value={editing.life}
              onChange={(e) => setEditing({ ...editing, life: e.target.value.replace(/\D/g, '') })} />
            <Field label="Default residual rate" hint="e.g. 0.0500; blank for none" value={editing.residual}
              onChange={(e) => setEditing({ ...editing, residual: e.target.value })} />
            <label className="block text-sm">Depreciation starts
              <Select value={editing.p.depreciationStart} className={select}
                onChange={(e) => setEditing({ ...editing, p: { ...editing.p, depreciationStart: e.target.value as AssetCategoryPolicy['depreciationStart'] } })}>
                {Object.entries(START).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</Select></label>
            <label className="block text-sm">Disposal month
              <Select value={editing.p.chargeDisposalMonth ? 'YES' : 'NO'} className={select}
                onChange={(e) => setEditing({ ...editing, p: { ...editing.p, chargeDisposalMonth: e.target.value === 'YES' } })}>
                <option value="NO">Not charged</option><option value="YES">Charged</option></Select></label>
            <label className="block text-sm">Measurement after recognition
              <Select value={editing.p.measurementModel} className={select}
                onChange={(e) => setEditing({ ...editing, p: { ...editing.p, measurementModel: e.target.value as AssetCategoryPolicy['measurementModel'] } })}>
                {Object.entries(MODEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</Select></label>
            {editing.p.measurementModel === 'REVALUATION' && (
              <Field label="Revalue every (years)" inputMode="numeric" value={editing.interval}
                onChange={(e) => setEditing({ ...editing, interval: e.target.value.replace(/\D/g, '') })} />)}
          </div>
          <div className="flex gap-2">
            <Button busy={save.isPending} disabled={!editing.life || (editing.p.measurementModel === 'REVALUATION' && !editing.interval)}
              onClick={() => save.mutate()}>Save policy</Button>
            <Button variant="secondary" onClick={() => setEditing(null)}>Cancel</Button>
          </div>
        </div>
      )}
    </section>
  );
}
