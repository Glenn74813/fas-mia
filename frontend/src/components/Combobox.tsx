import { useEffect, useId, useLayoutEffect, useMemo, useRef, useState, type CSSProperties } from 'react';

export interface ComboOption {
  value: string;
  label: string;
}

interface Props {
  value: string;
  onChange: (value: string) => void;
  options: ComboOption[];
  /** Label of the empty choice (offered while nothing is typed). */
  placeholder?: string;
  id?: string;
  'aria-label'?: string;
  /** Classes for the text box; a `w-full` width makes the whole control a block. */
  className?: string;
  disabled?: boolean;
  invalid?: boolean;
}

/** Rows rendered at once; typing narrows the rest. */
const LIMIT = 100;

/**
 * A dropdown you can type into (ARIA 1.2 combobox): every word typed must appear in an option's label, so
 * "5020 elec" finds "50204020 — Electricity Expenses". For lists already in the browser, such as the chart of
 * accounts; long server-side lists use SearchSelect.
 */
export function Combobox({ value, onChange, options, placeholder = 'Select…', id, className = '', disabled, invalid, ...rest }: Props) {
  const listId = useId();
  const [open, setOpen] = useState(false);
  const [text, setText] = useState('');
  const [active, setActive] = useState(0);
  const listRef = useRef<HTMLUListElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const [place, setPlace] = useState<CSSProperties>({});
  const selected = options.find((o) => o.value === value);

  const matches = useMemo(() => {
    const terms = text.toLowerCase().split(/\s+/).filter(Boolean);
    return terms.length ? options.filter((o) => { const l = o.label.toLowerCase(); return terms.every((t) => l.includes(t)); }) : options;
  }, [options, text]);
  const rows: ComboOption[] = [...(text ? [] : [{ value: '', label: placeholder }]), ...matches.slice(0, LIMIT)];

  useEffect(() => {
    listRef.current?.querySelector<HTMLElement>(`[data-index="${active}"]`)?.scrollIntoView?.({ block: 'nearest' });
  }, [active, open]);

  // The list is placed against the window, so a table's scroll box (overflow) cannot clip it; it opens upwards when
  // there is more room above the field than below.
  useLayoutEffect(() => {
    if (!open) return;
    const update = () => {
      const r = inputRef.current?.getBoundingClientRect();
      if (!r) return;
      const below = window.innerHeight - r.bottom;
      const up = below < 240 && r.top > below;
      setPlace({ position: 'fixed', left: r.left, minWidth: r.width, maxHeight: Math.max(160, Math.min(288, (up ? r.top : below) - 12)),
        ...(up ? { bottom: window.innerHeight - r.top + 4 } : { top: r.bottom + 4 }) });
    };
    update();
    window.addEventListener('scroll', update, true);
    window.addEventListener('resize', update);
    return () => { window.removeEventListener('scroll', update, true); window.removeEventListener('resize', update); };
  }, [open]);

  const show = () => {
    if (disabled) return;
    setText('');
    setActive(Math.max(0, rows.findIndex((r) => r.value === value)));
    setOpen(true);
  };
  const close = () => { setOpen(false); setText(''); };
  const choose = (o: ComboOption | undefined) => {
    if (o && o.value !== value) onChange(o.value);
    close();
  };

  return (
    <div className={`relative ${className.includes('w-full') ? 'block w-full' : 'inline-block'}`}>
      <input
        {...rest}
        ref={inputRef}
        id={id}
        type="text"
        role="combobox"
        autoComplete="off"
        spellCheck={false}
        aria-expanded={open}
        aria-controls={listId}
        aria-autocomplete="list"
        aria-activedescendant={open && rows[active] ? `${listId}-${active}` : undefined}
        aria-invalid={invalid || undefined}
        disabled={disabled}
        value={open ? text : (selected?.label ?? '')}
        placeholder={open ? (selected?.label ?? 'Type to search…') : placeholder}
        className={`${className} pr-7 text-ellipsis disabled:bg-slate-50 ${invalid ? 'border-amber-500' : ''}`}
        onFocus={show}
        onClick={() => { if (!open) show(); }}
        onBlur={close}
        onChange={(e) => { setText(e.target.value); setActive(0); setOpen(true); }}
        onKeyDown={(e) => {
          if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
            e.preventDefault();
            if (!open) { show(); return; }
            setActive((a) => Math.min(rows.length - 1, Math.max(0, a + (e.key === 'ArrowDown' ? 1 : -1))));
          } else if (e.key === 'Enter' && open) {
            e.preventDefault();
            choose(rows[active]);
          } else if (e.key === 'Escape' && open) {
            e.preventDefault();
            close();
          }
        }}
      />
      <svg aria-hidden viewBox="0 0 20 20" className="pointer-events-none absolute right-2 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400">
        <path fill="currentColor" d="M5.3 7.3a1 1 0 0 1 1.4 0L10 10.6l3.3-3.3a1 1 0 1 1 1.4 1.4l-4 4a1 1 0 0 1-1.4 0l-4-4a1 1 0 0 1 0-1.4Z" />
      </svg>
      {open && (
        <ul ref={listRef} id={listId} role="listbox"
          style={place} className="z-50 w-max max-w-[36rem] overflow-auto rounded-md border border-slate-200 bg-white py-1 text-sm shadow-lg">
          {rows.map((o, i) => (
            <li key={o.value || '__empty'} id={`${listId}-${i}`} data-index={i} role="option" aria-selected={o.value === value}
              className={`cursor-pointer whitespace-nowrap px-3 py-1.5 ${i === active ? 'bg-brand-50 text-brand-900' : ''} ${o.value ? '' : 'text-slate-500'} ${o.value === value ? 'font-medium' : ''}`}
              onMouseDown={(e) => { e.preventDefault(); choose(o); }} onMouseEnter={() => setActive(i)}>
              {o.label}
            </li>
          ))}
          {text && matches.length === 0 && <li className="px-3 py-1.5 text-slate-500">No match for “{text}”.</li>}
          {matches.length > LIMIT && (
            <li className="border-t border-slate-100 px-3 py-1.5 text-xs text-slate-500">
              Showing {LIMIT} of {matches.length}; keep typing to narrow the list.</li>
          )}
        </ul>
      )}
    </div>
  );
}
