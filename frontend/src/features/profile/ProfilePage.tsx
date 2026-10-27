import { useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { authApi } from '../../api/endpoints';
import { useAuth } from '../../auth/AuthContext';
import { Badge } from '../../components/Badge';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Field } from '../../components/Field';
import { PageHeader } from '../../components/PageHeader';
import { RecoveryCodes } from '../../components/RecoveryCodes';
import { visibleNav } from '../../layouts/navigation';
import { formatDateTime, humanize } from '../../utils/format';

/** The signed-in user's own account and access details, for both the internal shell and the concessionaire portal. */
export function ProfilePage({ portal = false }: { portal?: boolean }) {
  const { user, hasPermission, sessionExpiresAt } = useAuth();
  if (!user) return null;
  // The portal never lists internal pages, even as links.
  const shortcuts = portal ? [] : visibleNav(hasPermission).filter((i) => i.to !== '/');
  return (
    <>
      <PageHeader title="My profile" />
      <div className="grid gap-6 md:grid-cols-2">
        <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5" aria-labelledby="acct">
          <h2 id="acct" className="mb-3 font-semibold">Your account</h2>
          <dl className="grid grid-cols-3 gap-2 text-sm">
            <dt className="text-slate-500">Name</dt><dd className="col-span-2">{user.displayName}</dd>
            <dt className="text-slate-500">Username</dt><dd className="col-span-2">{user.username}</dd>
            <dt className="text-slate-500">Email</dt><dd className="col-span-2 break-all">{user.email || '—'}</dd>
            <dt className="text-slate-500">Account type</dt><dd className="col-span-2">{humanize(user.userType)}</dd>
            <dt className="text-slate-500">Last sign-in</dt><dd className="col-span-2">{formatDateTime(user.lastLoginAt)}</dd>
            <dt className="text-slate-500">Session ends</dt><dd className="col-span-2">{formatDateTime(sessionExpiresAt)}</dd>
            <dt className="text-slate-500">MFA</dt>
            <dd className="col-span-2"><Badge tone={user.mfaEnrolled ? 'green' : 'amber'}>{user.mfaEnrolled ? 'Enrolled' : 'Not enrolled'}</Badge></dd>
          </dl>
          <Link to="/change-password" className="mt-4 inline-block text-sm text-brand-700 hover:underline">Change password</Link>
        </section>
        <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5" aria-labelledby="access">
          <h2 id="access" className="mb-3 font-semibold">Your access</h2>
          <div className="mb-3 flex flex-wrap gap-2">
            {user.roles.length === 0 ? <span className="text-sm text-slate-500">No roles assigned.</span>
              : user.roles.map((r) => <Badge key={r} tone="blue">{humanize(r)}</Badge>)}
          </div>
          <p className="text-sm text-slate-600">{user.permissions.length} effective permission(s).</p>
          {shortcuts.length > 0 && (
            <ul className="mt-3 space-y-1 text-sm">
              {shortcuts.map((s) => <li key={s.to}><Link className="text-brand-700 hover:underline" to={s.to}>{s.label}</Link></li>)}
            </ul>
          )}
        </section>
        <TwoStepVerification />
      </div>
    </>
  );
}

/** Recovery codes: how many are left, and a new set on demand (needs a current authenticator code). */
function TwoStepVerification() {
  const { user, reloadUser } = useAuth();
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [codes, setCodes] = useState<string[] | null>(null);
  if (!user?.mfaEnrolled) return null;
  const left = user.recoveryCodesRemaining;

  const generate = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      setCodes((await authApi.regenerateRecoveryCodes(code)).codes);
      setCode('');
      await reloadUser();
    } catch (err) {
      setError(err);
      setCode('');
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5 md:col-span-2" aria-labelledby="mfa">
      <h2 id="mfa" className="mb-1 font-semibold">Two-step verification</h2>
      <p className="mb-4 text-sm text-slate-600">If you lose your authenticator, a recovery code lets you sign in once and
        set it up again.</p>
      <p className="mb-4 flex items-center gap-2 text-sm">
        <span className="text-slate-500">Recovery codes left:</span>
        <Badge tone={left === 0 ? 'red' : left <= 3 ? 'amber' : 'green'}>{`${left} of 10`}</Badge>
        {left === 0 && <span className="text-slate-600">Generate a set now, in case you lose your phone.</span>}
      </p>
      {codes ? (
        <div className="space-y-3">
          <p className="text-sm text-slate-700"><strong>Save these new codes now; they are shown only once.</strong> Your
            previous codes no longer work.</p>
          <RecoveryCodes codes={codes} username={user.username} />
          <Button variant="secondary" onClick={() => setCodes(null)}>Done</Button>
        </div>
      ) : (
        <form onSubmit={generate} className="flex max-w-md flex-wrap items-end gap-3">
          <div className="min-w-40 flex-1">
            <Field label="Current 6-digit code" name="regenCode" inputMode="numeric" autoComplete="one-time-code" pattern="\d{6}"
              maxLength={6} required value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))} />
          </div>
          <Button type="submit" busy={busy} disabled={code.length !== 6}>Generate new codes</Button>
          <div className="w-full"><ErrorAlert error={error} /></div>
        </form>
      )}
    </section>
  );
}
