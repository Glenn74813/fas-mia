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
test.setTimeout(240_000);

test('a full data export is requested by one person, approved by another and downloaded by the first', async ({ page }) => {
  const token = await adminToken(admin.username, admin.password, admin.totpSecret);
  const requester = await onboard(token, 'dxreq', ['SYSTEM_ADMINISTRATOR']);
  const approver = await onboard(token, 'dxfm', ['FINANCE_MANAGER']);
  const purpose = `E2E handover copy ${Date.now()}`;
  await nextTotpWindow();

  await signIn(page, requester);
  await nav(page, 'Data export');
  const main = page.getByRole('main');
  await main.getByLabel('Purpose').fill(purpose);
  await main.getByRole('button', { name: 'Request full export' }).click();
  const row = main.getByRole('row').filter({ hasText: purpose });
  await expect(row).toContainText('REQUESTED');
  await expect(row).toContainText('Waiting for another approver');
  await signOut(page);

  await nextTotpWindow();
  await signIn(page, approver);
  await nav(page, 'Data export');
  await row.getByRole('button', { name: 'Approve' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Approve', exact: true }).click();
  await expect(row).not.toContainText('REQUESTED');
  // The approver cannot download it.
  await expect(row.getByRole('button', { name: 'Download' })).toHaveCount(0);
  await signOut(page);

  await nextTotpWindow();
  await signIn(page, requester);
  await nav(page, 'Data export');
  await expect(row).toContainText('DONE', { timeout: 120_000 });
  const [download] = await Promise.all([page.waitForEvent('download'), row.getByRole('button', { name: 'Download' }).click()]);
  expect(download.suggestedFilename()).toMatch(/^avtech-cas-export-.*\.zip$/);
  const path = await download.path();
  const { statSync } = await import('node:fs');
  expect(statSync(path).size).toBeGreaterThan(1000);
});
