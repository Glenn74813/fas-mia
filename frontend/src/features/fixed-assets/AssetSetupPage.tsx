import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { assetApi } from '../../api/endpoints';
import { useAuth } from '../../auth/AuthContext';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Field } from '../../components/Field';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { StatusBadge } from '../../components/StatusBadge';
import { postableAccounts, useChart, useCostCenters } from '../../hooks/useAccounting';

export function AssetSetupPage() {
  const { hasPermission } = useAuth();
  const manage = hasPermission('FA_MANAGE');
  const qc = useQueryClient();
  const chart = useChart();
  const costCenters = useCostCenters();
  const categories = useQuery({ queryKey: ['asset-categories'], queryFn: assetApi.categories });
  const projects = useQuery({ queryKey: ['auc-projects'], queryFn: assetApi.projects });
  const accounts = postableAccounts(chart.data?.accounts);
  const [cat, setCat] = useState({ code: '', name: '', assetAccountId: '', accumulatedAccountId: '', expenseAccountId: '', life: '60', residual: '' });
  const [proj, setProj] = useState({ code: '', name: '', aucAccountId: '', costCenterId: '' });
  const createCat = useMutation({ mutationFn: () => assetApi.createCategory({ code: cat.code, name: cat.name, assetAccountId: cat.assetAccountId,
    accumulatedAccountId: cat.accumulatedAccountId, expenseAccountId: cat.expenseAccountId, defaultUsefulLifeMonths: Number(cat.life),
    defaultResidualRate: cat.residual || null }), onSuccess: () => qc.invalidateQueries({ queryKey: ['asset-categories'] }) });
  const createProj = useMutation({ mutationFn: () => assetApi.createProject({ ...proj, costCenterId: proj.costCenterId || null }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['auc-projects'] }) });
  const close = useMutation({ mutationFn: assetApi.closeProject, onSuccess: () => qc.invalidateQueries({ queryKey: ['auc-projects'] }) });
  const pick = (id: string, value: string, onChange: (v: string) => void, list: typeof accounts, label: string) => (
    <label className="block text-sm">{label}
      <select id={id} value={value} onChange={(e) => onChange(e.target.value)} className="mt-1.5 block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs">
        <option value="">Select…</option>{list.map((a) => <option key={a.id} value={a.id}>{a.code} — {a.name}</option>)}</select></label>
  );
  return (
    <>
      <PageHeader title="Asset setup" crumbs={[{ label: 'Fixed assets' }, { label: 'Setup' }]} />
      <section className="mb-4 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
        <h2 className="mb-3 font-semibold">Asset categories</h2>
        <table className="mb-4 min-w-full text-sm"><thead className="border-b border-slate-200 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="py-1 pr-3">Code</th><th className="py-1 pr-3">Name</th>
          <th className="py-1 pr-3">Asset / accum. / expense</th><th className="py-1 pr-3">Life (months)</th><th className="py-1 pr-3">Residual rate</th><th /></tr></thead>
          <tbody>{(categories.data ?? []).map((c) => (
            <tr key={c.id} className="border-t border-slate-100"><td className="py-1.5 pr-3 font-mono">{c.code}</td><td className="py-1.5 pr-3">{c.name}</td>
              <td className="py-1.5 pr-3 font-mono">{c.assetAccountCode} / {c.accumulatedAccountCode} / {c.expenseAccountCode}</td>
              <td className="py-1.5 pr-3">{c.defaultUsefulLifeMonths}</td><td className="py-1.5 pr-3">{c.defaultResidualRate ?? '—'}</td>
              <td className="py-1.5">{c.placeholder && <Badge tone="amber">PLACEHOLDER</Badge>}</td></tr>))}</tbody></table>
        {manage && (
          <div className="space-y-3 rounded-md border border-slate-200 p-4">
            <ErrorAlert error={createCat.error} />
            <div className="grid gap-3 md:grid-cols-4">
              <Field label="Category code" value={cat.code} onChange={(e) => setCat({ ...cat, code: e.target.value })} />
              <Field label="Category name" value={cat.name} onChange={(e) => setCat({ ...cat, name: e.target.value })} />
              <Field label="Default life (months)" inputMode="numeric" value={cat.life} onChange={(e) => setCat({ ...cat, life: e.target.value.replace(/\D/g, '') })} />
              <Field label="Default residual rate" hint="e.g. 0.0500; blank for none" value={cat.residual} onChange={(e) => setCat({ ...cat, residual: e.target.value })} />
              {pick('ca', cat.assetAccountId, (v) => setCat({ ...cat, assetAccountId: v }), accounts.filter((a) => a.accountType === 'ASSET' && a.normalBalance === 'DEBIT'), 'Asset account')}
              {pick('cd', cat.accumulatedAccountId, (v) => setCat({ ...cat, accumulatedAccountId: v }), accounts.filter((a) => a.accountType === 'ASSET' && a.normalBalance === 'CREDIT'), 'Accumulated depreciation')}
              {pick('ce', cat.expenseAccountId, (v) => setCat({ ...cat, expenseAccountId: v }), accounts.filter((a) => a.accountType === 'EXPENSE'), 'Depreciation expense')}
            </div>
            <Button variant="secondary" busy={createCat.isPending} disabled={!cat.code || !cat.name || !cat.assetAccountId || !cat.accumulatedAccountId
              || !cat.expenseAccountId || !cat.life} onClick={() => createCat.mutate()}>Add category</Button>
          </div>
        )}
      </section>
      <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
        <h2 className="mb-1 font-semibold">Assets under construction</h2>
        <p className="mb-3 text-xs text-slate-500">Post project costs (invoices or journal vouchers) to the AUC account with the project's subsidiary account; register the finished asset from the project to capitalise it.</p>
        <ErrorAlert error={close.error} />
        <table className="mb-4 min-w-full text-sm"><thead className="border-b border-slate-200 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="py-1 pr-3">Project</th>
          <th className="py-1 pr-3">Account / subsidiary</th><th className="py-1 pr-3">Status</th><th className="py-1 text-right">Costs</th><th className="py-1 text-right">Capitalised</th>
          <th className="py-1 text-right">Balance</th><th /></tr></thead>
          <tbody>{(projects.data ?? []).map((p) => (
            <tr key={p.id} className="border-t border-slate-100"><td className="py-1.5 pr-3">{p.code} — {p.name}</td><td className="py-1.5 pr-3 font-mono">{p.aucAccountCode} / {p.subsidiaryCode}</td>
              <td className="py-1.5 pr-3"><StatusBadge status={p.status} /></td><td className="py-1.5 text-right"><Money value={p.costIncurred} /></td>
              <td className="py-1.5 text-right"><Money value={p.capitalized} /></td><td className="py-1.5 text-right"><Money value={p.balance} /></td>
              <td className="py-1.5 pl-3 text-right">{manage && p.status === 'OPEN' && p.balance === '0.00' &&
                <button className="text-xs text-brand-700" onClick={() => close.mutate(p)}>Close</button>}</td></tr>))}
            {projects.data?.length === 0 && <tr><td colSpan={7} className="py-3 text-center text-slate-500">No projects.</td></tr>}</tbody></table>
        {manage && (
          <div className="space-y-3 rounded-md border border-slate-200 p-4">
            <ErrorAlert error={createProj.error} />
            <div className="grid gap-3 md:grid-cols-4">
              <Field label="Project code" value={proj.code} onChange={(e) => setProj({ ...proj, code: e.target.value })} />
              <Field label="Project name" value={proj.name} onChange={(e) => setProj({ ...proj, name: e.target.value })} />
              {pick('pa', proj.aucAccountId, (v) => setProj({ ...proj, aucAccountId: v }), accounts.filter((a) => a.subsidiaryType === 'OTHER' && a.accountType === 'ASSET'), 'AUC account')}
              <label className="block text-sm">Cost centre
                <select value={proj.costCenterId} onChange={(e) => setProj({ ...proj, costCenterId: e.target.value })} className="mt-1.5 block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs">
                  <option value="">—</option>{(costCenters.data ?? []).map((c) => <option key={c.id} value={c.id}>{c.code}</option>)}</select></label>
            </div>
            <Button variant="secondary" busy={createProj.isPending} disabled={!proj.code || !proj.name || !proj.aucAccountId} onClick={() => createProj.mutate()}>Open project</Button>
          </div>
        )}
      </section>
    </>
  );
}
