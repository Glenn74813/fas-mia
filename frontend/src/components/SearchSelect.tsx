import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import type { Page } from '../api/types';
import { Combobox } from './Combobox';

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
 * A searchable dropdown over a long master list (customers, suppliers, items), fed by a server search: typing asks
 * the server, the list shows its first page of matches, and the current choice is kept, so a list of thousands stays
 * usable without loading it whole.
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
  const width = compact ? 'w-48 rounded-md border border-slate-300 px-2 py-1' : 'block w-full rounded-lg border border-slate-300 px-3 py-2 shadow-xs text-sm';
  return (
    <div className={compact ? '' : 'space-y-1.5'}>
      <label htmlFor={id} className={compact ? 'sr-only' : 'block text-sm font-medium text-slate-700'}>{label}</label>
      <Combobox id={id} value={value} disabled={disabled} className={width} placeholder={placeholder}
        options={options.map((o) => ({ value: o.id, label: optionLabel(o) }))} onSearch={setText}
        footer={results.isFetching ? 'Searching…' : total > shown ? `First ${shown} of ${total}; type to narrow the list.` : undefined}
        onChange={(v) => {
          const item = options.find((o) => o.id === v) ?? null;
          setChosen(item);
          onChange(v, item);
        }} />
    </div>
  );
}
