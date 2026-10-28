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

test('the chief accountant sees the retention schedule and places and releases a legal hold', async ({ page }) => {
  const token = await adminToken(admin.username, admin.password, admin.totpSecret);
  const chief = await onboard(token, 'rtchief', ['CHIEF_ACCOUNTANT']);
  const reason = `E2E hold for audit observation ${Date.now()}`;
  await nextTotpWindow();

  await signIn(page, chief);
  await nav(page, 'Records retention');
  const main = page.getByRole('main');
  await expect(main.getByRole('heading', { name: 'Records retention' })).toBeVisible();
  await expect(main.getByText(/Disposal is not available/)).toBeVisible();
  // Every year is listed with its keep-until date: ten years after it ends.
  const first = main.getByRole('table').first().locator('tbody tr').first();
  const year = Number((await first.locator('td').first().textContent())?.slice(0, 4));
  await expect(first).toContainText(`${year + 10}-12-31`);

  await select(main.getByLabel('Records'), { label: `Fiscal year ${year}` });
  await main.getByLabel('Reference (optional)').fill('AOM E2E');
  await main.getByLabel('Reason').fill(reason);
  await main.getByRole('button', { name: 'Place hold' }).click();
  const hold = main.getByRole('row').filter({ hasText: reason });
  await expect(hold).toContainText('ACTIVE');
  await expect(first).toContainText('ON HOLD');

  await hold.getByRole('button', { name: 'Release' }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByRole('textbox').fill('Observation closed in E2E');
  await dialog.getByRole('button', { name: 'Release', exact: true }).click();
  await expect(dialog).toBeHidden();
  await expect(hold).toContainText('Observation closed in E2E');
  await expect(hold).not.toContainText('ACTIVE');
});
