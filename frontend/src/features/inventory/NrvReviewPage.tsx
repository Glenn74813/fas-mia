import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type ChangeEvent } from 'react';
import { Link } from 'react-router';
import { nrvApi } from '../../api/endpoints';
import type { NrvReview } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Spinner } from '../../components/Spinner';

/** "item_code,market_unit_value[,source]" lines, with or without a header row. */
function parsePrices(text: string): { itemCode: string; marketUnitValue: string; source?: string }[] {
  return text.split(/\r?\n/).map((l) => l.split(',').map((c) => c.trim().replace(/^"|"$/g, '')))
    .filter((c) => c[0] && c[1] && !/^item/i.test(c[0]) && !Number.isNaN(Number(c[1].replace(/,/g, ''))))
    .map((c) => ({ itemCode: c[0]!, marketUnitValue: c[1]!.replace(/,/g, ''), source: c[2] || 'Imported' }));
}

export function NrvReviewPage() {
  const { hasPermission } = useAuth();
  const canManage = hasPermission('INVENTORY_MANAGE');
  const qc = useQueryClient();
  const [asOf, setAsOf] = useState(`${new Date().getFullYear() - 1}-12-31`);
  const [open, setOpen] = useState<string | null>(null);
  const [edits, setEdits] = useState<Record<string, string>>({});
  const [note, setNote] = useState('');
  const reviews = useQuery({ queryKey: ['nrv'], queryFn: nrvApi.list });
  const review = useQuery({ queryKey: ['nrv', open], queryFn: () => nrvApi.get(open!), enabled: !!open });
  const refresh = () => qc.invalidateQueries({ queryKey: ['nrv'] });
  const create = useMutation({ mutationFn: () => nrvApi.create(asOf), onSuccess: (r) => { setOpen(r.id); setEdits({}); refresh(); } });
  const save = useMutation({
    mutationFn: (a: { r: NrvReview; prices: { itemCode: string; marketUnitValue: string | null; source?: string }[] }) => nrvApi.prices(a.r, a.prices),
    onSuccess: () => { setEdits({}); refresh(); },
  });
  const propose = useMutation({ mutationFn: (r: NrvReview) => nrvApi.propose(r, note), onSuccess: refresh });
  const cancel = useMutation({ mutationFn: nrvApi.cancel, onSuccess: refresh });

  const importFile = async (e: ChangeEvent<HTMLInputElement>, r: NrvReview) => {
    const file = e.target.files?.[0];
    if (file) save.mutate({ r, prices: parsePrices(await file.text()) });
    e.target.value = '';
  };
  const r = review.data;
  const editable = canManage && r?.status === 'OPEN';

  return (
    <>
      <PageHeader title="Cost vs market review" crumbs={[{ label: 'Inventory' }, { label: 'Cost vs market review' }]} />
      <p className="mb-4 max-w-3xl text-sm text-slate-600">The year-end review of inventory at the lower of cost and net realisable value.
        A review records each item's quantity and average cost on the date, how much was issued in the twelve months before, and how
        many months of stock that is; items with no issues or more than the threshold's months of stock are flagged slow-moving.
        Enter or import each item's market value per unit; where it is below cost, <strong>Propose write-down</strong> creates a draft
        price adjustment that is reviewed and posted like any stock document. The measurement basis and slow-moving rule are
        proposals for MIAA's accounting policy (C-07).</p>
      {canManage && (
        <div className="mb-4 flex flex-wrap items-end gap-2">
          <label className="text-sm">Review date<input type="date" value={asOf} onChange={(e) => setAsOf(e.target.value)}
            className="mt-1 block rounded-lg border border-slate-300 px-3 py-2 shadow-xs" /></label>
          <Button busy={create.isPending} onClick={() => create.mutate()}>Start review</Button>
        </div>
      )}
      <ErrorAlert error={reviews.error ?? create.error ?? save.error ?? propose.error ?? cancel.error} />
      {reviews.isPending ? <Spinner /> : reviews.data && (
        <div className="mb-4 overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs">
          <table className="min-w-full text-left text-sm">
            <thead className="border-b border-slate-200 bg-slate-50/80 text-[11px] font-semibold uppercase tracking-wider text-slate-500">
              <tr><th className="px-3 py-2">Review date</th><th className="px-3 py-2">Status</th><th className="px-3 py-2 text-right">Carrying value</th>
                <th className="px-3 py-2 text-right">Write-down</th><th className="px-3 py-2">Slow-moving</th><th className="px-3 py-2" /></tr>
            </thead>
            <tbody>
              {reviews.data.map((x) => (
                <tr key={x.id} className={`border-t border-slate-100 ${open === x.id ? 'bg-brand-50' : ''}`}>
                  <td className="px-3 py-2">{x.asOf}<span className="block text-xs text-slate-500">{x.preparedBy}</span></td>
                  <td className="px-3 py-2"><Badge tone={x.status === 'PROPOSED' ? 'green' : x.status === 'OPEN' ? 'blue' : 'slate'}>{x.status}</Badge></td>
                  <td className="px-3 py-2 text-right"><Money value={x.totalCarryingValue} /></td>
                  <td className="px-3 py-2 text-right"><Money value={x.totalWriteDown} blankZero /></td>
                  <td className="px-3 py-2">{x.slowMovingItems}</td>
                  <td className="px-3 py-2 text-right"><Button variant="secondary" onClick={() => { setOpen(open === x.id ? null : x.id); setEdits({}); }}>
                    {open === x.id ? 'Close' : 'Open'}</Button></td>
                </tr>
              ))}
              {reviews.data.length === 0 && <tr><td colSpan={6} className="px-4 py-6 text-center text-slate-500">No reviews yet.</td></tr>}
            </tbody>
          </table>
        </div>
      )}
      {open && (review.isPending ? <Spinner /> : r && (
        <section className="rounded-xl border border-slate-200/80 bg-white p-4 shadow-xs">
          <div className="mb-3 flex flex-wrap items-center gap-2">
            <h2 className="mr-auto text-base font-semibold text-slate-900">Review as of {r.asOf}: {r.lines.length} items, {r.pricedItems} priced,
              write-down <Money value={r.totalWriteDown} /></h2>
            {editable && <>
              <label className="cursor-pointer text-sm text-brand-700 hover:underline">Import prices (CSV)
                <input type="file" accept=".csv,.txt" className="sr-only" onChange={(e) => void importFile(e, r)} /></label>
              <Button variant="secondary" disabled={Object.keys(edits).length === 0} busy={save.isPending}
                onClick={() => save.mutate({ r, prices: Object.entries(edits).map(([itemCode, v]) => ({ itemCode, marketUnitValue: v === '' ? null : v, source: 'Entered' })) })}>
                Save prices</Button>
              <Button variant="secondary" busy={cancel.isPending} onClick={() => cancel.mutate(r)}>Cancel review</Button>
            </>}
          </div>
          {r.proposedDocumentId && <p className="mb-3 text-sm">Proposed write-down: <Link to={`/inventory/documents/${r.proposedDocumentId}`}
            className="text-brand-700 hover:underline">draft price adjustment</Link>{r.note && ` (${r.note})`}.</p>}
          <div className="overflow-x-auto">
            <table className="min-w-full whitespace-nowrap text-right text-sm">
              <thead className="text-[11px] font-semibold uppercase tracking-wider text-slate-500">
                <tr><th className="px-2 py-2 text-left">Item</th><th className="px-2 py-2">Quantity</th><th className="px-2 py-2">Unit cost</th>
                  <th className="px-2 py-2">Carrying value</th><th className="px-2 py-2">Last movement</th><th className="px-2 py-2">Issued 12 mo.</th>
                  <th className="px-2 py-2">Months of stock</th><th className="px-2 py-2">Market value / unit</th><th className="px-2 py-2">Write-down</th></tr>
              </thead>
              <tbody>
                {r.lines.map((l) => (
                  <tr key={l.itemId} className="border-t border-slate-100">
                    <td className="px-2 py-1.5 text-left">{l.itemCode} {l.itemName}
                      {l.slowMoving && <span className="ml-2"><Badge tone="amber">SLOW-MOVING</Badge></span>}</td>
                    <td className="px-2 py-1.5">{l.quantity} {l.unitOfMeasure}</td>
                    <td className="px-2 py-1.5">{Number(l.unitCost).toFixed(2)}</td>
                    <td className="px-2 py-1.5"><Money value={l.carryingValue} /></td>
                    <td className="px-2 py-1.5">{l.lastMovementDate ?? '—'}</td>
                    <td className="px-2 py-1.5">{l.issued12m}</td>
                    <td className="px-2 py-1.5">{l.coverageMonths ?? 'none issued'}</td>
                    <td className="px-2 py-1.5">{editable
                      ? <input aria-label={`Market value of ${l.itemCode}`} inputMode="decimal" className="w-28 rounded border border-slate-300 px-2 py-1 text-right"
                        value={edits[l.itemCode] ?? l.marketUnitValue ?? ''} onChange={(e) => setEdits({ ...edits, [l.itemCode]: e.target.value })} />
                      : l.marketUnitValue ?? '—'}{l.priceSource && <span className="block text-xs text-slate-500">{l.priceSource}</span>}</td>
                    <td className="px-2 py-1.5"><Money value={l.writeDown} blankZero /></td>
                  </tr>
                ))}
                {r.lines.length === 0 && <tr><td colSpan={9} className="px-4 py-6 text-center text-slate-500">No stock on the review date.</td></tr>}
              </tbody>
            </table>
          </div>
          {editable && (
            <div className="mt-3 flex flex-wrap items-end gap-2">
              <label className="text-sm">Note (optional)<input value={note} maxLength={500} onChange={(e) => setNote(e.target.value)}
                className="mt-1 block w-72 rounded-lg border border-slate-300 px-3 py-2 shadow-xs" /></label>
              <Button busy={propose.isPending} disabled={r.totalWriteDown === '0.00' || Object.keys(edits).length > 0}
                onClick={() => propose.mutate(r)}>Propose write-down</Button>
            </div>
          )}
        </section>
      ))}
    </>
  );
}
