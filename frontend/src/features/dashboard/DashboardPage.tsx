import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router';
import { dashboardApi } from '../../api/endpoints';
import type { AgingTotals, MonthAmount } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { Badge } from '../../components/Badge';
import { BarList, ColumnChart, SERIES, StatTile } from '../../components/charts';
import { ErrorAlert } from '../../components/ErrorAlert';
import { PageHeader } from '../../components/PageHeader';
import { Spinner } from '../../components/Spinner';
import { visibleNav } from '../../layouts/navigation';
import { formatDateTime, formatPeso, humanize } from '../../utils/format';
import { fromCents, toCents } from '../../utils/money';

const MONTH = new Intl.DateTimeFormat('en-PH', { month: 'short', timeZone: 'UTC' });
const month = (ym: string) => MONTH.format(new Date(`${ym}-01T00:00:00Z`));
const monthPoints = (rows: MonthAmount[]) => rows.map((r) => ({ label: month(r.month), value: Number(r.amount), display: formatPeso(r.amount) }));
const minus = (a: string, b: string) => fromCents((toCents(a) ?? 0n) - (toCents(b) ?? 0n));

function agingBars(a: AgingTotals) {
  return [['Not yet due', a.current], ['1–30 days', a.days1to30], ['31–60 days', a.days31to60], ['61–90 days', a.days61to90], ['Over 90 days', a.over90]]
    .map(([label, v]) => ({ label: label!, value: Number(v), display: formatPeso(v) }));
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5" aria-label={title}>
      <h2 className="mb-4 text-[15px] font-semibold text-slate-900">{title}</h2>
      {children}
    </section>
  );
}

/** Each user's own view: only the sections their permissions allow, computed by the server from posted records. */
export function DashboardPage() {
  const { user, hasPermission, sessionExpiresAt } = useAuth();
  const q = useQuery({ queryKey: ['dashboard'], queryFn: dashboardApi.mine, enabled: !!user });
  if (!user) return null;
  const d = q.data;
  const shortcuts = visibleNav(hasPermission).filter((i) => i.to !== '/');
  return (
    <>
      <PageHeader title={`Welcome, ${user.displayName}`} />
      <ErrorAlert error={q.error} />
      {q.isPending && <Spinner />}
      {d && (
        <div className="mb-6 space-y-6">
          {d.workQueue.length > 0 && (
            <Section title="Waiting for you">
              <ul className="grid gap-2 sm:grid-cols-2">
                {d.workQueue.map((w) => (
                  <li key={w.label}><Link to={w.link} className="flex items-center justify-between rounded-md border border-slate-200 px-3 py-2 text-sm hover:bg-slate-50">
                    <span>{w.label}</span><Badge tone="blue">{String(w.count)}</Badge></Link></li>))}
              </ul>
            </Section>
          )}

          {d.cash && (
            <Section title={`Cash as of ${d.asOf}`}>
              <div className="mb-4 grid gap-3 sm:grid-cols-3">
                <StatTile label="Cash in banks and on hand" value={formatPeso(d.cash.total)} />
                <StatTile label="Cash on hand" value={formatPeso(d.cash.cashOnHand)} />
                <StatTile label="Collections not yet deposited" value={formatPeso(d.cash.undeposited)}
                  tone={toCents(d.cash.undeposited)! > 0n ? 'warning' : undefined} hint={toCents(d.cash.undeposited)! > 0n ? 'Deposit with the bank' : undefined} />
              </div>
              <BarList title="Book balance by bank account" valueName="Book balance"
                bars={d.cash.banks.map((b) => ({ label: `${b.code} ${b.name}`, value: Number(b.amount), display: formatPeso(b.amount) }))} />
            </Section>
          )}

          {d.receivables && (
            <Section title="Receivables">
              <div className="mb-4 grid gap-3 sm:grid-cols-3">
                <StatTile label="Open receivables" value={formatPeso(d.receivables.aging.total)} />
                <StatTile label="Past due" value={formatPeso(minus(d.receivables.aging.total, d.receivables.aging.current))}
                  tone={toCents(minus(d.receivables.aging.total, d.receivables.aging.current))! > 0n ? 'warning' : undefined}
                  hint="Open amounts past their due date" />
                <StatTile label="Unapplied collections" value={formatPeso(d.receivables.unapplied)} hint="Credits on customers' accounts" />
              </div>
              <div className="grid gap-6 lg:grid-cols-2">
                <ColumnChart title="Collections by month" valueName="collected" color={SERIES[0]} points={monthPoints(d.receivables.collections)} />
                <BarList title="Receivables by age" valueName="Open amount" color={SERIES[0]} bars={agingBars(d.receivables.aging)} />
              </div>
              <div className="mt-6"><BarList title="Largest customer balances" valueName="Open amount" color={SERIES[0]}
                bars={d.receivables.topCustomers.map((c) => ({ label: `${c.code} ${c.name}`, value: Number(c.amount), display: formatPeso(c.amount) }))} /></div>
            </Section>
          )}

          {d.payables && (
            <Section title="Payables">
              <div className="mb-4 grid gap-3 sm:grid-cols-3">
                <StatTile label="Open payables" value={formatPeso(d.payables.aging.total)} />
                <StatTile label="Due in the next 30 days" value={formatPeso(d.payables.dueNext30Days)} />
                <StatTile label="Past due" value={formatPeso(minus(d.payables.aging.total, d.payables.aging.current))}
                  tone={toCents(minus(d.payables.aging.total, d.payables.aging.current))! > 0n ? 'warning' : undefined} />
              </div>
              <div className="grid gap-6 lg:grid-cols-2">
                <ColumnChart title="Disbursements posted by month" valueName="disbursed" color={SERIES[1]} points={monthPoints(d.payables.disbursements)} />
                <BarList title="Payables by age" valueName="Open amount" color={SERIES[1]} bars={agingBars(d.payables.aging)} />
              </div>
              <div className="mt-6"><BarList title="Largest supplier balances" valueName="Open amount" color={SERIES[1]}
                bars={d.payables.topSuppliers.map((c) => ({ label: `${c.code} ${c.name}`, value: Number(c.amount), display: formatPeso(c.amount) }))} /></div>
            </Section>
          )}

          {d.budget && (
            <Section title={`Budget ${d.budget.fiscalYear}`}>
              <div className="mb-4 grid gap-3 sm:grid-cols-4">
                <StatTile label="Allotment" value={formatPeso(d.budget.allotment)} />
                <StatTile label="Reserved" value={formatPeso(d.budget.reserved)} />
                <StatTile label="Actual" value={formatPeso(d.budget.actual)} />
                <StatTile label="Available" value={formatPeso(d.budget.available)} hint={`${d.budget.utilisationPercent} % used`} />
              </div>
              <BarList title="Highest utilisation" valueName="Used" max={100}
                bars={d.budget.highestUtilisation.map((b) => ({ label: `${b.account}${b.costCenter ? ` · ${b.costCenter}` : ''}`, value: Number(b.utilisationPercent),
                  display: `${b.utilisationPercent} %`, note: `${formatPeso(b.used)} of ${formatPeso(b.allotment)}` }))} />
            </Section>
          )}

          {d.tickets && (
            <Section title="Tickets">
              <div className="grid gap-3 sm:grid-cols-3">
                <StatTile label="Assigned to you" value={String(d.tickets.openAssignedToMe)} />
                <StatTile label="Open" value={String(d.tickets.openTotal)} />
                <StatTile label="Past their SLA" value={String(d.tickets.breached)} tone={d.tickets.breached > 0 ? 'warning' : undefined}
                  hint={d.tickets.breached > 0 ? 'Response or resolution breached' : undefined} />
              </div>
            </Section>
          )}
          {d.notes.map((n) => <p key={n} className="text-xs text-slate-500">{n}</p>)}
        </div>
      )}

      <div className="grid gap-6 md:grid-cols-2">
        <section className="rounded-xl border border-slate-200/80 bg-white shadow-xs p-5" aria-labelledby="acct">
          <h2 id="acct" className="mb-3 font-semibold">Your account</h2>
          <dl className="grid grid-cols-3 gap-2 text-sm">
            <dt className="text-slate-500">Username</dt><dd className="col-span-2">{user.username}</dd>
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
