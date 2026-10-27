import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
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
import { createPasskey, passkeysSupported } from '../../auth/webauthn';
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
        <Passkeys />
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

/**
 * Passkeys: sign in with a fingerprint, face or device PIN instead of the 6-digit code. Adding one needs a current
 * authenticator code; the browser then asks the device to create the passkey.
 */
function Passkeys() {
  const { user } = useAuth();
  const qc = useQueryClient();
  const list = useQuery({ queryKey: ['passkeys'], queryFn: authApi.passkeys, enabled: !!user?.mfaEnrolled });
  const [name, setName] = useState('');
  const [code, setCode] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const remove = useMutation({ mutationFn: (id: string) => authApi.removePasskey(id), onSuccess: () => qc.invalidateQueries({ queryKey: ['passkeys'] }) });
  if (!user?.mfaEnrolled) return null;
  const supported = passkeysSupported();

  const add = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const options = await authApi.passkeyRegistrationOptions(code);
      await authApi.registerPasskey(name.trim(), await createPasskey(options));
      setName('');
      setCode('');
      await qc.invalidateQueries({ queryKey: ['passkeys'] });
    } catch (err) {
      setError(err instanceof DOMException && err.name === 'NotAllowedError'
        ? new Error('The passkey prompt was cancelled or timed out.') : err);
      setCode('');
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5 md:col-span-2" aria-labelledby="passkeys">
      <h2 id="passkeys" className="mb-1 font-semibold">Passkeys</h2>
      <p className="mb-4 text-sm text-slate-600">Sign in with your fingerprint, face or device PIN instead of typing the 6-digit
        code. Your password is still needed.</p>
      {(list.data ?? []).length > 0 && (
        <ul className="mb-4 divide-y divide-slate-100 rounded-lg border border-slate-200 text-sm">
          {list.data!.map((k) => (
            <li key={k.id} className="flex items-center justify-between gap-3 px-3 py-2">
              <span><span className="font-medium">{k.name}</span>
                <span className="block text-xs text-slate-500">Added {formatDateTime(k.createdAt)}
                  {k.lastUsedAt ? ` · last used ${formatDateTime(k.lastUsedAt)}` : ' · not used yet'}</span></span>
              <Button variant="secondary" busy={remove.isPending} onClick={() => {
                if (window.confirm(`Remove the passkey "${k.name}"? You can still sign in with your authenticator app.`)) remove.mutate(k.id);
              }}>Remove</Button>
            </li>))}
        </ul>
      )}
      {!supported ? <p className="text-sm text-amber-800">This browser does not support passkeys.</p> : (
        <form onSubmit={add} className="flex max-w-2xl flex-wrap items-end gap-3">
          <div className="min-w-48 flex-1"><Field label="Name for this passkey" name="passkeyName" placeholder="e.g. Office laptop" maxLength={100}
            required value={name} onChange={(e) => setName(e.target.value)} /></div>
          <div className="w-40"><Field label="Current 6-digit code" name="passkeyCode" inputMode="numeric" autoComplete="one-time-code"
            pattern="\d{6}" maxLength={6} required value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))} /></div>
          <Button type="submit" busy={busy} disabled={code.length !== 6 || !name.trim()}>Add a passkey</Button>
          <div className="w-full"><ErrorAlert error={error} /></div>
        </form>
      )}
    </section>
  );
}
