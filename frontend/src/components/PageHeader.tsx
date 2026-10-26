import { ChevronRight } from 'lucide-react';
import type { ReactNode } from 'react';
import { Link } from 'react-router';

export interface Crumb {
  label: string;
  to?: string;
}

export function PageHeader({ title, crumbs = [], actions }: { title: string; crumbs?: Crumb[]; actions?: ReactNode }) {
  return (
    <header className="mb-7 flex flex-wrap items-end justify-between gap-4">
      <div className="min-w-0">
        {crumbs.length > 0 && (
          <nav aria-label="Breadcrumb" className="mb-1.5 text-xs font-medium text-slate-500">
            <ol className="flex flex-wrap items-center gap-1">
              {crumbs.map((c, i) => (
                <li key={c.label} className="flex items-center gap-1">
                  {c.to ? <Link className="hover:text-brand-700" to={c.to}>{c.label}</Link> : <span>{c.label}</span>}
                  {i < crumbs.length - 1 && <ChevronRight aria-hidden className="h-3.5 w-3.5 text-slate-400" />}
                </li>
              ))}
            </ol>
          </nav>
        )}
        <h1 className="text-[1.65rem] font-semibold leading-tight tracking-tight text-slate-900">{title}</h1>
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </header>
  );
}
