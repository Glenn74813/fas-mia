import { ArrowDown, ArrowUp, ArrowUpDown } from 'lucide-react';

/** A sort value as the API takes it: a key, or "-key" for descending. */
export type Sort = string;

const TH = 'px-3 py-2';

/**
 * A column header that sorts the list by `field`. Clicking a column sorts by it (descending first when `descFirst`,
 * suited to dates and amounts); clicking it again reverses. The direction is announced through aria-sort.
 */
export function SortHeader({ label, field, sort, onSort, descFirst = false, align = 'left' }: {
  label: string; field: string; sort: Sort; onSort: (next: Sort) => void; descFirst?: boolean; align?: 'left' | 'right';
}) {
  const active = sort === field || sort === `-${field}`;
  const desc = sort === `-${field}`;
  const next = active ? (desc ? field : `-${field}`) : (descFirst ? `-${field}` : field);
  const Icon = !active ? ArrowUpDown : desc ? ArrowDown : ArrowUp;
  return (
    <th className={`${TH} ${align === 'right' ? 'text-right' : ''}`} aria-sort={active ? (desc ? 'descending' : 'ascending') : 'none'}>
      <button type="button" onClick={() => onSort(next)}
        className={`inline-flex items-center gap-1 uppercase tracking-wider hover:text-slate-800 ${active ? 'text-slate-800' : ''}
          ${align === 'right' ? 'flex-row-reverse' : ''}`}>
        {label}
        <Icon aria-hidden className={`h-3.5 w-3.5 ${active ? '' : 'opacity-40'}`} strokeWidth={2} />
      </button>
    </th>
  );
}
