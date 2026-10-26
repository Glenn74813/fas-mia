import { expect, test } from '@playwright/test';
import { adminToken, nextTotpWindow, onboard } from './team';
import { act, nav, PDF, signIn, signOut } from './ui';

const admin = {
  username: process.env.E2E_ADMIN_USERNAME ?? '',
  password: process.env.E2E_ADMIN_PASSWORD ?? '',
  totpSecret: process.env.E2E_ADMIN_TOTP_SECRET ?? '',
};

test.skip(!admin.username || !admin.password || !admin.totpSecret,
  'Set E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD and E2E_ADMIN_TOTP_SECRET for an enrolled test administrator');
test.setTimeout(240_000);

test('a supplier invoice is recognised, paid by check and reconciled, each step by a different person', async ({ page }) => {
  const token = await adminToken(admin.username, admin.password, admin.totpSecret);
  const accountant = await onboard(token, 'apacct', ['ACCOUNTANT']);
  const certifier = await onboard(token, 'cert', ['ACCOUNTANT']);
  const chief = await onboard(token, 'chief', ['CHIEF_ACCOUNTANT']);
  const poster = await onboard(token, 'poster', ['CHIEF_ACCOUNTANT']);
  const dvOfficer = await onboard(token, 'dvo', ['DISBURSEMENT_OFFICER']);
  const manager = await onboard(token, 'fm', ['FINANCE_MANAGER']);
  const cashier = await onboard(token, 'cash', ['CASHIER']);
  await nextTotpWindow();
  const supplierCode = `E2E${Date.now().toString(36).toUpperCase()}`;
  const today = new Date().toISOString().slice(0, 10);

  // Accountant: supplier master record, then the invoice.
  await signIn(page, accountant);
  await nav(page, 'Suppliers');
  await page.getByRole('button', { name: 'New supplier' }).click();
  await page.getByLabel('Code').fill(supplierCode);
  await page.getByLabel('Registered name').fill(`E2E Aircon Services ${supplierCode}`);
  await page.getByLabel('Default withholding tax').selectOption({ label: 'PLC-EWT-SERV (0.0200)' });
  await page.getByLabel('VAT-registered').check();
  await page.getByRole('button', { name: 'Save supplier' }).click();
  await expect(page.getByRole('cell', { name: supplierCode, exact: true })).toBeVisible();

  await nav(page, 'Supplier invoices');
  await page.getByRole('link', { name: 'Register invoice' }).click();
  await page.getByLabel('Search supplier').fill(supplierCode);
  await page.getByLabel('Supplier', { exact: true }).selectOption({ label: `${supplierCode} — E2E Aircon Services ${supplierCode}` });
  await page.getByLabel('Supplier invoice number').fill(`SI-${supplierCode}`);
  await page.getByLabel('Input VAT').selectOption({ label: 'PLC-VAT-IN (0.1200)' });
  await page.getByLabel('Accounting date').fill(today);
  await page.getByLabel('Description', { exact: true }).fill('Aircon preventive maintenance, terminal 3');
  await page.getByLabel('Line 1 account').selectOption({ label: 'PLC-5202 — [PLACEHOLDER] Repairs and maintenance' });
  await page.getByLabel('Line 1 cost centre').selectOption({ label: 'PLC-CC-OPS' });
  await page.getByLabel('Line 1 net amount').fill('20000.00');
  await page.getByRole('button', { name: 'Save draft' }).click();
  // Net 20,000 + 12% VAT 2,400 - 2% EWT 400 = 22,000 payable.
  await expect(page.getByText('Payable to supplier')).toBeVisible();
  await expect(page.getByRole('main')).toContainText('22,000.00');
  await page.getByLabel('Choose supporting document').setInputFiles(PDF);
  await expect(page.getByRole('button', { name: 'support.pdf' })).toBeVisible();
  await act(page, 'Submit', 'Submit');
  const invoiceUrl = page.url();
  await signOut(page);

  await signIn(page, chief);
  await page.goto(invoiceUrl);
  await act(page, 'Approve', 'Approve');
  await signOut(page);

  await signIn(page, poster);
  await page.goto(invoiceUrl);
  await act(page, 'Post', 'Post');
  await expect(page.getByRole('main').getByText('POSTED', { exact: true })).toBeVisible();
  await signOut(page);

  // Disbursement officer prepares the DV for the full open balance.
  await signIn(page, dvOfficer);
  await nav(page, 'Disbursement vouchers');
  await page.getByRole('link', { name: 'New DV' }).click();
  await page.getByLabel('Search payee (supplier)').fill(supplierCode);
  await page.getByLabel('Payee (supplier)', { exact: true }).selectOption({ label: `${supplierCode} — E2E Aircon Services ${supplierCode}` });
  await page.getByLabel('Particulars').fill('Payment for aircon maintenance');
  await page.getByRole('button', { name: 'full' }).click();
  await page.getByRole('button', { name: 'Save draft' }).click();
  await expect(page.getByRole('heading', { name: 'Draft disbursement voucher' })).toBeVisible();
  await page.getByLabel('Choose supporting document').setInputFiles(PDF);
  await expect(page.getByRole('button', { name: 'support.pdf' })).toBeVisible();
  await act(page, 'Submit', 'Submit');
  const dvUrl = page.url();
  await signOut(page);

  await signIn(page, certifier);
  await page.goto(dvUrl);
  await act(page, 'Certify', 'Approve', 'Documents complete, funds available');
  await signOut(page);

  await signIn(page, manager);
  await page.goto(dvUrl);
  await act(page, 'Approve', 'Approve');
  await expect(page.getByRole('heading', { name: /^DV-\d{4}-\d{6}$/ })).toBeVisible();
  await signOut(page);

  // Cashier issues the check, then confirms its release.
  await signIn(page, cashier);
  await page.goto(dvUrl);
  await page.getByRole('main').getByRole('button', { name: 'Issue payment' }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('Bank account').selectOption({ index: 1 });
  await dialog.getByLabel('Check number').fill(`C${Date.now() % 1_000_000}`);
  await dialog.getByRole('button', { name: 'Issue payment', exact: true }).click();
  await expect(dialog).toBeHidden();
  await act(page, 'Confirm release', 'Confirm release');
  await expect(page.getByRole('main').getByText('PAID', { exact: true })).toBeVisible();
  await signOut(page);

  // Accounting posts the payment; the AP sub-ledger reconciles to the GL.
  await nextTotpWindow();
  await signIn(page, poster);
  await page.goto(dvUrl);
  await act(page, 'Post', 'Post');
  await expect(page.getByRole('main').getByText('POSTED', { exact: true })).toBeVisible();
  await nav(page, 'Payables reports');
  await expect(page.getByText('RECONCILED', { exact: true })).toBeVisible();
});
