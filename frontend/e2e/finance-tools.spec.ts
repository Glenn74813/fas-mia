import { expect, test, type Locator } from '@playwright/test';
import { adminToken, nextTotpWindow, onboard } from './team';
import { nav, signIn, signOut } from './ui';

const admin = {
  username: process.env.E2E_ADMIN_USERNAME ?? '',
  password: process.env.E2E_ADMIN_PASSWORD ?? '',
  totpSecret: process.env.E2E_ADMIN_TOTP_SECRET ?? '',
};

test.skip(!admin.username || !admin.password || !admin.totpSecret,
  'Set E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD and E2E_ADMIN_TOTP_SECRET for an enrolled test administrator');
test.setTimeout(180_000);

/** Picks the option whose text starts with the given code. */
async function choose(select: Locator, code: string) {
  const value = await select.locator('option').filter({ hasText: new RegExp(`^${code}\\b`) }).first().getAttribute('value');
  await select.selectOption(value!);
}

test('ad hoc report, overhead allocation preview and a cost vs market review', async ({ page }) => {
  const token = await adminToken(admin.username, admin.password, admin.totpSecret);
  const accountant = await onboard(token, 'ftacct', ['ACCOUNTANT']);
  const clerk = await onboard(token, 'ftclerk', ['INVENTORY_CLERK']);
  await nextTotpWindow();
  await signIn(page, accountant);
  const main = page.getByRole('main');
  const stamp = Date.now();

  // Ad hoc: ledger lines grouped by account, totals.
  await nav(page, 'Ad hoc reports');
  await main.getByLabel('Dataset').selectOption('LEDGER_LINES');
  const columns = main.getByRole('group', { name: /^Columns/ });
  await columns.getByRole('checkbox', { name: 'Account', exact: true }).check();
  await columns.getByRole('checkbox', { name: 'Debit', exact: true }).check();
  await columns.getByRole('checkbox', { name: 'Credit', exact: true }).check();
  await main.getByRole('group', { name: /Group by/ }).getByRole('checkbox', { name: 'Account', exact: true }).check();
  await main.getByRole('button', { name: 'Run' }).click();
  await expect(main.getByRole('columnheader', { name: 'Total debit' })).toBeVisible();
  await expect(main.locator('tfoot')).toContainText('Total');
  await main.getByLabel('Report name').fill(`Ledger by account ${stamp}`);
  await main.getByRole('button', { name: 'Save' }).click();
  await expect(main.getByRole('button', { name: `Ledger by account ${stamp}` })).toBeVisible();
  await main.getByRole('button', { name: 'Export CSV' }).click();
  await expect(main.getByRole('status')).toContainText('Export queued');

  // Overhead allocation: a cycle and its preview for October 2026.
  await nav(page, 'Overhead allocation');
  await main.getByLabel('Code').fill(`E2E${stamp % 100000}`);
  await main.getByLabel('Name').fill('Utilities by floor area');
  await choose(main.getByLabel('Sender account'), '50204020');
  await choose(main.getByLabel('Sender cost centre'), 'PLC-CC-FIN');
  await main.getByLabel('Key').selectOption('QUANTITY');
  await choose(main.getByLabel(/^Receiver/), 'PLC-CC-OPS');
  await main.getByLabel('Quantity').fill('120');
  await main.getByRole('button', { name: 'Create cycle' }).click();
  const cycle = main.locator('section').filter({ hasText: `E2E${stamp % 100000}` });
  await expect(cycle).toContainText('50204020 / PLC-CC-FIN');
  await cycle.getByRole('button', { name: 'Run or preview' }).click();
  await cycle.getByLabel('Period').selectOption({ label: 'October 2026' });
  await expect(cycle.getByRole('table')).toContainText('PLC-CC-OPS');
  await signOut(page);

  // Cost vs market review.
  await nextTotpWindow();
  await signIn(page, clerk);
  await nav(page, 'Cost vs market review');
  await main.getByLabel('Review date').fill('2026-10-27');
  await main.getByRole('button', { name: 'Start review' }).click();
  await expect(main.getByRole('heading', { name: /Review as of 2026-10-27/ })).toBeVisible();
  const first = main.getByRole('textbox', { name: /^Market value of / }).first();
  await first.fill('0.01');
  await main.getByRole('button', { name: 'Save prices' }).click();
  await expect(main.getByRole('heading', { name: /Review as of 2026-10-27/ })).not.toContainText('write-down ₱0.00');
});
