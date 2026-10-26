import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useParams } from 'react-router';
import { supplierApi, supplierBankApi } from '../../api/endpoints';
import { useAuth } from '../../auth/AuthContext';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Field } from '../../components/Field';
import { PageHeader } from '../../components/PageHeader';
import { StatusBadge } from '../../components/StatusBadge';
import { formatDateTime } from '../../utils/format';

export function SupplierBankPage() {
  const { id = '' } = useParams();
  const { hasPermission } = useAuth();
  const qc = useQueryClient();
  const supplier = useQuery({ queryKey: ['supplier', id], queryFn: () => supplierApi.get(id) });
  const list = useQuery({ queryKey: ['supplier-bank', id], queryFn: () => supplierBankApi.list(id) });
  const [form, setForm] = useState({ bankName: '', branch: '', accountName: '', accountNumber: '' });
  const [note, setNote] = useState('');
  const refresh = () => qc.invalidateQueries({ queryKey: ['supplier-bank', id] });
  const add = useMutation({ mutationFn: () => supplierBankApi.add(id, { ...form, branch: form.branch || null }),
    onSuccess: () => { setForm({ bankName: '', branch: '', accountName: '', accountNumber: '' }); refresh(); } });
  const verify = useMutation({ mutationFn: ({ acct, approve }: { acct: string; approve: boolean }) => supplierBankApi.verify(acct, approve, note),
    onSuccess: () => { setNote(''); refresh(); } });
  const s = supplier.data;
  return (
    <>
      <PageHeader title={`Bank details — ${s ? `${s.code} ${s.name}` : ''}`} crumbs={[{ label: 'Payables' }, { label: 'Suppliers', to: '/payables/suppliers' }, { label: 'Bank details' }]} />
      <p className="mb-4 text-sm text-slate-600">Used for ADA payment files. New or changed details withdraw the previous account at once and cannot be paid until a
        second person verifies them (for example by calling the supplier on a number already on file). Only the last four digits are ever shown.</p>
      <ErrorAlert error={list.error ?? verify.error} />
      <div className="mb-4 overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs"><table className="min-w-full text-sm">
        <thead className="border-b border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="px-3 py-2">Bank</th><th className="px-3 py-2">Account</th>
          <th className="px-3 py-2">Status</th><th className="px-3 py-2">Entered</th><th className="px-3 py-2">Verified</th><th /></tr></thead>
        <tbody>{(list.data ?? []).map((a) => (
          <tr key={a.id} className="border-t border-slate-100"><td className="px-3 py-2">{a.bankName}{a.branch ? ` — ${a.branch}` : ''}</td>
            <td className="px-3 py-2">{a.accountName} {a.accountNumberMasked}</td><td className="px-3 py-2"><StatusBadge status={a.status} /></td>
            <td className="px-3 py-2">{a.enteredBy}, {formatDateTime(a.enteredAt)}</td>
            <td className="px-3 py-2">{a.verifiedBy ? `${a.verifiedBy}, ${formatDateTime(a.verifiedAt)} — ${a.verificationNote}` : '—'}</td>
            <td className="px-3 py-2 text-right whitespace-nowrap">{a.availableActions.includes('VERIFY') && <>
              <input aria-label="Verification note" placeholder="How verified" value={note} onChange={(e) => setNote(e.target.value)} className="w-48 rounded-md border border-slate-300 px-2 py-1" />
              <button className="ml-2 text-xs text-brand-700" disabled={note.trim().length < 5} onClick={() => verify.mutate({ acct: a.id, approve: true })}>Verify</button>
              <button className="ml-2 text-xs text-red-700" disabled={note.trim().length < 5} onClick={() => verify.mutate({ acct: a.id, approve: false })}>Reject</button></>}</td></tr>))}
          {list.data?.length === 0 && <tr><td colSpan={6} className="px-4 py-6 text-center text-slate-500">No bank details on file.</td></tr>}</tbody>
      </table></div>
      {hasPermission('SUPPLIER_MANAGE') && (
        <section className="space-y-3 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
          <h2 className="font-semibold">Enter new bank details</h2>
          <ErrorAlert error={add.error} />
          <div className="grid gap-3 md:grid-cols-4">
            <Field label="Bank" value={form.bankName} onChange={(e) => setForm({ ...form, bankName: e.target.value })} />
            <Field label="Branch" value={form.branch} onChange={(e) => setForm({ ...form, branch: e.target.value })} />
            <Field label="Account name" value={form.accountName} onChange={(e) => setForm({ ...form, accountName: e.target.value })} />
            <Field label="Account number" autoComplete="off" value={form.accountNumber} onChange={(e) => setForm({ ...form, accountNumber: e.target.value })} />
          </div>
          <Button variant="secondary" busy={add.isPending} disabled={!form.bankName || !form.accountName || !/^[0-9 -]{6,30}$/.test(form.accountNumber)}
            onClick={() => add.mutate()}>Save for verification</Button>
        </section>
      )}
    </>
  );
}
