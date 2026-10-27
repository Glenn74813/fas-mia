const MANILA = 'Asia/Manila';

const dateTime = new Intl.DateTimeFormat('en-PH', {
  timeZone: MANILA,
  year: 'numeric',
  month: 'short',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
});

const peso = new Intl.NumberFormat('en-PH', { style: 'currency', currency: 'PHP', minimumFractionDigits: 2 });

/** Formats an ISO-8601 instant for display in Philippine Standard Time. */
export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '—' : `${dateTime.format(d)} PHT`;
}

/**
 * Formats a monetary amount received from the API as a decimal string. Amounts are never parsed
 * into floating point for arithmetic in the UI; this is display-only.
 */
export function formatPeso(amount: string | null | undefined): string {
  if (amount === null || amount === undefined || amount === '') return '—';
  if (!/^-?\d+(\.\d{1,6})?$/.test(amount)) return '—';
  return peso.format(Number(amount));
}

export function humanize(code: string): string {
  return code.toLowerCase().split('_').map((w) => w.charAt(0).toUpperCase() + w.slice(1)).join(' ');
}

const MANILA_DATE = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Manila', year: 'numeric', month: '2-digit', day: '2-digit' });

/** The instant at which access chosen "until" a date ends: 23:59:59 that day, Philippine time. */
export function endOfManilaDay(date: string): string {
  return new Date(`${date}T23:59:59+08:00`).toISOString();
}

/** The Philippine calendar date (YYYY-MM-DD) of an instant, for date inputs; '' when there is none. */
export function manilaDate(iso: string | null | undefined): string {
  if (!iso) return '';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '' : MANILA_DATE.format(d);
}
