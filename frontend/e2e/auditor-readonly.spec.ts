import { expect, request, test, type Page } from '@playwright/test';
import { adminToken, nextTotpWindow, onboard } from './team';
import { signIn } from './ui';

const admin = {
  username: process.env.E2E_ADMIN_USERNAME ?? '',
  password: process.env.E2E_ADMIN_PASSWORD ?? '',
  totpSecret: process.env.E2E_ADMIN_TOTP_SECRET ?? '',
};
const API = process.env.E2E_API_URL ?? 'http://localhost:8080';

test.skip(!admin.username || !admin.password || !admin.totpSecret,
  'Set E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD and E2E_ADMIN_TOTP_SECRET for an enrolled test administrator');
test.setTimeout(240_000);

/** Controls that change financial data. The auditor may still filter, page, export, view and download. */
const WRITE = /^(\+ ?)?(new|create|add|save|submit|approve|post|import|upload|attach|record|prepare|register|delete|remove|reverse|return|reject|release|pay|soft-close|close period|close…|request reopen|activate|dispose|transfer|run depreciation|clear selected|apply|manage by open item|complete task|generate closing voucher|close year)\b/i;

/** Pages whose write actions the internal-auditor role legitimately has: raising tickets, running access reviews, requesting reports. */
const SKIP = new Set(['Tickets', 'Service levels', 'Access reviews', 'My reports', 'Inbox']);

async function writeControls(page: Page): Promise<string[]> {
  // Some pages poll (notifications, SLA timers), so wait for the data rather than for network idle.
  await page.getByRole('main').locator('.animate-spin').first().waitFor({ state: 'detached', timeout: 10_000 }).catch(() => undefined);
  await page.waitForTimeout(300);
  const names = await page.getByRole('main').getByRole('button').evaluateAll((els) =>
    els.filter((e) => (e as HTMLElement).offsetParent !== null).map((e) => (e.getAttribute('aria-label') ?? e.textContent ?? '').trim()));
  return names.filter((n) => WRITE.test(n));
}

test('the internal auditor can see every financial area but change nothing', async ({ page }) => {
  const token = await adminToken(admin.username, admin.password, admin.totpSecret);
  const auditor = await onboard(token, 'auditor', ['INTERNAL_AUDITOR']);
  await nextTotpWindow();

  await signIn(page, auditor);
  const links = page.getByRole('navigation', { name: 'Main' }).getByRole('link');
  const labels = (await links.allTextContents()).map((s) => s.trim()).filter((s) => s && !SKIP.has(s));
  for (const area of ['Journal vouchers', 'Supplier invoices', 'Billing', 'Deposits', 'Asset register', 'Budgets', 'Fiscal periods', 'Audit trail']) {
    expect(labels, `auditor menu has ${area}`).toContain(area);
  }
  for (const label of ['Users', 'Roles & permissions', 'Opening balances', 'Recurring journals']) {
    expect(labels, `auditor menu lacks ${label}`).not.toContain(label);
  }
  const offenders: string[] = [];
  for (const label of labels) {
    await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: label, exact: true }).click();
    await expect(page.getByRole('main').getByRole('heading').first()).toBeVisible();
    (await writeControls(page)).forEach((b) => offenders.push(`${label}: ${b}`));
  }
  // Document pages too: a posted voucher and a supplier invoice.
  for (const [menu, link] of [['Journal vouchers', /^JV-|^JEV-/], ['Supplier invoices', /\S/]] as const) {
    await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: menu, exact: true }).click();
    const first = page.getByRole('main').getByRole('table').getByRole('link').filter({ hasText: link }).first();
    if (await first.count()) {
      await first.click();
      await expect(page.getByRole('main').getByRole('heading').first()).toBeVisible();
      (await writeControls(page)).forEach((b) => offenders.push(`${menu} detail: ${b}`));
    }
  }
  expect(offenders).toEqual([]);

  // The server refuses writes regardless of the screen.
  await nextTotpWindow();
  const bearer = await adminToken(auditor.username, auditor.password, auditor.secret);
  const ctx = await request.newContext({ extraHTTPHeaders: { Authorization: `Bearer ${bearer}` } });
  const chart = (await (await ctx.get(`${API}/api/v1/accounts`)).json()) as { accounts: { id: string; code: string }[] };
  const acct = (code: string) => chart.accounts.find((a) => a.code === code)!.id;
  const years = (await (await ctx.get(`${API}/api/v1/fiscal-years`)).json()) as { id: string; periods: { id: string; status: string; version: number }[] }[];
  const open = years.flatMap((y) => y.periods).find((p) => p.status === 'OPEN')!;
  const writes: [string, string, unknown][] = [
    ['POST', '/journal-vouchers', { voucherType: 'STANDARD', accountingDate: new Date().toISOString().slice(0, 10), adjustmentPeriod: false,
      description: 'auditor write', lines: [{ accountId: acct('PLC-5201'), debit: '1.00', credit: '0' }, { accountId: acct('PLC-1102'), debit: '0', credit: '1.00' }] }],
    ['POST', '/suppliers', { code: 'AUD-X', name: 'Auditor supplier', vatRegistered: false }],
    ['POST', '/customers', { code: 'AUD-X', name: 'Auditor customer', customerType: 'OTHER', paymentTermsDays: 30 }],
    ['POST', '/budgets', { fiscalYearId: years[0]!.id, name: 'Auditor budget' }],
    ['POST', `/accounting-periods/${open.id}/close`, { version: open.version, waivedChecks: [], justification: null }],
    ['POST', '/cost-centers', { code: 'AUD-X', name: 'Auditor cost centre' }],
    ['POST', '/fiscal-years', { fiscalYear: 2099 }],
  ];
  for (const [method, path, data] of writes) {
    const res = await ctx.fetch(`${API}/api/v1${path}`, { method, data });
    expect(res.status(), `${method} ${path}`).toBe(403);
  }
  // Reading is allowed: the audit trail answers.
  expect((await ctx.get(`${API}/api/v1/audit-events?size=5`)).status()).toBe(200);
  await ctx.dispose();
});
