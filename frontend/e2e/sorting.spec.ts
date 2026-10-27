import { expect, test } from '@playwright/test';
import { adminToken, nextTotpWindow, onboard } from './team';
import { nav, signIn } from './ui';

const admin = {
  username: process.env.E2E_ADMIN_USERNAME ?? '',
  password: process.env.E2E_ADMIN_PASSWORD ?? '',
  totpSecret: process.env.E2E_ADMIN_TOTP_SECRET ?? '',
};
test.skip(!admin.username || !admin.password || !admin.totpSecret, 'Set E2E_ADMIN_* for an enrolled test administrator');

test('list columns sort both ways, and the direction is announced', async ({ page }) => {
  const token = await adminToken(admin.username, admin.password, admin.totpSecret);
  const accountant = await onboard(token, 'sorter', ['ACCOUNTANT']);
  await nextTotpWindow();
  await signIn(page, accountant);

  await nav(page, 'Customers');
  const table = page.getByRole('table');
  const codes = async () => table.locator('tbody tr td:first-child').allTextContents();
  const header = table.getByRole('columnheader', { name: 'Code' });
  await expect(header).toHaveAttribute('aria-sort', 'none');

  // The server sorts (its collation decides the order); the page asks for it and shows the new first row.
  const asc = page.waitForResponse((r) => r.url().includes('/customers?') && r.url().includes('sort=code') && r.ok());
  await header.getByRole('button').click();
  await asc;
  await expect(header).toHaveAttribute('aria-sort', 'ascending');
  const first = (await codes())[0];

  const desc = page.waitForResponse((r) => r.url().includes('/customers?') && r.url().includes('sort=-code') && r.ok());
  await header.getByRole('button').click();
  await desc;
  await expect(header).toHaveAttribute('aria-sort', 'descending');
  await expect.poll(async () => (await codes())[0]).not.toBe(first);

  // Dates start newest first.
  await nav(page, 'Journal vouchers');
  const date = page.getByRole('columnheader', { name: 'Date' });
  await date.getByRole('button').click();
  await expect(date).toHaveAttribute('aria-sort', 'descending');
  const dates = page.getByRole('table').locator('tbody tr td:nth-child(2)');
  await expect.poll(async () => { const d = await dates.allTextContents(); return d.length > 1 && d.join() === [...d].sort().reverse().join(); })
    .toBe(true);
});
