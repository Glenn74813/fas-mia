import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Select } from '../../components/Select';
import { useState } from 'react';
import { orgApi } from '../../api/endpoints';
import type { OfficeAssignment, OrgDepartment, OrgOffice } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Field } from '../../components/Field';
import { PageHeader } from '../../components/PageHeader';
import { Spinner } from '../../components/Spinner';
import { activeCostCenters, useCostCenters } from '../../hooks/useAccounting';

const sel = 'rounded-md border border-slate-300 px-2 py-1 text-sm';

/** Organisations, offices and departments (FR-ORG-01). MIAA's structure is entered here; none is seeded. */
export function OrganizationPage() {
  const { hasPermission } = useAuth();
  const admin = hasPermission('ORGANIZATION_ADMINISTER');
  const qc = useQueryClient();
  const tree = useQuery({ queryKey: ['organizations'], queryFn: orgApi.tree });
  const costCenters = useCostCenters();
  const refresh = () => qc.invalidateQueries({ queryKey: ['organizations'] });
  const [office, setOffice] = useState({ code: '', name: '' });
  const [dept, setDept] = useState<Record<string, { code: string; name: string; costCenterId: string }>>({});
  const addOffice = useMutation({ mutationFn: (orgId: string) => orgApi.createOffice(orgId, office.code.trim(), office.name.trim()),
    onSuccess: () => { setOffice({ code: '', name: '' }); void refresh(); } });
  const addDept = useMutation({ mutationFn: (o: OrgOffice) => {
    const d = dept[o.id]!;
    return orgApi.createDepartment(o.id, d.code.trim(), d.name.trim(), d.costCenterId || null);
  }, onSuccess: (_, o) => { setDept({ ...dept, [o.id]: { code: '', name: '', costCenterId: '' } }); void refresh(); } });
  const toggleOffice = useMutation({ mutationFn: (o: OrgOffice) => orgApi.updateOffice(o, o.name, o.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'),
    onSuccess: refresh });
  const toggleDept = useMutation({ mutationFn: (d: OrgDepartment) =>
    orgApi.updateDepartment(d, d.name, d.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE', d.costCenterId), onSuccess: refresh });
  const error = tree.error ?? addOffice.error ?? addDept.error ?? toggleOffice.error ?? toggleDept.error;
  return (
    <>
      <PageHeader title="Organisation" crumbs={[{ label: 'Administration' }, { label: 'Organisation' }]} />
      <p className="mb-4 text-sm text-slate-600">Offices and departments, and the cost centre a department's work is usually charged to.
        Codes cannot change once created. A unit with active units or assigned users under it cannot be deactivated.</p>
      <ErrorAlert error={error} />
      {tree.isPending ? <Spinner /> : (tree.data ?? []).map((org) => (
        <section key={org.id} className="mb-6 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5" aria-label={org.name}>
          <h2 className="font-semibold">{org.name} <span className="font-mono text-sm text-slate-500">{org.code}</span></h2>
          <ul className="mt-3 space-y-3">
            {org.offices.map((o) => (
              <li key={o.id} className="rounded-md border border-slate-200 p-3">
                <div className="flex flex-wrap items-center gap-2 text-sm">
                  <span className="font-mono">{o.code}</span><span className="font-medium">{o.name}</span>
                  <Badge tone={o.status === 'ACTIVE' ? 'green' : 'slate'}>{o.status}</Badge>
                  <span className="text-xs text-slate-500">{o.currentUsers} assigned</span>
                  {admin && <button className="text-xs text-brand-700 hover:underline" onClick={() => toggleOffice.mutate(o)}>
                    {o.status === 'ACTIVE' ? 'Deactivate' : 'Reactivate'}</button>}
                </div>
                <ul className="ml-4 mt-2 space-y-1 text-sm">
                  {o.departments.map((d) => (
                    <li key={d.id} className="flex flex-wrap items-center gap-2">
                      <span className="font-mono">{d.code}</span>{d.name}
                      {d.costCenterCode && <span className="text-xs text-slate-500">cost centre {d.costCenterCode}</span>}
                      <Badge tone={d.status === 'ACTIVE' ? 'green' : 'slate'}>{d.status}</Badge>
                      <span className="text-xs text-slate-500">{d.currentUsers} assigned</span>
                      {admin && <button className="text-xs text-brand-700 hover:underline" onClick={() => toggleDept.mutate(d)}>
                        {d.status === 'ACTIVE' ? 'Deactivate' : 'Reactivate'}</button>}
                    </li>
                  ))}
                  {o.departments.length === 0 && <li className="text-xs text-slate-500">No departments.</li>}
                </ul>
                {admin && o.status === 'ACTIVE' && (
                  <div className="ml-4 mt-2 flex flex-wrap items-end gap-2">
                    <input aria-label={`New department code for ${o.code}`} placeholder="Code" value={dept[o.id]?.code ?? ''} className={`${sel} w-28`}
                      onChange={(e) => setDept({ ...dept, [o.id]: { ...(dept[o.id] ?? { name: '', costCenterId: '' }), code: e.target.value.toUpperCase() } })} />
                    <input aria-label={`New department name for ${o.code}`} placeholder="Department name" value={dept[o.id]?.name ?? ''} className={`${sel} w-64`}
                      onChange={(e) => setDept({ ...dept, [o.id]: { ...(dept[o.id] ?? { code: '', costCenterId: '' }), name: e.target.value } })} />
                    <Select aria-label={`Cost centre for the new department of ${o.code}`} value={dept[o.id]?.costCenterId ?? ''} className={sel}
                      onChange={(e) => setDept({ ...dept, [o.id]: { ...(dept[o.id] ?? { code: '', name: '' }), costCenterId: e.target.value } })}>
                      <option value="">No cost centre</option>{activeCostCenters(costCenters.data).map((c) => <option key={c.id} value={c.id}>{c.code}</option>)}</Select>
                    <button className="text-sm text-brand-700 hover:underline disabled:text-slate-400" disabled={!dept[o.id]?.code || !dept[o.id]?.name}
                      onClick={() => addDept.mutate(o)}>Add department</button>
                  </div>
                )}
              </li>
            ))}
            {org.offices.length === 0 && <li className="text-sm text-slate-500">No offices yet.</li>}
          </ul>
          {admin && org.status === 'ACTIVE' && (
            <div className="mt-4 flex flex-wrap items-end gap-3">
              <Field label="Office code" value={office.code} maxLength={20} onChange={(e) => setOffice({ ...office, code: e.target.value.toUpperCase() })} />
              <Field label="Office name" value={office.name} maxLength={200} onChange={(e) => setOffice({ ...office, name: e.target.value })} />
              <Button variant="secondary" busy={addOffice.isPending} disabled={!office.code.trim() || !office.name.trim()}
                onClick={() => addOffice.mutate(org.id)}>Add office</Button>
            </div>
          )}
        </section>
      ))}
    </>
  );
}

/** A user's office assignment history, with transfer and end for organisation administrators. */
export function OfficeAssignmentsPanel({ userId, internal }: { userId: string; internal: boolean }) {
  const { hasPermission } = useAuth();
  const admin = hasPermission('ORGANIZATION_ADMINISTER') && internal;
  const qc = useQueryClient();
  const list = useQuery({ queryKey: ['office-assignments', userId], queryFn: () => orgApi.assignments(userId) });
  const tree = useQuery({ queryKey: ['organizations'], queryFn: orgApi.tree, enabled: admin });
  const offices = (tree.data ?? []).flatMap((o) => o.offices).filter((o) => o.status === 'ACTIVE');
  const today = new Date().toISOString().slice(0, 10);
  const [form, setForm] = useState({ officeId: '', departmentId: '', from: today });
  const [endOn, setEndOn] = useState(today);
  const set = (data: OfficeAssignment[]) => qc.setQueryData(['office-assignments', userId], data);
  const assign = useMutation({ mutationFn: () => orgApi.assign(userId, form.officeId, form.departmentId || null, form.from), onSuccess: set });
  const end = useMutation({ mutationFn: (a: OfficeAssignment) => orgApi.end(a.id, endOn), onSuccess: set });
  const depts = offices.find((o) => o.id === form.officeId)?.departments.filter((d) => d.status === 'ACTIVE') ?? [];
  const current = (list.data ?? []).find((a) => !a.effectiveTo);
  return (
    <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5" aria-label="Office assignment">
      <h2 className="mb-2 font-semibold">Office assignment</h2>
      <ErrorAlert error={list.error ?? assign.error ?? end.error} />
      <p className="text-sm">{current ? <>Currently <strong>{current.officeName}</strong>{current.departmentName ? ` · ${current.departmentName}` : ''} since {current.effectiveFrom}</>
        : 'Not assigned to an office.'}</p>
      {(list.data ?? []).length > 1 && (
        <ul className="mt-2 text-xs text-slate-600">{list.data!.filter((a) => a.effectiveTo).map((a) => (
          <li key={a.id}>{a.officeCode}{a.departmentCode ? `/${a.departmentCode}` : ''}: {a.effectiveFrom} to {a.effectiveTo}</li>))}</ul>
      )}
      {admin && (
        <div className="mt-3 flex flex-wrap items-end gap-2 text-sm">
          <label>Office <Select value={form.officeId} onChange={(e) => setForm({ ...form, officeId: e.target.value, departmentId: '' })} className={sel}>
            <option value="">Select…</option>{offices.map((o) => <option key={o.id} value={o.id}>{o.code} {o.name}</option>)}</Select></label>
          <label>Department <Select value={form.departmentId} onChange={(e) => setForm({ ...form, departmentId: e.target.value })} className={sel}
            disabled={!form.officeId}><option value="">None</option>{depts.map((d) => <option key={d.id} value={d.id}>{d.code} {d.name}</option>)}</Select></label>
          <label>From <input type="date" value={form.from} onChange={(e) => setForm({ ...form, from: e.target.value })} className={sel} /></label>
          <button className="inline-flex h-9 items-center text-brand-700 hover:underline disabled:text-slate-400" disabled={!form.officeId || assign.isPending}
            onClick={() => assign.mutate()}>{current ? 'Transfer' : 'Assign'}</button>
          {current && <>
            <label className="ml-4">End on <input type="date" value={endOn} onChange={(e) => setEndOn(e.target.value)} className={sel} /></label>
            <button className="inline-flex h-9 items-center text-red-700 hover:underline" disabled={end.isPending} onClick={() => end.mutate(current)}>End assignment</button></>}
        </div>
      )}
    </section>
  );
}
