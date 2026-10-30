import { expect, test } from '@playwright/test';
import { adminToken, nextTotpWindow, onboard } from './team';
import { totp } from './totp';
import { act, nav, signIn, signOut, pick, select } from './ui';

const admin = {
  username: process.env.E2E_ADMIN_USERNAME ?? '',
  password: process.env.E2E_ADMIN_PASSWORD ?? '',
  totpSecret: process.env.E2E_ADMIN_TOTP_SECRET ?? '',
};

test.skip(!admin.username || !admin.password || !admin.totpSecret,
  'Set E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD and E2E_ADMIN_TOTP_SECRET for an enrolled test administrator');
test.setTimeout(300_000);

test('a concession invoice is billed, collected with tax withheld, reconciled and visible in the portal', async ({ page }) => {
  const token = await adminToken(admin.username, admin.password, admin.totpSecret);
  const accountant = await onboard(token, 'aracct', ['ACCOUNTANT']);
  const chief = await onboard(token, 'archief', ['CHIEF_ACCOUNTANT']);
  const poster = await onboard(token, 'arpost', ['CHIEF_ACCOUNTANT']);
  const cashier = await onboard(token, 'arcash', ['CASHIER']);
  const tenant = await onboard(token, 'tenant', ['CONCESSIONAIRE'], 'CONCESSIONAIRE');
  await nextTotpWindow();
  const code = `C${Date.now().toString(36).toUpperCase()}`;
  const name = `E2E Duty Free ${code}`;

  // Accountant: customer master, portal link, and the invoice.
  await signIn(page, accountant);
  await nav(page, 'Customers');
  await page.getByRole('button', { name: 'New customer' }).click();
  await page.getByLabel('Code', { exact: true }).fill(code);
  await page.getByLabel('Registered name').fill(name);
  await page.getByLabel('TIN').fill('123-456-789-00000');
  await page.getByRole('button', { name: 'Save customer' }).click();
  // The list is paged; search, since earlier runs leave many customers.
  await page.getByLabel('Search customers').fill(code);
  await page.getByRole('link', { name: code, exact: true }).click();
  // Linking needs evidence and a second person's approval (C-18): the request alone grants nothing.
  await page.getByLabel('Portal username').fill(tenant.username);
  await page.getByLabel('Evidence of identity and authority').fill('Lease contract signatory; ID checked against contract file');
  await page.getByRole('button', { name: 'Request link' }).click();
  await expect(page.getByText(tenant.username, { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Withdraw request' })).toBeVisible();

  await nav(page, 'Billing');
  await page.getByRole('link', { name: 'New invoice or memo' }).click();
  await pick(page.getByLabel('Customer', { exact: true }), code);
  await select(page.getByLabel('Output VAT'), { label: 'PLC-VAT-OUT (0.1200)' });
  await page.getByLabel('Description', { exact: true }).fill('Concession rent, retail unit T3-21');
  await pick(page.getByLabel('Line 1 account'), '40202050 — Rent/Lease Income');
  await page.getByLabel('Line 1 description').fill('Monthly rent');
  await page.getByLabel('Line 1 net amount').fill('50000.00');
  await page.getByRole('button', { name: 'Save draft' }).click();
  // Net 50,000 + 12% output VAT 6,000 = 56,000.
  await expect(page.getByRole('main')).toContainText('56,000.00');
  await act(page, 'Submit', 'Submit');
  const invoiceUrl = page.url();
  await signOut(page);

  await signIn(page, chief);
  await nav(page, 'Portal access');
  await page.getByLabel(`Decision note for ${tenant.username}`).fill('Signatory verified against lease file');
  await page.getByRole('button', { name: 'Approve access' }).click();
  await expect(page.getByLabel(`Decision note for ${tenant.username}`)).toHaveCount(0);
  await page.goto(invoiceUrl);
  await act(page, 'Approve', 'Approve');
  await signOut(page);

  await signIn(page, poster);
  await page.goto(invoiceUrl);
  await act(page, 'Post', 'Post');
  await expect(page.getByRole('heading', { name: /^Invoice BI-\d{4}-\d{6}$/ })).toBeVisible();
  await signOut(page);

  // Cashier: 55,000 by check plus a 1,000 withholding certificate settles the invoice in full.
  await signIn(page, cashier);
  await nav(page, 'Collections');
  await page.getByRole('link', { name: 'Record collection' }).click();
  await pick(page.getByLabel('Customer', { exact: true }), code);
  await page.getByLabel('Check number / bank').fill(`CHK-${Date.now() % 1_000_000}`);
  await page.getByLabel('Amount received').fill('55000.00');
  await page.getByLabel('Tax withheld by customer').fill('1000.00');
  await page.getByRole('button', { name: 'full' }).click();
  await page.getByRole('button', { name: 'Save receipt' }).click();
  await expect(page.getByRole('heading', { name: /^Receipt RCPT-\d{4}-\d{6}$/ })).toBeVisible();
  await expect(page.getByRole('main').getByRole('button', { name: 'Post' })).toHaveCount(0);
  const receiptUrl = page.url();
  await signOut(page);

  await nextTotpWindow();
  await signIn(page, poster);
  await page.goto(receiptUrl);
  await act(page, 'Post', 'Post');
  await expect(page.getByRole('main').getByText('POSTED', { exact: true })).toBeVisible();
  await nav(page, 'Receivables reports');
  await expect(page.getByText('OK', { exact: true })).toHaveCount(2);
  await signOut(page);

  // The concessionaire sees their own posted invoice, paid in full.
  await page.goto('/login');
  await page.getByLabel('Username or e-mail').fill(tenant.username);
  await page.getByLabel('Password').fill(tenant.password);
  await page.getByRole('button', { name: 'Continue' }).click();
  await page.getByLabel('6-digit code').fill(totp(tenant.secret));
  await page.getByRole('button', { name: 'Verify' }).click();
  await expect(page.getByRole('heading', { name })).toBeVisible();
  await expect(page.getByText('AVTECH Concessionaire Portal')).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Main' })).toHaveCount(0);
  await page.getByRole('link', { name: /^BI-\d{4}-\d{6}$/ }).click();
  await expect(page.getByRole('main')).toContainText('56,000.00');
  await expect(page.getByRole('main')).toContainText('not an official receipt');
  let download = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Download PDF' }).click();
  expect((await download).suggestedFilename()).toMatch(/^BI-\d{4}-\d{6}\.pdf$/);
  await page.getByRole('link', { name: /Back to account/ }).click();
  await page.getByRole('button', { name: 'Statement of account' }).click();
  download = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Download PDF' }).click();
  expect((await download).suggestedFilename()).toMatch(/^statement_.*\.pdf$/);

  // A question through the portal, answered by Finance.
  await page.getByRole('button', { name: 'Inquiries' }).click();
  await page.getByRole('button', { name: 'New inquiry' }).click();
  await page.getByLabel('Subject').fill('Withholding certificate');
  await page.getByLabel('Message').fill('Please confirm you received our Form 2307 for this invoice.');
  await page.getByRole('button', { name: 'Send' }).click();
  const sent = page.getByRole('listitem').filter({ hasText: 'Withholding certificate' });
  await expect(sent).toContainText(/INQ-\d{4}-\d{6}/);
  const reference = (await sent.textContent())!.match(/INQ-\d{4}-\d{6}/)![0];
  // Internal pages are not reachable from a portal session.
  await page.goto('/receivables/customers');
  await expect(page).toHaveURL(/\/portal$/);
  await signOut(page);

  await signIn(page, accountant);
  await nav(page, 'Inquiries');
  const item = page.getByRole('listitem').filter({ hasText: reference });
  await item.getByLabel(`Reply to ${reference}`).fill('Received, thank you. It is filed with the receipt.');
  await item.getByRole('button', { name: 'Send reply' }).click();
  await page.getByRole('button', { name: 'Answered' }).click();
  await expect(page.getByRole('listitem').filter({ hasText: reference })).toContainText('It is filed with the receipt.');
});
