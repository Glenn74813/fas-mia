import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Combobox } from '../../components/Combobox';
import { useState, type FormEvent } from 'react';
import { useNavigate, useParams } from 'react-router';
import { billingApi, customerApi, settingsApi } from '../../api/endpoints';
import type { BillingInput, BillingKind, BillingLineInput, BillingView } from '../../api/types';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Field } from '../../components/Field';
import { PageHeader } from '../../components/PageHeader';
import { SearchSelect } from '../../components/SearchSelect';
import { Spinner } from '../../components/Spinner';
import { postableAccounts, useChart, useCostCenters } from '../../hooks/useAccounting';
import { fromCents, isValidAmount, sumCents } from '../../utils/money';
import { KIND_LABEL } from './BillingsPage';

const emptyLine = (): BillingLineInput => ({ revenueAccountId: '', costCenterId: null, description: '', netAmount: '' });

export function BillingFormPage() {
  const { id } = useParams();
  const existing = useQuery({ queryKey: ['billing', id], queryFn: () => billingApi.get(id!), enabled: !!id });
  if (id && existing.isPending) return <Spinner />;
  if (id && existing.error) return <ErrorAlert error={existing.error} />;
  return <BillingForm existing={existing.data} />;
}

function BillingForm({ existing }: { existing?: BillingView }) {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const existingCustomer = useQuery({ queryKey: ['customer', existing?.customerId], queryFn: () => customerApi.get(existing!.customerId),
    enabled: !!existing });
  const taxCodes = useQuery({ queryKey: ['tax-codes'], queryFn: settingsApi.taxCodes });
  const chart = useChart();
  const costCenters = useCostCenters();
  const today = new Date().toISOString().slice(0, 10);
  const [form, setForm] = useState<Omit<BillingInput, 'lines'>>({
    kind: existing?.kind ?? 'INVOICE', customerId: existing?.customerId ?? '', relatedInvoiceId: existing?.relatedInvoiceId ?? null,
    documentDate: existing?.documentDate ?? today, dueDate: existing && existing.kind !== 'CREDIT_MEMO' ? existing.dueDate : null,
    description: existing?.description ?? '', vatCodeId: existing?.vatCodeId ?? null,
  });
  const [lines, setLines] = useState<BillingLineInput[]>(existing
    ? existing.lines.map((l) => ({ revenueAccountId: l.revenueAccountId, costCenterId: l.costCenterId, description: l.description, netAmount: l.netAmount }))
    : [emptyLine()]);
  const openItems = useQuery({ queryKey: ['open-items', form.customerId], enabled: !!form.customerId && form.kind !== 'INVOICE',
    queryFn: () => customerApi.openItems(form.customerId) });
  const revenue = postableAccounts(chart.data?.accounts).filter((a) => a.accountType === 'REVENUE');
  const vatCodes = (taxCodes.data ?? []).filter((t) => t.taxType === 'VAT_OUTPUT' && t.status === 'ACTIVE');
  const set = (i: number, patch: Partial<BillingLineInput>) => setLines(lines.map((l, j) => (j === i ? { ...l, ...patch } : l)));
  const save = useMutation({
    mutationFn: () => existing ? billingApi.update(existing.id, existing.version, { ...form, lines }) : billingApi.create({ ...form, lines }),
    onSuccess: (b) => { qc.setQueryData(['billing', b.id], b); qc.invalidateQueries({ queryKey: ['billings'] }); navigate(`/receivables/billings/${b.id}`); },
  });
  const memo = form.kind === 'CREDIT_MEMO';
  const valid = form.customerId && form.description && (!memo || form.relatedInvoiceId)
    && lines.every((l) => l.revenueAccountId && l.description && isValidAmount(l.netAmount) && l.netAmount);
  const submit = (e: FormEvent) => { e.preventDefault(); save.mutate(); };
  const select = 'block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm';
  return (
    <>
      <PageHeader title={existing ? `Edit ${(KIND_LABEL[existing.kind] ?? '').toLowerCase()}` : 'New billing document'}
        crumbs={[{ label: 'Receivables' }, { label: 'Billing', to: '/receivables/billings' }, { label: existing ? 'Edit' : 'New' }]} />
      <form onSubmit={submit} className="space-y-4">
        <ErrorAlert error={save.error} />
        <div className="grid gap-4 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5 md:grid-cols-3">
          <div className="space-y-1.5"><label htmlFor="kind" className="block text-sm font-medium text-slate-700">Kind</label>
            <select id="kind" value={form.kind} disabled={!!existing} className={select}
              onChange={(e) => setForm({ ...form, kind: e.target.value as BillingKind, relatedInvoiceId: null })}>
              {Object.entries(KIND_LABEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></div>
          <SearchSelect id="customer" label="Customer" value={form.customerId} disabled={!!existing} queryKey="customers" placeholder="Select…"
            search={(q) => customerApi.list(q, 0, 50)} optionLabel={(c) => `${c.code} — ${c.name}`} filter={(c) => c.status === 'ACTIVE'}
            selected={existingCustomer.data}
            onChange={(id) => setForm({ ...form, customerId: id, relatedInvoiceId: null })} />
          {form.kind !== 'INVOICE' && (
            <div className="space-y-1.5"><label htmlFor="related" className="block text-sm font-medium text-slate-700">
              {memo ? 'Invoice to reduce' : 'Related invoice (optional)'}</label>
              <select id="related" value={form.relatedInvoiceId ?? ''} className={select}
                onChange={(e) => setForm({ ...form, relatedInvoiceId: e.target.value || null })}>
                <option value="">{memo ? 'Select…' : 'None'}</option>
                {(openItems.data ?? []).map((o) => <option key={o.id} value={o.id}>{o.documentNumber} — open {o.openBalance}</option>)}</select></div>
          )}
          <Field label="Document date" type="date" value={form.documentDate} onChange={(e) => setForm({ ...form, documentDate: e.target.value })} />
          {!memo && <Field label="Due date" type="date" hint="Blank: document date plus the customer's payment terms" value={form.dueDate ?? ''}
            onChange={(e) => setForm({ ...form, dueDate: e.target.value || null })} />}
          {!memo && (
            <div className="space-y-1.5"><label htmlFor="vat" className="block text-sm font-medium text-slate-700">Output VAT</label>
              <select id="vat" value={form.vatCodeId ?? ''} className={select} onChange={(e) => setForm({ ...form, vatCodeId: e.target.value || null })}>
                <option value="">No VAT</option>
                {vatCodes.map((t) => <option key={t.id} value={t.id}>{t.code} ({t.rate})</option>)}</select></div>
          )}
          <div className="md:col-span-3"><Field label="Description" value={form.description} maxLength={1000}
            onChange={(e) => setForm({ ...form, description: e.target.value })} /></div>
        </div>
        {memo && <p className="text-xs text-slate-500">A credit memo uses the VAT treatment of the invoice it reduces and needs a justification attached before submission.</p>}
        <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs">
          <table className="min-w-full text-sm">
            <thead className="border-b border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500">
              <tr><th className="px-2 py-2">#</th><th className="px-2 py-2">Revenue account</th><th className="px-2 py-2">Cost centre</th>
                <th className="px-2 py-2">Description</th><th className="px-2 py-2 text-right">Net amount</th><th /></tr>
            </thead>
            <tbody>
              {lines.map((l, i) => (
                <tr key={i} className="border-t border-slate-100">
                  <td className="px-2 py-2 text-slate-500">{i + 1}</td>
                  <td className="px-2 py-2"><Combobox aria-label={`Line ${i + 1} account`} value={l.revenueAccountId} onChange={(v) => set(i, { revenueAccountId: v })}
                    className="w-64 rounded-md border border-slate-300 px-2 py-1" placeholder="Select…"
              options={revenue.map((a) => ({ value: a.id, label: `${a.code} — ${a.name}` }))} /></td>
                  <td className="px-2 py-2"><Combobox aria-label={`Line ${i + 1} cost centre`} value={l.costCenterId ?? ''} onChange={(v) => set(i, { costCenterId: v || null })}
                    className="w-36 rounded-md border border-slate-300 px-2 py-1" placeholder="—"
              options={(costCenters.data ?? []).map((c) => ({ value: c.id, label: `${c.code} — ${c.name}` }))} /></td>
                  <td className="px-2 py-2"><input aria-label={`Line ${i + 1} description`} value={l.description} onChange={(e) => set(i, { description: e.target.value })}
                    className="w-64 rounded-md border border-slate-300 px-2 py-1" /></td>
                  <td className="px-2 py-2"><input aria-label={`Line ${i + 1} net amount`} inputMode="decimal" value={l.netAmount}
                    onChange={(e) => set(i, { netAmount: e.target.value.replace(/[^\d.]/g, '') })} aria-invalid={!isValidAmount(l.netAmount) || undefined}
                    className={`w-32 rounded border px-2 py-1 text-right ${isValidAmount(l.netAmount) ? 'border-slate-300' : 'border-red-500'}`} /></td>
                  <td className="px-2 py-2"><button type="button" disabled={lines.length <= 1} className="text-sm text-red-700 disabled:text-slate-300"
                    onClick={() => setLines(lines.filter((_, j) => j !== i))}>Remove</button></td>
                </tr>
              ))}
            </tbody>
            <tfoot className="border-t-2 border-slate-300 font-medium"><tr><td colSpan={4} className="px-2 py-2 text-right">Net total</td>
              <td className="px-2 py-2 text-right tabular-nums">{fromCents(sumCents(lines.map((l) => l.netAmount)))}</td><td /></tr></tfoot>
          </table>
        </div>
        <button type="button" className="text-sm text-brand-700 hover:underline" onClick={() => setLines([...lines, emptyLine()])}>+ Add line</button>
        <p className="text-xs text-slate-500">Output VAT is computed by the server from the configured tax code when you save.</p>
        <div className="flex gap-2">
          <Button type="submit" busy={save.isPending} disabled={!valid}>Save draft</Button>
          <Button variant="secondary" onClick={() => navigate(-1)}>Cancel</Button>
        </div>
      </form>
    </>
  );
}
