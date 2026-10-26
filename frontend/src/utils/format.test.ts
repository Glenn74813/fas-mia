import { describe, expect, it } from 'vitest';
import { formatDateTime, formatPeso, humanize } from './format';

describe('format', () => {
  it('shows instants in Philippine time', () => {
    expect(formatDateTime('2026-10-22T00:30:00Z')).toContain('08:30:00');
    expect(formatDateTime(null)).toBe('—');
    expect(formatDateTime('garbage')).toBe('—');
  });

  it('formats decimal-string amounts as pesos without accepting junk', () => {
    expect(formatPeso('1234567.5')).toBe('₱1,234,567.50');
    expect(formatPeso('-10')).toBe('-₱10.00');
    expect(formatPeso('1e5')).toBe('—');
    expect(formatPeso(null)).toBe('—');
  });

  it('humanizes codes', () => {
    expect(humanize('CHIEF_ACCOUNTANT')).toBe('Chief Accountant');
  });
});
