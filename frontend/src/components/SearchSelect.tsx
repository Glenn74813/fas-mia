import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import type { Page } from '../api/types';

interface Props<T extends { id: string }> {
  id: string;
  label: string;
  value: string;
  onChange: (id: string, item: T | null) => void;
  /** Server-side search; called with the typed text (empty for the first page). */
  search: (q: string) => Promise<Page<T>>;
  queryKey: string;
  optionLabel: (item: T) => string;
  /** Hides options the form cannot use (for example inactive records); the current choice is always kept. */
  filter?: (item: T) => boolean;
  /** The currently chosen record, when the form already knows it (edit forms). */
  selected?: T | null;
  disabled?: boolean;
  placeholder?: string;
  /** Table-row layout: the label is for screen readers only and the controls are narrow. */
  compact?: boolean;
}

/**
 * A native select over a long master list (customers, suppliers, items), fed by a server search: it lists the first
 * page of matches and keeps the current choice, so a list of thousands stays usable without loading it whole.
 */
export function SearchSelect<T extends { id: string }>({ id, label, value, onChange, search, queryKey, optionLabel, filter,
  selected, disabled, placeholder = 'Choose…', compact = false }: Props<T>) {
  const [text, setText] = useState('');
  const [q, setQ] = useState('');
  const [chosen, setChosen] = useState<T | null>(null);
  useEffect(() => { const t = setTimeout(() => setQ(text.trim()), 300); return () => clearTimeout(t); }, [text]);
  const results = useQuery({ queryKey: [queryKey, 'search', q], queryFn: () => search(q), placeholderData: keepPreviousData, enabled: !disabled });
  const items = (results.data?.items ?? []).filter((i) => !filter || filter(i));
  const current = [chosen, selected].find((c) => c && c.id === value) ?? items.find((i) => i.id === value) ?? null;
  const options = current && !items.some((i) => i.id === current.id) ? [current, ...items] : items;
  const total = results.data?.totalItems ?? 0;
  const shown = results.data?.items.length ?? 0;
  const cls = compact ? 'w-40 rounded-md border border-slate-300 px-2 py-1' : 'block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm shadow-sm';
  return (
    <div className={compact ? '' : 'space-y-1'}>
      <label htmlFor={id} className={compact ? 'sr-only' : 'block text-sm font-medium text-slate-700'}>{label}</label>
      <div className={compact ? 'flex gap-1' : 'flex gap-2'}>
        {!disabled && <input type="search" value={text} onChange={(e) => setText(e.target.value)} placeholder="Search…"
          aria-label={`Search ${label.toLowerCase()}`}
          className={compact ? 'w-20 rounded border border-slate-300 px-1 py-1' : 'w-32 shrink-0 rounded-md border border-slate-300 px-2 py-2 text-sm'} />}
        <select id={id} value={value} disabled={disabled} className={cls} onChange={(e) => {
          const item = options.find((o) => o.id === e.target.value) ?? null;
          setChosen(item);
          onChange(e.target.value, item);
        }}>
          <option value="">{placeholder}</option>
          {options.map((o) => <option key={o.id} value={o.id}>{optionLabel(o)}</option>)}
        </select>
      </div>
      {!disabled && total > shown && <p className="text-xs text-slate-500">{compact ? `First ${shown} of ${total}` : `Showing the first ${shown} of ${total}; search to narrow the list.`}</p>}
      {!disabled && q && total === 0 && !results.isFetching && <p className="text-xs text-slate-500">No match for “{q}”.</p>}
    </div>
  );
}
