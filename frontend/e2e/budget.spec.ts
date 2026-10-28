import { expect, request, test } from '@playwright/test';
import { futureYear } from './fiscal';
import { adminToken, nextTotpWindow, onboard } from './team';
import { act, nav, signIn, signOut, pick } from './ui';

const admin = {
  username: process.env.E2E_ADMIN_USERNAME ?? '',
  password: process.env.E2E_ADMIN_PASSWORD ?? '',
  totpSecret: process.env.E2E_ADMIN_TOTP_SECRET ?? '',
};
const API = process.env.E2E_API_URL ?? 'http://localhost:8080';

test.skip(!admin.username || !admin.password || !admin.totpSecret,
  'Set E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD and E2E_ADMIN_TOTP_SECRET for an enrolled test administrator');
test.setTimeout(300_000);

test('a budget is validated, approved by a second person, and realignments may only move available budget', async ({ page }) => {
  const token = await adminToken(admin.username, admin.password, admin.totpSecret);
  const officer = await onboard(token, 'bgofficer', ['BUDGET_OFFICER']);
  const manager = await onboard(token, 'bgfm', ['FINANCE_MANAGER']);
  const chief = await onboard(token, 'bgchief', ['CHIEF_ACCOUNTANT']);
  await nextTotpWindow();
  // One budget per fiscal year: work in a future year that has none.
  const chiefToken = await adminToken(chief.username, chief.password, chief.secret);
  const ctx = await request.newContext({ extraHTTPHeaders: { Authorization: `Bearer ${chiefToken}` } });
  const budgets = ((await (await ctx.get(`${API}/api/v1/budgets?size=200`)).json()) as { items: { fiscalYearId: string }[] }).items;
  await ctx.dispose();
  const fy = await futureYear(chiefToken, (y) => !budgets.some((b) => b.fiscalYearId === y.id));

  await signIn(page, officer);
  await nav(page, 'Budgets');
  await page.getByLabel('Fiscal year').selectOption({ label: String(fy.fiscalYear) });
  await page.getByRole('button', { name: 'Create budget' }).click();
  await expect(page.getByRole('heading', { name: `Corporate Operating Budget ${fy.fiscalYear}` })).toBeVisible();

  // Validation: the same account and cost centre twice is refused, with the reason shown.
  const utilities = '50204020 — Electricity Expenses';
  const repairs = '50213050 — Repairs and Maintenance-Machinery and Equipment';
  await pick(page.getByLabel('Allocation 1 account'), utilities);
  await pick(page.getByLabel('Allocation 1 cost centre'), 'PLC-CC-FIN');
  await page.getByLabel('Allocation 1 amount').fill('50000.00');
  await page.getByRole('button', { name: '+ Add allocation' }).click();
  await pick(page.getByLabel('Allocation 2 account'), utilities);
  await pick(page.getByLabel('Allocation 2 cost centre'), 'PLC-CC-FIN');
  await page.getByLabel('Allocation 2 amount').fill('1000.00');
  await page.getByRole('button', { name: 'Save allocations' }).click();
  await expect(page.getByText(/allocated twice for the same cost centre/)).toBeVisible();
  // Mixing an account-level and a cost-centre allocation is refused too.
  await pick(page.getByLabel('Allocation 2 cost centre'), 'All cost centres');
  await page.getByRole('button', { name: 'Save allocations' }).click();
  await expect(page.getByText(/mixes account-level and cost-centre allocations/)).toBeVisible();
  await pick(page.getByLabel('Allocation 2 account'), repairs);
  await pick(page.getByLabel('Allocation 2 cost centre'), 'PLC-CC-FIN');
  await page.getByLabel('Allocation 2 amount').fill('30000.00');
  await page.getByRole('button', { name: 'Save allocations' }).click();
  await expect(page.getByRole('row', { name: /50213050.*30,000\.00/ }).first()).toBeVisible();
  await act(page, 'Submit', 'Submit', 'Budget call figures for the year.');
  await expect(page.getByRole('main').getByText('SUBMITTED', { exact: true }).first()).toBeVisible();
  // The preparer cannot approve their own budget.
  await expect(page.getByRole('main').getByRole('button', { name: 'Approve' })).toHaveCount(0);
  const budgetUrl = page.url();
  await signOut(page);

  await signIn(page, manager);
  await page.goto(budgetUrl);
  await act(page, 'Approve', 'Approve', 'Approved per the board resolution.');
  await expect(page.getByRole('main').getByText('APPROVED', { exact: true }).first()).toBeVisible();
  await signOut(page);
  await nextTotpWindow();

  // A realignment larger than the source's available budget cannot be approved; one within it can.
  await signIn(page, officer);
  await page.goto(budgetUrl);
  await page.getByLabel('From allocation').selectOption({ label: 'From 50204020 / PLC-CC-FIN' });
  await page.getByLabel('To allocation').selectOption({ label: 'To 50213050 / PLC-CC-FIN' });
  await page.getByLabel('Adjustment amount').fill('60000.00');
  await page.getByLabel('Adjustment reason').fill('Move utilities savings to repairs');
  await page.getByRole('button', { name: 'Request' }).click();
  await expect(page.getByText(/60,000\.00/).first()).toBeVisible();
  await page.getByLabel('From allocation').selectOption({ label: 'From 50204020 / PLC-CC-FIN' });
  await page.getByLabel('To allocation').selectOption({ label: 'To 50213050 / PLC-CC-FIN' });
  await page.getByLabel('Adjustment amount').fill('5000.00');
  await page.getByLabel('Adjustment reason').fill('Urgent roof repair in the terminal');
  await page.getByRole('button', { name: 'Request' }).click();
  await expect(page.getByText(/Urgent roof repair/)).toBeVisible();
  // The requester cannot decide their own adjustment.
  await expect(page.getByRole('main').getByRole('button', { name: 'Approve' })).toHaveCount(0);
  await signOut(page);

  await signIn(page, manager);
  await page.goto(budgetUrl);
  const tooMuch = page.getByRole('listitem').filter({ hasText: 'Move utilities savings' });
  await tooMuch.getByRole('button', { name: 'Approve' }).click();
  await expect(page.getByText(/does not have enough available budget/)).toBeVisible();
  await tooMuch.getByRole('button', { name: 'Reject' }).click();
  await expect(tooMuch.getByText('REJECTED')).toBeVisible();
  const fits = page.getByRole('listitem').filter({ hasText: 'Urgent roof repair' });
  await fits.getByRole('button', { name: 'Approve' }).click();
  await expect(fits.getByText('APPROVED')).toBeVisible();
  await expect(page.getByRole('row', { name: /50204020.*45,000\.00/ }).first()).toBeVisible();
  await expect(page.getByRole('row', { name: /50213050.*35,000\.00/ }).first()).toBeVisible();

  // Variance analysis of the same budget: nothing is spent yet in this future year, so the full budget to date is favourable.
  await nav(page, 'My reports');
  const panel = page.locator('section').filter({ has: page.getByRole('heading', { name: 'Request a report' }) });
  await panel.getByLabel('Report').selectOption({ label: 'Variance analysis' });
  await panel.getByLabel('Fiscal year').selectOption({ label: String(fy.fiscalYear) });
  await panel.getByLabel('Through period').selectOption('6');
  await panel.getByLabel('Format').selectOption('CSV');
  await panel.getByRole('button', { name: 'Queue report' }).click();
  await expect(page.getByRole('status')).toContainText('Queued');
  const row = page.getByRole('row').filter({ hasText: 'Variance analysis' }).first();
  await expect(row).toContainText('DONE', { timeout: 30_000 });
  const download = page.waitForEvent('download');
  await row.getByRole('button', { name: 'Download' }).click();
  const file = await download;
  expect(file.suggestedFilename()).toBe(`variance_analysis_${fy.fiscalYear}_p06.csv`);
  const text = (await import('node:fs')).readFileSync((await file.path())!, 'utf8');
  expect(text).toContain('Variance analysis: budget against actual');
  expect(text).toMatch(/50204020[^\n]*,45000\.00,22500\.00,0\.00,22500\.00,100\.0,45000\.00,"Favourable"/);
});
