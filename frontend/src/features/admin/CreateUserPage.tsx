import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router';
import { userApi } from '../../api/endpoints';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Field } from '../../components/Field';
import { PageHeader } from '../../components/PageHeader';
import { endOfManilaDay } from '../../utils/format';
import { RolePicker } from './RolePicker';

export function CreateUserPage() {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [form, setForm] = useState({ username: '', email: '', displayName: '', userType: 'INTERNAL', temporaryPassword: '', accessUntil: '' });
  const auditor = form.userType === 'EXTERNAL_AUDITOR';
  const [roleCodes, setRoleCodes] = useState<string[]>([]);
  const create = useMutation({
    mutationFn: () => {
      const { accessUntil, ...rest } = form;
      return userApi.create({ ...rest, roleCodes, accessExpiresAt: accessUntil ? endOfManilaDay(accessUntil) : null });
    },
    onSuccess: (res) => {
      qc.invalidateQueries({ queryKey: ['users'] });
      navigate(`/admin/users/${res.user.id}`, { state: { warnings: res.warnings, pendingGrant: res.pendingGrant } });
    },
  });
  const set = (k: keyof typeof form) => (e: { target: { value: string } }) => setForm({ ...form, [k]: e.target.value });

  const submit = (e: FormEvent) => {
    e.preventDefault();
    create.mutate();
  };

  return (
    <>
      <PageHeader title="New user" crumbs={[{ label: 'Administration' }, { label: 'Users', to: '/admin/users' }, { label: 'New' }]} />
      <form onSubmit={submit} className="max-w-2xl space-y-4 rounded-xl border border-slate-200/80 bg-white shadow-xs p-6">
        <ErrorAlert error={create.error} />
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Username" required value={form.username} onChange={set('username')}
            hint="Lower-case letters, digits, '.', '_' or '-'" />
          <Field label="E-mail" type="email" required value={form.email} onChange={set('email')} />
          <Field label="Display name" required value={form.displayName} onChange={set('displayName')} />
          <div className="space-y-1">
            <label htmlFor="userType" className="block text-sm font-medium text-slate-700">User type</label>
            <select id="userType" value={form.userType} onChange={set('userType')}
              className="block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm">
              <option value="INTERNAL">Internal</option>
              <option value="EXTERNAL_AUDITOR">External auditor</option>
              <option value="CONCESSIONAIRE">Concessionaire</option>
            </select>
          </div>
          <Field label="Access expires" type="date" required={auditor} value={form.accessUntil} onChange={set('accessUntil')}
            min={new Date().toISOString().slice(0, 10)}
            hint={auditor ? 'Required for external auditors: access ends at 23:59 that day (Philippine time).'
              : 'Optional, for temporary staff: access ends at 23:59 that day (Philippine time).'} />
        </div>
        <Field label="Temporary password" type="password" autoComplete="new-password" required value={form.temporaryPassword}
          onChange={set('temporaryPassword')} hint="Give it to the user through a separate channel; they must change it at first sign-in." />
        <RolePicker value={roleCodes} onChange={setRoleCodes} />
        <Button type="submit" busy={create.isPending}>Create user</Button>
      </form>
    </>
  );
}
