import { expect, request, test } from '@playwright/test';
import { adminToken, nextTotpWindow, onboard, type Member } from './team';
import { act, nav, signIn, signOut } from './ui';

const admin = {
  username: process.env.E2E_ADMIN_USERNAME ?? '',
  password: process.env.E2E_ADMIN_PASSWORD ?? '',
  totpSecret: process.env.E2E_ADMIN_TOTP_SECRET ?? '',
};
const API = process.env.E2E_API_URL ?? 'http://localhost:8080';

test.skip(!admin.username || !admin.password || !admin.totpSecret,
  'Set E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD and E2E_ADMIN_TOTP_SECRET for an enrolled test administrator');
test.setTimeout(300_000);

async function call<T>(token: string, method: 'GET' | 'POST', path: string, body?: unknown): Promise<T> {
  const ctx = await request.newContext();
  // Money-moving POSTs require an Idempotency-Key; a fresh one per call (other endpoints ignore it).
  const headers: Record<string, string> = { Authorization: `Bearer ${token}` };
  if (method === 'POST') headers['Idempotency-Key'] = `e2e-${crypto.randomUUID()}`;
  const res = await ctx.fetch(`${API}/api/v1${path}`, { method, data: body, headers });
  if (!res.ok()) throw new Error(`${method} ${path} -> ${res.status()} ${await res.text()}`);
  const out = (await res.json()) as T;
  await ctx.dispose();
  return out;
}

const tokenOf = (m: Member) => adminToken(m.username, m.password, m.secret);

test('collections are deposited, the statement is imported, reconciled and approved by a second person', async ({ page }) => {
  const token = await adminToken(admin.username, admin.password, admin.totpSecret);
  const accountant = await onboard(token, 'tracct', ['ACCOUNTANT']);
  const chief = await onboard(token, 'trchief', ['CHIEF_ACCOUNTANT']);
  const approver = await onboard(token, 'trappr', ['CHIEF_ACCOUNTANT']);
  const cashier = await onboard(token, 'trcash', ['CASHIER']);
  const manager = await onboard(token, 'trfm', ['FINANCE_MANAGER']);
  await nextTotpWindow();
  const tag = Date.now().toString(36).toUpperCase();
  const today = new Date().toISOString().slice(0, 10);

  // Setup through the API: a dedicated cash-in-bank account so each run reconciles from zero, and a posted collection.
  const [acc, ch, ap, ca] = await Promise.all([tokenOf(accountant), tokenOf(chief), tokenOf(approver), tokenOf(cashier)]);
  const gl = await call<{ id: string }>(ch, 'POST', '/accounts', { code: `E2E-BANK-${tag}`.slice(0, 30), name: `E2E bank ${tag}`,
    accountType: 'ASSET', parentCode: '10102', postable: true, fsLineCode: 'RCA-SFP-CASH', requiresCostCenter: false, budgetControlled: false });
  const bank = await call<{ id: string; code: string }>(ch, 'POST', '/bank-accounts', { code: `E2E-${tag}`, bankName: 'E2E Bank',
    accountName: 'Collections', accountNumberLast4: '4321', glAccountId: gl.id });
  const customer = await call<{ id: string }>(acc, 'POST', '/customers', { code: `T${tag}`, name: `E2E Lessee ${tag}`,
    customerType: 'CONCESSIONAIRE', paymentTermsDays: 30 });
  const revenue = (await call<{ accounts: { id: string; code: string }[] }>(acc, 'GET', '/accounts')).accounts.find((a) => a.code === '40699990')!;
  let inv = await call<{ id: string; version: number }>(acc, 'POST', '/accounts-receivable/billings', { kind: 'INVOICE', customerId: customer.id,
    documentDate: today, description: 'Miscellaneous charges', lines: [{ revenueAccountId: revenue.id, description: 'Charge', netAmount: '12500.00' }] });
  inv = await call(acc, 'POST', `/accounts-receivable/billings/${inv.id}/submit`, { version: inv.version });
  inv = await call(ap, 'POST', `/accounts-receivable/billings/${inv.id}/approve`, { version: inv.version });
  await call(ch, 'POST', `/accounts-receivable/billings/${inv.id}/post`, { version: inv.version });
  const rc = await call<{ id: string; version: number; receiptNumber: string }>(ca, 'POST', '/receipts', { customerId: customer.id,
    receiptDate: today, paymentMethod: 'CASH', amount: '12500.00', taxWithheld: '0', allocations: [{ documentId: inv.id, amount: '12500.00' }] });
  await call(ch, 'POST', `/receipts/${rc.id}/post`, { version: rc.version });
  await nextTotpWindow();

  // Cashier: deposit slip in the UI.
  await signIn(page, cashier);
  await nav(page, 'Deposits');
  await page.getByRole('link', { name: 'New deposit' }).click();
  await page.getByLabel('Bank account').selectOption({ label: `${bank.code} — E2E Bank ****4321` });
  await page.getByLabel('Deposit slip number').fill(`SLIP-${tag}`);
  await page.getByLabel(`Include ${rc.receiptNumber}`).check();
  await page.getByRole('button', { name: 'Save deposit' }).click();
  await expect(page.getByRole('heading', { name: /^Deposit DEP-\d{4}-\d{6}$/ })).toBeVisible();
  await expect(page.getByRole('main').getByRole('button', { name: 'Post' })).toHaveCount(0);
  const depositUrl = page.url();
  await nav(page, 'Daily collections');
  await expect(page.getByRole('cell', { name: rc.receiptNumber })).toBeVisible();
  await signOut(page);

  await signIn(page, chief);
  await page.goto(depositUrl);
  await act(page, 'Post', 'Post');
  await expect(page.getByRole('main').getByText('POSTED', { exact: true })).toBeVisible();
  await signOut(page);

  // Accountant: import the bank's statement (deposit credited, plus a service charge) and reconcile.
  const csv = `date,description,reference,amount\n${today},Deposit SLIP-${tag},SLIP-${tag},12500.00\n${today},Service charge,,-25.00\n`;
  await signIn(page, accountant);
  await nav(page, 'Bank statements');
  await page.getByLabel('Bank account', { exact: true }).selectOption({ label: `${bank.code} ****4321` });
  await page.getByLabel('Format').selectOption('CSV');
  await page.getByLabel('Opening balance').fill('0.00');
  await page.getByLabel('Closing balance').fill('12475.00');
  await page.getByLabel('Statement file').setInputFiles({ name: 'statement.csv', mimeType: 'text/csv', buffer: Buffer.from(csv) });
  await page.getByRole('button', { name: 'Import' }).click();
  await expect(page.getByRole('status')).toContainText('Imported 2 lines');
  await page.getByLabel('Filter bank account').selectOption({ label: bank.code });
  await page.getByRole('button', { name: 'Reconcile' }).click();
  await expect(page.getByRole('heading', { name: /^Bank reconciliation/ })).toBeVisible();
  await page.getByRole('button', { name: 'Auto-match' }).click();
  await expect(page.getByRole('status')).toContainText('1 item(s) matched');
  await expect(page.getByText('Difference: ₱0.00')).toBeVisible();
  await act(page, 'Submit', 'Submit');
  await expect(page.getByRole('main').getByText('SUBMITTED', { exact: true })).toBeVisible();
  const reconUrl = page.url();
  await signOut(page);

  await signIn(page, manager);
  await page.goto(reconUrl);
  await act(page, 'Approve', 'Approve');
  await expect(page.getByRole('main').getByText('APPROVED', { exact: true })).toBeVisible();
  await nav(page, 'Cash position');
  await expect(page.getByRole('row', { name: new RegExp(bank.code) })).toContainText(today);
});
