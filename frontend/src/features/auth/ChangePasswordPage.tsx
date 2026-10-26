import { useState, type FormEvent } from 'react';
import { Navigate, useNavigate } from 'react-router';
import { authApi } from '../../api/endpoints';
import { useAuth } from '../../auth/AuthContext';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Field } from '../../components/Field';
import { AuthLayout } from '../../layouts/AuthLayout';

export function ChangePasswordPage() {
  const { status, user, reloadUser } = useAuth();
  const navigate = useNavigate();
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  if (status === 'anonymous') return <Navigate to="/login" replace />;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (next !== confirm) {
      setError(new Error('The new passwords do not match.'));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await authApi.changePassword(current, next);
      await reloadUser();
      navigate('/', { replace: true });
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <AuthLayout title={user?.mustChangePassword ? 'Choose a new password' : 'Change password'}>
      <form onSubmit={submit} className="space-y-4">
        {user?.mustChangePassword && (
          <p className="text-sm text-slate-600">You signed in with a temporary password. Set your own password to continue.</p>
        )}
        <ErrorAlert error={error} />
        <Field label="Current password" type="password" autoComplete="current-password" required value={current}
          onChange={(e) => setCurrent(e.target.value)} />
        <Field label="New password" type="password" autoComplete="new-password" required value={next}
          hint="At least 12 characters with three of: upper-case, lower-case, digit, symbol."
          onChange={(e) => setNext(e.target.value)} />
        <Field label="Confirm new password" type="password" autoComplete="new-password" required value={confirm}
          onChange={(e) => setConfirm(e.target.value)} />
        <Button type="submit" busy={busy} disabled={!current || !next || !confirm} className="w-full">Save password</Button>
      </form>
    </AuthLayout>
  );
}
