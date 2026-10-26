import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router';
import { Spinner } from '../components/Spinner';
import { useAuth } from './AuthContext';

interface Props {
  children: ReactNode;
  /** Any one of these permissions grants access (presentation only; the API enforces authorisation). */
  anyOf?: string[];
  portal?: boolean;
}

export function RequireAuth({ children, anyOf, portal = false }: Props) {
  const { status, user, hasPermission } = useAuth();
  const location = useLocation();

  if (status === 'loading') {
    return <div className="flex h-screen items-center justify-center"><Spinner label="Restoring session" /></div>;
  }
  if (status === 'anonymous' || !user) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }
  if (user.mustChangePassword) {
    return <Navigate to="/change-password" replace />;
  }
  const isConcessionaire = user.userType === 'CONCESSIONAIRE';
  if (isConcessionaire && !portal) return <Navigate to="/portal" replace />;
  if (!isConcessionaire && portal) return <Navigate to="/" replace />;
  if (anyOf && !anyOf.some(hasPermission)) {
    return (
      <div role="alert" className="m-8 rounded-lg border border-amber-300 bg-amber-50 p-6">
        <h1 className="text-lg font-semibold">Access restricted</h1>
        <p className="mt-1 text-sm">Your roles do not include access to this page. Contact your administrator if you need it.</p>
      </div>
    );
  }
  return <>{children}</>;
}
