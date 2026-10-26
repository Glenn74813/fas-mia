import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { useNavigate, useParams } from 'react-router';
import { invoiceApi, settingsApi, supplierApi } from '../../api/endpoints';
import type { InvoiceInput, InvoiceLineInput, InvoiceView } from '../../api/types';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Field } from '../../components/Field';
import { PageHeader } from '../../components/PageHeader';
import { SearchSelect } from '../../components/SearchSelect';
import { Spinner } from '../../components/Spinner';
import { postableAccounts, useChart, useCostCenters } from '../../hooks/useAccounting';
import { fromCents, isValidAmount, sumCents } from '../../utils/money';

const emptyLine = (): InvoiceLineInput => ({ accountId: '', costCenterId: null, description: null, netAmount: '', ewtCodeId: null });

export function InvoiceFormPage() {
  const { id } = useParams();
  const existing = useQuery({ queryKey: ['invoice', id], queryFn: () => invoiceApi.get(id!), enabled: !!id });
  if (id && existing.isPending) return <Spinner />;
  if (id && existing.error) return <ErrorAlert error={existing.error} />;
  return <InvoiceForm existing={existing.data} />;
}

function InvoiceForm({ existing }: { existing?: InvoiceView }) {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const taxCodes = useQuery({ queryKey: ['tax-codes'], queryFn: settingsApi.taxCodes });
  const chart = useChart();
  const costCenters = useCostCenters();
  const today = new Date().toISOString().slice(0, 10);
  const [form, setForm] = useState<Omit<InvoiceInput, 'lines'>>({
    supplierId: existing?.supplierId ?? '', invoiceNumber: existing?.invoiceNumber ?? '',
    invoiceDate: existing?.invoiceDate ?? today, dueDate: existing?.dueDate ?? today,
    accountingDate: existing?.accountingDate ?? today, description: existing?.description ?? '', vatCodeId: existing?.vatCodeId ?? null,
  });
  const [lines, setLines] = useState<InvoiceLineInput[]>(existing
    ? existing.lines.map((l) => ({ accountId: l.accountId, costCenterId: l.costCenterId, description: l.description,
      netAmount: l.netAmount, ewtCodeId: l.ewtCodeId }))
    : [emptyLine()]);
  const supplierQuery = useQuery({ queryKey: ['supplier', form.supplierId], queryFn: () => supplierApi.get(form.supplierId), enabled: !!form.supplierId });
  const supplier = supplierQuery.data;
  const accounts = postableAccounts(chart.data?.accounts).filter((a) => a.accountType === 'EXPENSE' || a.accountType === 'ASSET');
  const ewtCodes = (taxCodes.data ?? []).filter((t) => t.taxType === 'EWT' && t.status === 'ACTIVE');
  const vatCodes = (taxCodes.data ?? []).filter((t) => t.taxType === 'VAT_INPUT' && t.status === 'ACTIVE');
  const set = (i: number, patch: Partial<InvoiceLineInput>) => setLines(lines.map((l, j) => (j === i ? { ...l, ...patch } : l)));
  const save = useMutation({
    mutationFn: () => existing ? invoiceApi.update(existing.id, existing.version, { ...form, lines }) : invoiceApi.create({ ...form, lines }),
    onSuccess: (inv) => { qc.setQueryData(['invoice', inv.id], inv); qc.invalidateQueries({ queryKey: ['invoices'] });
      navigate(`/payables/invoices/${inv.id}`); },
  });
  const valid = form.supplierId && form.invoiceNumber && form.description && lines.every((l) => l.accountId && isValidAmount(l.netAmount) && l.netAmount);
  const submit = (e: FormEvent) => { e.preventDefault(); save.mutate(); };

  return (
    <>
      <PageHeader title={existing ? `Edit invoice ${existing.invoiceNumber}` : 'Register supplier invoice'}
        crumbs={[{ label: 'Payables' }, { label: 'Invoices', to: '/payables/invoices' }, { label: existing ? 'Edit' : 'New' }]} />
      <form onSubmit={submit} className="space-y-4">
        <ErrorAlert error={save.error} />
        <div className="grid gap-4 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5 md:grid-cols-3">
          <SearchSelect id="supplier" label="Supplier" value={form.supplierId} disabled={!!existing} queryKey="suppliers" placeholder="Select…"
            search={(q) => supplierApi.list(q, 0, 50)} optionLabel={(s) => `${s.code} — ${s.name}`} filter={(s) => s.status === 'ACTIVE'}
            selected={supplier} onChange={(id) => setForm({ ...form, supplierId: id, vatCodeId: null })} />
          <Field label="Supplier invoice number" value={form.invoiceNumber} maxLength={60} onChange={(e) => setForm({ ...form, invoiceNumber: e.target.value })} />
          <div className="space-y-1">
            <label htmlFor="vat" className="block text-sm font-medium text-slate-700">Input VAT</label>
            <select id="vat" value={form.vatCodeId ?? ''} disabled={!supplier?.vatRegistered} onChange={(e) => setForm({ ...form, vatCodeId: e.target.value || null })}
              className="block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm">
              <option value="">{supplier?.vatRegistered ? 'No VAT' : 'Supplier not VAT-registered'}</option>
              {vatCodes.map((t) => <option key={t.id} value={t.id}>{t.code} ({t.rate})</option>)}
            </select>
          </div>
          <Field label="Invoice date" type="date" value={form.invoiceDate} onChange={(e) => setForm({ ...form, invoiceDate: e.target.value })} />
          <Field label="Due date" type="date" value={form.dueDate} onChange={(e) => setForm({ ...form, dueDate: e.target.value })} />
          <Field label="Accounting date" type="date" value={form.accountingDate} onChange={(e) => setForm({ ...form, accountingDate: e.target.value })} />
          <div className="md:col-span-3"><Field label="Description" value={form.description} maxLength={1000}
            onChange={(e) => setForm({ ...form, description: e.target.value })} /></div>
        </div>
        <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs">
          <table className="min-w-full text-sm">
            <thead className="border-b border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500">
              <tr><th className="px-2 py-2">#</th><th className="px-2 py-2">Expense / asset account</th><th className="px-2 py-2">Cost centre</th>
                <th className="px-2 py-2">Description</th><th className="px-2 py-2">Withholding</th><th className="px-2 py-2 text-right">Net amount</th><th /></tr>
            </thead>
            <tbody>
              {lines.map((l, i) => (
                <tr key={i} className="border-t border-slate-100">
                  <td className="px-2 py-2 text-slate-500">{i + 1}</td>
                  <td className="px-2 py-2"><select aria-label={`Line ${i + 1} account`} value={l.accountId} onChange={(e) => set(i, { accountId: e.target.value })}
                    className="w-64 rounded-md border border-slate-300 px-2 py-1"><option value="">Select…</option>
                    {accounts.map((a) => <option key={a.id} value={a.id}>{a.code} — {a.name}</option>)}</select></td>
                  <td className="px-2 py-2"><select aria-label={`Line ${i + 1} cost centre`} value={l.costCenterId ?? ''} onChange={(e) => set(i, { costCenterId: e.target.value || null })}
                    className="w-36 rounded-md border border-slate-300 px-2 py-1"><option value="">—</option>
                    {(costCenters.data ?? []).map((c) => <option key={c.id} value={c.id}>{c.code}</option>)}</select></td>
                  <td className="px-2 py-2"><input aria-label={`Line ${i + 1} description`} value={l.description ?? ''} onChange={(e) => set(i, { description: e.target.value || null })}
                    className="w-48 rounded-md border border-slate-300 px-2 py-1" /></td>
                  <td className="px-2 py-2"><select aria-label={`Line ${i + 1} withholding`} value={l.ewtCodeId ?? ''} onChange={(e) => set(i, { ewtCodeId: e.target.value || null })}
                    className="w-40 rounded-md border border-slate-300 px-2 py-1"><option value="">{supplier?.defaultEwtCode ? `Default (${supplier.defaultEwtCode})` : 'None'}</option>
                    {ewtCodes.map((t) => <option key={t.id} value={t.id}>{t.code} ({t.rate})</option>)}</select></td>
                  <td className="px-2 py-2"><input aria-label={`Line ${i + 1} net amount`} inputMode="decimal" value={l.netAmount}
                    onChange={(e) => set(i, { netAmount: e.target.value.replace(/[^\d.]/g, '') })} aria-invalid={!isValidAmount(l.netAmount) || undefined}
                    className={`w-32 rounded border px-2 py-1 text-right ${isValidAmount(l.netAmount) ? 'border-slate-300' : 'border-red-500'}`} /></td>
                  <td className="px-2 py-2"><button type="button" disabled={lines.length <= 1} className="text-sm text-red-700 disabled:text-slate-300"
                    onClick={() => setLines(lines.filter((_, j) => j !== i))}>Remove</button></td>
                </tr>
              ))}
            </tbody>
            <tfoot className="border-t-2 border-slate-300 font-medium"><tr><td colSpan={5} className="px-2 py-2 text-right">Net total</td>
              <td className="px-2 py-2 text-right tabular-nums">{fromCents(sumCents(lines.map((l) => l.netAmount)))}</td><td /></tr></tfoot>
          </table>
        </div>
        <button type="button" className="text-sm text-brand-700 hover:underline" onClick={() => setLines([...lines, emptyLine()])}>+ Add line</button>
        <p className="text-xs text-slate-500">VAT and withholding are computed by the server from the configured tax codes when you save.</p>
        <div className="flex gap-2">
          <Button type="submit" busy={save.isPending} disabled={!valid}>Save draft</Button>
          <Button variant="secondary" onClick={() => navigate(-1)}>Cancel</Button>
        </div>
      </form>
    </>
  );
}
