import QRCode from 'qrcode';
import { useEffect, useState, type FormEvent } from 'react';
import { Navigate, useNavigate } from 'react-router';
import type { TotpEnrollment } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Field } from '../../components/Field';
import { Spinner } from '../../components/Spinner';
import { AuthLayout } from '../../layouts/AuthLayout';

export function MfaPage() {
  const { pendingMfa, beginEnrollment, completeMfa, status } = useAuth();
  const navigate = useNavigate();
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [enrollment, setEnrollment] = useState<TotpEnrollment | null>(null);
  const [qr, setQr] = useState<string | null>(null);
  const enrolling = pendingMfa?.status === 'MFA_ENROLLMENT_REQUIRED';

  useEffect(() => {
    if (!enrolling) return;
    let cancelled = false;
    beginEnrollment()
      .then(async (e) => {
        if (cancelled) return;
        setEnrollment(e);
        setQr(await QRCode.toDataURL(e.otpauthUri, { margin: 1, width: 200 }));
      })
      .catch((err) => !cancelled && setError(err));
    return () => {
      cancelled = true;
    };
  }, [enrolling, beginEnrollment]);

  if (status === 'authenticated') return <Navigate to="/" replace />;
  if (!pendingMfa) return <Navigate to="/login" replace />;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const tokens = await completeMfa(code);
      navigate(tokens.user.mustChangePassword ? '/change-password' : '/', { replace: true });
    } catch (err) {
      setError(err);
      setCode('');
    } finally {
      setBusy(false);
    }
  };

  return (
    <AuthLayout title={enrolling ? 'Set up your authenticator' : 'Two-step verification'}>
      <form onSubmit={submit} className="space-y-4">
        <ErrorAlert error={error} />
        {enrolling && (
          <div className="space-y-3 text-sm">
            <p>Multi-factor authentication is required for every CAS account. Scan this code with an approved
              authenticator app, then enter the 6-digit code it shows.</p>
            {qr ? <img src={qr} alt="Authenticator enrolment QR code" className="mx-auto h-48 w-48" /> : <Spinner label="Preparing" />}
            {enrollment && (
              <p className="break-all rounded bg-slate-100 p-2 font-mono text-xs">Manual key: {enrollment.secret}</p>
            )}
          </div>
        )}
        <Field label="6-digit code" name="code" inputMode="numeric" autoComplete="one-time-code" pattern="\d{6}"
          maxLength={6} required value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))} />
        <Button type="submit" busy={busy} disabled={code.length !== 6} className="w-full">Verify</Button>
      </form>
    </AuthLayout>
  );
}
