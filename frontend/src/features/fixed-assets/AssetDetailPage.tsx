import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Select } from '../../components/Select';
import { Combobox } from '../../components/Combobox';
import { useState } from 'react';
import { Link, useParams } from 'react-router';
import { assetApi } from '../../api/endpoints';
import type { Remeasurement } from '../../api/types';
import { ActionDialog, type ActionSpec } from '../../components/ActionDialog';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Field } from '../../components/Field';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Spinner } from '../../components/Spinner';
import { StatusBadge } from '../../components/StatusBadge';
import { accountsFor, useChart } from '../../hooks/useAccounting';
import { isValidAmount } from '../../utils/money';
import { AssetTransfersPanel } from './AssetTransfersPanel';

type Kind = 'activate' | 'cancel' | 'dispose' | 'postDisposal' | 'cancelDisposal' | 'impair' | 'revalue' | 'postRm' | 'cancelRm';

const SPECS: Record<Kind, ActionSpec> = {
  activate: { title: 'Activate the asset?', text: 'Assigns the asset number and records the acquisition in the ledger (except purchases already posted through the invoice).', confirm: 'Activate' },
  cancel: { title: 'Cancel this draft?', text: 'The draft is kept for the record.', confirm: 'Cancel draft', commentRequired: true, commentLabel: 'Reason', danger: true },
  dispose: { title: 'Prepare disposal', text: 'A second person posts it. Depreciation must be posted through the month before disposal.', confirm: 'Save disposal' },
  postDisposal: { title: 'Post the disposal?', text: 'Removes the cost and accumulated depreciation and records the gain or loss.', confirm: 'Post' },
  cancelDisposal: { title: 'Cancel the disposal?', text: 'The asset stays in service.', confirm: 'Cancel disposal', danger: true },
  impair: { title: 'Prepare impairment', text: 'Writes the asset down to its recoverable amount (PAS 36). A second person posts it. Depreciation must be posted through the month before.', confirm: 'Save impairment', commentRequired: true, commentLabel: 'Reason' },
  revalue: { title: 'Prepare revaluation', text: 'Restates the asset at fair value (PAS 16): accumulated depreciation and impairment are eliminated against cost. A second person posts it.', confirm: 'Save revaluation', commentRequired: true, commentLabel: 'Reason' },
  postRm: { title: 'Post this entry?', text: 'Records the amounts shown in the ledger and restarts depreciation from the new carrying amount.', confirm: 'Post' },
  cancelRm: { title: 'Cancel this draft?', text: 'Nothing is posted; the asset is unchanged.', confirm: 'Cancel draft', danger: true },
};

const today = () => new Date().toISOString().slice(0, 10);

export function AssetDetailPage() {
  const { id = '' } = useParams();
  const qc = useQueryClient();
  const query = useQuery({ queryKey: ['asset', id], queryFn: () => assetApi.get(id) });
  const chart = useChart();
  const [dialog, setDialog] = useState<Kind | null>(null);
  const [disp, setDisp] = useState({ disposalDate: today(), method: 'SCRAP', proceeds: '', proceedsAccountId: '', reason: '' });
  const [rm, setRm] = useState({ date: today(), amount: '', remainingLife: '', reference: '' });
  const [target, setTarget] = useState<Remeasurement | null>(null);
  const act = useMutation({
    mutationFn: async ({ kind, comment }: { kind: Kind; comment: string }) => {
      const a = query.data!;
      if (kind === 'activate') return assetApi.activate(id, a.version);
      if (kind === 'cancel') return assetApi.cancel(id, a.version, comment);
      if (kind === 'dispose') return assetApi.prepareDisposal({ assetId: id, disposalDate: disp.disposalDate, method: disp.method,
        proceeds: disp.proceeds || '0', proceedsAccountId: disp.proceedsAccountId || null, reason: disp.reason });
      if (kind === 'impair' || kind === 'revalue') return assetApi.prepareRemeasurement({ assetId: id,
        kind: kind === 'impair' ? 'IMPAIRMENT' : 'REVALUATION', remeasuredOn: rm.date, newCarryingAmount: rm.amount,
        remainingLifeMonths: rm.remainingLife ? Number(rm.remainingLife) : null, basisReference: rm.reference.trim() || null, reason: comment });
      if (kind === 'postRm' || kind === 'cancelRm') return assetApi.remeasurementAction(target!, kind === 'postRm' ? 'post' : 'cancel');
      return assetApi.disposalAction(a.disposal!.id, kind === 'postDisposal' ? 'post' : 'cancel', a.disposal!.version);
    },
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['asset', id] }); qc.invalidateQueries({ queryKey: ['assets'] }); setDialog(null); },
  });
  if (query.isPending) return <Spinner />;
  if (query.error) return <ErrorAlert error={query.error} />;
  const a = query.data;
  const can = (x: string) => a.availableActions.includes(x);
  const d = a.disposal;
  const open = (k: Kind) => { act.reset(); setDialog(k); };
  const cash = accountsFor(chart.data?.accounts, 'CASH');
  const rmValid = !!rm.amount && isValidAmount(rm.amount)
    && (!rm.remainingLife || (/^\d+$/.test(rm.remainingLife) && Number(rm.remainingLife) <= 1200));
  const openRm = (k: 'impair' | 'revalue') => { setRm({ date: today(), amount: '', remainingLife: '', reference: '' }); open(k); };
  const dispValid = disp.reason.trim().length >= 5 && isValidAmount(disp.proceeds) && (!disp.proceeds || Number(disp.proceeds) === 0 || disp.proceedsAccountId);
  return (
    <>
      <PageHeader title={`Asset ${a.assetNumber ?? '(draft)'}`} crumbs={[{ label: 'Fixed assets' }, { label: 'Register', to: '/fixed-assets' }, { label: a.assetNumber ?? 'Draft' }]}
        actions={<div className="flex gap-2">
          {can('ACTIVATE') && <Button onClick={() => open('activate')}>Activate</Button>}
          {can('IMPAIR') && <Button variant="secondary" onClick={() => openRm('impair')}>Impair</Button>}
          {can('REVALUE') && <Button variant="secondary" onClick={() => openRm('revalue')}>Revalue</Button>}
          {can('DISPOSE') && <Button variant="secondary" onClick={() => open('dispose')}>Dispose</Button>}
          {can('CANCEL') && <Button variant="secondary" onClick={() => open('cancel')}>Cancel</Button>}
        </div>} />
      <div className="space-y-4">
        <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
          <div className="mb-3"><StatusBadge status={a.status} /></div>
          <p className="mb-3 text-sm font-medium">{a.description}</p>
          <dl className="grid gap-2 text-sm sm:grid-cols-4">
            <dt className="text-slate-500">Category</dt><dd>{a.categoryCode} — {a.categoryName}</dd>
            <dt className="text-slate-500">Serial</dt><dd>{a.serialNumber ?? '—'}</dd>
            <dt className="text-slate-500">Location / custodian</dt><dd>{a.location ?? '—'} / {a.custodian ?? '—'}</dd>
            <dt className="text-slate-500">Cost centre</dt><dd>{a.costCenterCode ?? '—'}</dd>
            <dt className="text-slate-500">Acquired / in service</dt><dd>{a.acquisitionDate} / {a.inServiceDate}</dd>
            <dt className="text-slate-500">Source</dt><dd>{a.acquisitionSource === 'INVOICE' ? `Invoice ${a.supplierInvoiceNumber}` : a.acquisitionSource === 'AUC'
              ? `AUC ${a.aucProjectCode}` : a.acquisitionSource === 'MIGRATION' ? 'Migrated from the legacy register'
                : `Other, offset ${a.offsetAccountCode}: ${a.justification}`}</dd>
            <dt className="text-slate-500">Prepared / activated</dt><dd>{a.preparedBy} / {a.activatedBy ?? '—'}</dd>
            {a.acquisitionEntryId && <><dt className="text-slate-500">Acquisition entry</dt>
              <dd><Link className="text-brand-700 hover:underline" to={`/general-ledger/entries/${a.acquisitionEntryId}`}>{a.acquisitionEntryNumber}</Link></dd></>}
          </dl>
          <dl className="mt-4 grid max-w-md grid-cols-2 gap-1 text-sm">
            <dt>Cost</dt><dd className="text-right"><Money value={a.cost} /></dd>
            {Number(a.grossCarryingAmount) !== Number(a.cost) && <><dt>Revalued amount</dt><dd className="text-right"><Money value={a.grossCarryingAmount} /></dd></>}
            <dt>Residual value</dt><dd className="text-right"><Money value={a.residualValue} /></dd>
            <dt>Useful life</dt><dd className="text-right">{a.usefulLifeMonths} months (<Money value={a.monthlyDepreciation} /> a month)</dd>
            <dt>Accumulated depreciation</dt><dd className="text-right"><Money value={a.accumulatedDepreciation} /></dd>
            {Number(a.accumulatedImpairment) > 0 && <><dt>Accumulated impairment</dt><dd className="text-right"><Money value={a.accumulatedImpairment} /></dd></>}
            {Number(a.revaluationSurplus) > 0 && <><dt>Revaluation surplus (equity)</dt><dd className="text-right"><Money value={a.revaluationSurplus} /></dd></>}
            <dt className="font-semibold">Net book value</dt><dd className="text-right font-semibold"><Money value={a.netBookValue} /></dd>
          </dl>
        </section>
        {d && (
          <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5 text-sm">
            <div className="mb-2 flex items-center justify-between"><h2 className="font-semibold">Disposal <StatusBadge status={d.status} /></h2>
              <div className="flex gap-2">
                {d.availableActions.includes('POST') && <Button onClick={() => open('postDisposal')}>Post disposal</Button>}
                {d.availableActions.includes('CANCEL') && <Button variant="secondary" onClick={() => open('cancelDisposal')}>Cancel disposal</Button>}</div></div>
            <p>{d.method.toLowerCase()} on {d.disposalDate} — {d.reason} (prepared by {d.preparedBy})</p>
            <dl className="mt-2 grid max-w-md grid-cols-2 gap-1">
              <dt>Net book value</dt><dd className="text-right"><Money value={d.netBookValue} /></dd>
              <dt>Proceeds {d.proceedsAccountCode ? `(${d.proceedsAccountCode})` : ''}</dt><dd className="text-right"><Money value={d.proceeds} /></dd>
              <dt className="font-semibold">{Number(d.gainLoss) >= 0 ? 'Gain' : 'Loss'}</dt><dd className="text-right font-semibold"><Money value={d.gainLoss.replace('-', '')} /></dd>
            </dl>
            {d.postedEntryId && <p className="mt-2">Entry <Link className="text-brand-700 hover:underline" to={`/general-ledger/entries/${d.postedEntryId}`}>{d.postedEntryNumber}</Link></p>}
          </section>
        )}
        {a.remeasurements.length > 0 && (
          <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5 text-sm" aria-label="Impairments and revaluations">
            <h2 className="mb-2 font-semibold">Impairments and revaluations</h2>
            <ul className="space-y-3">{a.remeasurements.map((r) => (
              <li key={r.id} className="rounded-lg border border-slate-200 p-3">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <p><span className="font-medium">{r.kind === 'IMPAIRMENT' ? 'Impairment' : 'Revaluation'}</span> on {r.remeasuredOn} <StatusBadge status={r.status} /></p>
                  <div className="flex gap-2">
                    {r.availableActions.includes('POST') && <Button onClick={() => { setTarget(r); open('postRm'); }}>Post</Button>}
                    {r.availableActions.includes('CANCEL') && <Button variant="secondary" onClick={() => { setTarget(r); open('cancelRm'); }}>Cancel</Button>}</div></div>
                <p className="text-slate-600">{r.reason}{r.basisReference ? ` · ${r.basisReference}` : ''} (prepared by {r.preparedBy})</p>
                {r.carryingBefore != null && (
                  <dl className="mt-2 grid max-w-lg grid-cols-2 gap-1">
                    <dt>Carrying amount before</dt><dd className="text-right"><Money value={r.carryingBefore} /></dd>
                    <dt>{r.kind === 'IMPAIRMENT' ? 'Recoverable amount' : 'Fair value'}</dt><dd className="text-right"><Money value={r.newCarryingAmount} /></dd>
                    {Number(r.depreciationEliminated) > 0 && <><dt>Accumulated depreciation eliminated</dt><dd className="text-right"><Money value={r.depreciationEliminated!} /></dd></>}
                    {Number(r.impairmentChange) !== 0 && <><dt>{Number(r.impairmentChange) > 0 ? 'Impairment recognised' : 'Accumulated impairment eliminated'}</dt>
                      <dd className="text-right"><Money value={r.impairmentChange!.replace('-', '')} /></dd></>}
                    {Number(r.surplusChange) !== 0 && <><dt>{Number(r.surplusChange) > 0 ? 'To revaluation surplus' : 'From revaluation surplus'}</dt>
                      <dd className="text-right"><Money value={r.surplusChange!.replace('-', '')} /></dd></>}
                    {Number(r.lossInProfit) > 0 && <><dt>Loss in profit or loss</dt><dd className="text-right"><Money value={r.lossInProfit!} /></dd></>}
                    {Number(r.reversalInProfit) > 0 && <><dt>Reversal of earlier loss</dt><dd className="text-right"><Money value={r.reversalInProfit!} /></dd></>}
                    <dt>Remaining useful life</dt><dd className="text-right">{r.basisRemainingMonths} months</dd>
                  </dl>
                )}
                {r.postedEntryId && <p className="mt-2">Entry <Link className="text-brand-700 hover:underline" to={`/general-ledger/entries/${r.postedEntryId}`}>{r.postedEntryNumber}</Link></p>}
              </li>))}
            </ul>
          </section>
        )}
        <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
          <h2 className="mb-2 font-semibold">Depreciation history</h2>
          <table className="min-w-full text-sm"><thead className="border-b border-slate-200 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="py-1 pr-3">Period</th>
            <th className="py-1 pr-3">Run</th><th className="py-1 pr-3">Entry</th><th className="py-1 text-right">Amount</th></tr></thead>
            <tbody>{a.depreciation.map((h) => (
              <tr key={h.periodEnd + h.runStatus} className="border-t border-slate-100"><td className="py-1.5 pr-3">{h.periodName}</td>
                <td className="py-1.5 pr-3"><StatusBadge status={h.runStatus} /></td><td className="py-1.5 pr-3">{h.entryNumber ?? '—'}</td>
                <td className="py-1.5 text-right"><Money value={h.amount} /></td></tr>))}
              {a.depreciation.length === 0 && <tr><td colSpan={4} className="py-3 text-center text-slate-500">No depreciation yet.</td></tr>}</tbody></table>
        </section>
        {a.assetNumber && <AssetTransfersPanel asset={a} />}
      </div>
      <ActionDialog spec={dialog ? SPECS[dialog] : null} busy={act.isPending} error={act.error} onClose={() => setDialog(null)}
        confirmDisabled={(dialog === 'dispose' && !dispValid) || ((dialog === 'impair' || dialog === 'revalue') && !rmValid)} onConfirm={(comment) => act.mutate({ kind: dialog!, comment })}>
        {dialog === 'dispose' && (
          <div className="grid gap-3 text-sm">
            <Field label="Disposal date" type="date" value={disp.disposalDate} onChange={(e) => setDisp({ ...disp, disposalDate: e.target.value })} />
            <label className="block">Method
              <Select value={disp.method} onChange={(e) => setDisp({ ...disp, method: e.target.value })} className="mt-1.5 block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs">
                {['SALE', 'SCRAP', 'DONATION', 'TRANSFER', 'LOSS'].map((m) => <option key={m} value={m}>{m.toLowerCase()}</option>)}</Select></label>
            <Field label="Proceeds" inputMode="decimal" value={disp.proceeds} onChange={(e) => setDisp({ ...disp, proceeds: e.target.value.replace(/[^\d.]/g, '') })} />
            <label className="block">Proceeds received in
              <Combobox value={disp.proceedsAccountId} onChange={(v) => setDisp({ ...disp, proceedsAccountId: v })} className="mt-1.5 block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs" placeholder="—"
              options={cash.map((x) => ({ value: x.id, label: `${x.code} — ${x.name}` }))} /></label>
            <Field label="Reason / authority" value={disp.reason} onChange={(e) => setDisp({ ...disp, reason: e.target.value })} />
          </div>
        )}
        {(dialog === 'impair' || dialog === 'revalue') && (
          <div className="grid gap-3 text-sm">
            <p className="text-slate-600">Carrying amount now: <Money value={a.netBookValue} /></p>
            <Field label="Date" type="date" value={rm.date} onChange={(e) => setRm({ ...rm, date: e.target.value })} />
            <Field label={dialog === 'impair' ? 'Recoverable amount' : 'Fair value'} inputMode="decimal" value={rm.amount}
              hint={dialog === 'impair' ? 'The higher of fair value less costs of disposal and value in use; below the carrying amount.' : 'From the appraisal.'}
              onChange={(e) => setRm({ ...rm, amount: e.target.value.replace(/[^\d.]/g, '') })} />
            <Field label="Remaining useful life in months (optional)" inputMode="numeric" value={rm.remainingLife}
              hint="Leave empty to keep the current schedule." onChange={(e) => setRm({ ...rm, remainingLife: e.target.value.replace(/\D/g, '') })} />
            <Field label={dialog === 'impair' ? 'Basis (optional)' : 'Appraisal report (optional)'} maxLength={200} value={rm.reference}
              onChange={(e) => setRm({ ...rm, reference: e.target.value })} />
          </div>
        )}
      </ActionDialog>
    </>
  );
}
