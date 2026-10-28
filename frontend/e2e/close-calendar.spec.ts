import { expect, test } from '@playwright/test';
import { adminToken, nextTotpWindow, onboard } from './team';
import { nav, signIn, signOut, pick } from './ui';

const admin = {
  username: process.env.E2E_ADMIN_USERNAME ?? '',
  password: process.env.E2E_ADMIN_PASSWORD ?? '',
  totpSecret: process.env.E2E_ADMIN_TOTP_SECRET ?? '',
};

test.skip(!admin.username || !admin.password || !admin.totpSecret,
  'Set E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD and E2E_ADMIN_TOTP_SECRET for an enrolled test administrator');
test.setTimeout(240_000);

test('a close task is set up, reaches its owner through the inbox and is ticked off; GR/IR is managed by open item', async ({ page }) => {
  const token = await adminToken(admin.username, admin.password, admin.totpSecret);
  const chief = await onboard(token, 'clchief', ['CHIEF_ACCOUNTANT']);
  const accountant = await onboard(token, 'clacct', ['ACCOUNTANT']);
  const tag = Date.now().toString(36).toUpperCase();
  const name = `E2E match GR/IR items ${tag}`;
  await nextTotpWindow();

  // The chief accountant adds a day-end task owned by whoever may clear open items.
  await signIn(page, chief);
  await nav(page, 'Close calendar');
  const tasks = page.getByRole('region', { name: 'Close tasks' });
  await tasks.getByLabel('Task code').fill(`E2E-${tag}`);
  await tasks.getByLabel('Task name').fill(name);
  await tasks.getByLabel('Checklist').selectOption({ label: 'Day end' });
  await tasks.getByLabel('Due after (days)').fill('0');
  await tasks.getByLabel('Responsible permission').fill('GL_CLEARING');
  await tasks.getByRole('button', { name: 'Add task' }).click();
  await expect(tasks.getByRole('cell', { name, exact: true })).toBeVisible();

  // GR/IR is the classic clearing account: manage it by open item (once per database) and preview automatic clearing.
  await nav(page, 'Open items');
  const managed = page.getByRole('region', { name: 'Managed accounts' });
  const grir = managed.getByRole('button', { name: /^29999990 / });
  // Wait for the list to load before deciding whether GR/IR still has to be added.
  await expect(managed.getByRole('table').getByRole('row').nth(1)).toBeVisible();
  if (await grir.count() === 0) {
    await pick(managed.getByLabel('Account to manage by open item'), '29999990');
    await managed.getByRole('button', { name: 'Manage by open item' }).click();
  }
  await grir.click();
  await expect(page.getByRole('heading', { name: /^Open items — 29999990/ })).toBeVisible();
  await page.getByLabel('Automatic clearing rule').selectOption('AMOUNT');
  await page.getByRole('button', { name: 'Preview' }).click();
  await expect(page.getByRole('list', { name: 'Proposed clearings' })).toBeVisible();
  await signOut(page);
  await nextTotpWindow();

  // The accountant finds the task in the inbox and completes today's (or the latest weekday's) occurrence with a note.
  await signIn(page, accountant);
  await nav(page, 'Inbox');
  await page.getByRole('link', { name: `Open ${name}` }).first().click();
  await expect(page.getByRole('region', { name: 'Checklist' })).toBeVisible();
  await expect(page.getByRole('region', { name: 'Checklist' }).getByText('Posted collections up to this day are deposited')).toBeVisible();
  const item = page.getByRole('listitem', { name });
  await item.getByLabel('Note').fill('All GR/IR items matched to supplier invoices');
  await item.getByRole('button', { name: 'Complete task' }).click();
  await expect(item.getByText('DONE')).toBeVisible();
  await expect(item.getByText(/All GR\/IR items matched/)).toBeVisible();
  await signOut(page);
  await nextTotpWindow();

  // The chief accountant retires the test task so it stops appearing in inboxes.
  await signIn(page, chief);
  await nav(page, 'Close calendar');
  const row = page.getByRole('region', { name: 'Close tasks' }).getByRole('row', { name: new RegExp(`E2E-${tag}`) });
  await row.getByRole('button', { name: 'Edit' }).click();
  await page.getByRole('region', { name: 'Close tasks' }).getByRole('button', { name: 'Deactivate' }).click();
  await expect(row.getByRole('cell', { name: 'inactive' })).toBeVisible();
});
