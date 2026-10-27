import type { ReactNode } from 'react';
import { vi } from 'vitest';
import type { CurrentUser } from '../api/types';
import { AuthContext, type AuthState } from '../auth/AuthContext';

export function user(overrides: Partial<CurrentUser> = {}): CurrentUser {
  return {
    id: 'u1', username: 'j.cruz', displayName: 'Juan Cruz', email: 'j@miaa.test', userType: 'INTERNAL',
    roles: [], permissions: [], mustChangePassword: false, mfaEnrolled: true, recoveryCodesRemaining: 10, lastLoginAt: null, ...overrides,
  };
}

export function authState(overrides: Partial<AuthState> = {}): AuthState {
  const u = overrides.user === undefined ? user() : overrides.user;
  return {
    status: 'authenticated', user: u, sessionExpiresAt: null, pendingMfa: null,
    passwordStep: vi.fn(), beginEnrollment: vi.fn(), completeMfa: vi.fn(), recoverWithCode: vi.fn(),
    newRecoveryCodes: null, acknowledgeRecoveryCodes: vi.fn(), logout: vi.fn(), reloadUser: vi.fn(),
    hasPermission: (p: string) => !!u && u.permissions.includes(p),
    ...overrides,
  };
}

export function WithAuth({ state, children }: { state: AuthState; children: ReactNode }) {
  return <AuthContext.Provider value={state}>{children}</AuthContext.Provider>;
}
