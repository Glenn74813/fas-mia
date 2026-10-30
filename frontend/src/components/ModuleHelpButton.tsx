import { ArrowRight, Info, X } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { Link, useLocation } from 'react-router';
import { useAuth } from '../auth/AuthContext';
import { DETAIL_ONLY, helpFor, MODULE_HELP } from '../help';
import { NAV_ITEMS } from '../layouts/navigation';

/**
 * The "i" next to a page title. It opens a side panel on how to use the current module and how it connects to the
 * other modules. Renders nothing for pages without help.
 */
export function ModuleHelpButton() {
  const { pathname } = useLocation();
  const { hasPermission } = useAuth();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDialogElement>(null);
  const found = helpFor(pathname);

  useEffect(() => {
    if (open) {
      if (!ref.current?.open) ref.current?.showModal();
    } else {
      ref.current?.close();
    }
  }, [open]);

  if (!found) return null;
  const { help } = found;
  // A connection is a link only when the module has a page of its own that the user may open; otherwise it is plain text.
  const canOpen = (to: string) => {
    if (DETAIL_ONLY.has(to)) return false;
    const nav = NAV_ITEMS.find((n) => n.to === to);
    return !nav || nav.anyOf.length === 0 || nav.anyOf.some(hasPermission);
  };

  return (
    <>
      <button type="button" onClick={() => setOpen(true)} aria-label={`How to use ${help.title}`} title={`How to use ${help.title}`}
        className="inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-full text-slate-400 transition-colors hover:bg-brand-50 hover:text-brand-700 focus-visible:outline-2 focus-visible:outline-brand-500">
        <Info aria-hidden className="h-[18px] w-[18px]" strokeWidth={2} />
      </button>
      <dialog ref={ref} aria-labelledby="module-help-title" onClose={() => setOpen(false)}
        onClick={(e) => { if (e.target === ref.current) setOpen(false); }}
        className="fixed inset-y-0 right-0 left-auto m-0 h-full max-h-none w-full max-w-md bg-white p-0 shadow-xl backdrop:bg-black/30">
        {open && (
          <div className="flex h-full flex-col">
            <div className="flex items-start justify-between gap-3 border-b border-slate-200 px-5 py-4">
              <div>
                <p className="text-xs font-medium uppercase tracking-wide text-brand-700">How to use</p>
                <h2 id="module-help-title" className="text-lg font-semibold text-slate-900">{help.title}</h2>
              </div>
              <button type="button" onClick={() => setOpen(false)} aria-label="Close"
                className="rounded-md p-1.5 text-slate-500 hover:bg-slate-100 hover:text-slate-800">
                <X aria-hidden className="h-5 w-5" />
              </button>
            </div>
            <div className="flex-1 space-y-6 overflow-y-auto px-5 py-5 text-sm text-slate-700">
              <p>{help.summary}</p>
              <section>
                <h3 className="mb-2 font-semibold text-slate-900">Steps</h3>
                <ol className="list-decimal space-y-1.5 pl-5">
                  {help.steps.map((s) => <li key={s}>{s}</li>)}
                </ol>
              </section>
              {help.roles && (
                <section>
                  <h3 className="mb-2 font-semibold text-slate-900">Who does what</h3>
                  <p>{help.roles}</p>
                </section>
              )}
              {help.connections.length > 0 && (
                <section>
                  <h3 className="mb-2 font-semibold text-slate-900">Connected modules</h3>
                  <ul className="space-y-2">
                    {help.connections.map((c) => {
                      const label = MODULE_HELP[c.to]?.title ?? c.to;
                      return (
                        <li key={c.to} className="rounded-lg border border-slate-200 px-3 py-2.5">
                          {canOpen(c.to)
                            ? <Link to={c.to} onClick={() => setOpen(false)} className="inline-flex items-center gap-1 font-medium text-brand-700 hover:underline">
                                {label}<ArrowRight aria-hidden className="h-3.5 w-3.5" /></Link>
                            : <span className="font-medium text-slate-900">{label}</span>}
                          <p className="mt-0.5 text-slate-600">{c.how}</p>
                        </li>
                      );
                    })}
                  </ul>
                </section>
              )}
            </div>
          </div>
        )}
      </dialog>
    </>
  );
}
