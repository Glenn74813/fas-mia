import { createContext, useContext } from 'react';
import type { CurrentUser, LoginResponse, TokenResponse, TotpEnrollment } from '../api/types';

export type AuthStatus = 'loading' | 'anonymous' | 'authenticated';

export interface PendingMfa {
  status: LoginResponse['status'];
  mfaToken: string;
}

export interface AuthState {
  status: AuthStatus;
  user: CurrentUser | null;
  sessionExpiresAt: string | null;
  pendingMfa: PendingMfa | null;
  passwordStep: (identifier: string, password: string) => Promise<LoginResponse>;
  beginEnrollment: () => Promise<TotpEnrollment>;
  completeMfa: (code: string) => Promise<TokenResponse>;
  logout: () => Promise<void>;
  reloadUser: () => Promise<void>;
  hasPermission: (permission: string) => boolean;
}

export const AuthContext = createContext<AuthState | null>(null);

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>');
  return ctx;
}
