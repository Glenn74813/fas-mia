import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { assetApi } from '../../api/endpoints';
import type { AssetTransfer, AssetView } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Field } from '../../components/Field';
import { StatusBadge } from '../../components/StatusBadge';
import { useCostCenters } from '../../hooks/useAccounting';

/** Transfers of an asset between cost centres, locations and custodians: prepared by one person, posted by another. */
export function AssetTransfersPanel({ asset }: { asset: AssetView }) {
  const { hasPermission } = useAuth();
  const qc = useQueryClient();
  const list = useQuery({ queryKey: ['asset-transfers', asset.id], queryFn: () => assetApi.transfers(asset.id) });
  const costCenters = useCostCenters();
  const today = new Date().toISOString().slice(0, 10);
  const [form, setForm] = useState({ transferDate: today, toCostCenterId: asset.costCenterId ?? '', toLocation: asset.location ?? '',
    toCustodian: asset.custodian ?? '', reason: '' });
  const refresh = () => {
    void qc.invalidateQueries({ queryKey: ['asset-transfers', asset.id] });
    void qc.invalidateQueries({ queryKey: ['asset', asset.id] });
    void qc.invalidateQueries({ queryKey: ['inbox'] });
  };
  const prepare = useMutation({ mutationFn: () => assetApi.prepareTransfer(asset.id, { transferDate: form.transferDate,
    toCostCenterId: form.toCostCenterId || null, toLocation: form.toLocation.trim() || null, toCustodian: form.toCustodian.trim() || null,
    reason: form.reason.trim() }), onSuccess: () => { setForm({ ...form, reason: '' }); refresh(); } });
  const act = useMutation({ mutationFn: ({ t, action }: { t: AssetTransfer; action: 'post' | 'cancel' }) => assetApi.transferAction(t, action),
    onSuccess: refresh });
  const transfers = list.data ?? [];
  const pending = transfers.some((t) => t.status === 'DRAFT');
  const canPrepare = hasPermission('FA_MANAGE') && ['ACTIVE', 'FULLY_DEPRECIATED'].includes(asset.status) && !pending;
  const unit = (cc: string | null, loc: string | null, cust: string | null) => [cc, loc, cust].filter(Boolean).join(' · ') || '—';
  return (
    <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5" aria-label="Transfers">
      <h2 className="mb-2 font-semibold">Transfers</h2>
      <ErrorAlert error={list.error ?? act.error} />
      <ul className="space-y-2 text-sm">
        {transfers.map((t) => (
          <li key={t.id} className="rounded-md border border-slate-200 p-3">
            <div className="flex flex-wrap items-center gap-2"><StatusBadge status={t.status} /><span>{t.transferDate}</span>
              <span>{unit(t.fromCostCenter, t.fromLocation, t.fromCustodian)} → {unit(t.toCostCenter, t.toLocation, t.toCustodian)}</span></div>
            <p className="text-xs text-slate-500">{t.reason} · prepared by {t.preparedBy}{t.postedBy ? ` · posted by ${t.postedBy}` : ''}
              {t.entryNumber ? ` · ledger entry ${t.entryNumber}` : ''}</p>
            {t.availableActions.length > 0 && <div className="mt-1 flex gap-3">
              {t.availableActions.includes('POST') && <button className="text-brand-700 hover:underline" disabled={act.isPending}
                onClick={() => act.mutate({ t, action: 'post' })}>Post transfer</button>}
              {t.availableActions.includes('CANCEL') && <button className="text-red-700 hover:underline" disabled={act.isPending}
                onClick={() => act.mutate({ t, action: 'cancel' })}>Cancel</button>}
            </div>}
          </li>
        ))}
        {transfers.length === 0 && <li className="text-slate-500">No transfers.</li>}
      </ul>
      {canPrepare && (
        <div className="mt-4 grid gap-3 border-t border-slate-100 pt-4 md:grid-cols-3">
          <Field label="Transfer date" type="date" value={form.transferDate} onChange={(e) => setForm({ ...form, transferDate: e.target.value })} />
          <label className="text-sm font-medium text-slate-700">To cost centre
            <select value={form.toCostCenterId} onChange={(e) => setForm({ ...form, toCostCenterId: e.target.value })}
              className="block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm">
              <option value="">None</option>{(costCenters.data ?? []).filter((c) => c.status === 'ACTIVE').map((c) => <option key={c.id} value={c.id}>{c.code}</option>)}</select></label>
          <Field label="To location" value={form.toLocation} maxLength={200} onChange={(e) => setForm({ ...form, toLocation: e.target.value })} />
          <Field label="To custodian" value={form.toCustodian} maxLength={200} onChange={(e) => setForm({ ...form, toCustodian: e.target.value })} />
          <div className="md:col-span-2"><Field label="Reason / authority" value={form.reason} maxLength={1000}
            onChange={(e) => setForm({ ...form, reason: e.target.value })} /></div>
          <div className="md:col-span-3 flex items-center gap-3">
            <Button variant="secondary" busy={prepare.isPending} disabled={form.reason.trim().length < 5} onClick={() => prepare.mutate()}>Prepare transfer</Button>
            <span className="text-xs text-slate-500">A different person posts it. A cost-centre change also moves the cost and accumulated
              depreciation in the ledger when the accounts carry cost centres.</span>
          </div>
          <div className="md:col-span-3"><ErrorAlert error={prepare.error} /></div>
        </div>
      )}
    </section>
  );
}
