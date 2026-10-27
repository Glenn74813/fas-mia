import { expect, request, test, type APIRequestContext } from '@playwright/test';
import { adminToken, nextTotpWindow, onboard, type Member } from './team';
import { nav, signIn, signOut } from './ui';

const admin = {
  username: process.env.E2E_ADMIN_USERNAME ?? '',
  password: process.env.E2E_ADMIN_PASSWORD ?? '',
  totpSecret: process.env.E2E_ADMIN_TOTP_SECRET ?? '',
};
const API = process.env.E2E_API_URL ?? 'http://localhost:8080';

test.skip(!admin.username || !admin.password || !admin.totpSecret,
  'Set E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD and E2E_ADMIN_TOTP_SECRET for an enrolled test administrator');
test.setTimeout(300_000);

const PDF = { name: 'support.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.4\n% e2e\n%%EOF\n') };

async function client(m: Member): Promise<APIRequestContext> {
  const token = await adminToken(m.username, m.password, m.secret);
  return request.newContext({ baseURL: `${API}/api/v1/`, extraHTTPHeaders: { Authorization: `Bearer ${token}` } });
}

async function call<T>(ctx: APIRequestContext, method: 'GET' | 'POST', path: string, data?: unknown): Promise<T> {
  const res = await ctx.fetch(path, { method, data });
  if (!res.ok()) throw new Error(`${method} ${path} -> ${res.status()} ${await res.text()}`);
  return (await res.json()) as T;
}

test('verified bank details feed an ADA payment file, and posted documents go to the simulated BIR EIS channel', async ({ page }) => {
  const token = await adminToken(admin.username, admin.password, admin.totpSecret);
  const acct = await onboard(token, 'pfacct', ['ACCOUNTANT']);
  const chief = await onboard(token, 'pfchief', ['CHIEF_ACCOUNTANT']);
  const poster = await onboard(token, 'pfpost', ['CHIEF_ACCOUNTANT']);
  const dvo = await onboard(token, 'pfdvo', ['DISBURSEMENT_OFFICER']);
  const fm = await onboard(token, 'pffm', ['FINANCE_MANAGER']);
  const cashier = await onboard(token, 'pfcash', ['CASHIER']);
  const budgetOfficer = await onboard(token, 'pfbgt', ['BUDGET_OFFICER']);
  await nextTotpWindow();
  const tag = Date.now().toString(36).toUpperCase();
  const today = new Date().toISOString().slice(0, 10);

  // Setup through the API: a supplier with bank details, and an approved DV paying its invoice.
  const [a, c, p, d, f, b] = await Promise.all([client(acct), client(chief), client(poster), client(dvo), client(fm), client(budgetOfficer)]);
  const supplier = await call<{ id: string }>(a, 'POST', 'suppliers', { code: `B${tag}`, name: `E2E Electrical ${tag}`, vatRegistered: false });
  await call(a, 'POST', `suppliers/${supplier.id}/bank-accounts`, { bankName: 'E2E Bank', accountName: `E2E Electrical ${tag}`,
    accountNumber: '1234-5678-9012' });
  const accounts = (await call<{ accounts: { id: string; code: string }[] }>(a, 'GET', 'accounts')).accounts;
  const cc = (await call<{ id: string; code: string }[]>(a, 'GET', 'cost-centers')).find((x) => x.code === 'PLC-CC-OPS')!;
  let inv = await call<{ id: string; version: number; payableAmount: string }>(a, 'POST', 'accounts-payable/invoices', {
    supplierId: supplier.id, invoiceNumber: `SI-${tag}`, invoiceDate: today, dueDate: today, accountingDate: today, description: 'Wiring repairs',
    lines: [{ accountId: accounts.find((x) => x.code === 'PLC-5202')!.id, costCenterId: cc.id, netAmount: '8000.00' }] });
  await a.post(`accounts-payable/invoices/${inv.id}/documents`, { multipart: { file: PDF } });
  inv = await call(a, 'POST', `accounts-payable/invoices/${inv.id}/submit`, { version: inv.version });
  inv = await call(c, 'POST', `accounts-payable/invoices/${inv.id}/approve`, { version: inv.version });
  inv = await call(p, 'POST', `accounts-payable/invoices/${inv.id}/post`, { version: inv.version });
  let dv = await call<{ id: string; version: number; dvNumber: string | null }>(d, 'POST', 'disbursement-vouchers', { supplierId: supplier.id,
    particulars: 'Payment for wiring repairs', allocations: [{ invoiceId: inv.id, amount: inv.payableAmount }] });
  await d.post(`disbursement-vouchers/${dv.id}/documents`, { multipart: { file: PDF } });
  dv = await call(d, 'POST', `disbursement-vouchers/${dv.id}/submit`, { version: dv.version });
  dv = await call(b, 'POST', `disbursement-vouchers/${dv.id}/approve`, { version: dv.version, comment: 'Within the COB' });
  dv = await call(a, 'POST', `disbursement-vouchers/${dv.id}/approve`, { version: dv.version, comment: 'Documents complete' });
  dv = await call(f, 'POST', `disbursement-vouchers/${dv.id}/approve`, { version: dv.version, comment: 'Approved' });
  await Promise.all([a, c, p, d, f, b].map((x) => x.dispose()));
  await nextTotpWindow();

  // Chief accountant: verifies the bank details (not the person who entered them) and queues posted documents for BIR EIS.
  await signIn(page, chief);
  await page.goto('/payables/suppliers');
  // The list is paged and grows with every run: search for this run's supplier.
  await page.getByLabel('Search suppliers').fill(`B${tag}`);
  await page.getByRole('row', { name: new RegExp(`B${tag}`) }).getByRole('link', { name: 'Bank details' }).click();
  await expect(page.getByText('****9012')).toBeVisible();
  await page.getByLabel('Verification note').fill('Confirmed by call-back to the number on file');
  await page.getByRole('button', { name: 'Verify' }).click();
  await expect(page.getByRole('main').getByText('VERIFIED', { exact: true })).toBeVisible();
  await nav(page, 'Integrations');
  await expect(page.getByText('Loopback simulation (nothing leaves CAS)')).toBeVisible();
  await page.getByRole('button', { name: 'Queue', exact: true }).click();
  await expect(page.getByRole('status')).toContainText('queued');
  await signOut(page);

  // Cashier: the voucher is now eligible; the file issues the ADA instruction.
  await signIn(page, cashier);
  await nav(page, 'Payment files');
  await page.getByLabel(`Include ${dv.dvNumber}`).check();
  await page.getByLabel('Debit account').selectOption({ index: 1 });
  await page.getByRole('button', { name: 'Create payment file' }).click();
  // This run's file: earlier runs leave theirs in the list, so pick the row created by this cashier.
  const batchRow = page.getByRole('row', { name: new RegExp(`PAY-\\d{4}-\\d{6}.*${cashier.username}`) });
  await expect(batchRow).toBeVisible();
  const download = page.waitForEvent('download');
  await batchRow.getByRole('button', { name: 'Download' }).click();
  const file = await download;
  const text = (await import('node:fs')).readFileSync((await file.path())!, 'utf8');
  expect(text).toContain('CAS-GENERIC-PAYMENT-FILE');
  expect(text).toContain('"123456789012"');
  expect(text).toContain(dv.dvNumber!);
});
