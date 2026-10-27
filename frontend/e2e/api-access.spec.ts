import { expect, request, test } from '@playwright/test';
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { adminToken, nextTotpWindow, onboard } from './team';
import { nav, signIn } from './ui';

const admin = {
  username: process.env.E2E_ADMIN_USERNAME ?? '',
  password: process.env.E2E_ADMIN_PASSWORD ?? '',
  totpSecret: process.env.E2E_ADMIN_TOTP_SECRET ?? '',
};
const API = process.env.E2E_API_URL ?? 'http://localhost:8080';

test.skip(!admin.username || !admin.password || !admin.totpSecret,
  'Set E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD and E2E_ADMIN_TOTP_SECRET for an enrolled test administrator');
test.setTimeout(120_000);

test('the chief accountant creates an API client that reads the ledger, and a webhook that receives a test event', async ({ page }) => {
  // A receiver on this machine (the test backend allows local targets; production refuses them).
  const received: string[] = [];
  const server: Server = createServer((req, res) => {
    let body = '';
    req.on('data', (c) => { body += c; });
    req.on('end', () => { received.push(`${req.headers['x-cas-event']} ${body}`); res.writeHead(204).end(); });
  });
  await new Promise<void>((r) => server.listen(0, '127.0.0.1', r));
  const port = (server.address() as AddressInfo).port;

  try {
    const token = await adminToken(admin.username, admin.password, admin.totpSecret);
    const chief = await onboard(token, 'apichief', ['CHIEF_ACCOUNTANT']);
    await nextTotpWindow();
    await signIn(page, chief);
    await nav(page, 'API access');
    const main = page.getByRole('main');

    const name = `E2E ledger reader ${Date.now()}`;
    await main.getByLabel('Client name').fill(name);
    await main.getByLabel('Ledger view').check();
    await main.getByRole('button', { name: 'Create client' }).click();
    const shown = main.getByRole('status').filter({ hasText: 'shown only once' });
    const clientId = (await shown.textContent())?.match(/cas_[a-z0-9]+/)?.[0] ?? '';
    const secret = (await shown.locator('p.font-mono').textContent())?.trim() ?? '';
    expect(clientId).not.toBe('');
    await shown.getByRole('button', { name: 'I have saved it' }).click();
    await expect(main.getByRole('row').filter({ hasText: name })).toContainText('ACTIVE');

    // The other system: client credentials, then a read within the scope.
    const ctx = await request.newContext();
    const t = await ctx.post(`${API}/api/v1/auth/token`, { form: { grant_type: 'client_credentials', client_id: clientId, client_secret: secret } });
    expect(t.status()).toBe(200);
    const access = (await t.json()).access_token as string;
    expect((await ctx.get(`${API}/api/v1/accounts`, { headers: { Authorization: `Bearer ${access}` } })).status()).toBe(200);
    expect((await ctx.get(`${API}/api/v1/suppliers`, { headers: { Authorization: `Bearer ${access}` } })).status()).toBe(403);

    // A webhook, and a test event to it.
    const hook = `E2E receiver ${Date.now()}`;
    await main.getByLabel('Name', { exact: true }).fill(hook);
    await main.getByLabel('URL (HTTPS)').fill(`http://127.0.0.1:${port}/cas`);
    await main.getByLabel('Journal voucher posted').check();
    await main.getByRole('button', { name: 'Subscribe' }).click();
    await expect(main.getByRole('status').filter({ hasText: 'Signing secret' })).toBeVisible();
    await main.getByRole('status').filter({ hasText: 'Signing secret' }).getByRole('button', { name: 'I have saved it' }).click();
    const row = main.getByRole('row').filter({ hasText: hook });
    await row.getByRole('button', { name: 'Send test' }).click();
    await expect.poll(() => received.length).toBe(1);
    expect(received[0]).toMatch(/^PING \{"id":/);
    await row.getByRole('button', { name: 'Deliveries' }).click();
    await expect(main.getByRole('row').filter({ hasText: 'Ping' })).toContainText('DELIVERED');
    // Leave nothing active that points at this test's receiver.
    await row.getByRole('button', { name: 'Pause' }).click();
    await expect(row).toContainText('PAUSED');
  } finally {
    server.close();
  }
});
