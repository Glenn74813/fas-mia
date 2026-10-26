import type { ButtonHTMLAttributes } from 'react';

type Variant = 'primary' | 'secondary' | 'danger';

const styles: Record<Variant, string> = {
  primary: 'bg-brand-700 text-white shadow-xs hover:bg-brand-800 active:bg-brand-900 disabled:bg-slate-300 disabled:text-slate-500 disabled:shadow-none',
  secondary: 'border border-slate-300 bg-white text-slate-800 shadow-xs hover:border-slate-400 hover:bg-slate-50 disabled:border-slate-200 disabled:text-slate-400 disabled:shadow-none',
  danger: 'bg-red-600 text-white shadow-xs hover:bg-red-700 disabled:bg-slate-300 disabled:text-slate-500 disabled:shadow-none',
};

interface Props extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  busy?: boolean;
}

/** Disabled while busy so a consequential request cannot be submitted twice from the UI. */
export function Button({ variant = 'primary', busy = false, disabled, children, className = '', ...rest }: Props) {
  return (
    <button
      type="button"
      {...rest}
      disabled={disabled || busy}
      aria-busy={busy || undefined}
      className={`inline-flex h-9 items-center justify-center gap-1.5 whitespace-nowrap rounded-lg px-3.5 text-sm font-medium transition-colors
        focus-visible:outline-2 focus-visible:outline-offset-2 disabled:cursor-not-allowed ${styles[variant]} ${className}`}
    >
      {busy && <span aria-hidden className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-current border-r-transparent" />}
      {busy ? 'Please wait…' : children}
    </button>
  );
}
