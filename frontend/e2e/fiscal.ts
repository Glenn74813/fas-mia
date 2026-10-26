import { request } from '@playwright/test';

const API = process.env.E2E_API_URL ?? 'http://localhost:8080';

export interface Period { id: string; name: string; periodNumber: number; periodType: string; status: string; startDate: string; endDate: string;
  version: number }
export interface FiscalYear { id: string; fiscalYear: number; status: string; periods: Period[] }

/**
 * A fiscal year after the current one that satisfies `fits`, creating the next year when none does. Specs that close
 * periods or approve a (one-per-year) budget work there, so the shared development data for the current year stays usable.
 * The token needs PERIOD_OPEN (a chief accountant).
 */
export async function futureYear(token: string, fits: (fy: FiscalYear) => boolean | Promise<boolean>): Promise<FiscalYear> {
  const ctx = await request.newContext({ extraHTTPHeaders: { Authorization: `Bearer ${token}` } });
  try {
    const current = new Date().getFullYear();
    const years = ((await (await ctx.get(`${API}/api/v1/fiscal-years`)).json()) as FiscalYear[]).sort((a, b) => a.fiscalYear - b.fiscalYear);
    for (const fy of years.filter((y) => y.fiscalYear > current && y.status === 'OPEN')) {
      if (await fits(fy)) return fy;
    }
    const next = Math.max(current, ...years.map((y) => y.fiscalYear)) + 1;
    const res = await ctx.post(`${API}/api/v1/fiscal-years`, { data: { fiscalYear: next } });
    if (!res.ok()) throw new Error(`create fiscal year ${next} -> ${res.status()} ${await res.text()}`);
    return (await res.json()) as FiscalYear;
  } finally {
    await ctx.dispose();
  }
}
