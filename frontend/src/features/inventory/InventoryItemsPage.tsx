import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useParams } from 'react-router';
import { inventoryApi } from '../../api/endpoints';
import { useAuth } from '../../auth/AuthContext';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Field } from '../../components/Field';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Pagination } from '../../components/Pagination';
import { Spinner } from '../../components/Spinner';
import { postableAccounts, useChart } from '../../hooks/useAccounting';
import { formatDateTime } from '../../utils/format';
import { fromCents, sumCents } from '../../utils/money';

const qty = (v: string) => Number(v).toLocaleString('en-PH', { maximumFractionDigits: 4 });

export function InventoryItemsPage() {
  const { hasPermission } = useAuth();
  const manage = hasPermission('INVENTORY_MANAGE');
  const qc = useQueryClient();
  const [page, setPage] = useState(0);
  const items = useQuery({ queryKey: ['inventory-items', page], queryFn: () => inventoryApi.items(undefined, page), placeholderData: keepPreviousData });
  const warehouses = useQuery({ queryKey: ['warehouses'], queryFn: inventoryApi.warehouses });
  const chart = useChart();
  const accounts = postableAccounts(chart.data?.accounts).filter((a) => !a.subsidiaryType);
  const [item, setItem] = useState({ code: '', name: '', unitOfMeasure: 'pc', inventoryAccountId: '', consumptionAccountId: '' });
  const [wh, setWh] = useState({ code: '', name: '' });
  const createItem = useMutation({ mutationFn: () => inventoryApi.createItem(item), onSuccess: () => qc.invalidateQueries({ queryKey: ['inventory-items'] }) });
  const createWh = useMutation({ mutationFn: () => inventoryApi.createWarehouse(wh.code, wh.name), onSuccess: () => qc.invalidateQueries({ queryKey: ['warehouses'] }) });
  // Exact decimal arithmetic: never sum money as floating point.
  const total = fromCents(sumCents((items.data?.items ?? []).map((i) => String(i.value))));
  const onePage = (items.data?.totalPages ?? 1) <= 1;
  return (
    <>
      <PageHeader title="Stock on hand" crumbs={[{ label: 'Inventory' }, { label: 'Items' }]} />
      <ErrorAlert error={items.error} />
      {items.isPending ? <Spinner /> : (
        <div className="mb-4 overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs"><table className="min-w-full text-sm">
          <thead className="border-b border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="px-3 py-2">Item</th><th className="px-3 py-2">Name</th>
            <th className="px-3 py-2">By warehouse</th><th className="px-3 py-2">Accounts</th><th className="px-3 py-2 text-right">Quantity</th>
            <th className="px-3 py-2 text-right">Average cost</th><th className="px-3 py-2 text-right">Value</th></tr></thead>
          <tbody>{(items.data?.items ?? []).map((i) => (
            <tr key={i.id} className="border-t border-slate-100"><td className="px-3 py-2 font-mono">
              <Link className="text-brand-700 hover:underline" to={`/inventory/items/${i.id}`}>{i.code}</Link></td><td className="px-3 py-2">{i.name}</td>
              <td className="px-3 py-2">{i.warehouses.map((w) => `${w.warehouseCode}: ${qty(w.quantity)}`).join(', ') || '—'}</td>
              <td className="px-3 py-2 font-mono text-xs">{i.inventoryAccountCode} / {i.consumptionAccountCode}</td>
              <td className="px-3 py-2 text-right">{qty(i.quantity)} {i.unitOfMeasure}</td>
              <td className="px-3 py-2 text-right">{i.averageCost ? Number(i.averageCost).toLocaleString('en-PH', { minimumFractionDigits: 2, maximumFractionDigits: 6 }) : '—'}</td>
              <td className="px-3 py-2 text-right"><Money value={i.value} /></td></tr>))}
            {items.data?.items.length === 0 && <tr><td colSpan={7} className="px-4 py-6 text-center text-slate-500">No items.</td></tr>}</tbody>
          <tfoot className="border-t-2 border-slate-300 font-medium"><tr><td colSpan={6} className="px-3 py-2">{onePage ? 'Total inventory at average cost'
            : <>Total of the items on this page (the whole stock is valued in <Link className="text-brand-700 hover:underline" to="/inventory/reports">Inventory reports</Link>)</>}</td>
            <td className="px-3 py-2 text-right"><Money value={total} /></td></tr></tfoot>
        </table></div>
      )}
      {items.data && <Pagination page={items.data.page} totalPages={items.data.totalPages} totalItems={items.data.totalItems} onPage={setPage} />}
      {manage && (
        <div className="grid gap-4 lg:grid-cols-2">
          <section className="space-y-3 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
            <h2 className="font-semibold">New item</h2>
            <ErrorAlert error={createItem.error} />
            <div className="grid gap-3 md:grid-cols-3">
              <Field label="Item code" value={item.code} onChange={(e) => setItem({ ...item, code: e.target.value })} />
              <Field label="Item name" value={item.name} onChange={(e) => setItem({ ...item, name: e.target.value })} />
              <Field label="Unit of measure" value={item.unitOfMeasure} onChange={(e) => setItem({ ...item, unitOfMeasure: e.target.value })} />
              <label className="block text-sm md:col-span-3">Inventory account
                <select value={item.inventoryAccountId} onChange={(e) => setItem({ ...item, inventoryAccountId: e.target.value })} className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs">
                  <option value="">Select…</option>{accounts.filter((a) => a.accountType === 'ASSET').map((a) => <option key={a.id} value={a.id}>{a.code} — {a.name}</option>)}</select></label>
              <label className="block text-sm md:col-span-3">Consumption account
                <select value={item.consumptionAccountId} onChange={(e) => setItem({ ...item, consumptionAccountId: e.target.value })} className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs">
                  <option value="">Select…</option>{accounts.filter((a) => a.accountType === 'EXPENSE').map((a) => <option key={a.id} value={a.id}>{a.code} — {a.name}</option>)}</select></label>
            </div>
            <Button variant="secondary" busy={createItem.isPending} disabled={!item.code || !item.name || !item.unitOfMeasure || !item.inventoryAccountId
              || !item.consumptionAccountId} onClick={() => createItem.mutate()}>Add item</Button>
          </section>
          <section className="space-y-3 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
            <h2 className="font-semibold">Warehouses</h2>
            <ul className="text-sm">{(warehouses.data ?? []).map((w) => <li key={w.id}><span className="font-mono">{w.code}</span> {w.name}</li>)}</ul>
            <ErrorAlert error={createWh.error} />
            <div className="grid gap-3 md:grid-cols-2">
              <Field label="Warehouse code" value={wh.code} onChange={(e) => setWh({ ...wh, code: e.target.value })} />
              <Field label="Warehouse name" value={wh.name} onChange={(e) => setWh({ ...wh, name: e.target.value })} />
            </div>
            <Button variant="secondary" busy={createWh.isPending} disabled={!wh.code || !wh.name} onClick={() => createWh.mutate()}>Add warehouse</Button>
          </section>
        </div>
      )}
    </>
  );
}

export function ItemMovementsPage() {
  const { id = '' } = useParams();
  const item = useQuery({ queryKey: ['inventory-item', id], queryFn: () => inventoryApi.item(id) });
  const [page, setPage] = useState(0);
  const moves = useQuery({ queryKey: ['inventory-movements', id, page], queryFn: () => inventoryApi.movements(id, page), placeholderData: keepPreviousData });
  if (item.isPending || moves.isPending) return <Spinner />;
  if (item.error || moves.error) return <ErrorAlert error={item.error ?? moves.error} />;
  return (
    <>
      <PageHeader title={`${item.data.code} — ${item.data.name}`} crumbs={[{ label: 'Inventory' }, { label: 'Items', to: '/inventory/items' }, { label: item.data.code }]} />
      <p className="mb-3 text-sm">On hand {qty(item.data.quantity)} {item.data.unitOfMeasure} · value <Money value={item.data.value} /> · inventory {item.data.inventoryAccountCode}</p>
      <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs"><table className="min-w-full text-sm">
        <thead className="border-b border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="px-3 py-2">Date</th><th className="px-3 py-2">Document</th>
          <th className="px-3 py-2">Movement</th><th className="px-3 py-2">Warehouse</th><th className="px-3 py-2 text-right">Quantity</th>
          <th className="px-3 py-2 text-right">Value</th><th className="px-3 py-2 text-right">Pool quantity</th><th className="px-3 py-2 text-right">Pool value</th>
          <th className="px-3 py-2 text-right">Average after</th></tr></thead>
        <tbody>{moves.data.items.map((m, i) => (
          <tr key={i} className="border-t border-slate-100" title={formatDateTime(m.createdAt)}><td className="px-3 py-2">{m.movementDate}</td>
            <td className="px-3 py-2">{m.documentNumber}</td><td className="px-3 py-2">{m.movementType.replace('_', ' ').toLowerCase()}</td>
            <td className="px-3 py-2">{m.warehouseCode}</td><td className="px-3 py-2 text-right">{qty(m.quantity)}</td>
            <td className="px-3 py-2 text-right"><Money value={m.value} blankZero /></td><td className="px-3 py-2 text-right">{qty(m.poolQuantity)}</td>
            <td className="px-3 py-2 text-right"><Money value={m.poolValue} /></td>
            <td className="px-3 py-2 text-right">{m.averageAfter ? Number(m.averageAfter).toLocaleString('en-PH', { minimumFractionDigits: 2, maximumFractionDigits: 6 }) : '—'}</td></tr>))}
          {moves.data.items.length === 0 && <tr><td colSpan={9} className="px-4 py-6 text-center text-slate-500">No movements.</td></tr>}</tbody>
      </table></div>
      <Pagination page={moves.data.page} totalPages={moves.data.totalPages} totalItems={moves.data.totalItems} onPage={setPage} />
    </>
  );
}
