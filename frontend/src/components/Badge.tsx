const tones = {
  green: 'bg-emerald-50 text-emerald-700 ring-emerald-600/20',
  red: 'bg-red-50 text-red-700 ring-red-600/20',
  amber: 'bg-amber-50 text-amber-800 ring-amber-600/25',
  slate: 'bg-slate-50 text-slate-600 ring-slate-500/20',
  blue: 'bg-brand-50 text-brand-700 ring-brand-600/20',
} as const;

const dots = {
  green: 'bg-emerald-500', red: 'bg-red-500', amber: 'bg-amber-500', slate: 'bg-slate-400', blue: 'bg-brand-500',
} as const;

export function Badge({ tone = 'slate', children }: { tone?: keyof typeof tones; children: string }) {
  return (
    <span className={`inline-flex items-center gap-1.5 whitespace-nowrap rounded-full px-2 py-0.5 text-[11px] font-semibold tracking-wide ring-1 ring-inset ${tones[tone]}`}>
      <span aria-hidden className={`h-1.5 w-1.5 rounded-full ${dots[tone]}`} />
      {children}
    </span>
  );
}
