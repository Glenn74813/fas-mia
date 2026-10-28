import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Select } from '../../components/Select';
import { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { dvApi, invoiceApi, supplierApi } from '../../api/endpoints';
import { useAuth } from '../../auth/AuthContext';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Field } from '../../components/Field';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Pagination } from '../../components/Pagination';
import { SortHeader } from '../../components/SortHeader';
import { SearchSelect } from '../../components/SearchSelect';
import { Spinner } from '../../components/Spinner';
import { StatusBadge } from '../../components/StatusBadge';
import { fromCents, isValidAmount, sumCents, toCents } from '../../utils/money';

export function DisbursementVouchersPage() {
  const { hasPermission } = useAuth();
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(0);
  const [sort, setSort] = useState('');
  const onSort = (next: string) => { setSort(next); setPage(0); };
  const list = useQuery({ queryKey: ['dvs', status, page, sort], queryFn: () => dvApi.list({ status, page, size: 25, sort: sort || undefined }),
    placeholderData: keepPreviousData });
  return (
    <>
      <PageHeader title="Disbursement vouchers" crumbs={[{ label: 'Payables' }, { label: 'Disbursement vouchers' }]}
        actions={hasPermission('DISBURSEMENT_PREPARE') && (
          <Link to="/payables/disbursements/new" className="rounded-md bg-brand-700 px-4 py-2 text-sm font-medium text-white hover:bg-brand-900">New DV</Link>
        )} />
      <Select aria-label="Status" value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }}
        className="mb-4 rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm">
        {['', 'DRAFT', 'RETURNED', 'SUBMITTED', 'APPROVED', 'PAYMENT_INSTRUCTED', 'PAID', 'POSTED', 'REJECTED', 'CANCELLED'].map((s) =>
          <option key={s} value={s}>{s ? s.replace('_', ' ') : 'All statuses'}</option>)}
      </Select>
      <ErrorAlert error={list.error} />
      {list.isPending ? <Spinner /> : list.data && (
        <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs">
          <table className="min-w-full text-sm">
            <thead className="border-b border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500">
              <tr><SortHeader label="DV" field="dvNumber" sort={sort} onSort={onSort} /><th className="px-3 py-2">Payee</th><th className="px-3 py-2">Particulars</th>
                <th className="px-3 py-2">Status</th><SortHeader label="Amount" field="amount" sort={sort} onSort={onSort} descFirst align="right" /></tr>
            </thead>
            <tbody>
              {list.data.items.map((d) => (
                <tr key={d.id} className="border-t border-slate-100">
                  <td className="whitespace-nowrap px-3 py-2"><Link className="font-mono text-brand-700 hover:underline" to={`/payables/disbursements/${d.id}`}>
                    {d.dvNumber ?? `Draft ${d.id.slice(0, 8)}`}</Link></td>
                  <td className="px-3 py-2">{d.supplierName}</td><td className="max-w-md truncate px-3 py-2">{d.particulars}</td>
                  <td className="space-x-1 px-3 py-2"><StatusBadge status={d.status} />
                    {d.currentStep && <span className="text-xs text-slate-500">awaiting {d.currentStep.toLowerCase()}</span>}</td>
                  <td className="px-3 py-2 text-right"><Money value={d.amount} /></td>
                </tr>
              ))}
              {list.data.items.length === 0 && <tr><td colSpan={5} className="px-4 py-6 text-center text-slate-500">No vouchers.</td></tr>}
            </tbody>
          </table>
        </div>
      )}
      {list.data && <Pagination page={list.data.page} totalPages={list.data.totalPages} totalItems={list.data.totalItems} onPage={setPage} />}
    </>
  );
}

/** New DV: choose a supplier, then allocate amounts to its posted invoices with open balances. */
export function NewDisbursementPage() {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [supplierId, setSupplierId] = useState('');
  const [particulars, setParticulars] = useState('');
  const [amounts, setAmounts] = useState<Record<string, string>>({});
  const open = useQuery({ queryKey: ['invoices', 'open', supplierId], enabled: !!supplierId,
    queryFn: () => invoiceApi.list({ status: 'POSTED', supplierId, page: 0, size: 200 }) });
  const invoices = (open.data?.items ?? []).filter((i) => (toCents(i.openBalance) ?? 0n) > 0n);
  const chosen = Object.entries(amounts).filter(([, v]) => v.trim() !== '');
  const create = useMutation({
    mutationFn: () => dvApi.create({ supplierId, particulars, allocations: chosen.map(([invoiceId, amount]) => ({ invoiceId, amount })) }),
    onSuccess: (dv) => { qc.invalidateQueries({ queryKey: ['dvs'] }); navigate(`/payables/disbursements/${dv.id}`); },
  });
  const valid = supplierId && particulars.trim() && chosen.length > 0 && chosen.every(([, v]) => isValidAmount(v));
  return (
    <>
      <PageHeader title="New disbursement voucher" crumbs={[{ label: 'Payables' }, { label: 'Disbursement vouchers', to: '/payables/disbursements' }, { label: 'New' }]} />
      <div className="space-y-4">
        <ErrorAlert error={create.error} />
        <div className="grid gap-4 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5 md:grid-cols-2">
          <SearchSelect id="payee" label="Payee (supplier)" value={supplierId} queryKey="suppliers" placeholder="Select…"
            search={(q) => supplierApi.list(q, 0, 50)} optionLabel={(s) => `${s.code} — ${s.name}`} filter={(s) => s.status === 'ACTIVE'}
            onChange={(id) => { setSupplierId(id); setAmounts({}); }} />
          <Field label="Particulars" value={particulars} maxLength={1000} onChange={(e) => setParticulars(e.target.value)} />
        </div>
        {supplierId && (
          <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs">
            <table className="min-w-full text-sm">
              <thead className="border-b border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="px-3 py-2">Invoice</th>
                <th className="px-3 py-2">Due</th><th className="px-3 py-2 text-right">Open balance</th><th className="px-3 py-2 text-right">Pay now</th></tr></thead>
              <tbody>
                {invoices.map((i) => (
                  <tr key={i.id} className="border-t border-slate-100"><td className="px-3 py-2">{i.invoiceNumber}</td><td className="px-3 py-2">{i.dueDate}</td>
                    <td className="px-3 py-2 text-right"><Money value={i.openBalance} /></td>
                    <td className="px-3 py-2 text-right"><input aria-label={`Pay ${i.invoiceNumber}`} inputMode="decimal" value={amounts[i.id] ?? ''}
                      placeholder="0.00" onChange={(e) => setAmounts({ ...amounts, [i.id]: e.target.value.replace(/[^\d.]/g, '') })}
                      className="w-32 rounded-md border border-slate-300 px-2 py-1 text-right" />
                      <button type="button" className="ml-2 text-xs text-brand-700" onClick={() => setAmounts({ ...amounts, [i.id]: i.openBalance })}>full</button></td></tr>
                ))}
                {invoices.length === 0 && <tr><td colSpan={4} className="px-4 py-6 text-center text-slate-500">No posted invoices with an open balance.</td></tr>}
              </tbody>
              <tfoot className="border-t-2 border-slate-300 font-medium"><tr><td colSpan={3} className="px-3 py-2 text-right">DV amount</td>
                <td className="px-3 py-2 text-right tabular-nums">{fromCents(sumCents(chosen.map(([, v]) => v)))}</td></tr></tfoot>
            </table>
          </div>
        )}
        <Button busy={create.isPending} disabled={!valid} onClick={() => create.mutate()}>Save draft</Button>
      </div>
    </>
  );
}
