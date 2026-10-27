import { describe, expect, it } from 'vitest';
import { endOfManilaDay, formatDateTime, formatPeso, humanize, manilaDate } from './format';

describe('format', () => {
  it('turns an access end date into the end of that Philippine day, and back', () => {
    expect(endOfManilaDay('2026-12-31')).toBe('2026-12-31T15:59:59.000Z');
    expect(manilaDate('2026-12-31T15:59:59Z')).toBe('2026-12-31');
    expect(manilaDate('2026-12-31T16:30:00Z')).toBe('2027-01-01');
    expect(manilaDate(null)).toBe('');
  });

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
