import { describe, expect, it } from 'vitest';
import { fromCents, isValidAmount, sumCents, toCents } from './money';

describe('money', () => {
  it('parses amounts exactly into centavos', () => {
    expect(toCents('1234.5')).toBe(123450n);
    expect(toCents('0.10')).toBe(10n);
    expect(toCents('')).toBe(0n);
    expect(toCents('99999999999999999.99')).toBe(9999999999999999999n);
  });

  it('rejects malformed, negative and over-precise input', () => {
    for (const bad of ['1.005', '-5', '1,000', 'abc', '1e3', '.5']) {
      expect(isValidAmount(bad)).toBe(false);
    }
  });

  it('sums without floating-point drift', () => {
    expect(fromCents(sumCents(['0.10', '0.20']))).toBe('0.30');
    expect(fromCents(sumCents(['12500.50', '0.01']))).toBe('12500.51');
    expect(fromCents(-25n)).toBe('-0.25');
  });
});
