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
