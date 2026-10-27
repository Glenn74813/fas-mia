import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import type { AuthState } from '../../auth/AuthContext';
import { authState, WithAuth } from '../../test/authStub';
import { MfaPage } from './MfaPage';

function setup(overrides: Partial<AuthState>) {
  const router = createMemoryRouter([
    { path: '/login/mfa', element: <MfaPage /> },
    { path: '/', element: <p>home</p> },
    { path: '/login', element: <p>sign in</p> },
  ], { initialEntries: ['/login/mfa'] });
  render(<WithAuth state={authState({ status: 'anonymous', user: null, ...overrides })}><RouterProvider router={router} /></WithAuth>);
}

describe('MfaPage', () => {
  it('explains where the code comes from and offers a recovery code', async () => {
    const recoverWithCode = vi.fn<AuthState['recoverWithCode']>().mockResolvedValue();
    setup({ pendingMfa: { status: 'MFA_REQUIRED', mfaToken: 't' }, recoverWithCode });
    expect(screen.getByText(/Open your authenticator app/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /Use a recovery code/ }));
    const continueButton = screen.getByRole('button', { name: 'Continue' });
    await userEvent.type(screen.getByLabelText('Recovery code'), 'abcde-2345');
    expect(continueButton).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Recovery code'), '6');
    await userEvent.click(continueButton);
    expect(recoverWithCode).toHaveBeenCalledWith('abcde-23456');
  });

  it('offers a code by e-mail when the sign-in step allows it', async () => {
    const sendEmailCode = vi.fn<AuthState['sendEmailCode']>().mockResolvedValue({ sentTo: 'j***@miaa.test', expiresAt: '2026-10-27T05:00:00Z' });
    const completeEmailCode = vi.fn<AuthState['completeEmailCode']>().mockResolvedValue({
      accessToken: 'a', tokenType: 'Bearer', expiresIn: 600, sessionExpiresAt: '2026-10-27T13:00:00Z',
      user: { id: 'u1', username: 'j', displayName: 'J', email: 'j@miaa.test', userType: 'INTERNAL', roles: [], permissions: [],
        mustChangePassword: false, mfaEnrolled: true, recoveryCodesRemaining: 10, lastLoginAt: null } });
    setup({ pendingMfa: { status: 'MFA_REQUIRED', mfaToken: 't', methods: ['TOTP', 'EMAIL'] }, sendEmailCode, completeEmailCode });
    await userEvent.click(screen.getByRole('button', { name: 'Send a code to my e-mail instead' }));
    expect(await screen.findByText('j***@miaa.test')).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('6-digit code'), '123456');
    await userEvent.click(screen.getByRole('button', { name: 'Verify' }));
    expect(completeEmailCode).toHaveBeenCalledWith('123456');
    expect(await screen.findByText('home')).toBeInTheDocument();
  });

  it('does not offer e-mail when the step does not allow it', () => {
    setup({ pendingMfa: { status: 'MFA_REQUIRED', mfaToken: 't', methods: ['TOTP'] } });
    expect(screen.queryByRole('button', { name: /e-mail instead/ })).toBeNull();
  });

  it('shows new recovery codes and continues only once they are saved', async () => {
    const acknowledgeRecoveryCodes = vi.fn();
    const codes = ['ABCDE-23456', 'FGHJK-78923'];
    setup({ pendingMfa: null, newRecoveryCodes: codes, acknowledgeRecoveryCodes });
    expect(screen.getByRole('heading', { name: 'Save your recovery codes' })).toBeInTheDocument();
    expect(screen.getByText('ABCDE-23456')).toBeInTheDocument();

    const continueButton = screen.getByRole('button', { name: 'Continue' });
    expect(continueButton).toBeDisabled();
    await userEvent.click(screen.getByLabelText(/I have saved these codes/));
    await userEvent.click(continueButton);
    expect(acknowledgeRecoveryCodes).toHaveBeenCalled();
    expect(await screen.findByText('home')).toBeInTheDocument();
  });
});
