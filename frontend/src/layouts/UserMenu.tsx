import { ChevronsUpDown, KeyRound, LogOut, UserRound } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { useAuth } from '../auth/AuthContext';
import { humanize } from '../utils/format';

function initials(name: string | undefined) {
  const parts = (name ?? '').trim().split(/\s+/).filter(Boolean);
  return ((parts[0]?.[0] ?? '') + (parts.length > 1 ? parts[parts.length - 1]![0] : '')).toUpperCase() || '?';
}

const ITEM = 'flex w-full items-center gap-2.5 px-3 py-2 text-left text-sm text-slate-700 hover:bg-slate-100 focus:bg-slate-100 focus:outline-none';

/**
 * The signed-in user's account menu: initials and name, opening My profile, Change password and Sign out.
 * "sidebar" sits at the foot of the dark navigation and opens upwards; "header" is the compact form for the portal
 * and small screens, opening downwards.
 */
export function UserMenu({ variant = 'header' }: { variant?: 'sidebar' | 'header' }) {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const profile = user?.userType === 'CONCESSIONAIRE' ? '/portal/profile' : '/profile';
  const role = user?.roles[0];
  const sidebar = variant === 'sidebar';

  useEffect(() => {
    if (!open) return;
    const outside = (e: MouseEvent) => { if (!ref.current?.contains(e.target as Node)) setOpen(false); };
    const escape = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false); };
    document.addEventListener('mousedown', outside);
    document.addEventListener('keydown', escape);
    return () => { document.removeEventListener('mousedown', outside); document.removeEventListener('keydown', escape); };
  }, [open]);

  return (
    <div ref={ref} className="relative">
      <button type="button" aria-label="Account menu" aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen((o) => !o)}
        className={sidebar
          ? 'flex w-full items-center gap-3 rounded-lg px-2 py-2 text-left text-white transition hover:bg-white/5 aria-expanded:bg-white/10'
          : 'flex items-center gap-2 rounded-lg px-1.5 py-1 text-left transition hover:bg-slate-100'}>
        <span aria-hidden className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-full text-xs font-semibold ${sidebar
          ? 'bg-white/15 text-white' : 'bg-brand-100 text-brand-800'}`}>{initials(user?.displayName)}</span>
        <span className={`min-w-0 flex-1 truncate text-sm font-medium ${sidebar ? '' : 'hidden text-slate-900 sm:block'}`}>{user?.displayName}</span>
        <ChevronsUpDown aria-hidden className={`h-4 w-4 shrink-0 ${sidebar ? 'text-slate-400' : 'text-slate-500'}`} strokeWidth={1.75} />
      </button>
      {open && (
        <div role="menu" aria-label="Account"
          className={`absolute z-30 w-60 overflow-hidden rounded-xl border border-slate-200 bg-white py-1 shadow-lg ${sidebar
            ? 'bottom-full left-0 mb-2' : 'right-0 top-full mt-2'}`}>
          <div className="border-b border-slate-100 px-3 pb-2 pt-1.5">
            <p className="truncate text-sm font-medium text-slate-900">{user?.displayName}</p>
            <p className="truncate text-xs text-slate-500">
              {role ? `${humanize(role)}${user!.roles.length > 1 ? ` +${user!.roles.length - 1}` : ''}` : user?.username}</p>
          </div>
          <Link role="menuitem" to={profile} className={ITEM} onClick={() => setOpen(false)}>
            <UserRound aria-hidden className="h-4 w-4 text-slate-500" strokeWidth={1.75} />My profile</Link>
          <Link role="menuitem" to="/change-password" className={ITEM} onClick={() => setOpen(false)}>
            <KeyRound aria-hidden className="h-4 w-4 text-slate-500" strokeWidth={1.75} />Change password</Link>
          <div className="my-1 border-t border-slate-100" />
          <button type="button" role="menuitem" className={ITEM}
            onClick={async () => { setOpen(false); await logout(); navigate('/login', { replace: true }); }}>
            <LogOut aria-hidden className="h-4 w-4 text-slate-500" strokeWidth={1.75} />Sign out</button>
        </div>
      )}
    </div>
  );
}
