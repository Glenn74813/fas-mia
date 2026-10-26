import { LogOut } from 'lucide-react';
import { Link, useNavigate } from 'react-router';
import { useAuth } from '../auth/AuthContext';
import { humanize } from '../utils/format';

function initials(name: string | undefined) {
  const parts = (name ?? '').trim().split(/\s+/).filter(Boolean);
  return ((parts[0]?.[0] ?? '') + (parts.length > 1 ? parts[parts.length - 1]![0] : '')).toUpperCase() || '?';
}

/** The signed-in user's name and main role (linking to their profile) and the sign-out button, for both shells. */
export function UserMenu() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const role = user?.roles[0];
  const profile = user?.userType === 'CONCESSIONAIRE' ? '/portal/profile' : '/profile';
  return (
    <div className="flex items-center gap-3">
      <Link to={profile} title="My profile" aria-label="My profile"
        className="-mx-1.5 flex items-center gap-2.5 rounded-lg px-1.5 py-1 transition hover:bg-slate-100">
        <span aria-hidden className="flex h-8 w-8 items-center justify-center rounded-full bg-brand-100 text-xs font-semibold text-brand-800">
          {initials(user?.displayName)}
        </span>
        <span className="hidden leading-tight lg:block">
          <span className="block text-sm font-medium text-slate-900">{user?.displayName}</span>
          {role && <span className="block text-xs text-slate-500">{humanize(role)}{user!.roles.length > 1 ? ` +${user!.roles.length - 1}` : ''}</span>}
        </span>
      </Link>
      <button type="button" onClick={async () => { await logout(); navigate('/login', { replace: true }); }}
        className="inline-flex items-center gap-1.5 whitespace-nowrap rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-sm font-medium text-slate-700 shadow-xs transition hover:bg-slate-50 hover:text-slate-900">
        <LogOut aria-hidden className="h-4 w-4" strokeWidth={1.75} />
        Sign out
      </button>
    </div>
  );
}
