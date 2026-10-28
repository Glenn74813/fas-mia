import { expect, request, test, type APIRequestContext } from '@playwright/test';
import { adminToken, nextTotpWindow, onboard, type Member } from './team';
import { nav, signIn, signOut } from './ui';

const admin = {
  username: process.env.E2E_ADMIN_USERNAME ?? '',
  password: process.env.E2E_ADMIN_PASSWORD ?? '',
  totpSecret: process.env.E2E_ADMIN_TOTP_SECRET ?? '',
};
const API = process.env.E2E_API_URL ?? 'http://127.0.0.1:8080';

test.skip(!admin.username || !admin.password || !admin.totpSecret,
  'Set E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD and E2E_ADMIN_TOTP_SECRET for an enrolled test administrator');
test.setTimeout(240_000);

async function client(m: Member): Promise<APIRequestContext> {
  const token = await adminToken(m.username, m.password, m.secret);
  return request.newContext({ baseURL: `${API}/api/v1/`, extraHTTPHeaders: { Authorization: `Bearer ${token}` } });
}

async function call<T>(ctx: APIRequestContext, method: 'GET' | 'POST', path: string, data?: unknown): Promise<T> {
  const res = await ctx.fetch(path, { method, data });
  if (!res.ok()) throw new Error(`${method} ${path} -> ${res.status()} ${await res.text()}`);
  return (await res.json()) as T;
}

const csv = (name: string, text: string) => ({ name, mimeType: 'text/csv', buffer: Buffer.from(text) });

test('an opening trial balance is posted through the journal workflow and open invoices are applied by a second person', async ({ page }) => {
  const token = await adminToken(admin.username, admin.password, admin.totpSecret);
  const acct = await onboard(token, 'mgacct', ['ACCOUNTANT']);
  const rev = await onboard(token, 'mgrev', ['ACCOUNTANT']);
  const chief = await onboard(token, 'mgchief', ['CHIEF_ACCOUNTANT']);
  const poster = await onboard(token, 'mgpost', ['CHIEF_ACCOUNTANT']);
  await nextTotpWindow();
  const tag = Date.now().toString(36).toUpperCase();

  const a = await client(acct);
  const customer = await call<{ id: string; code: string }>(a, 'POST', 'customers', { code: `M${tag}`, name: `E2E Migrated Tenant ${tag}`,
    customerType: 'CONCESSIONAIRE', paymentTermsDays: 30 });
  await a.dispose();
  await nextTotpWindow();

  // Accountant: uploads the opening trial balance and turns it into a draft opening voucher.
  await signIn(page, acct);
  await nav(page, 'Opening balances');
  await page.getByLabel('Cutover date').fill('2026-01-01');
  await page.getByLabel('Batch description').fill(`E2E opening balances ${tag}`);
  await page.getByLabel('CSV file').setInputFiles(csv(`tb-${tag}.csv`, 'account_code,cost_center_code,subsidiary_code,description,debit,credit\n'
    + `10301010,,${customer.code},Receivable,25000.00,\n30701010,,,Accumulated surplus,,25000.00\n`));
  await page.getByRole('button', { name: 'Upload and validate' }).click();
  await expect(page).toHaveURL(/\/migration\/[0-9a-f-]{36}$/);
  await expect(page.getByRole('main').getByText('VALIDATED', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Create opening voucher' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Confirm', exact: true }).click();
  await expect(page.getByRole('main').getByText('APPLIED', { exact: true })).toBeVisible();
  const voucherHref = await page.getByRole('link', { name: 'draft' }).getAttribute('href');
  const voucherId = voucherHref!.split('/').pop()!;
  await signOut(page);
  await nextTotpWindow();

  // The voucher goes through the normal journal workflow.
  const [ap, r, c, p] = await Promise.all([client(acct), client(rev), client(chief), client(poster)]);
  let v = await call<{ version: number; voucherNumber: string | null; status: string }>(ap, 'GET', `journal-vouchers/${voucherId}`);
  v = await call(ap, 'POST', `journal-vouchers/${voucherId}/submit`, { version: v.version });
  v = await call(r, 'POST', `journal-vouchers/${voucherId}/approve`, { version: v.version, comment: 'Agrees with legacy trial balance' });
  v = await call(c, 'POST', `journal-vouchers/${voucherId}/approve`, { version: v.version, comment: 'Approved' });
  const posted = await p.post(`journal-vouchers/${voucherId}/post`, { data: { version: v.version },
    headers: { 'Idempotency-Key': `e2e-opening-${tag}` } });
  expect(posted.ok(), await posted.text()).toBeTruthy();
  v = await posted.json();
  expect(v.status).toBe('POSTED');
  const entryNumber = v.voucherNumber!;
  await Promise.all([ap, r, c, p].map((x) => x.dispose()));
  await nextTotpWindow();

  // Accountant: uploads the customer's open invoices against that entry; they agree, but the uploader cannot apply them.
  await signIn(page, acct);
  await nav(page, 'Opening balances');
  await page.getByLabel('Batch type', { exact: true }).selectOption('AR_OPEN_ITEMS');
  await page.getByLabel('Opening entry number').fill(entryNumber);
  await page.getByLabel('Batch description').fill(`E2E open invoices ${tag}`);
  await page.getByLabel('CSV file').setInputFiles(csv(`ar-${tag}.csv`, 'customer_code,document_number,document_date,due_date,description,outstanding_amount\n'
    + `${customer.code},L${tag}-1,2025-11-15,2025-12-15,November rent,10000.00\n${customer.code},L${tag}-2,2025-12-15,2026-01-15,December rent,15000.00\n`));
  await page.getByRole('button', { name: 'Upload and validate' }).click();
  await expect(page).toHaveURL(/\/migration\/[0-9a-f-]{36}$/);
  await expect(page.getByText('All 1 parties agree.')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Apply batch' })).toHaveCount(0);
  await signOut(page);

  // Chief accountant: applies them.
  await signIn(page, chief);
  await nav(page, 'Opening balances');
  await page.getByRole('link', { name: `E2E open invoices ${tag}` }).click();
  await page.getByRole('button', { name: 'Apply batch' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Confirm', exact: true }).click();
  await expect(page.getByRole('main').getByText('APPLIED', { exact: true })).toBeVisible();
  await signOut(page);
  await nextTotpWindow();

  const check = await client(acct);
  const after = await call<{ openBalance: string }>(check, 'GET', `customers/${customer.id}`);
  expect(after.openBalance).toBe('25000.00');
  await check.dispose();
});

test('a migrated asset keeps its legacy accumulated depreciation and depreciates over its remaining life', async ({ page }) => {
  const token = await adminToken(admin.username, admin.password, admin.totpSecret);
  const acct = await onboard(token, 'faacct', ['ACCOUNTANT']);
  const rev = await onboard(token, 'farev', ['ACCOUNTANT']);
  const chief = await onboard(token, 'fachief', ['CHIEF_ACCOUNTANT']);
  const poster = await onboard(token, 'fapost', ['CHIEF_ACCOUNTANT']);
  await nextTotpWindow();
  const tag = Date.now().toString(36).toUpperCase();

  // Setup through the API: a posted opening entry carrying equipment cost and accumulated depreciation.
  const [a, r, c, p] = await Promise.all([client(acct), client(rev), client(chief), client(poster)]);
  const up = await a.post('migration/batches', { multipart: { kind: 'GL_OPENING', description: `E2E asset opening ${tag}`, cutoverDate: '2026-01-01',
    file: csv(`tb-${tag}.csv`, 'account_code,cost_center_code,subsidiary_code,description,debit,credit\n'
      + `10605990,,,Equipment ${tag},60000.00,\n10605991,,,Accumulated depreciation,,24000.00\n30701010,,,Accumulated surplus,,36000.00\n`) } });
  expect(up.ok(), await up.text()).toBeTruthy();
  let batch = (await up.json()) as { summary: { id: string; version: number; journalVoucherId: string } };
  batch = await call(a, 'POST', `migration/batches/${batch.summary.id}/apply`, { version: batch.summary.version });
  const voucherId = batch.summary.journalVoucherId;
  let v = await call<{ version: number; voucherNumber: string | null }>(a, 'GET', `journal-vouchers/${voucherId}`);
  v = await call(a, 'POST', `journal-vouchers/${voucherId}/submit`, { version: v.version });
  v = await call(r, 'POST', `journal-vouchers/${voucherId}/approve`, { version: v.version, comment: 'Agrees with legacy register' });
  v = await call(c, 'POST', `journal-vouchers/${voucherId}/approve`, { version: v.version, comment: 'Approved' });
  const posted = await p.post(`journal-vouchers/${voucherId}/post`, { data: { version: v.version },
    headers: { 'Idempotency-Key': `e2e-fa-opening-${tag}` } });
  expect(posted.ok(), await posted.text()).toBeTruthy();
  const entryNumber = ((await posted.json()) as { voucherNumber: string }).voucherNumber;
  await Promise.all([a, r, c, p].map((x) => x.dispose()));
  await nextTotpWindow();

  // Accountant: uploads the register; it agrees with the opening entry account by account.
  await signIn(page, acct);
  await nav(page, 'Opening balances');
  await page.getByLabel('Batch type', { exact: true }).selectOption('FA_REGISTER');
  await page.getByLabel('Opening entry number').fill(entryNumber);
  await page.getByLabel('Batch description').fill(`E2E asset register ${tag}`);
  await page.getByLabel('CSV file').setInputFiles(csv(`fa-${tag}.csv`, 'asset_number,category_code,description,serial_number,location,custodian,'
    + 'cost_center_code,acquisition_date,in_service_date,cost,residual_value,useful_life_months,remaining_life_months,accumulated_depreciation\n'
    + `FA${tag},PLC-FA-EQPT,Baggage scanner,SN-${tag},Terminal 3,Security,,2023-12-15,2024-01-01,60000.00,,60,36,24000.00\n`));
  await page.getByRole('button', { name: 'Upload and validate' }).click();
  await expect(page).toHaveURL(/\/migration\/[0-9a-f-]{36}$/);
  await expect(page.getByText('All 2 accounts agree.')).toBeVisible();
  await signOut(page);

  // Chief accountant: applies it; the asset shows its legacy accumulated depreciation and the remaining-life charge.
  await signIn(page, chief);
  await nav(page, 'Opening balances');
  await page.getByRole('link', { name: `E2E asset register ${tag}` }).click();
  await page.getByRole('button', { name: 'Apply batch' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Confirm', exact: true }).click();
  await expect(page.getByRole('main').getByText('APPLIED', { exact: true })).toBeVisible();
  await nav(page, 'Asset register');
  await page.getByRole('link', { name: `FA${tag}` }).click();
  const main = page.getByRole('main');
  await expect(main).toContainText('24,000.00');
  await expect(main).toContainText('36,000.00');
  // 36,000 left over 36 months, not 60,000 over 60 from 2024.
  await expect(main).toContainText('1,000.00');
  await signOut(page);
});
