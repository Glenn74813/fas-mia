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

test('a legacy trial balance is compared with CAS, every difference explained, and signed off by a second person', async ({ page }) => {
  const token = await adminToken(admin.username, admin.password, admin.totpSecret);
  const acct = await onboard(token, 'pracct', ['ACCOUNTANT']);
  const chief = await onboard(token, 'prchief', ['CHIEF_ACCOUNTANT']);
  await nextTotpWindow();
  const tag = Date.now().toString(36).toUpperCase();

  // The legacy figures: CAS's own movements for the current period, with one account off by 100.00 and a suspense
  // account that has no CAS counterpart.
  const a = await client(acct);
  const years = await (await a.get('fiscal-years')).json() as { periods: { id: string; name: string; status: string; startDate: string }[] }[];
  const today = new Date().toISOString().slice(0, 10);
  const period = years.flatMap((y) => y.periods).filter((p) => p.status === 'OPEN' && p.startDate <= today && !p.name.includes('Adj')).at(-1)!;
  const tb = await (await a.get(`general-ledger/trial-balance?periodId=${period.id}`)).json() as {
    rows: { code: string; periodDebit: string; periodCredit: string }[] };
  await a.dispose();
  await nextTotpWindow();
  const moved = tb.rows.filter((r) => Number(r.periodDebit) - Number(r.periodCredit) !== 0);
  expect(moved.length).toBeGreaterThan(0);
  const target = moved[0]!.code;
  const lines = moved.map((r) => {
    const net = Math.round((Number(r.periodDebit) - Number(r.periodCredit)) * 100) + (r.code === target ? 10000 : 0);
    const dc = net >= 0 ? [(net / 100).toFixed(2), ''] : ['', (-net / 100).toFixed(2)];
    return `L-${r.code},Legacy ${r.code},${r.code},${dc[0]},${dc[1]}`;
  });
  const csv = ['legacy_account_code,legacy_account_name,cas_account_code,debit,credit', ...lines, `L-SUSP-${tag},Suspense,,,100.00`].join('\n') + '\n';

  // Accountant: uploads and explains both differences.
  await signIn(page, acct);
  await nav(page, 'Parallel run');
  await page.getByLabel('CAS period').selectOption({ label: period.name });
  await page.getByLabel('Compare').selectOption('PERIOD');
  await page.getByLabel('Legacy system').fill(`E2E legacy ${tag}`);
  await page.getByLabel('Legacy trial balance (CSV)').setInputFiles({ name: `legacy-${tag}.csv`, mimeType: 'text/csv', buffer: Buffer.from(csv) });
  await page.getByRole('button', { name: 'Compare', exact: true }).click();
  await expect(page).toHaveURL(/\/migration\/parallel-runs\/[0-9a-f-]{36}$/);
  const main = page.getByRole('main');
  await expect(main).toContainText('Different 1');
  await expect(main).toContainText('Legacy account not mapped 1');
  await page.getByLabel(`Explanation for ${target}`).fill('Legacy accrual posted a month early');
  await page.getByRole('row', { name: new RegExp(target) }).getByRole('button', { name: 'Save' }).click();
  await expect(main).toContainText('Legacy accrual posted a month early');
  await page.getByLabel(`Explanation for L-SUSP-${tag} Suspense`).fill('Suspense cleared in CAS by reclassification');
  await page.getByRole('row', { name: /Suspense/ }).getByRole('button', { name: 'Save' }).click();
  await expect(main).toContainText('Suspense cleared in CAS by reclassification');
  await expect(page.getByRole('button', { name: 'Sign off' })).toHaveCount(0);
  await signOut(page);

  // Chief accountant: signs it off.
  await signIn(page, chief);
  await nav(page, 'Parallel run');
  await page.getByRole('row', { name: new RegExp(`E2E legacy ${tag}`) }).getByRole('link').click();
  await page.getByLabel('Sign-off note').fill('Both differences reviewed with the legacy team');
  await page.getByRole('button', { name: 'Sign off' }).click();
  await expect(main.getByText('SIGNED OFF', { exact: true })).toBeVisible();
  await expect(main).toContainText('Both differences reviewed with the legacy team');
  await signOut(page);
});
