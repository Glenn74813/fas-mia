import QRCode from 'qrcode';
import { useEffect, useState, type FormEvent } from 'react';
import { Navigate, useNavigate } from 'react-router';
import type { TotpEnrollment } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { Button } from '../../components/Button';
import { ErrorAlert } from '../../components/ErrorAlert';
import { Field } from '../../components/Field';
import { RecoveryCodes } from '../../components/RecoveryCodes';
import { Spinner } from '../../components/Spinner';
import { AuthLayout } from '../../layouts/AuthLayout';

export function MfaPage() {
  const { pendingMfa, beginEnrollment, completeMfa, recoverWithCode, newRecoveryCodes, acknowledgeRecoveryCodes, status } = useAuth();
  const navigate = useNavigate();
  const [code, setCode] = useState('');
  const [recoveryCode, setRecoveryCode] = useState('');
  const [useRecovery, setUseRecovery] = useState(false);
  const [saved, setSaved] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [enrollment, setEnrollment] = useState<TotpEnrollment | null>(null);
  const [qr, setQr] = useState<string | null>(null);
  const [mustChangePassword, setMustChangePassword] = useState(false);
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

  if (newRecoveryCodes) {
    return (
      <AuthLayout title="Save your recovery codes">
        <div className="space-y-4 text-sm text-slate-700">
          <p>If you lose your phone or remove MIAA CAS from your authenticator app, each of these codes lets you sign in
            once and set up the authenticator again. <strong>They are shown only now.</strong></p>
          <p>Print them, download them, or save them in a password manager. Keep them away from your phone.</p>
          <RecoveryCodes codes={newRecoveryCodes} />
          <label className="flex items-start gap-2">
            <input type="checkbox" className="mt-0.5" checked={saved} onChange={(e) => setSaved(e.target.checked)} />
            <span>I have saved these codes somewhere safe.</span>
          </label>
          <Button className="w-full" disabled={!saved} onClick={() => {
            acknowledgeRecoveryCodes();
            navigate(mustChangePassword ? '/change-password' : '/', { replace: true });
          }}>Continue</Button>
        </div>
      </AuthLayout>
    );
  }
  if (status === 'authenticated') return <Navigate to="/" replace />;
  if (!pendingMfa) return <Navigate to="/login" replace />;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const tokens = await completeMfa(code);
      if (tokens.recoveryCodes?.length) {
        setMustChangePassword(tokens.user.mustChangePassword);
      } else {
        navigate(tokens.user.mustChangePassword ? '/change-password' : '/', { replace: true });
      }
    } catch (err) {
      setError(err);
      setCode('');
    } finally {
      setBusy(false);
    }
  };

  const recover = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await recoverWithCode(recoveryCode);
      setUseRecovery(false);
      setRecoveryCode('');
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  if (useRecovery && !enrolling) {
    return (
      <AuthLayout title="Use a recovery code">
        <form onSubmit={recover} className="space-y-4">
          <ErrorAlert error={error} />
          <p className="text-sm text-slate-700">Enter one of the recovery codes you saved when you set up your authenticator,
            for example <span className="font-mono">ABCDE-23456</span>. Each code works once. You will then set up your
            authenticator again, and your old codes will stop working.</p>
          <Field label="Recovery code" name="recoveryCode" autoComplete="off" spellCheck={false} maxLength={32} required
            value={recoveryCode} onChange={(e) => setRecoveryCode(e.target.value)} />
          <Button type="submit" busy={busy} disabled={recoveryCode.replace(/[\s-]/g, '').length !== 10} className="w-full">
            Continue</Button>
          <button type="button" className="text-sm text-brand-700 hover:underline" onClick={() => { setUseRecovery(false); setError(null); }}>
            Back to the authenticator code</button>
        </form>
      </AuthLayout>
    );
  }

  return (
    <AuthLayout title={enrolling ? 'Set up your authenticator' : 'Two-step verification'}>
      <form onSubmit={submit} className="space-y-4">
        <ErrorAlert error={error} />
        {enrolling ? (
          <div className="space-y-3 text-sm text-slate-700">
            <p>Every CAS account needs an authenticator app, such as Microsoft Authenticator or Google Authenticator.</p>
            <ol className="list-decimal space-y-1 pl-5">
              <li>In the app, add an account (in Microsoft Authenticator: <strong>+ → Other account</strong>).</li>
              <li>Scan this QR code, or type the setup key below.</li>
              <li>Enter the 6-digit code the app shows for <strong>MIAA CAS</strong>.</li>
            </ol>
            {qr ? <img src={qr} alt="Authenticator enrolment QR code" className="mx-auto h-48 w-48" /> : <Spinner label="Preparing" />}
            {enrollment && (
              <p className="break-all rounded bg-slate-100 p-2 font-mono text-xs">Manual key: {enrollment.secret}</p>
            )}
          </div>
        ) : (
          <p className="text-sm text-slate-700">Open your authenticator app and enter the 6-digit code shown for
            <strong> MIAA CAS</strong>. The code changes every 30 seconds.</p>
        )}
        <Field label="6-digit code" name="code" inputMode="numeric" autoComplete="one-time-code" pattern="\d{6}"
          maxLength={6} required value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))} />
        <Button type="submit" busy={busy} disabled={code.length !== 6} className="w-full">Verify</Button>
        {!enrolling && (
          <div className="space-y-1 border-t border-slate-200 pt-4 text-sm text-slate-600">
            <button type="button" className="font-medium text-brand-700 hover:underline" onClick={() => { setUseRecovery(true); setError(null); }}>
              Lost your authenticator? Use a recovery code</button>
            <p>No recovery codes either? Ask your system administrator to reset your two-step verification.</p>
          </div>
        )}
      </form>
    </AuthLayout>
  );
}
