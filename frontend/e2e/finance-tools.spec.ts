import { expect, test } from '@playwright/test';
import { adminToken, apiAs, nextTotpWindow, onboard, tokenFor, type Member } from './team';
import { nav, signIn, signOut, pick, select } from './ui';

const admin = {
  username: process.env.E2E_ADMIN_USERNAME ?? '',
  password: process.env.E2E_ADMIN_PASSWORD ?? '',
  totpSecret: process.env.E2E_ADMIN_TOTP_SECRET ?? '',
};

test.skip(!admin.username || !admin.password || !admin.totpSecret,
  'Set E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD and E2E_ADMIN_TOTP_SECRET for an enrolled test administrator');
test.setTimeout(180_000);


test('ad hoc report, overhead allocation preview and a cost vs market review', async ({ page }) => {
  const token = await adminToken(admin.username, admin.password, admin.totpSecret);
  const accountant = await onboard(token, 'ftacct', ['ACCOUNTANT']);
  const clerk = await onboard(token, 'ftclerk', ['INVENTORY_CLERK']);
  const chief = await onboard(token, 'ftchief', ['CHIEF_ACCOUNTANT']);
  await nextTotpWindow();
  await stockOneItem(accountant, clerk, chief);
  await nextTotpWindow(); // the set-up used this window's codes
  await signIn(page, accountant);
  const main = page.getByRole('main');
  const stamp = Date.now();

  // Ad hoc: ledger lines grouped by account, totals.
  await nav(page, 'Ad hoc reports');
  await select(main.getByLabel('Dataset'), 'LEDGER_LINES');
  const columns = main.getByRole('group', { name: /^Columns/ });
  await columns.getByRole('checkbox', { name: 'Account', exact: true }).check();
  await columns.getByRole('checkbox', { name: 'Debit', exact: true }).check();
  await columns.getByRole('checkbox', { name: 'Credit', exact: true }).check();
  await main.getByRole('group', { name: /Group by/ }).getByRole('checkbox', { name: 'Account', exact: true }).check();
  await main.getByRole('button', { name: 'Run' }).click();
  await expect(main.getByRole('columnheader', { name: 'Total debit' })).toBeVisible();
  await expect(main.locator('tfoot')).toContainText('Total');
  await main.getByLabel('Report name').fill(`Ledger by account ${stamp}`);
  await main.getByRole('button', { name: 'Save' }).click();
  await expect(main.getByRole('button', { name: `Ledger by account ${stamp}` })).toBeVisible();
  await main.getByRole('button', { name: 'Export CSV' }).click();
  await expect(main.getByRole('status')).toContainText('Export queued');

  // Overhead allocation: a cycle and its preview for October 2026.
  await nav(page, 'Overhead allocation');
  await main.getByLabel('Code').fill(`E2E${stamp % 100000}`);
  await main.getByLabel('Name').fill('Utilities by floor area');
  await pick(main.getByLabel('Sender account'), '50204020');
  await pick(main.getByLabel('Sender cost centre'), 'PLC-CC-FIN');
  await select(main.getByLabel('Key'), 'QUANTITY');
  await pick(main.getByLabel(/^Receiver/), 'PLC-CC-OPS');
  await main.getByLabel('Quantity').fill('120');
  await main.getByRole('button', { name: 'Create cycle' }).click();
  const cycle = main.locator('section').filter({ hasText: `E2E${stamp % 100000}` });
  await expect(cycle).toContainText('50204020 / PLC-CC-FIN');
  await cycle.getByRole('button', { name: 'Run or preview' }).click();
  await select(cycle.getByLabel('Period'), { label: 'October 2026' });
  await expect(cycle.getByRole('table')).toContainText('PLC-CC-OPS');
  await signOut(page);

  // Cost vs market review.
  await nextTotpWindow();
  await signIn(page, clerk);
  await nav(page, 'Cost vs market review');
  await main.getByLabel('Review date').fill('2026-10-27');
  await main.getByRole('button', { name: 'Start review' }).click();
  await expect(main.getByRole('heading', { name: /Review as of 2026-10-27/ })).toBeVisible();
  const first = main.getByRole('textbox', { name: /^Market value of / }).first();
  await first.fill('0.01');
  await main.getByRole('button', { name: 'Save prices' }).click();
  await expect(main.getByRole('heading', { name: /Review as of 2026-10-27/ })).not.toContainText('write-down ₱0.00');
});

/** The cost vs market review lists items with stock on hand: receive one item, so the test needs no other spec's data. */
async function stockOneItem(accountant: Member, clerk: Member, chief: Member) {
  const tag = Date.now().toString(36).toUpperCase();
  const acct = await apiAs(await tokenFor(accountant));
  const supplier = await acct.post('/suppliers', { code: `S${tag}`, name: `E2E Market Test Supply ${tag}`, vatRegistered: false });
  await acct.dispose();
  const store = await apiAs(await tokenFor(clerk));
  const accounts: { id: string; code: string }[] = (await store.get('/accounts')).accounts;
  const id = (code: string) => accounts.find((a) => a.code === code)!.id;
  const warehouse = (await store.get('/inventory/warehouses') as { id: string; code: string }[]).find((w) => w.code === 'PLC-WH-MAIN')!;
  const item = await store.post('/inventory/items', { code: `NRV-${tag}`, name: 'Market test item', unitOfMeasure: 'pc',
    inventoryAccountId: id('10404990'), consumptionAccountId: id('50213050') });
  const grn = await store.post('/inventory/documents', { kind: 'GOODS_RECEIPT', documentDate: '2026-10-20', // before the review date below
    supplierId: supplier.id, reference: `DR-${tag}`, costCenterId: null, description: 'Stock for the cost vs market review',
    lines: [{ itemId: item.id, warehouseId: warehouse.id, toWarehouseId: null, quantity: '5', unitCost: '100' }] });
  await store.dispose();
  const poster = await apiAs(await tokenFor(chief));
  await poster.post(`/inventory/documents/${grn.id}/post`, { version: grn.version });
  await poster.dispose();
}
