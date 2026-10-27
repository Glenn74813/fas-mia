import { expect, test } from '@playwright/test';
import { adminToken, nextTotpWindow, onboard } from './team';
import { nav, signIn, signOut } from './ui';

const admin = {
  username: process.env.E2E_ADMIN_USERNAME ?? '',
  password: process.env.E2E_ADMIN_PASSWORD ?? '',
  totpSecret: process.env.E2E_ADMIN_TOTP_SECRET ?? '',
};

test.skip(!admin.username || !admin.password || !admin.totpSecret,
  'Set E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD and E2E_ADMIN_TOTP_SECRET for an enrolled test administrator');
test.setTimeout(120_000);

test('an internal auditor runs detection and explains a finding; an accountant has no anomalies menu', async ({ page }) => {
  const token = await adminToken(admin.username, admin.password, admin.totpSecret);
  const auditor = await onboard(token, 'anomaud', ['INTERNAL_AUDITOR']);
  const accountant = await onboard(token, 'anomacc', ['ACCOUNTANT']);
  await nextTotpWindow();

  await signIn(page, auditor);
  await nav(page, 'Anomalies');
  const main = page.getByRole('main');
  await expect(main.getByRole('heading', { name: 'Anomalies' })).toBeVisible();
  await main.getByRole('button', { name: 'Run detection now' }).click();
  await expect(main.getByRole('status')).toContainText('Detection finished');

  // Any open finding will do; the new auditor is involved in none of them.
  await main.getByLabel('Status').selectOption('OPEN');
  const row = main.getByRole('row').filter({ has: page.getByRole('button', { name: 'Explain' }) }).first();
  await expect(row, 'detection found at least one open finding in the shared data').toBeVisible();
  const title = (await row.locator('td').nth(1).locator('span').first().textContent())?.trim() ?? '';
  const detail = (await row.locator('td').nth(1).locator('span').nth(2).textContent())?.trim() ?? '';
  await row.getByRole('button', { name: 'Explain' }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByRole('textbox').fill('short');
  await expect(dialog.getByRole('button', { name: 'Mark as explained', exact: true })).toBeDisabled();
  await dialog.getByRole('textbox').fill('Checked the supporting documents in e2e');
  await dialog.getByRole('button', { name: 'Mark as explained', exact: true }).click();
  await expect(dialog).toBeHidden();

  await main.getByLabel('Status').selectOption('EXPLAINED');
  const done = main.getByRole('row').filter({ hasText: detail }).filter({ hasText: 'Checked the supporting documents in e2e' });
  await expect(done).toContainText(title);
  await expect(done).toContainText(auditor.username);
  await signOut(page);

  await nextTotpWindow();
  await signIn(page, accountant);
  await expect(page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Anomalies', exact: true })).toHaveCount(0);
});
