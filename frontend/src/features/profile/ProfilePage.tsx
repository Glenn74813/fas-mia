import { Link } from 'react-router';
import { useAuth } from '../../auth/AuthContext';
import { Badge } from '../../components/Badge';
import { PageHeader } from '../../components/PageHeader';
import { visibleNav } from '../../layouts/navigation';
import { formatDateTime, humanize } from '../../utils/format';

/** The signed-in user's own account and access details, for both the internal shell and the concessionaire portal. */
export function ProfilePage({ portal = false }: { portal?: boolean }) {
  const { user, hasPermission, sessionExpiresAt } = useAuth();
  if (!user) return null;
  // The portal never lists internal pages, even as links.
  const shortcuts = portal ? [] : visibleNav(hasPermission).filter((i) => i.to !== '/');
  return (
    <>
      <PageHeader title="My profile" />
      <div className="grid gap-6 md:grid-cols-2">
        <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5" aria-labelledby="acct">
          <h2 id="acct" className="mb-3 font-semibold">Your account</h2>
          <dl className="grid grid-cols-3 gap-2 text-sm">
            <dt className="text-slate-500">Name</dt><dd className="col-span-2">{user.displayName}</dd>
            <dt className="text-slate-500">Username</dt><dd className="col-span-2">{user.username}</dd>
            <dt className="text-slate-500">Email</dt><dd className="col-span-2 break-all">{user.email || '—'}</dd>
            <dt className="text-slate-500">Account type</dt><dd className="col-span-2">{humanize(user.userType)}</dd>
            <dt className="text-slate-500">Last sign-in</dt><dd className="col-span-2">{formatDateTime(user.lastLoginAt)}</dd>
            <dt className="text-slate-500">Session ends</dt><dd className="col-span-2">{formatDateTime(sessionExpiresAt)}</dd>
            <dt className="text-slate-500">MFA</dt>
            <dd className="col-span-2"><Badge tone={user.mfaEnrolled ? 'green' : 'amber'}>{user.mfaEnrolled ? 'Enrolled' : 'Not enrolled'}</Badge></dd>
          </dl>
          <Link to="/change-password" className="mt-4 inline-block text-sm text-brand-700 hover:underline">Change password</Link>
        </section>
        <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5" aria-labelledby="access">
          <h2 id="access" className="mb-3 font-semibold">Your access</h2>
          <div className="mb-3 flex flex-wrap gap-2">
            {user.roles.length === 0 ? <span className="text-sm text-slate-500">No roles assigned.</span>
              : user.roles.map((r) => <Badge key={r} tone="blue">{humanize(r)}</Badge>)}
          </div>
          <p className="text-sm text-slate-600">{user.permissions.length} effective permission(s).</p>
          {shortcuts.length > 0 && (
            <ul className="mt-3 space-y-1 text-sm">
              {shortcuts.map((s) => <li key={s.to}><Link className="text-brand-700 hover:underline" to={s.to}>{s.label}</Link></li>)}
            </ul>
          )}
        </section>
      </div>
    </>
  );
}
