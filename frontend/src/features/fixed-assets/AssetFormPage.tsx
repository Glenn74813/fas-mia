import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Select } from '../../components/Select';
import { Combobox } from '../../components/Combobox';
import { useState } from 'react';
import { useNavigate } from 'react-router';
import { assetApi, invoiceApi } from '../../api/endpoints';
import type { AssetInput } from '../../api/types';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Field } from '../../components/Field';
import { PageHeader } from '../../components/PageHeader';
import { activeCostCenters, accountsFor, useChart, useCostCenters } from '../../hooks/useAccounting';
import { isValidAmount } from '../../utils/money';

export function AssetFormPage() {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const categories = useQuery({ queryKey: ['asset-categories'], queryFn: assetApi.categories });
  const projects = useQuery({ queryKey: ['auc-projects'], queryFn: assetApi.projects });
  const invoices = useQuery({ queryKey: ['invoices', 'posted-for-assets'], queryFn: () => invoiceApi.list({ status: 'POSTED', page: 0, size: 200 }) });
  const chart = useChart();
  const costCenters = useCostCenters();
  const today = new Date().toISOString().slice(0, 10);
  const [f, setF] = useState<AssetInput>({ categoryId: '', description: '', serialNumber: null, location: null, custodian: null,
    costCenterId: null, acquisitionSource: 'INVOICE', supplierInvoiceId: null, aucProjectId: null, offsetAccountId: null,
    justification: null, acquisitionDate: today, inServiceDate: today, cost: '', residualValue: null, usefulLifeMonths: null });
  const set = (p: Partial<AssetInput>) => setF({ ...f, ...p });
  const category = categories.data?.find((c) => c.id === f.categoryId);
  const save = useMutation({ mutationFn: () => assetApi.create(f),
    onSuccess: (a) => { qc.invalidateQueries({ queryKey: ['assets'] }); navigate(`/fixed-assets/${a.id}`); } });
  const offsets = accountsFor(chart.data?.accounts, 'ASSET_OFFSET');
  const sourceOk = f.acquisitionSource === 'INVOICE' ? !!f.supplierInvoiceId : f.acquisitionSource === 'AUC' ? !!f.aucProjectId
    : !!f.offsetAccountId && (f.justification ?? '').trim().length >= 10;
  const valid = f.categoryId && f.description.trim() && f.cost && isValidAmount(f.cost) && (!f.residualValue || isValidAmount(f.residualValue)) && sourceOk;
  const sel = 'block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm';
  return (
    <>
      <PageHeader title="Register asset" crumbs={[{ label: 'Fixed assets' }, { label: 'Register', to: '/fixed-assets' }, { label: 'New' }]} />
      <div className="space-y-4">
        <ErrorAlert error={save.error} />
        <div className="grid gap-4 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5 md:grid-cols-3">
          <div className="space-y-1.5"><label htmlFor="cat" className="block text-sm font-medium text-slate-700">Category</label>
            <Combobox id="cat" value={f.categoryId} onChange={(v) => set({ categoryId: v })} className={sel} placeholder="Select…"
              options={(categories.data ?? []).filter((c) => c.status === 'ACTIVE').map((c) => ({ value: c.id, label: `${c.code} — ${c.name}` }))} /></div>
          <div className="md:col-span-2"><Field label="Description" value={f.description} maxLength={500} onChange={(e) => set({ description: e.target.value })} /></div>
          <Field label="Serial number" value={f.serialNumber ?? ''} onChange={(e) => set({ serialNumber: e.target.value || null })} />
          <Field label="Location" value={f.location ?? ''} onChange={(e) => set({ location: e.target.value || null })} />
          <Field label="Custodian" value={f.custodian ?? ''} onChange={(e) => set({ custodian: e.target.value || null })} />
          <div className="space-y-1.5"><label htmlFor="cc" className="block text-sm font-medium text-slate-700">Cost centre</label>
            <Combobox id="cc" value={f.costCenterId ?? ''} onChange={(v) => set({ costCenterId: v || null })} className={sel} placeholder="—"
              options={activeCostCenters(costCenters.data).map((c) => ({ value: c.id, label: `${c.code} — ${c.name}` }))} /></div>
          <Field label="Acquisition date" type="date" value={f.acquisitionDate} onChange={(e) => set({ acquisitionDate: e.target.value })} />
          <Field label="In-service date" type="date" value={f.inServiceDate} onChange={(e) => set({ inServiceDate: e.target.value })} />
          <Field label="Cost" inputMode="decimal" value={f.cost} onChange={(e) => set({ cost: e.target.value.replace(/[^\d.]/g, '') })} />
          <Field label="Residual value" hint={category?.defaultResidualRate ? `Default: ${category.defaultResidualRate} of cost` : 'Default: none'}
            inputMode="decimal" value={f.residualValue ?? ''} onChange={(e) => set({ residualValue: e.target.value.replace(/[^\d.]/g, '') || null })} />
          <Field label="Useful life (months)" hint={category ? `Category default: ${category.defaultUsefulLifeMonths}` : undefined} inputMode="numeric"
            value={f.usefulLifeMonths?.toString() ?? ''} onChange={(e) => set({ usefulLifeMonths: e.target.value ? Number(e.target.value.replace(/\D/g, '')) : null })} />
        </div>
        <fieldset className="space-y-3 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
          <legend className="px-1 text-sm font-semibold">How was it acquired?</legend>
          <div className="flex flex-wrap gap-4 text-sm">
            {([['INVOICE', 'Purchased (posted supplier invoice)'], ['AUC', 'Completed construction (AUC project)'], ['OTHER', 'Other (donation, transfer-in, found)']] as const).map(([v, l]) => (
              <label key={v} className="flex items-center gap-2"><input type="radio" name="src" checked={f.acquisitionSource === v}
                onChange={() => set({ acquisitionSource: v, supplierInvoiceId: null, aucProjectId: null, offsetAccountId: null })} />{l}</label>))}
          </div>
          {f.acquisitionSource === 'INVOICE' && (
            <div className="space-y-1.5"><label htmlFor="inv" className="block text-sm font-medium text-slate-700">Supplier invoice</label>
              <Select id="inv" value={f.supplierInvoiceId ?? ''} onChange={(e) => set({ supplierInvoiceId: e.target.value || null })} className={sel}>
                <option value="">Select…</option>{(invoices.data?.items ?? []).map((i) => <option key={i.id} value={i.id}>{i.invoiceNumber} — {i.supplierName}</option>)}</Select>
              <p className="text-xs text-slate-500">The invoice must have charged the category's asset account; it is already in the ledger, so activation posts nothing new.</p></div>
          )}
          {f.acquisitionSource === 'AUC' && (
            <div className="space-y-1.5"><label htmlFor="proj" className="block text-sm font-medium text-slate-700">AUC project</label>
              <Select id="proj" value={f.aucProjectId ?? ''} onChange={(e) => set({ aucProjectId: e.target.value || null })} className={sel}>
                <option value="">Select…</option>{(projects.data ?? []).filter((p) => p.status === 'OPEN').map((p) => <option key={p.id} value={p.id}>{p.code} — balance {p.balance}</option>)}</Select></div>
          )}
          {f.acquisitionSource === 'OTHER' && (
            <div className="grid gap-3 md:grid-cols-2">
              <div className="space-y-1.5"><label htmlFor="off" className="block text-sm font-medium text-slate-700">Offset account</label>
                <Combobox id="off" value={f.offsetAccountId ?? ''} onChange={(v) => set({ offsetAccountId: v || null })} className={sel} placeholder="Select…"
              options={offsets.map((a) => ({ value: a.id, label: `${a.code} — ${a.name}` }))} /></div>
              <Field label="Justification" value={f.justification ?? ''} maxLength={1000} onChange={(e) => set({ justification: e.target.value || null })} />
            </div>
          )}
        </fieldset>
        <p className="text-xs text-slate-500">The asset stays a draft until a second person with posting authority activates it.</p>
        <Button busy={save.isPending} disabled={!valid} onClick={() => save.mutate()}>Save draft</Button>
      </div>
    </>
  );
}
