import { Outlet } from 'react-router';
import { NotificationBell } from '../components/NotificationBell';
import { BrandMark } from './InternalLayout';
import { UserMenu } from './UserMenu';

/** Separate shell for concessionaires: no internal navigation is ever rendered here. */
export function PortalLayout() {
  return (
    <div className="min-h-screen">
      <header data-shell className="sticky top-0 z-10 border-b border-slate-200/80 bg-white/90 backdrop-blur">
        <div className="mx-auto flex h-16 max-w-5xl items-center justify-between px-6">
          <div className="flex items-center gap-3">
            <BrandMark />
            <p className="font-semibold text-slate-900">MIAA Concessionaire Portal</p>
          </div>
          <div className="flex items-center gap-3">
            <NotificationBell />
            <span className="h-6 w-px bg-slate-200" aria-hidden />
            <UserMenu />
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-5xl px-6 py-8"><Outlet /></main>
    </div>
  );
}
