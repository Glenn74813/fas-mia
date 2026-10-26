import { expect, test } from '@playwright/test';
import { adminToken, nextTotpWindow, onboard } from './team';
import { nav, signIn } from './ui';

const admin = {
  username: process.env.E2E_ADMIN_USERNAME ?? '',
  password: process.env.E2E_ADMIN_PASSWORD ?? '',
  totpSecret: process.env.E2E_ADMIN_TOTP_SECRET ?? '',
};

test.skip(!admin.username || !admin.password || !admin.totpSecret,
  'Set E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD and E2E_ADMIN_TOTP_SECRET for an enrolled test administrator');
test.setTimeout(180_000);

test('the statement of financial position balances on screen and exports to XLSX through a background job', async ({ page }) => {
  const token = await adminToken(admin.username, admin.password, admin.totpSecret);
  const chief = await onboard(token, 'rptchief', ['CHIEF_ACCOUNTANT']);
  await nextTotpWindow();
  await signIn(page, chief);
  await nav(page, 'Financial statements');
  await page.getByLabel('Statement').selectOption({ label: '[PLACEHOLDER] Statement of Financial Position (v1)' });
  await expect(page.getByRole('heading', { name: '[PLACEHOLDER] Statement of Financial Position' })).toBeVisible();
  await expect(page.getByText('BALANCED', { exact: true })).toBeVisible();
  await expect(page.getByText('Accounts with balances not shown on this statement')).toHaveCount(0);
  const totalAssets = (await page.getByRole('row', { name: /^Total assets/ }).getByRole('cell').nth(1).textContent())!;
  const totalLe = (await page.getByRole('row', { name: /^Total liabilities and equity/ }).getByRole('cell').nth(1).textContent())!;
  expect(totalAssets).toBe(totalLe);

  await page.getByRole('button', { name: 'Export XLSX' }).click();
  await expect(page.getByRole('status')).toContainText('Export queued');
  await nav(page, 'My reports');
  const row = page.getByRole('row').filter({ hasText: 'XLSX' }).first();
  await expect(row).toContainText('DONE', { timeout: 30_000 });
  const download = page.waitForEvent('download');
  await row.getByRole('button', { name: 'Download' }).click();
  const file = await download;
  expect(file.suggestedFilename()).toMatch(/^PLC-SFP_.*\.xlsx$/);

  // Movement statements: the cash flow statement reconciles opening and closing cash, and the statement of changes in
  // equity agrees with the equity on the statement of financial position.
  await nav(page, 'Financial statements');
  await page.getByLabel('Statement').selectOption({ label: '[PLACEHOLDER] Cash Flow Statement (v1)' });
  await expect(page.getByRole('heading', { name: '[PLACEHOLDER] Cash Flow Statement' })).toBeVisible();
  await expect(page.getByRole('row', { name: /^Collections from customers and other revenue/ })).toBeVisible();
  await expect(page.getByText('BALANCED', { exact: true })).toBeVisible();
  await expect(page.getByText('Movements not classified by any line of this statement')).toHaveCount(0);
  await page.getByLabel('Statement').selectOption({ label: '[PLACEHOLDER] Statement of Changes in Equity (v1)' });
  await expect(page.getByRole('heading', { name: '[PLACEHOLDER] Statement of Changes in Equity' })).toBeVisible();
  await expect(page.getByText('BALANCED', { exact: true })).toBeVisible();
});

test('an internal auditor exports the audit trail with row hashes and a verified chain', async ({ page }) => {
  const token = await adminToken(admin.username, admin.password, admin.totpSecret);
  const auditor = await onboard(token, 'auditor', ['INTERNAL_AUDITOR']);
  await nextTotpWindow();
  await signIn(page, auditor);
  await nav(page, 'My reports');
  await page.getByLabel('Report').selectOption({ label: 'Audit trail export' });
  await page.getByLabel('Format').selectOption('CSV');
  await page.getByRole('button', { name: 'Queue report' }).click();
  await expect(page.getByRole('status')).toContainText('Queued');
  const row = page.getByRole('row').filter({ hasText: 'Audit trail export' }).first();
  await expect(row).toContainText('DONE', { timeout: 30_000 });
  const download = page.waitForEvent('download');
  await row.getByRole('button', { name: 'Download' }).click();
  const file = await download;
  expect(file.suggestedFilename()).toMatch(/^audit_trail_.*\.csv$/);
  const path = await file.path();
  const text = (await import('node:fs')).readFileSync(path!, 'utf8');
  expect(text).toContain('Hash chain verified intact at export');
  expect(text).toMatch(/"[0-9a-f]{64}"/);
});

test('the chief accountant sees the finance dashboard and schedules a monthly collection summary', async ({ page }) => {
  const token = await adminToken(admin.username, admin.password, admin.totpSecret);
  const chief = await onboard(token, 'dashchief', ['CHIEF_ACCOUNTANT']);
  await nextTotpWindow();
  await signIn(page, chief);
  for (const section of ['Receivables', 'Payables']) await expect(page.getByRole('region', { name: section })).toBeVisible();
  await expect(page.getByRole('figure', { name: 'Collections by month' })).toBeVisible();
  await expect(page.getByRole('figure', { name: 'Payables by age' })).toBeVisible();

  await nav(page, 'My reports');
  await page.getByLabel('Report').selectOption({ label: 'Collection summary' });
  await page.getByLabel('Repeat on a schedule').check();
  const name = `E2E collections ${Date.now().toString(36)}`;
  await page.getByLabel('Schedule name').fill(name);
  await page.getByRole('button', { name: 'Save schedule' }).click();
  await expect(page.getByRole('status')).toContainText('Schedule saved');
  const row = page.getByRole('region', { name: 'Scheduled reports' }).getByRole('row').filter({ hasText: name });
  await expect(row).toContainText('Monthly on day 1 at 07:00');
  page.once('dialog', (d) => d.accept());
  await row.getByRole('button', { name: 'Delete' }).click();
  await expect(row).toHaveCount(0);
});
