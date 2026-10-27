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
test.setTimeout(120_000);

test('an accountant checks a payroll file, sees its problems, then turns a good one into a draft voucher', async ({ page }) => {
  const token = await adminToken(admin.username, admin.password, admin.totpSecret);
  const accountant = await onboard(token, 'ifacct', ['ACCOUNTANT']);
  await nextTotpWindow();
  await signIn(page, accountant);
  await nav(page, 'Inbound files');
  const main = page.getByRole('main');
  const stamp = Date.now();

  // An unbalanced file: kept as INVALID with its problems listed.
  await main.getByLabel('File (.csv, .json or .xml)').setInputFiles({ name: 'payroll-bad.csv', mimeType: 'text/csv',
    buffer: Buffer.from(`account_code,debit,credit\nPLC-5201,100.00,\nPLC-1102,,90.00\nNO-SUCH,5.00,\n`) });
  await main.getByLabel('Reference').fill(`PR-BAD-${stamp}`);
  await main.getByLabel('Payroll period').fill('2026-10');
  await main.getByRole('button', { name: 'Check file' }).click();
  await expect(main.getByRole('alert').filter({ hasText: 'Problems found' })).toContainText('do not balance');

  // A good one: VALIDATED, then drafts.
  await main.getByLabel('File (.csv, .json or .xml)').setInputFiles({ name: 'payroll.csv', mimeType: 'text/csv',
    buffer: Buffer.from(`account_code,cost_center_code,description,debit,credit\nPLC-5201,PLC-CC-FIN,Salaries ${stamp},250.00,\nPLC-1102,,Net pay,,250.00\n`) });
  await main.getByLabel('Reference').fill(`PR-${stamp}`);
  await main.getByLabel('Payroll period').fill('2026-10');
  await main.getByRole('button', { name: 'Check file' }).click();
  const row = main.getByRole('row').filter({ hasText: `PR-${stamp}` });
  await expect(row).toContainText('VALIDATED');
  await row.getByRole('button', { name: 'Create drafts' }).click();
  await expect(row).toContainText('ACCEPTED');
  await main.getByRole('link', { name: '1', exact: true }).click();
  await expect(page.getByRole('main')).toContainText('Payroll interface PR-' + stamp);
  await expect(page.getByRole('main')).toContainText(/DRAFT/i);
});
