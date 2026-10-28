import { expect, test } from '@playwright/test';
import { adminToken, nextTotpWindow, onboard } from './team';
import { nav, signIn, select } from './ui';

const admin = {
  username: process.env.E2E_ADMIN_USERNAME ?? '',
  password: process.env.E2E_ADMIN_PASSWORD ?? '',
  totpSecret: process.env.E2E_ADMIN_TOTP_SECRET ?? '',
};

test.skip(!admin.username || !admin.password || !admin.totpSecret,
  'Set E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD and E2E_ADMIN_TOTP_SECRET for an enrolled test administrator');
test.setTimeout(120_000);

test('an accountant reads the cash-flow forecast by week and by month and drills into a period', async ({ page }) => {
  const token = await adminToken(admin.username, admin.password, admin.totpSecret);
  const accountant = await onboard(token, 'cfacct', ['ACCOUNTANT']);
  await nextTotpWindow();

  await signIn(page, accountant);
  await nav(page, 'Cash-flow forecast');
  const main = page.getByRole('main');
  await expect(main.getByRole('heading', { name: 'Cash-flow forecast' })).toBeVisible();
  await expect(main.getByText('Cash now')).toBeVisible();
  await expect(main.getByText('Lowest projected balance')).toBeVisible();
  // 13 weeks by default, one row each, and each opens at the previous close.
  const rows = main.getByRole('table').first().locator('tbody tr');
  await expect(rows).toHaveCount(13);
  const closing0 = await rows.nth(0).locator('td').last().textContent();
  expect(await rows.nth(1).locator('td').nth(1).textContent()).toBe(closing0);
  await expect(main.getByRole('columnheader', { name: 'Budgeted spending' })).toBeVisible();

  await select(main.getByLabel('Group by'), 'MONTH');
  await expect(rows).toHaveCount(12);
  await main.getByLabel('Include budgeted spending not yet committed').uncheck();
  await expect(main.getByRole('columnheader', { name: 'Budgeted spending' })).toHaveCount(0);

  await rows.nth(1).getByRole('button').click();
  await expect(main.getByRole('heading', { level: 2 })).toContainText('Expected in');
  await main.getByRole('button', { name: 'Show all' }).click();
  await expect(main.getByRole('heading', { level: 2 })).toHaveText('Expected collections and payments');
});
