import { expect, request, test } from '@playwright/test';
import { adminToken, nextTotpWindow, onboard } from './team';
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

test('stock is received and issued at weighted average cost, each posting by accounting', async ({ page }) => {
  const token = await adminToken(admin.username, admin.password, admin.totpSecret);
  const accountant = await onboard(token, 'invacct', ['ACCOUNTANT']);
  const clerk = await onboard(token, 'store', ['INVENTORY_CLERK']);
  const chief = await onboard(token, 'invchief', ['CHIEF_ACCOUNTANT']);
  await nextTotpWindow();
  const tag = Date.now().toString(36).toUpperCase();
  const acc = await adminToken(accountant.username, accountant.password, accountant.secret);
  const ctx = await request.newContext({ extraHTTPHeaders: { Authorization: `Bearer ${acc}` } });
  const res = await ctx.post(`${API}/api/v1/suppliers`, { data: { code: `S${tag}`, name: `E2E Lighting Supply ${tag}`, vatRegistered: false } });
  expect(res.ok()).toBeTruthy();
  await ctx.dispose();
  const today = new Date().toISOString().slice(0, 10);

  // Storekeeper: item master and the goods receipt.
  await signIn(page, clerk);
  await nav(page, 'Stock on hand');
  await page.getByLabel('Item code').fill(`LAMP-${tag}`);
  await page.getByLabel('Item name').fill('Runway edge lamp');
  await page.getByLabel('Inventory account').selectOption({ label: 'PLC-1302 — [PLACEHOLDER] Spare parts inventory' });
  await page.getByLabel('Consumption account').selectOption({ label: 'PLC-5202 — [PLACEHOLDER] Repairs and maintenance' });
  await page.getByRole('button', { name: 'Add item' }).click();
  await expect(page.getByRole('link', { name: `LAMP-${tag}` })).toBeVisible();

  await nav(page, 'Stock documents');
  await page.getByRole('link', { name: 'New document' }).click();
  await page.getByLabel('Date').fill(today);
  await page.getByLabel('Search supplier').fill(`S${tag}`);
  await page.getByLabel('Supplier', { exact: true }).selectOption({ label: `S${tag} — E2E Lighting Supply ${tag}` });
  await page.getByLabel('Delivery receipt / PO reference').fill(`DR-${tag}`);
  await page.getByLabel('Description', { exact: true }).fill('Delivery of runway lamps');
  await page.getByPlaceholder('Item code or name').fill(`LAMP-${tag}`);
  await page.getByLabel('Line 1 item').selectOption({ label: `LAMP-${tag} — Runway edge lamp` });
  await page.getByLabel('Line 1 warehouse').selectOption({ label: 'PLC-WH-MAIN' });
  await page.getByLabel('Line 1 quantity').fill('10');
  await page.getByLabel('Line 1 unit cost').fill('250');
  await page.getByRole('button', { name: 'Save draft' }).click();
  await expect(page.getByRole('heading', { name: 'Goods receipt (draft)' })).toBeVisible();
  await expect(page.getByRole('main').getByRole('button', { name: 'Post' })).toHaveCount(0);
  const grnUrl = page.url();
  await signOut(page);

  await signIn(page, chief);
  await page.goto(grnUrl);
  await act(page, 'Post', 'Post');
  await expect(page.getByRole('heading', { name: /^Goods receipt GRN-\d{4}-\d{6}$/ })).toBeVisible();
  await expect(page.getByRole('main')).toContainText('2,500.00');
  await signOut(page);

  // Issue four lamps to Airport Operations.
  await nextTotpWindow();
  await signIn(page, clerk);
  await nav(page, 'Stock documents');
  await page.getByRole('link', { name: 'New document' }).click();
  await page.getByLabel('Document').selectOption('ISSUE');
  await page.getByLabel('Date').fill(today);
  await page.getByLabel('Requesting cost centre').selectOption({ label: 'PLC-CC-OPS' });
  await page.getByLabel('Description', { exact: true }).fill('Replacement of failed edge lamps, runway 06/24');
  await page.getByPlaceholder('Item code or name').fill(`LAMP-${tag}`);
  await page.getByLabel('Line 1 item').selectOption({ label: `LAMP-${tag} — Runway edge lamp` });
  await page.getByLabel('Line 1 warehouse').selectOption({ label: 'PLC-WH-MAIN' });
  await page.getByLabel('Line 1 quantity').fill('4');
  await page.getByRole('button', { name: 'Save draft' }).click();
  await expect(page.getByRole('heading', { name: 'Issue (draft)' })).toBeVisible();
  const issueUrl = page.url();
  await signOut(page);

  await signIn(page, chief);
  await page.goto(issueUrl);
  await act(page, 'Post', 'Post');
  await expect(page.getByRole('main')).toContainText('1,000.00');
  await nav(page, 'Stock on hand');
  const row = page.getByRole('row', { name: new RegExp(`LAMP-${tag}`) });
  await expect(row).toContainText('6 pc');
  await expect(row).toContainText('1,500.00');
  await nav(page, 'Inventory reports');
  await expect(page.getByText('GR/IR clearing')).toBeVisible();
  await expect(page.getByRole('row', { name: /GRN-\d{4}-\d{6}/ }).filter({ hasText: `E2E Lighting Supply ${tag}` })).toContainText('2,500.00');
});
