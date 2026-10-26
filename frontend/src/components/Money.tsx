import { formatPeso } from '../utils/format';

/** Right-aligned peso amount; zero renders as a dash to keep financial tables readable. */
export function Money({ value, blankZero = false }: { value: string | null | undefined; blankZero?: boolean }) {
  const zero = !value || /^-?0+(\.0+)?$/.test(value);
  return <span className="tabular-nums">{blankZero && zero ? '—' : formatPeso(value ?? '0')}</span>;
}
