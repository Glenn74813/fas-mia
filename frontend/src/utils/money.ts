// Exact decimal arithmetic for PHP amounts in the browser. Amounts travel as strings ("1234.50") and are
// summed as integer centavos (BigInt) - never as binary floating point. The server remains authoritative.

const AMOUNT = /^\d{1,17}(\.\d{1,2})?$/;

/** Parses a user-entered amount (no thousands separators) into centavos, or null if invalid. */
export function toCents(value: string): bigint | null {
  const v = value.trim();
  if (v === '') return 0n;
  if (!AMOUNT.test(v)) return null;
  const [whole, frac = ''] = v.split('.');
  return BigInt(whole!) * 100n + BigInt((frac + '00').slice(0, 2));
}

export function fromCents(cents: bigint): string {
  const negative = cents < 0n;
  const abs = negative ? -cents : cents;
  const s = `${abs / 100n}.${(abs % 100n).toString().padStart(2, '0')}`;
  return negative ? `-${s}` : s;
}

export function isValidAmount(value: string): boolean {
  return toCents(value) !== null;
}

export function sumCents(values: string[]): bigint {
  return values.reduce((acc, v) => acc + (toCents(v) ?? 0n), 0n);
}
