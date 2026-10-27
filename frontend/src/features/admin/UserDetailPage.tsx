import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useLocation, useParams } from 'react-router';
import { userApi } from '../../api/endpoints';
import type { RoleGrant, SodConflict, UserDetail } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { ConfirmButton } from '../../components/ConfirmButton';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Field } from '../../components/Field';
import { PageHeader } from '../../components/PageHeader';
import { Spinner } from '../../components/Spinner';
import { endOfManilaDay, formatDateTime, humanize, manilaDate } from '../../utils/format';
import { OfficeAssignmentsPanel } from './OrganizationPage';
import { RolePicker } from './RolePicker';

export function UserDetailPage() {
  const { id = '' } = useParams();
  const location = useLocation();
  const { user: me, hasPermission } = useAuth();
  const qc = useQueryClient();
  const query = useQuery({ queryKey: ['user', id], queryFn: () => userApi.get(id) });
  // Unsaved role edits; null means "show what the server has".
  const [roleDraft, setRoleDraft] = useState<string[] | null>(null);
  const [warnings, setWarnings] = useState<SodConflict[]>(
    (location.state as { warnings?: SodConflict[] } | null)?.warnings ?? []);
  const [notice, setNotice] = useState<string | null>(() => {
    const grant = (location.state as { pendingGrant?: RoleGrant | null } | null)?.pendingGrant;
    return grant ? `User created. ${grant.financialAdded.join(', ')} will apply once a second person approves the grant.` : null;
  });
  const [error, setError] = useState<unknown>(null);
  const [tempPassword, setTempPassword] = useState('');
  const canAdmin = hasPermission('USER_ADMINISTER');
  const isSelf = me?.id === id;

  const apply = (u: UserDetail, message: string) => {
    qc.setQueryData(['user', id], u);
    qc.invalidateQueries({ queryKey: ['users'] });
    setNotice(message);
    setError(null);
  };
  const run = async (fn: () => Promise<unknown>) => {
    setNotice(null);
    try {
      await fn();
    } catch (err) {
      setError(err);
      qc.invalidateQueries({ queryKey: ['user', id] });
    }
  };

  const saveRoles = useMutation({
    mutationFn: () => userApi.setRoles(id, query.data!.version, roleDraft ?? query.data!.roles),
    onSuccess: (res) => {
      setRoleDraft(null);
      apply(res.user, res.pendingGrant
        ? `${res.pendingGrant.financialAdded.join(', ')} carries financial permissions: the change waits for a second person's approval (Access grants).`
        : 'Roles updated.');
      setWarnings(res.warnings);
    },
    onError: setError,
  });

  if (query.isPending) return <Spinner />;
  if (query.error) return <ErrorAlert error={query.error} />;
  const u = query.data;

  return (
    <>
      <PageHeader title={u.displayName} crumbs={[{ label: 'Administration' }, { label: 'Users', to: '/admin/users' }, { label: u.username }]} />
      <div className="space-y-4">
        {notice && <p role="status" className="rounded-md border border-emerald-300 bg-emerald-50 p-3 text-sm">{notice}</p>}
        <ErrorAlert error={error} />
        {warnings.length > 0 && (
          <div className="rounded-md border border-amber-300 bg-amber-50 p-3 text-sm">
            <p className="font-medium">Segregation-of-duties warnings (allowed, but review):</p>
            <ul className="list-disc pl-5">{warnings.map((w) => <li key={w.permissionA + w.permissionB}>{w.permissionA} + {w.permissionB}: {w.rationale}</li>)}</ul>
          </div>
        )}
        <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
          <div className="mb-3 flex flex-wrap gap-2">
            <Badge tone={u.status === 'ACTIVE' ? 'green' : 'slate'}>{u.status}</Badge>
            {u.locked && <Badge tone="red">LOCKED</Badge>}
            {u.mustChangePassword && <Badge tone="amber">PASSWORD CHANGE PENDING</Badge>}
            <Badge tone={u.mfaEnrolled ? 'green' : 'amber'}>{u.mfaEnrolled ? 'MFA ENROLLED' : 'MFA NOT ENROLLED'}</Badge>
          </div>
          <dl className="grid gap-2 text-sm sm:grid-cols-4">
            <dt className="text-slate-500">Username</dt><dd>{u.username}</dd>
            <dt className="text-slate-500">E-mail</dt><dd>{u.email}</dd>
            <dt className="text-slate-500">Type</dt><dd>{humanize(u.userType)}</dd>
            <dt className="text-slate-500">Active sessions</dt><dd>{u.activeSessions}</dd>
            <dt className="text-slate-500">Last sign-in</dt><dd>{formatDateTime(u.lastLoginAt)}</dd>
            <dt className="text-slate-500">Access expires</dt><dd>{formatDateTime(u.accessExpiresAt)}</dd>
            <dt className="text-slate-500">Created</dt><dd>{formatDateTime(u.createdAt)} by {u.createdBy}</dd>
            <dt className="text-slate-500">Version</dt><dd>{u.version}</dd>
          </dl>
        </section>

        {canAdmin && !isSelf && (
          <section className="flex flex-wrap gap-2 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5" aria-label="Account actions">
            {u.status === 'ACTIVE' ? (
              <ConfirmButton label="Deactivate" title="Deactivate this user?" onConfirm={() => run(async () =>
                apply(await userApi.deactivate(id, u.version), 'User deactivated and signed out.'))}>
                The user will be signed out everywhere and cannot sign in until reactivated.
              </ConfirmButton>
            ) : (
              <ConfirmButton label="Activate" variant="primary" title="Activate this user?" onConfirm={() => run(async () =>
                apply(await userApi.activate(id, u.version), 'User activated.'))}>
                The user will be able to sign in again.
              </ConfirmButton>
            )}
            {u.locked && <Button variant="secondary" onClick={() => run(async () => apply(await userApi.unlock(id), 'Account unlocked.'))}>Unlock</Button>}
            {hasPermission('SESSION_REVOKE') && (
              <ConfirmButton label="Revoke sessions" title="Sign this user out everywhere?" onConfirm={() => run(async () => {
                const r = await userApi.revokeSessions(id);
                qc.invalidateQueries({ queryKey: ['user', id] });
                setNotice(`${r.sessionsRevoked} session(s) revoked.`);
              })}>All active sessions end immediately.</ConfirmButton>
            )}
            <ConfirmButton label="Reset MFA" title="Reset this user's authenticator?" onConfirm={() => run(async () =>
              apply(await userApi.resetMfa(id), 'Authenticator reset; the user must enrol again at next sign-in.'))}>
              Their current authenticator stops working and they must enrol a new one.
            </ConfirmButton>
          </section>
        )}

        {canAdmin && <ProfileEditor key={u.version} user={u} onSaved={(saved) => apply(saved, 'Profile saved.')} />}

        {canAdmin && !isSelf && (
          <section className="max-w-md space-y-3 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5" aria-label="Temporary password">
            <h2 className="font-semibold">Issue temporary password</h2>
            <Field label="Temporary password" type="password" autoComplete="new-password" value={tempPassword}
              onChange={(e) => setTempPassword(e.target.value)} />
            <ConfirmButton label="Issue password" variant="primary" disabled={!tempPassword} title="Issue a temporary password?"
              onConfirm={() => run(async () => {
                apply(await userApi.temporaryPassword(id, tempPassword), 'Temporary password issued; the user must change it.');
                setTempPassword('');
              })}>The user is signed out and must change this password at next sign-in.</ConfirmButton>
          </section>
        )}

        <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5">
          {!(canAdmin && !isSelf) && <h2 className="mb-3 font-semibold">Roles</h2>}
          {canAdmin && !isSelf ? (
            <div className="space-y-3">
              <RolePicker value={roleDraft ?? u.roles} onChange={setRoleDraft} />
              <Button busy={saveRoles.isPending} onClick={() => { setNotice(null); saveRoles.mutate(); }}>Save roles</Button>
            </div>
          ) : (
            <div className="flex flex-wrap gap-2">{u.roles.map((r) => <Badge key={r} tone="blue">{humanize(r)}</Badge>)}</div>
          )}
          {isSelf && <p className="mt-2 text-xs text-slate-500">You cannot change your own roles.</p>}
          <h3 className="mb-2 mt-5 text-sm font-semibold">Effective permissions</h3>
          <p className="text-xs text-slate-600">{u.effectivePermissions.join(', ') || 'None'}</p>
        </section>
        <OfficeAssignmentsPanel userId={u.id} internal={u.userType === 'INTERNAL'} />
      </div>
    </>
  );
}

/** E-mail, display name and access expiry. External auditors always keep an expiry; a changed one must lie ahead. */
function ProfileEditor({ user, onSaved }: { user: UserDetail; onSaved: (u: UserDetail) => void }) {
  const initial = { email: user.email, displayName: user.displayName, accessUntil: manilaDate(user.accessExpiresAt) };
  const [form, setForm] = useState(initial);
  const [error, setError] = useState<unknown>(null);
  const auditor = user.userType === 'EXTERNAL_AUDITOR';
  const unchanged = form.email === initial.email && form.displayName === initial.displayName && form.accessUntil === initial.accessUntil;
  const save = useMutation({
    // An untouched expiry is sent back exactly as stored, so editing an expired account does not move it.
    mutationFn: () => userApi.update(user.id, { version: user.version, email: form.email.trim(), displayName: form.displayName.trim(),
      accessExpiresAt: form.accessUntil === initial.accessUntil ? user.accessExpiresAt : form.accessUntil ? endOfManilaDay(form.accessUntil) : null }),
    onSuccess: (saved) => { setError(null); onSaved(saved); },
    onError: setError,
  });
  return (
    <form aria-label="Profile" className="max-w-2xl space-y-3 rounded-xl border border-slate-200/80 bg-white shadow-xs p-5"
      onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
      <h2 className="font-semibold">Profile</h2>
      <ErrorAlert error={error} />
      <div className="grid gap-4 sm:grid-cols-3">
        <Field label="E-mail" type="email" required value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} />
        <Field label="Display name" required value={form.displayName} onChange={(e) => setForm({ ...form, displayName: e.target.value })} />
        <Field label="Access expires" type="date" required={auditor} value={form.accessUntil}
          onChange={(e) => setForm({ ...form, accessUntil: e.target.value })}
          hint={auditor ? 'Required for external auditors.' : 'Leave empty for no expiry.'} />
      </div>
      <p className="text-xs text-slate-500">Access ends at 23:59 on that day (Philippine time). To end access now, deactivate the user.</p>
      <Button type="submit" variant="secondary" busy={save.isPending} disabled={unchanged}>Save profile</Button>
    </form>
  );
}
