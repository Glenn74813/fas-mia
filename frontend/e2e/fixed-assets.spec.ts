import { expect, request, test } from '@playwright/test';
import { futureYear } from './fiscal';
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
test.setTimeout(420_000);

interface P { id: string; name: string; periodType: string; status: string; startDate: string; version: number }

test('an asset is registered, activated, depreciated and disposed of, each control step by a second person', async ({ page }) => {
  const token = await adminToken(admin.username, admin.password, admin.totpSecret);
  const accountant = await onboard(token, 'faacct', ['ACCOUNTANT']);
  const chief = await onboard(token, 'fachief', ['CHIEF_ACCOUNTANT']);
  await nextTotpWindow();

  // The latest open month without a depreciation run (one per period), and an in-service date the month before.
  const acc = await adminToken(accountant.username, accountant.password, accountant.secret);
  const ctx = await request.newContext({ extraHTTPHeaders: { Authorization: `Bearer ${acc}` } });
  const years = (await (await ctx.get(`${API}/api/v1/fiscal-years`)).json()) as { periods: P[] }[];
  // Months that have not started yet are skipped (other specs may open future periods).
  const today = new Date().toISOString().slice(0, 10);
  const used = new Set<string>();
  for (let page = 0, pages = 1; page < pages; page++) {
    const runs = (await (await ctx.get(`${API}/api/v1/depreciation-runs?page=${page}&size=200`)).json()) as
      { items: { periodName: string; status: string }[]; totalPages: number };
    pages = runs.totalPages;
    runs.items.filter((r) => r.status !== 'CANCELLED').forEach((r) => used.add(r.periodName));
  }
  await ctx.dispose();
  const monthBefore = (iso: string) => {
    const d = new Date(`${iso}T00:00:00Z`);
    return new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() - 1, 1)).toISOString().slice(0, 10);
  };
  const regular = (ys: { periods: P[] }[]) => ys.flatMap((y) => y.periods).filter((p) => p.periodType === 'REGULAR');
  // The month before must be an open period too: the asset goes in service then and its acquisition is posted there.
  const all = regular(years);
  let period = all.filter((p) => p.status === 'OPEN' && p.startDate <= today && !used.has(p.name)
      && all.some((q) => q.startDate === monthBefore(p.startDate) && q.status === 'OPEN'))
    .sort((a, b) => b.startDate.localeCompare(a.startDate))[0];
  if (!period) {
    // This year's months are used up: work in a future fiscal year, opening the two months needed.
    const chiefToken = await adminToken(chief.username, chief.password, chief.secret);
    const usable = (p: P) => p.status === 'OPEN' || p.status === 'FUTURE';
    const pair = (ps: P[]) => ps.find((p) => usable(p) && !used.has(p.name) && ps.some((q) => q.startDate === monthBefore(p.startDate) && usable(q)));
    const fy = await futureYear(chiefToken, (y) => !!pair(regular([y])));
    const target = pair(regular([fy]))!;
    const chiefCtx = await request.newContext({ extraHTTPHeaders: { Authorization: `Bearer ${chiefToken}` } });
    for (const p of regular([fy]).filter((q) => (q.startDate === monthBefore(target.startDate) || q.id === target.id) && q.status === 'FUTURE')) {
      const res = await chiefCtx.post(`${API}/api/v1/accounting-periods/${p.id}/open`, { data: { version: p.version } });
      expect(res.ok(), `open ${p.name}`).toBeTruthy();
    }
    await chiefCtx.dispose();
    period = target;
  }
  const start = new Date(`${period!.startDate}T00:00:00Z`);
  const inService = new Date(Date.UTC(start.getUTCFullYear(), start.getUTCMonth() - 1, 1)).toISOString().slice(0, 10);
  const tag = Date.now().toString(36).toUpperCase();
  await nextTotpWindow();

  await signIn(page, accountant);
  await nav(page, 'Asset register');
  await page.getByRole('link', { name: 'Register asset' }).click();
  await page.getByLabel('Category').selectOption({ label: 'PLC-FA-EQPT — [PLACEHOLDER] Machinery and equipment (verify useful life, C-30)' });
  await page.getByLabel('Description', { exact: true }).fill(`E2E generator set ${tag}`);
  await page.getByLabel('Serial number').fill(`SN-${tag}`);
  await page.getByLabel('Acquisition date').fill(inService);
  await page.getByLabel('In-service date').fill(inService);
  await page.getByLabel('Cost', { exact: true }).fill('60000.00');
  await page.getByLabel('Other (donation, transfer-in, found)').check();
  await page.getByLabel('Offset account').selectOption({ label: 'PLC-4203 — [PLACEHOLDER] Miscellaneous income' });
  await page.getByLabel('Justification').fill('Donated by an airline partner, deed of donation attached');
  await page.getByRole('button', { name: 'Save draft' }).click();
  await expect(page.getByRole('heading', { name: 'Asset (draft)' })).toBeVisible();
  await expect(page.getByRole('main').getByRole('button', { name: 'Activate' })).toHaveCount(0);
  const assetUrl = page.url();
  await signOut(page);

  await signIn(page, chief);
  await page.goto(assetUrl);
  await act(page, 'Activate', 'Activate');
  await expect(page.getByRole('heading', { name: /^Asset FA-\d{4}-\d{6}$/ })).toBeVisible();
  const assetNumber = (await page.getByRole('heading', { name: /^Asset FA-/ }).textContent())!.replace('Asset ', '');
  await signOut(page);

  // Depreciation for the month after in service: 60,000 / 60 months = 1,000.
  await nextTotpWindow();
  await signIn(page, accountant);
  await nav(page, 'Depreciation');
  await page.getByLabel('Period').selectOption({ label: period!.name });
  await page.getByRole('button', { name: 'Compute depreciation' }).click();
  await expect(page.getByRole('heading', { name: `Depreciation ${period!.name}` })).toBeVisible();
  await expect(page.getByRole('row', { name: new RegExp(assetNumber) })).toContainText('1,000.00');
  await expect(page.getByRole('main').getByRole('button', { name: 'Post' })).toHaveCount(0);
  const runUrl = page.url();
  await signOut(page);

  await signIn(page, chief);
  await page.goto(runUrl);
  await act(page, 'Post', 'Post');
  await expect(page.getByRole('main').getByText('POSTED', { exact: true })).toBeVisible();
  await signOut(page);

  // Moved to another location: prepared by the accountant, posted by the chief accountant.
  await nextTotpWindow();
  await signIn(page, accountant);
  await page.goto(assetUrl);
  const transfers = page.getByRole('region', { name: 'Transfers' });
  // Dated in the depreciated month, never before the asset was in service.
  await transfers.getByLabel('Transfer date').fill(period!.startDate);
  await transfers.getByLabel('To location').fill('Hangar 2');
  await transfers.getByLabel('Reason / authority').fill('Reassigned to the maintenance hangar');
  await transfers.getByRole('button', { name: 'Prepare transfer' }).click();
  await expect(transfers.getByText('DRAFT', { exact: true })).toBeVisible();
  await signOut(page);
  await nextTotpWindow();
  await signIn(page, chief);
  await page.goto(assetUrl);
  await page.getByRole('region', { name: 'Transfers' }).getByRole('button', { name: 'Post transfer' }).click();
  await expect(page.getByRole('region', { name: 'Transfers' }).getByText('POSTED', { exact: true })).toBeVisible();
  await expect(page.getByRole('main')).toContainText('Hangar 2');
  await signOut(page);

  // Scrapped: the net book value of 59,000 becomes a loss.
  await nextTotpWindow();
  await signIn(page, accountant);
  await page.goto(assetUrl);
  await page.getByRole('main').getByRole('button', { name: 'Dispose' }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('Disposal date').fill(period!.startDate);
  await dialog.getByLabel('Reason / authority').fill('Beyond economic repair, inspection report IR-2026-15');
  await dialog.getByRole('button', { name: 'Save disposal', exact: true }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByRole('main')).toContainText('59,000.00');
  await signOut(page);

  await nextTotpWindow();
  await signIn(page, chief);
  await page.goto(assetUrl);
  await act(page, 'Post disposal', 'Post');
  await expect(page.getByRole('main').getByText('DISPOSED', { exact: true })).toBeVisible();
  await nav(page, 'PPE reports');
  await expect(page.getByRole('heading', { name: 'PPE schedule' })).toBeVisible();
  await expect(page.getByRole('row', { name: /PLC-FA-EQPT/ })).toBeVisible();
});
