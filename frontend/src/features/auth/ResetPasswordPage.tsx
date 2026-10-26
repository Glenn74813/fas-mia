import { useState, type FormEvent } from 'react';
import { Link, useLocation } from 'react-router';
import { authApi } from '../../api/endpoints';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Field } from '../../components/Field';
import { AuthLayout } from '../../layouts/AuthLayout';

/** The token arrives in the URL fragment so it is never sent to servers or written to access logs. */
export function ResetPasswordPage() {
  const { hash } = useLocation();
  const token = new URLSearchParams(hash.replace(/^#/, '')).get('token') ?? '';
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await authApi.resetPassword(token, password);
      setDone(true);
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <AuthLayout title="Set a new password">
      {done ? (
        <p role="status" className="text-sm">Your password has been changed and all sessions were signed out.
          <Link className="ml-1 text-brand-700 hover:underline" to="/login">Sign in</Link></p>
      ) : !token ? (
        <p className="text-sm">This reset link is incomplete. Request a new one from the sign-in page.</p>
      ) : (
        <form onSubmit={submit} className="space-y-4">
          <ErrorAlert error={error} />
          <Field label="New password" type="password" autoComplete="new-password" required value={password}
            onChange={(e) => setPassword(e.target.value)} />
          <Button type="submit" busy={busy} disabled={!password} className="w-full">Save password</Button>
        </form>
      )}
    </AuthLayout>
  );
}
