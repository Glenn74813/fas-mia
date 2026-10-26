import { useId, type InputHTMLAttributes } from 'react';

interface Props extends InputHTMLAttributes<HTMLInputElement> {
  label: string;
  error?: string;
  hint?: string;
}

export function Field({ label, error, hint, ...rest }: Props) {
  const id = useId();
  const describedBy = error ? `${id}-error` : hint ? `${id}-hint` : undefined;
  return (
    <div className="space-y-1.5">
      <label htmlFor={id} className="block text-sm font-medium text-slate-700">{label}</label>
      <input
        id={id}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        className={`block h-9 w-full rounded-lg border px-3 text-sm shadow-xs placeholder:text-slate-400 ${error ? 'border-red-500' : 'border-slate-300'}`}
        {...rest}
      />
      {hint && !error && <p id={`${id}-hint`} className="text-xs text-slate-500">{hint}</p>}
      {error && <p id={`${id}-error`} className="text-xs text-red-700">{error}</p>}
    </div>
  );
}
