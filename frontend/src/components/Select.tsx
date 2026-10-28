import { Children, Fragment, isValidElement, type ReactElement, type ReactNode } from 'react';
import { Combobox, type ComboOption } from './Combobox';

/** What a native select's change handler reads; existing `(e) => set(e.target.value)` handlers work unchanged. */
export interface SelectChange {
  target: { value: string };
}

interface Props {
  value?: string | number | null;
  onChange?: (e: SelectChange) => void;
  children?: ReactNode;
  id?: string;
  'aria-label'?: string;
  className?: string;
  disabled?: boolean;
  required?: boolean;
  title?: string;
}

function text(node: ReactNode): string {
  return Children.toArray(node).map((c) => (typeof c === 'string' || typeof c === 'number' ? String(c)
    : isValidElement<{ children?: ReactNode }>(c) ? text(c.props.children) : '')).join('');
}

function collect(node: ReactNode, out: ComboOption[]) {
  Children.forEach(node, (c) => {
    if (!isValidElement(c)) return;
    const el = c as ReactElement<{ value?: string | number; children?: ReactNode; disabled?: boolean }>;
    if (el.type === 'option') {
      const label = text(el.props.children).trim();
      out.push({ value: el.props.value === undefined ? label : String(el.props.value), label, disabled: el.props.disabled });
    } else if (el.type === 'optgroup' || el.type === Fragment) {
      collect(el.props.children, out);
    }
  });
}

/**
 * Drop-in for a native `<select>` with `<option>` children that renders the searchable Combobox, so every dropdown
 * in the app can be typed into. An option with an empty value becomes the "none" choice.
 */
export function Select({ value, onChange, children, className = '', ...rest }: Props) {
  const all: ComboOption[] = [];
  collect(children, all);
  const empty = all.find((o) => o.value === '');
  return (
    <Combobox {...rest} className={className} value={value == null ? '' : String(value)} options={all.filter((o) => o.value !== '')}
      allowEmpty={!!empty} placeholder={empty?.label || 'Select…'} onChange={(v) => onChange?.({ target: { value: v } })} />
  );
}
