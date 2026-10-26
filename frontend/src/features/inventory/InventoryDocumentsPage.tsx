import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { inventoryApi, supplierApi } from '../../api/endpoints';
import type { InventoryItem, InventoryKind, InventoryLineInput } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { ActionDialog, type ActionSpec } from '../../components/ActionDialog';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Field } from '../../components/Field';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Pagination } from '../../components/Pagination';
import { SearchSelect } from '../../components/SearchSelect';
import { Spinner } from '../../components/Spinner';
import { StatusBadge } from '../../components/StatusBadge';
import { useCostCenters } from '../../hooks/useAccounting';

export const KIND: Record<InventoryKind, string> = { GOODS_RECEIPT: 'Goods receipt', ISSUE: 'Issue', COUNT: 'Stock count', TRANSFER: 'Transfer',
  REVALUATION: 'Price adjustment' };
const QTY = /^\d{1,15}(\.\d{1,4})?$/;
const COST = /^\d{1,13}(\.\d{1,6})?$/;

const IMPORT_COLUMNS: Record<InventoryKind, string> = {
  GOODS_RECEIPT: 'item_code, warehouse_code, quantity, unit_cost', ISSUE: 'item_code, warehouse_code, quantity',
  COUNT: 'item_code, warehouse_code, quantity (and optionally unit_cost)', TRANSFER: 'item_code, warehouse_code, to_warehouse_code, quantity',
  REVALUATION: 'item_code, unit_cost',
};

/** Creates a draft document from a CSV of lines; every row is checked and nothing is created on any error. */
function ImportMovementsPanel() {
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [kind, setKind] = useState<InventoryKind>('GOODS_RECEIPT');
  const [form, setForm] = useState({ documentDate: new Date().toISOString().slice(0, 10), description: '', supplierId: '', costCenterId: '' });
  const [file, setFile] = useState<File | null>(null);
  const costCenters = useCostCenters();
  const run = useMutation({ mutationFn: () => inventoryApi.importDocument(file!, { kind, documentDate: form.documentDate,
    description: form.description.trim(), supplierId: kind === 'GOODS_RECEIPT' ? form.supplierId : undefined,
    costCenterId: kind === 'ISSUE' ? form.costCenterId : undefined }), onSuccess: (d) => navigate(`/inventory/documents/${d.id}`) });
  const sel = 'block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm';
  if (!open) {
    return <button className="mb-3 text-sm text-brand-700 hover:underline" onClick={() => setOpen(true)}>Import lines from a CSV file…</button>;
  }
  const ready = file && form.description.trim() && (kind !== 'GOODS_RECEIPT' || form.supplierId) && (kind !== 'ISSUE' || form.costCenterId);
  return (
    <section className="mb-4 grid gap-3 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5 md:grid-cols-3" aria-label="Import lines">
      <label className="text-sm font-medium text-slate-700">Document kind
        <select value={kind} onChange={(e) => setKind(e.target.value as InventoryKind)} className={sel}>
          {Object.entries(KIND).map(([k, l]) => <option key={k} value={k}>{l}</option>)}</select></label>
      <Field label="Date" type="date" value={form.documentDate} onChange={(e) => setForm({ ...form, documentDate: e.target.value })} />
      <Field label="Description" value={form.description} maxLength={1000} onChange={(e) => setForm({ ...form, description: e.target.value })} />
      {kind === 'GOODS_RECEIPT' && <SearchSelect id="import-supplier" label="Supplier" value={form.supplierId} queryKey="suppliers" placeholder="Select…"
        search={(q) => supplierApi.list(q, 0, 50)} optionLabel={(x) => `${x.code} — ${x.name}`} filter={(x) => x.status === 'ACTIVE'}
        onChange={(id) => setForm({ ...form, supplierId: id })} />}
      {kind === 'ISSUE' && <label className="text-sm font-medium text-slate-700">Requesting cost centre
        <select value={form.costCenterId} onChange={(e) => setForm({ ...form, costCenterId: e.target.value })} className={sel}>
          <option value="">Select…</option>{(costCenters.data ?? []).map((c) => <option key={c.id} value={c.id}>{c.code}</option>)}</select></label>}
      <label className="text-sm font-medium text-slate-700 md:col-span-2">CSV file (UTF-8, header row)
        <input type="file" accept=".csv,text/csv" className="mt-1 block text-sm" onChange={(e) => setFile(e.target.files?.[0] ?? null)} /></label>
      <p className="text-xs text-slate-500 md:col-span-3">Columns: {IMPORT_COLUMNS[kind]}. Codes are those of active items and warehouses; at most 500
        lines. The result is a draft, posted by a second person like any other document.</p>
      <div className="flex gap-2 md:col-span-3">
        <Button busy={run.isPending} disabled={!ready} onClick={() => run.mutate()}>Import as draft</Button>
        <Button variant="secondary" onClick={() => setOpen(false)}>Close</Button>
      </div>
      <div className="md:col-span-3"><ErrorAlert error={run.error} /></div>
    </section>
  );
}

export function InventoryDocumentsPage() {
  const { hasPermission } = useAuth();
  const [kind, setKind] = useState('');
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(0);
  const list = useQuery({ queryKey: ['inventory-documents', kind, status, page], queryFn: () => inventoryApi.documents(kind, status, page),
    placeholderData: keepPreviousData });
  return (
    <>
      <PageHeader title="Stock documents" crumbs={[{ label: 'Inventory' }, { label: 'Documents' }]}
        actions={hasPermission('INVENTORY_MANAGE') && (
          <Link to="/inventory/documents/new" className="rounded-md bg-brand-700 px-4 py-2 text-sm font-medium text-white hover:bg-brand-900">New document</Link>
        )} />
      {hasPermission('INVENTORY_MANAGE') && <ImportMovementsPanel />}
      <div className="mb-4 flex gap-3">
        <select aria-label="Kind" value={kind} onChange={(e) => setKind(e.target.value)} className="rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm">
          <option value="">All kinds</option>{Object.entries(KIND).map(([k, l]) => <option key={k} value={k}>{l}</option>)}</select>
        <select aria-label="Status" value={status} onChange={(e) => setStatus(e.target.value)} className="rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm">
          {['', 'DRAFT', 'POSTED', 'CANCELLED'].map((s) => <option key={s} value={s}>{s || 'All statuses'}</option>)}</select>
      </div>
      <ErrorAlert error={list.error} />
      {list.isPending ? <Spinner /> : (
        <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs"><table className="min-w-full text-sm">
          <thead className="border-b border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="px-3 py-2">Number</th><th className="px-3 py-2">Kind</th>
            <th className="px-3 py-2">Date</th><th className="px-3 py-2">Description</th><th className="px-3 py-2">Status</th><th className="px-3 py-2 text-right">Value</th></tr></thead>
          <tbody>{(list.data?.items ?? []).map((d) => (
            <tr key={d.id} className="border-t border-slate-100"><td className="px-3 py-2"><Link className="text-brand-700 hover:underline" to={`/inventory/documents/${d.id}`}>
              {d.documentNumber ?? '(draft)'}</Link></td><td className="px-3 py-2">{KIND[d.kind]}</td><td className="px-3 py-2">{d.documentDate}</td>
              <td className="px-3 py-2">{d.supplierName ? `${d.supplierName} · ` : ''}{d.description}</td><td className="px-3 py-2"><StatusBadge status={d.status} /></td>
              <td className="px-3 py-2 text-right">{d.totalValue ? <Money value={d.totalValue} /> : '—'}</td></tr>))}
            {list.data?.items.length === 0 && <tr><td colSpan={6} className="px-4 py-6 text-center text-slate-500">No documents.</td></tr>}</tbody>
        </table></div>
      )}
      {list.data && <Pagination page={list.data.page} totalPages={list.data.totalPages} totalItems={list.data.totalItems} onPage={setPage} />}
    </>
  );
}

const emptyLine = (): InventoryLineInput => ({ itemId: '', warehouseId: '', toWarehouseId: null, quantity: '', unitCost: null });

export function NewInventoryDocumentPage() {
  const navigate = useNavigate();
  const qc = useQueryClient();
  // One item search for all lines; each line keeps the item it already chose when the search changes.
  const [itemText, setItemText] = useState('');
  const [itemQ, setItemQ] = useState('');
  useEffect(() => { const t = setTimeout(() => setItemQ(itemText.trim()), 300); return () => clearTimeout(t); }, [itemText]);
  const items = useQuery({ queryKey: ['inventory-items', 'search', itemQ], queryFn: () => inventoryApi.items(itemQ, 0, 200),
    placeholderData: keepPreviousData });
  const [picked, setPicked] = useState<Record<string, InventoryItem>>({});
  const itemOptions = (chosen: string) => {
    const found = (items.data?.items ?? []).filter((x) => x.status === 'ACTIVE');
    const current = chosen ? picked[chosen] : undefined;
    return current && !found.some((x) => x.id === chosen) ? [current, ...found] : found;
  };
  const warehouses = useQuery({ queryKey: ['warehouses'], queryFn: inventoryApi.warehouses });
  const costCenters = useCostCenters();
  const [kind, setKind] = useState<InventoryKind>('GOODS_RECEIPT');
  const [head, setHead] = useState({ documentDate: new Date().toISOString().slice(0, 10), supplierId: '', costCenterId: '', reference: '', description: '' });
  const [lines, setLines] = useState<InventoryLineInput[]>([emptyLine()]);
  const set = (i: number, p: Partial<InventoryLineInput>) => setLines(lines.map((l, j) => (j === i ? { ...l, ...p } : l)));
  const save = useMutation({
    mutationFn: () => inventoryApi.createDocument({ kind, documentDate: head.documentDate, supplierId: head.supplierId || null,
      reference: head.reference || null, costCenterId: head.costCenterId || null, description: head.description,
      // A price adjustment restates the item's whole cost pool: no warehouse, no quantity.
      lines: reval ? lines.map((l) => ({ ...l, warehouseId: null, quantity: '0' })) : lines }),
    onSuccess: (d) => { qc.invalidateQueries({ queryKey: ['inventory-documents'] }); navigate(`/inventory/documents/${d.id}`); },
  });
  const reval = kind === 'REVALUATION';
  const valid = head.description.trim() && (kind !== 'GOODS_RECEIPT' || head.supplierId) && (kind !== 'ISSUE' || head.costCenterId)
    && (!reval || lines.every((l) => l.itemId && l.unitCost && COST.test(l.unitCost)))
    && (reval || lines.every((l) => l.itemId && l.warehouseId && QTY.test(l.quantity) && (kind === 'COUNT' || Number(l.quantity) > 0)
      && (kind !== 'GOODS_RECEIPT' || (l.unitCost && COST.test(l.unitCost))) && (kind !== 'TRANSFER' || (l.toWarehouseId && l.toWarehouseId !== l.warehouseId))));
  const sel = 'block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm';
  const whs = (warehouses.data ?? []).filter((w) => w.status === 'ACTIVE');
  return (
    <>
      <PageHeader title="New stock document" crumbs={[{ label: 'Inventory' }, { label: 'Documents', to: '/inventory/documents' }, { label: 'New' }]} />
      <div className="space-y-4">
        <ErrorAlert error={save.error} />
        <div className="grid gap-4 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5 md:grid-cols-3">
          <div className="space-y-1"><label htmlFor="kind" className="block text-sm font-medium text-slate-700">Document</label>
            <select id="kind" value={kind} onChange={(e) => { setKind(e.target.value as InventoryKind); setLines([emptyLine()]); }} className={sel}>
              {Object.entries(KIND).map(([k, l]) => <option key={k} value={k}>{l}</option>)}</select></div>
          <Field label="Date" type="date" value={head.documentDate} onChange={(e) => setHead({ ...head, documentDate: e.target.value })} />
          {kind === 'GOODS_RECEIPT' && (
            <SearchSelect id="sup" label="Supplier" value={head.supplierId} queryKey="suppliers" placeholder="Select…"
              search={(q) => supplierApi.list(q, 0, 50)} optionLabel={(s) => `${s.code} — ${s.name}`} filter={(s) => s.status === 'ACTIVE'}
              onChange={(id) => setHead({ ...head, supplierId: id })} />
          )}
          {kind === 'ISSUE' && (
            <div className="space-y-1"><label htmlFor="cc" className="block text-sm font-medium text-slate-700">Requesting cost centre</label>
              <select id="cc" value={head.costCenterId} onChange={(e) => setHead({ ...head, costCenterId: e.target.value })} className={sel}><option value="">Select…</option>
                {(costCenters.data ?? []).map((c) => <option key={c.id} value={c.id}>{c.code}</option>)}</select></div>
          )}
          <Field label={kind === 'GOODS_RECEIPT' ? 'Delivery receipt / PO reference' : 'Reference'} value={head.reference} maxLength={100}
            onChange={(e) => setHead({ ...head, reference: e.target.value })} />
          <div className="md:col-span-2"><Field label="Description" value={head.description} maxLength={1000} onChange={(e) => setHead({ ...head, description: e.target.value })} /></div>
        </div>
        <label className="block text-sm">Find items for the lines <input type="search" value={itemText} onChange={(e) => setItemText(e.target.value)}
          placeholder="Item code or name" className="ml-2 w-64 rounded-md border border-slate-300 px-2 py-1" />
          {(items.data?.totalItems ?? 0) > (items.data?.items.length ?? 0) && <span className="ml-2 text-xs text-slate-500">
            Showing {items.data?.items.length} of {items.data?.totalItems}; search to narrow.</span>}</label>
        <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs"><table className="min-w-full text-sm">
          <thead className="border-b border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="px-2 py-2">#</th><th className="px-2 py-2">Item</th>
            {!reval && <th className="px-2 py-2">{kind === 'TRANSFER' ? 'From' : 'Warehouse'}</th>}{kind === 'TRANSFER' && <th className="px-2 py-2">To</th>}
            {!reval && <th className="px-2 py-2 text-right">{kind === 'COUNT' ? 'Counted quantity' : 'Quantity'}</th>}
            {(kind === 'GOODS_RECEIPT' || kind === 'COUNT' || reval) && <th className="px-2 py-2 text-right">{reval ? 'New average unit cost'
              : `Unit cost${kind === 'COUNT' ? ' (if no stock on record)' : ''}`}</th>}<th /></tr></thead>
          <tbody>{lines.map((l, i) => (
            <tr key={i} className="border-t border-slate-100"><td className="px-2 py-2 text-slate-500">{i + 1}</td>
              <td className="px-2 py-2"><select aria-label={`Line ${i + 1} item`} value={l.itemId} onChange={(e) => {
                const x = items.data?.items.find((it) => it.id === e.target.value);
                if (x) setPicked({ ...picked, [x.id]: x });
                set(i, { itemId: e.target.value });
              }} className="w-64 rounded-md border border-slate-300 px-2 py-1">
                <option value="">Select…</option>{itemOptions(l.itemId).map((x) => <option key={x.id} value={x.id}>{x.code} — {x.name}</option>)}</select></td>
              {!reval && <td className="px-2 py-2"><select aria-label={`Line ${i + 1} warehouse`} value={l.warehouseId ?? ''} onChange={(e) => set(i, { warehouseId: e.target.value })} className="w-40 rounded-md border border-slate-300 px-2 py-1">
                <option value="">Select…</option>{whs.map((w) => <option key={w.id} value={w.id}>{w.code}</option>)}</select></td>}
              {kind === 'TRANSFER' && <td className="px-2 py-2"><select aria-label={`Line ${i + 1} destination`} value={l.toWarehouseId ?? ''} onChange={(e) => set(i, { toWarehouseId: e.target.value || null })}
                className="w-40 rounded-md border border-slate-300 px-2 py-1"><option value="">Select…</option>{whs.map((w) => <option key={w.id} value={w.id}>{w.code}</option>)}</select></td>}
              {!reval && <td className="px-2 py-2"><input aria-label={`Line ${i + 1} quantity`} inputMode="decimal" value={l.quantity} onChange={(e) => set(i, { quantity: e.target.value.replace(/[^\d.]/g, '') })}
                className="w-28 rounded-md border border-slate-300 px-2 py-1 text-right" /></td>}
              {(kind === 'GOODS_RECEIPT' || kind === 'COUNT' || reval) && <td className="px-2 py-2"><input aria-label={`Line ${i + 1} unit cost`} inputMode="decimal" value={l.unitCost ?? ''}
                onChange={(e) => set(i, { unitCost: e.target.value.replace(/[^\d.]/g, '') || null })} className="w-32 rounded-md border border-slate-300 px-2 py-1 text-right" /></td>}
              <td className="px-2 py-2"><button type="button" disabled={lines.length <= 1} className="text-sm text-red-700 disabled:text-slate-300"
                onClick={() => setLines(lines.filter((_, j) => j !== i))}>Remove</button></td></tr>))}</tbody>
        </table></div>
        <button type="button" className="text-sm text-brand-700 hover:underline" onClick={() => setLines([...lines, emptyLine()])}>+ Add line</button>
        <p className="text-xs text-slate-500">Values are computed when the document is posted: receipts at their cost, issues and count differences at the weighted average.
          Posting is done by accounting, not by the preparer.</p>
        <Button busy={save.isPending} disabled={!valid} onClick={() => save.mutate()}>Save draft</Button>
      </div>
    </>
  );
}

const SPECS: Record<'post' | 'cancel', ActionSpec> = {
  post: { title: 'Post the document?', text: 'Stock moves, the average cost is updated and the ledger entry is recorded. Posted movements cannot be changed.', confirm: 'Post' },
  cancel: { title: 'Cancel this draft?', text: 'Nothing moves.', confirm: 'Cancel document', danger: true },
};

export function InventoryDocumentPage() {
  const { id = '' } = useParams();
  const qc = useQueryClient();
  const query = useQuery({ queryKey: ['inventory-document', id], queryFn: () => inventoryApi.document(id) });
  const [dialog, setDialog] = useState<'post' | 'cancel' | null>(null);
  const act = useMutation({ mutationFn: (k: 'post' | 'cancel') => inventoryApi.act(id, k, query.data!.version),
    onSuccess: (d) => { qc.setQueryData(['inventory-document', id], d); qc.invalidateQueries({ queryKey: ['inventory-documents'] });
      qc.invalidateQueries({ queryKey: ['inventory-items'] }); setDialog(null); } });
  if (query.isPending) return <Spinner />;
  if (query.error) return <ErrorAlert error={query.error} />;
  const d = query.data;
  return (
    <>
      <PageHeader title={`${KIND[d.kind]} ${d.documentNumber ?? '(draft)'}`} crumbs={[{ label: 'Inventory' }, { label: 'Documents', to: '/inventory/documents' }, { label: d.documentNumber ?? 'Draft' }]}
        actions={<div className="flex gap-2">
          {d.availableActions.includes('POST') && <Button onClick={() => { act.reset(); setDialog('post'); }}>Post</Button>}
          {d.availableActions.includes('CANCEL') && <Button variant="secondary" onClick={() => { act.reset(); setDialog('cancel'); }}>Cancel</Button>}
        </div>} />
      <section className="mb-4 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
        <div className="mb-3"><StatusBadge status={d.status} /></div>
        <dl className="grid gap-2 text-sm sm:grid-cols-4">
          <dt className="text-slate-500">Date</dt><dd>{d.documentDate}</dd>
          {d.supplierName && <><dt className="text-slate-500">Supplier</dt><dd>{d.supplierName}</dd></>}
          {d.costCenterCode && <><dt className="text-slate-500">Cost centre</dt><dd>{d.costCenterCode}</dd></>}
          <dt className="text-slate-500">Reference</dt><dd>{d.reference ?? '—'}</dd>
          <dt className="text-slate-500">Prepared / posted</dt><dd>{d.preparedBy} / {d.postedBy ?? '—'}</dd>
          {d.postedEntryId && <><dt className="text-slate-500">Ledger entry</dt>
            <dd><Link className="text-brand-700 hover:underline" to={`/general-ledger/entries/${d.postedEntryId}`}>{d.postedEntryNumber}</Link></dd></>}
        </dl>
        <p className="mt-3 text-sm">{d.description}</p>
      </section>
      <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs"><table className="min-w-full text-sm">
        <thead className="border-b border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="px-3 py-2">#</th><th className="px-3 py-2">Item</th>
          <th className="px-3 py-2">Warehouse</th><th className="px-3 py-2 text-right">Quantity</th>{d.kind === 'COUNT' && <th className="px-3 py-2 text-right">On record</th>}
          <th className="px-3 py-2 text-right">Unit cost</th><th className="px-3 py-2 text-right">Value</th></tr></thead>
        <tbody>{d.lines.map((l) => (
          <tr key={l.lineNo} className="border-t border-slate-100"><td className="px-3 py-2">{l.lineNo}</td>
            <td className="px-3 py-2"><Link className="text-brand-700 hover:underline" to={`/inventory/items/${l.itemId}`}>{l.itemCode}</Link> {l.itemName}</td>
            <td className="px-3 py-2">{l.warehouseCode}{l.toWarehouseCode ? ` → ${l.toWarehouseCode}` : ''}</td>
            <td className="px-3 py-2 text-right">{Number(l.quantity).toLocaleString('en-PH', { maximumFractionDigits: 4 })} {l.unitOfMeasure}</td>
            {d.kind === 'COUNT' && <td className="px-3 py-2 text-right">{l.systemQuantity ? Number(l.systemQuantity).toLocaleString('en-PH', { maximumFractionDigits: 4 }) : '—'}</td>}
            <td className="px-3 py-2 text-right">{l.unitCost ?? '—'}</td><td className="px-3 py-2 text-right">{l.value ? <Money value={l.value} /> : '—'}</td></tr>))}</tbody>
        {d.totalValue && <tfoot className="border-t-2 border-slate-300 font-medium"><tr><td colSpan={d.kind === 'COUNT' ? 6 : 5} className="px-3 py-2">Total</td>
          <td className="px-3 py-2 text-right"><Money value={d.totalValue} /></td></tr></tfoot>}
      </table></div>
      <ActionDialog spec={dialog ? SPECS[dialog] : null} busy={act.isPending} error={act.error} onClose={() => setDialog(null)} onConfirm={() => act.mutate(dialog!)} />
    </>
  );
}
