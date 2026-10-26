import { useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { authApi } from '../../api/endpoints';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Field } from '../../components/Field';
import { AuthLayout } from '../../layouts/AuthLayout';

export function ForgotPasswordPage() {
  const [identifier, setIdentifier] = useState('');
  const [busy, setBusy] = useState(false);
  const [sent, setSent] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await authApi.forgotPassword(identifier);
      setSent(true);
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <AuthLayout title="Reset your password">
      {sent ? (
        <p role="status" className="text-sm">If an active account matches, a reset link has been sent to its registered
          e-mail address. The link expires in 30 minutes.</p>
      ) : (
        <form onSubmit={submit} className="space-y-4">
          <ErrorAlert error={error} />
          <Field label="Username or e-mail" required value={identifier} onChange={(e) => setIdentifier(e.target.value)} />
          <Button type="submit" busy={busy} disabled={!identifier} className="w-full">Send reset link</Button>
        </form>
      )}
      <p className="mt-4 text-center text-sm"><Link className="text-brand-700 hover:underline" to="/login">Back to sign in</Link></p>
    </AuthLayout>
  );
}
