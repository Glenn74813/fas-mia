import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { logoutRequest, refreshSession, session } from '../api/client';
import { authApi } from '../api/endpoints';
import type { CurrentUser, TokenResponse } from '../api/types';
import { AuthContext, type AuthState, type AuthStatus, type PendingMfa } from './AuthContext';

/**
 * Holds the signed-in user. On load it tries to resume the session from the HttpOnly refresh cookie;
 * the access token itself is kept only in memory by the API client.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>('loading');
  const [user, setUser] = useState<CurrentUser | null>(null);
  const [sessionExpiresAt, setSessionExpiresAt] = useState<string | null>(null);
  const [pendingMfa, setPendingMfa] = useState<PendingMfa | null>(null);
  const [heldSignIn, setHeldSignIn] = useState<TokenResponse | null>(null);

  const signedIn = useCallback((tokens: TokenResponse) => {
    session.setAccessToken(tokens.accessToken);
    setUser(tokens.user);
    setSessionExpiresAt(tokens.sessionExpiresAt);
    setPendingMfa(null);
    setStatus('authenticated');
  }, []);

  const signedOut = useCallback(() => {
    session.setAccessToken(null);
    setUser(null);
    setSessionExpiresAt(null);
    setStatus('anonymous');
  }, []);

  useEffect(() => {
    session.onSessionEnded(signedOut);
    let cancelled = false;
    refreshSession().then((tokens) => {
      if (cancelled) return;
      if (tokens) signedIn(tokens);
      else signedOut();
    });
    return () => {
      cancelled = true;
      session.onSessionEnded(null);
    };
  }, [signedIn, signedOut]);

  const passwordStep = useCallback(async (identifier: string, password: string) => {
    const res = await authApi.login(identifier, password);
    if (res.status === 'DEV_MFA_BYPASS') {
      // Development server only: this user is configured to sign in without the authenticator code.
      signedIn(await authApi.devBypass(res.mfaToken));
      return res;
    }
    setPendingMfa({ status: res.status, mfaToken: res.mfaToken });
    return res;
  }, [signedIn]);

  const beginEnrollment = useCallback(async () => {
    if (!pendingMfa) throw new Error('No sign-in in progress');
    return authApi.enrollTotp(pendingMfa.mfaToken);
  }, [pendingMfa]);

  const completeMfa = useCallback(async (code: string) => {
    if (!pendingMfa) throw new Error('No sign-in in progress');
    const tokens = pendingMfa.status === 'MFA_ENROLLMENT_REQUIRED'
      ? await authApi.activateTotp(pendingMfa.mfaToken, code)
      : await authApi.verifyTotp(pendingMfa.mfaToken, code);
    if (tokens.recoveryCodes?.length) {
      // Show the codes before the app opens; the session is already valid on the server. The enrolment step is over.
      setHeldSignIn(tokens);
      setPendingMfa(null);
    } else {
      signedIn(tokens);
    }
    return tokens;
  }, [pendingMfa, signedIn]);

  const recoverWithCode = useCallback(async (recoveryCode: string) => {
    if (!pendingMfa) throw new Error('No sign-in in progress');
    const res = await authApi.recover(pendingMfa.mfaToken, recoveryCode);
    setPendingMfa({ status: res.status, mfaToken: res.mfaToken });
  }, [pendingMfa]);

  const acknowledgeRecoveryCodes = useCallback(() => {
    if (heldSignIn) signedIn(heldSignIn);
    setHeldSignIn(null);
  }, [heldSignIn, signedIn]);

  const logout = useCallback(async () => {
    try {
      await logoutRequest();
    } finally {
      signedOut();
    }
  }, [signedOut]);

  const reloadUser = useCallback(async () => {
    setUser(await authApi.me());
  }, []);

  const hasPermission = useCallback((p: string) => !!user && user.permissions.includes(p), [user]);

  const value = useMemo<AuthState>(() => ({
    status, user, sessionExpiresAt, pendingMfa, passwordStep, beginEnrollment, completeMfa, recoverWithCode,
    newRecoveryCodes: heldSignIn?.recoveryCodes ?? null, acknowledgeRecoveryCodes, logout, reloadUser, hasPermission,
  }), [status, user, sessionExpiresAt, pendingMfa, passwordStep, beginEnrollment, completeMfa, recoverWithCode, heldSignIn,
    acknowledgeRecoveryCodes, logout, reloadUser, hasPermission]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
