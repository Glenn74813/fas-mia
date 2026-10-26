import { Circle, Clock, Menu, X } from 'lucide-react';
import { useState } from 'react';
import { NavLink, Outlet } from 'react-router';
import { useAuth } from '../auth/AuthContext';
import { NotificationBell } from '../components/NotificationBell';
import { PlaceholderBanner } from '../components/PlaceholderBanner';
import { formatDateTime } from '../utils/format';
import { NAV_ICONS } from './navIcons';
import { visibleNav } from './navigation';
import { UserMenu } from './UserMenu';

/** The brand mark: a neutral monogram (not an official MIAA logo). */
export function BrandMark({ className = '' }: { className?: string }) {
  return (
    <span aria-hidden className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-gradient-to-br from-brand-400 to-brand-700
      text-[13px] font-bold tracking-tight text-white shadow-sm ring-1 ring-white/15 ${className}`}>CAS</span>
  );
}

export function InternalLayout() {
  const { hasPermission, sessionExpiresAt } = useAuth();
  const [menuOpen, setMenuOpen] = useState(false);
  const items = visibleNav(hasPermission);

  return (
    <div className="min-h-screen md:flex">
      <a href="#main" className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-50 focus:rounded-md focus:bg-white focus:px-3 focus:py-2">
        Skip to content</a>
      <aside className="bg-ink-900 text-slate-300 md:sticky md:top-0 md:flex md:h-screen md:w-64 md:shrink-0 md:flex-col">
        <div className="flex items-center justify-between gap-3 border-b border-white/5 px-4 py-4">
          <div className="flex items-center gap-3">
            <BrandMark />
            <div className="leading-tight">
              <p className="text-sm font-semibold tracking-tight text-white">MIAA CAS</p>
              <p className="text-[11px] text-slate-400">Computerized Accounting System</p>
            </div>
          </div>
          <button type="button" className="rounded-md p-1.5 text-slate-300 hover:bg-white/10 md:hidden" aria-label="Menu" aria-expanded={menuOpen}
            aria-controls="main-nav" onClick={() => setMenuOpen((o) => !o)}>
            {menuOpen ? <X aria-hidden className="h-5 w-5" /> : <Menu aria-hidden className="h-5 w-5" />}
          </button>
        </div>
        <nav id="main-nav" aria-label="Main" className={`${menuOpen ? 'block' : 'hidden'} scrollbar-thin px-3 py-3 md:block md:flex-1 md:overflow-y-auto`}>
          {items.map((item, i) => {
            const Icon = NAV_ICONS[item.to] ?? Circle;
            return (
              <div key={item.to}>
                {item.section && item.section !== items[i - 1]?.section && (
                  <p className="mb-1 mt-5 px-3 text-[10.5px] font-semibold uppercase tracking-[0.08em] text-slate-500">{item.section}</p>
                )}
                <NavLink to={item.to} end={item.to === '/'} onClick={() => setMenuOpen(false)}
                  className={({ isActive }) => `group relative flex items-center gap-3 rounded-lg px-3 py-[7px] text-[13.5px] transition-colors ${isActive
                    ? 'bg-white/10 font-medium text-white before:absolute before:inset-y-1.5 before:-left-3 before:w-[3px] before:rounded-r-full before:bg-brand-400'
                    : 'text-slate-300 hover:bg-white/5 hover:text-white'}`}>
                  <Icon aria-hidden className="h-[17px] w-[17px] shrink-0 opacity-80 group-hover:opacity-100" strokeWidth={1.75} />
                  {item.label}
                </NavLink>
              </div>
            );
          })}
        </nav>
        <div className="hidden border-t border-white/5 p-3 md:block">
          {import.meta.env.DEV && <p className="px-2 pb-2 text-[11px] text-slate-500">Development build</p>}
          <UserMenu variant="sidebar" />
        </div>
      </aside>
      <div className="flex min-w-0 flex-1 flex-col">
        <header data-shell className="sticky top-0 z-10 flex h-16 items-center justify-end gap-4 border-b border-slate-200/80 bg-white/90 px-6 backdrop-blur lg:px-8">
          <span className="hidden items-center gap-1.5 whitespace-nowrap rounded-full bg-slate-100 px-3 py-1 text-xs text-slate-600 xl:inline-flex">
            <Clock aria-hidden className="h-3.5 w-3.5" strokeWidth={1.75} />Session ends {formatDateTime(sessionExpiresAt)}
          </span>
          <NotificationBell />
          {/* On small screens the sidebar collapses to a top bar, so the account menu moves up here. */}
          <div className="flex items-center gap-4 md:hidden">
            <span className="h-6 w-px bg-slate-200" aria-hidden />
            <UserMenu />
          </div>
        </header>
        <PlaceholderBanner />
        <main id="main" className="mx-auto w-full max-w-7xl flex-1 px-6 py-8 lg:px-8">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
