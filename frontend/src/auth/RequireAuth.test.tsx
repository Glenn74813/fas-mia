import { render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { describe, expect, it } from 'vitest';
import { authState, user, WithAuth } from '../test/authStub';
import type { AuthState } from './AuthContext';
import { RequireAuth } from './RequireAuth';

function renderAt(state: AuthState, path: string, anyOf?: string[]) {
  const router = createMemoryRouter([
    { path: '/', element: <RequireAuth anyOf={anyOf}><p>internal page</p></RequireAuth> },
    { path: '/portal', element: <RequireAuth portal><p>portal page</p></RequireAuth> },
    { path: '/login', element: <p>login page</p> },
    { path: '/change-password', element: <p>change password page</p> },
  ], { initialEntries: [path] });
  render(<WithAuth state={state}><RouterProvider router={router} /></WithAuth>);
}

describe('RequireAuth', () => {
  it('sends anonymous users to sign-in', () => {
    renderAt(authState({ status: 'anonymous', user: null }), '/');
    expect(screen.getByText('login page')).toBeInTheDocument();
  });

  it('forces a pending password change first', () => {
    renderAt(authState({ user: user({ mustChangePassword: true }) }), '/');
    expect(screen.getByText('change password page')).toBeInTheDocument();
  });

  it('hides pages the user lacks permission for', () => {
    renderAt(authState({ user: user({ permissions: ['LEDGER_VIEW'] }) }), '/', ['USER_VIEW']);
    expect(screen.getByRole('alert')).toHaveTextContent('Access restricted');
    expect(screen.queryByText('internal page')).not.toBeInTheDocument();
  });

  it('allows users holding any listed permission', () => {
    renderAt(authState({ user: user({ permissions: ['USER_VIEW'] }) }), '/', ['USER_VIEW', 'ROLE_VIEW']);
    expect(screen.getByText('internal page')).toBeInTheDocument();
  });

  it('keeps concessionaires inside the portal and internal users out of it', () => {
    renderAt(authState({ user: user({ userType: 'CONCESSIONAIRE' }) }), '/');
    expect(screen.getByText('portal page')).toBeInTheDocument();
  });
});
