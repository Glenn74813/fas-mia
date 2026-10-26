import { useState, type FormEvent } from 'react';
import { Link, Navigate, useNavigate } from 'react-router';
import { useAuth } from '../../auth/AuthContext';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Field } from '../../components/Field';
import { AuthLayout } from '../../layouts/AuthLayout';

export function LoginPage() {
  const { status, passwordStep } = useAuth();
  const navigate = useNavigate();
  const [identifier, setIdentifier] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  if (status === 'authenticated') return <Navigate to="/" replace />;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const res = await passwordStep(identifier, password);
      setPassword('');
      navigate(res.status === 'DEV_MFA_BYPASS' ? '/' : '/login/mfa');
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <AuthLayout title="Sign in">
      <form onSubmit={submit} className="space-y-4" noValidate>
        <ErrorAlert error={error} />
        <Field label="Username or e-mail" name="identifier" autoComplete="username" required value={identifier}
          onChange={(e) => setIdentifier(e.target.value)} />
        <Field label="Password" name="password" type="password" autoComplete="current-password" required
          value={password} onChange={(e) => setPassword(e.target.value)} />
        <Button type="submit" busy={busy} disabled={!identifier || !password} className="w-full">Continue</Button>
        <p className="text-center text-sm"><Link className="text-brand-700 hover:underline" to="/forgot-password">Forgot password?</Link></p>
      </form>
    </AuthLayout>
  );
}
