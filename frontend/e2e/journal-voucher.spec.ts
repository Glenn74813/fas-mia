import { expect, test, type Page } from '@playwright/test';
import { adminToken, nextTotpWindow, onboard, type Member } from './team';
import { totp } from './totp';

const admin = {
  username: process.env.E2E_ADMIN_USERNAME ?? '',
  password: process.env.E2E_ADMIN_PASSWORD ?? '',
  totpSecret: process.env.E2E_ADMIN_TOTP_SECRET ?? '',
};

test.skip(!admin.username || !admin.password || !admin.totpSecret,
  'Set E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD and E2E_ADMIN_TOTP_SECRET for an enrolled test administrator');
test.setTimeout(180_000);

async function signIn(page: Page, m: Member) {
  await page.goto('/login');
  await page.getByLabel('Username or e-mail').fill(m.username);
  await page.getByLabel('Password').fill(m.password);
  await page.getByRole('button', { name: 'Continue' }).click();
  await page.getByLabel('6-digit code').fill(totp(m.secret));
  await page.getByRole('button', { name: 'Verify' }).click();
  await expect(page.getByRole('heading', { name: /Welcome/ })).toBeVisible();
}

async function signOut(page: Page) {
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page).toHaveURL(/\/login$/);
}

async function confirm(page: Page, button: RegExp | string, confirmLabel: string, comment?: string) {
  await page.getByRole('button', { name: button }).first().click();
  const dialog = page.getByRole('dialog');
  if (comment) await dialog.getByLabel(/Comment/).fill(comment);
  await dialog.getByRole('button', { name: confirmLabel, exact: true }).click();
  await expect(dialog).toBeHidden();
}

test('a journal voucher moves from preparation to the posted ledger with four different people', async ({ page }) => {
  const token = await adminToken(admin.username, admin.password, admin.totpSecret);
  const preparer = await onboard(token, 'prep', ['ACCOUNTANT']);
  const reviewer = await onboard(token, 'rev', ['ACCOUNTANT']);
  const approver = await onboard(token, 'chief', ['CHIEF_ACCOUNTANT']);
  const poster = await onboard(token, 'poster', ['CHIEF_ACCOUNTANT']);
  await nextTotpWindow();

  // Preparer drafts a balanced voucher, attaches support and submits.
  await signIn(page, preparer);
  await expect(page.getByText('Placeholder chart of accounts in use.')).toBeVisible();
  const nav = page.getByRole('navigation', { name: 'Main' });
  await nav.getByRole('link', { name: 'Journal vouchers' }).click();
  await page.getByRole('link', { name: 'New voucher' }).click();
  const today = new Date().toISOString().slice(0, 10);
  await page.getByLabel('Accounting date').fill(today);
  const bill = `E2E utilities bill ${Date.now().toString(36)}`;
  await page.getByLabel('Description / particulars').fill(`${bill} for the terminal`);
  await page.getByLabel('Line 1 account').selectOption({ label: 'PLC-5201 — [PLACEHOLDER] Utilities' });
  await page.getByLabel('Line 1 cost centre').selectOption({ label: 'PLC-CC-FIN' });
  await page.getByLabel('Line 1 debit').fill('1500.75');
  await page.getByLabel('Line 2 account').selectOption({ label: 'PLC-1102 — [PLACEHOLDER] Cash in bank - current account' });
  await page.getByLabel('Line 2 credit').fill('1500.7');
  await expect(page.getByText('Out of balance by 0.05')).toBeVisible();
  await page.getByLabel('Line 2 credit').fill('1500.75');
  await expect(page.getByText('Balanced', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Save draft' }).click();
  await expect(page.getByRole('heading', { name: 'Draft voucher' })).toBeVisible();

  await page.getByLabel('Choose supporting document').setInputFiles({ name: 'meralco-bill.pdf', mimeType: 'application/pdf',
    buffer: Buffer.from('%PDF-1.4\n% e2e supporting document\n%%EOF\n') });
  await expect(page.getByRole('button', { name: 'meralco-bill.pdf' })).toBeVisible();
  await confirm(page, 'Submit', 'Submit');
  await expect(page.getByText('Voucher submitted for review.')).toBeVisible();
  const voucherUrl = page.url();
  await signOut(page);

  // Reviewer finds it in the inbox.
  await signIn(page, reviewer);
  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Inbox', exact: true }).click();
  await page.getByRole('searchbox', { name: 'Search' }).fill(bill);
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  await page.getByRole('row', { name: new RegExp(bill) }).getByRole('link').click();
  await confirm(page, 'Approve step', 'Approve', 'Agrees to the bill');
  await expect(page.getByText('Step approved.')).toBeVisible();
  await signOut(page);

  // Chief accountant approves.
  await signIn(page, approver);
  await page.goto(voucherUrl);
  await confirm(page, 'Approve step', 'Approve');
  await expect(page.locator('main').getByText('APPROVED', { exact: true })).toBeVisible();
  await signOut(page);

  // A different chief accountant posts it.
  await signIn(page, poster);
  await page.goto(voucherUrl);
  await confirm(page, /^Post$/, 'Post');
  await expect(page.getByText(/Posted as JEV-\d{4}-\d{6}\./)).toBeVisible();
  await page.getByRole('link', { name: 'View posted entry' }).click();
  await expect(page.getByRole('heading', { name: /Journal entry JEV-/ })).toBeVisible();
  await expect(page.getByText('Posted entries are immutable.')).toBeVisible();
  await expect(page.getByRole('row', { name: /PLC-5201/ })).toContainText('1,500.75');

  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Trial balance' }).click();
  await expect(page.getByText('BALANCED', { exact: true })).toBeVisible();
  await expect(page.getByText('USES PLACEHOLDER ACCOUNTS')).toBeVisible();
  await expect(page.getByRole('row', { name: /PLC-5201/ })).toBeVisible();
  await signOut(page);

  // The preparer was told about each step: reviewed, approved and posted.
  await nextTotpWindow();
  await signIn(page, preparer);
  const bell = page.getByRole('button', { name: /^Notifications, \d+ unread$/ });
  await expect(bell).toHaveAccessibleName('Notifications, 3 unread');
  await bell.click();
  const panel = page.getByRole('dialog', { name: 'Notifications' });
  await expect(panel).toContainText(/was reviewed by/);
  await expect(panel).toContainText(/was approved by/);
  await panel.getByRole('button', { name: /was posted by/ }).click();
  await expect(page).toHaveURL(voucherUrl);
  await expect(page.getByRole('button', { name: 'Notifications, 2 unread' })).toBeVisible();
});
