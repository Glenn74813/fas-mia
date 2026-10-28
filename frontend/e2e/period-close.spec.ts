import { expect, request, test } from '@playwright/test';
import { futureYear } from './fiscal';
import { adminToken, nextTotpWindow, onboard } from './team';
import { nav, signIn, signOut } from './ui';

const admin = {
  username: process.env.E2E_ADMIN_USERNAME ?? '',
  password: process.env.E2E_ADMIN_PASSWORD ?? '',
  totpSecret: process.env.E2E_ADMIN_TOTP_SECRET ?? '',
};
const API = process.env.E2E_API_URL ?? 'http://localhost:8080';

test.skip(!admin.username || !admin.password || !admin.totpSecret,
  'Set E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD and E2E_ADMIN_TOTP_SECRET for an enrolled test administrator');
test.setTimeout(300_000);

test('a period closes only with justified waivers, refuses postings once closed, and reopens with a second person', async ({ page }) => {
  const token = await adminToken(admin.username, admin.password, admin.totpSecret);
  const chief = await onboard(token, 'pcchief', ['CHIEF_ACCOUNTANT']);
  const accountant = await onboard(token, 'pcacct', ['ACCOUNTANT']);
  const manager = await onboard(token, 'pcfm', ['FINANCE_MANAGER']);
  await nextTotpWindow();
  // Work in a future year so the current year's shared data stays open for the other specs.
  const chiefToken = await adminToken(chief.username, chief.password, chief.secret);
  const fy = await futureYear(chiefToken, (y) => y.periods.some((p) => p.periodType === 'REGULAR' && p.status === 'FUTURE'));
  const period = fy.periods.filter((p) => p.periodType === 'REGULAR' && p.status === 'FUTURE').sort((a, b) => a.periodNumber - b.periodNumber)[0]!;
  await nextTotpWindow();

  await signIn(page, chief);
  await nav(page, 'Fiscal periods');
  const year = page.locator('section').filter({ has: page.getByRole('heading', { name: new RegExp(`^Fiscal year ${fy.fiscalYear}`) }) });
  const row = year.getByRole('row').filter({ hasText: period.name });
  await row.getByRole('button', { name: 'Open' }).click();
  await expect(row.getByText('OPEN', { exact: true })).toBeVisible();

  // The checklist: earlier periods are still open, so closing needs every failing waivable check waived, with a reason.
  await row.getByRole('button', { name: 'Close…' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByRole('heading', { name: `Close ${period.name}` })).toBeVisible();
  await expect(dialog.getByText('All earlier periods are closed')).toBeVisible();
  const close = dialog.getByRole('button', { name: 'Close period' });
  await expect(close).toBeDisabled();
  const waivers = dialog.getByRole('checkbox', { name: 'waive' });
  const count = await waivers.count();
  expect(count).toBeGreaterThan(0);
  for (let i = 0; i < count; i++) await waivers.nth(i).check();
  await expect(close).toBeDisabled();
  await dialog.getByLabel(/Justification for waivers/).fill('E2E: earlier test periods are out of scope for this close');
  await close.click();
  await expect(dialog).toBeHidden();
  await expect(row.getByText('CLOSED', { exact: true })).toBeVisible();
  await signOut(page);

  // Nothing can be posted into the closed period.
  const accToken = await adminToken(accountant.username, accountant.password, accountant.secret);
  const ctx = await request.newContext({ extraHTTPHeaders: { Authorization: `Bearer ${accToken}` } });
  const chart = (await (await ctx.get(`${API}/api/v1/accounts`)).json()) as { accounts: { id: string; code: string }[] };
  const id = (code: string) => chart.accounts.find((a) => a.code === code)!.id;
  const cc = ((await (await ctx.get(`${API}/api/v1/cost-centers`)).json()) as { id: string; code: string }[]).find((c) => c.code === 'PLC-CC-FIN')!.id;
  const res = await ctx.post(`${API}/api/v1/journal-vouchers`, { data: { voucherType: 'STANDARD', accountingDate: period.startDate,
    adjustmentPeriod: false, description: 'E2E posting into a closed period', lines: [
      { accountId: id('50204020'), costCenterId: cc, debit: '10.00', credit: '0' }, { accountId: id('10102020'), debit: '0', credit: '10.00' }] } });
  expect(res.status()).toBeGreaterThanOrEqual(400);
  expect(await res.text()).toMatch(/closed/i);
  await ctx.dispose();

  // Reopening is requested by one person and decided by another with reopen authority.
  await nextTotpWindow();
  await signIn(page, chief);
  await nav(page, 'Fiscal periods');
  await row.getByRole('button', { name: 'Request reopen' }).click();
  await page.getByRole('dialog').getByLabel(/Reason/).fill('E2E: a late supplier invoice belongs in this month');
  await page.getByRole('dialog').getByRole('button', { name: 'Submit request' }).click();
  await expect(page.getByText(/Pending reopen requests/)).toBeVisible();
  await expect(page.locator('section').filter({ hasText: 'Pending reopen requests' }).getByRole('button', { name: 'Approve' })).toHaveCount(0);
  await signOut(page);

  await signIn(page, manager);
  await nav(page, 'Fiscal periods');
  const request_ = page.locator('li').filter({ hasText: period.name }).filter({ hasText: 'late supplier invoice' });
  await request_.getByRole('button', { name: 'Approve' }).click();
  await expect(request_).toHaveCount(0);
  await expect(row.getByText('OPEN', { exact: true })).toBeVisible();
});
