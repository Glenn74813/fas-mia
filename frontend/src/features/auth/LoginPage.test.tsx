import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../api/client';
import type { AuthState } from '../../auth/AuthContext';
import { authState, WithAuth } from '../../test/authStub';
import { LoginPage } from './LoginPage';

function setup(passwordStep: AuthState['passwordStep']) {
  const router = createMemoryRouter([
    { path: '/login', element: <LoginPage /> },
    { path: '/login/mfa', element: <p>mfa step</p> },
  ], { initialEntries: ['/login'] });
  render(<WithAuth state={authState({ status: 'anonymous', user: null, passwordStep })}><RouterProvider router={router} /></WithAuth>);
}

describe('LoginPage', () => {
  it('submits credentials and proceeds to the MFA step', async () => {
    const passwordStep = vi.fn<AuthState['passwordStep']>().mockResolvedValue({ status: 'MFA_REQUIRED', mfaToken: 't' });
    setup(passwordStep);
    await userEvent.type(screen.getByLabelText('Username or e-mail'), 'j.cruz');
    await userEvent.type(screen.getByLabelText('Password'), 'Secret-Passw0rd!');
    await userEvent.click(screen.getByRole('button', { name: 'Continue' }));
    expect(passwordStep).toHaveBeenCalledWith('j.cruz', 'Secret-Passw0rd!');
    expect(await screen.findByText('mfa step')).toBeInTheDocument();
  });

  it('shows the server message and correlation reference on failure', async () => {
    const passwordStep = vi.fn<AuthState['passwordStep']>().mockRejectedValue(new ApiError(401, {
      code: 'AUTH_INVALID_CREDENTIALS', detail: 'Sign-in failed.', correlationId: 'req-42' }));
    setup(passwordStep);
    await userEvent.type(screen.getByLabelText('Username or e-mail'), 'x');
    await userEvent.type(screen.getByLabelText('Password'), 'y');
    await userEvent.click(screen.getByRole('button', { name: 'Continue' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Sign-in failed.');
    expect(alert).toHaveTextContent('req-42');
  });

  it('disables submit until both fields are filled', () => {
    setup(vi.fn<AuthState['passwordStep']>());
    expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled();
  });
});
