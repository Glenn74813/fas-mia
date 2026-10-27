import { FileCheck2, ShieldCheck, Users } from 'lucide-react';
import type { ReactNode } from 'react';
import { BrandMark } from './InternalLayout';

const POINTS = [
  { icon: ShieldCheck, text: 'Two-step sign-in for every account' },
  { icon: Users, text: 'Every posting reviewed and approved by a second person' },
  { icon: FileCheck2, text: 'A tamper-evident audit trail of every action' },
];

export function AuthLayout({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="flex min-h-screen bg-white">
      <section aria-hidden className="relative hidden w-[44%] max-w-xl flex-col justify-between overflow-hidden bg-ink-900 p-12 text-white lg:flex">
        <div className="pointer-events-none absolute -right-32 -top-32 h-96 w-96 rounded-full bg-brand-600/25 blur-3xl" />
        <div className="pointer-events-none absolute -bottom-40 -left-20 h-96 w-96 rounded-full bg-brand-400/10 blur-3xl" />
        <div className="relative flex items-center gap-4">
          <BrandMark className="h-16 w-16" />
          <div className="leading-tight">
            <p className="text-sm font-semibold">Manila International Airport Authority</p>
            <p className="text-xs text-slate-400">Finance and Accounting</p>
          </div>
        </div>
        <div className="relative">
          <h2 className="text-3xl font-semibold leading-tight tracking-tight">Computerized<br />Accounting System</h2>
          <p className="mt-4 max-w-sm text-sm leading-relaxed text-slate-300">
            General ledger, payables, receivables, treasury, assets, inventory and budgets in one controlled system.</p>
          <ul className="mt-8 space-y-3.5">
            {POINTS.map(({ icon: Icon, text }) => (
              <li key={text} className="flex items-center gap-3 text-sm text-slate-200">
                <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-white/10 ring-1 ring-white/10">
                  <Icon className="h-4 w-4 text-brand-300" strokeWidth={1.75} /></span>
                {text}
              </li>
            ))}
          </ul>
        </div>
        <p className="relative text-xs text-slate-500">Authorised users only. Activity is recorded.</p>
      </section>
      <div className="flex flex-1 items-center justify-center bg-canvas px-4 py-12 lg:bg-white">
        <main className="w-full max-w-sm rounded-2xl bg-white p-8 shadow-xl ring-1 ring-slate-200 lg:p-0 lg:shadow-none lg:ring-0">
          <div className="mb-8 flex items-center gap-3 lg:hidden"><BrandMark className="h-12 w-12" />
            <p className="text-sm font-semibold text-slate-900">MIAA Computerized Accounting System</p></div>
          <p className="text-xs font-semibold uppercase tracking-[0.08em] text-brand-700">Manila International Airport Authority</p>
          <h1 className="mb-7 mt-1.5 text-2xl font-semibold tracking-tight text-slate-900">{title}</h1>
          {children}
        </main>
      </div>
    </div>
  );
}
