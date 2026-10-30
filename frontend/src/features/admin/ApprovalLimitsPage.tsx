import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Select } from '../../components/Select';
import { useState } from 'react';
import { governanceApi, roleApi } from '../../api/endpoints';
import { useAuth } from '../../auth/AuthContext';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Field } from '../../components/Field';
import { Money } from '../../components/Money';
import { PageHeader } from '../../components/PageHeader';
import { Spinner } from '../../components/Spinner';
import { formatDateTime, humanize } from '../../utils/format';
import { isValidAmount } from '../../utils/money';

/** Per-role approval limits by document type (C-24). */
export function ApprovalLimitsPage() {
  const { hasPermission } = useAuth();
  const manage = hasPermission('APPROVAL_LIMIT_MANAGE');
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['approval-limits'], queryFn: governanceApi.limits });
  const roles = useQuery({ queryKey: ['roles'], queryFn: roleApi.list, enabled: manage });
  const [form, setForm] = useState({ roleCode: '', documentType: '', maxAmount: '' });
  const refresh = () => qc.invalidateQueries({ queryKey: ['approval-limits'] });
  const save = useMutation({ mutationFn: () => governanceApi.setLimit(form.roleCode, form.documentType, form.maxAmount.trim()),
    onSuccess: () => { setForm({ ...form, maxAmount: '' }); void refresh(); } });
  const remove = useMutation({ mutationFn: ({ roleCode, documentType }: { roleCode: string; documentType: string }) =>
    governanceApi.removeLimit(roleCode, documentType), onSuccess: refresh });
  const sel = 'block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm';
  return (
    <>
      <PageHeader title="Approval limits" crumbs={[{ label: 'Administration' }, { label: 'Approval limits' }]} />
      <p className="mb-4 text-sm text-slate-600">The most a holder of a role may approve on one document of a type. A user's limit is the
        highest among their roles that have one; users none of whose roles has a limit are not limited. The amounts are AVTECH's decision (C-24);
        none are set by default. Every change is recorded in the audit trail.</p>
      {manage && (
        <section className="mb-4 grid gap-3 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5 md:grid-cols-4 md:items-end">
          <label className="text-sm font-medium text-slate-700">Role
            <Select value={form.roleCode} onChange={(e) => setForm({ ...form, roleCode: e.target.value })} className={sel}>
              <option value="">Select…</option>{(roles.data ?? []).map((r) => <option key={r.code} value={r.code}>{r.name}</option>)}</Select></label>
          <label className="text-sm font-medium text-slate-700">Document type
            <Select value={form.documentType} onChange={(e) => setForm({ ...form, documentType: e.target.value })} className={sel}>
              <option value="">Select…</option>{(q.data?.documentTypes ?? []).map((t) => <option key={t} value={t}>{humanize(t)}</option>)}</Select></label>
          <Field label="Maximum amount (₱)" inputMode="decimal" value={form.maxAmount} placeholder="0.00"
            onChange={(e) => setForm({ ...form, maxAmount: e.target.value.replace(/[^\d.]/g, '') })} />
          <Button busy={save.isPending} disabled={!form.roleCode || !form.documentType || !form.maxAmount || !isValidAmount(form.maxAmount)}
            onClick={() => save.mutate()}>Save limit</Button>
          <div className="md:col-span-4"><ErrorAlert error={save.error ?? remove.error} /></div>
        </section>
      )}
      <ErrorAlert error={q.error} />
      {q.isPending ? <Spinner /> : (
        <div className="overflow-x-auto rounded-xl border border-slate-200/80 bg-white shadow-xs"><table className="min-w-full text-sm">
          <thead className="border-b border-slate-200 bg-slate-50/80 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"><tr><th className="px-3 py-2">Role</th><th className="px-3 py-2">Document type</th>
            <th className="px-3 py-2 text-right">Maximum</th><th className="px-3 py-2">Last changed</th><th /></tr></thead>
          <tbody>{(q.data?.limits ?? []).map((l) => (
            <tr key={l.roleCode + l.documentType} className="border-t border-slate-100"><td className="px-3 py-2">{humanize(l.roleCode)}</td>
              <td className="px-3 py-2">{humanize(l.documentType)}</td><td className="px-3 py-2 text-right"><Money value={l.maxAmount} /></td>
              <td className="px-3 py-2">{l.updatedBy}, {formatDateTime(l.updatedAt)}</td>
              <td className="px-3 py-2 text-right">{manage && <button className="text-red-700 hover:underline" disabled={remove.isPending}
                onClick={() => { if (window.confirm(`Remove the ${humanize(l.roleCode)} limit for ${humanize(l.documentType)}?`)) remove.mutate(l); }}>Remove</button>}</td></tr>))}
            {q.data?.limits.length === 0 && <tr><td colSpan={5} className="px-4 py-6 text-center text-slate-500">No approval limits are set: approvers are not limited by amount.</td></tr>}</tbody>
        </table></div>
      )}
    </>
  );
}
