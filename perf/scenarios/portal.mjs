import { think } from '../lib/client.mjs';

/** 60 concessionaire portal users browsing their own account, billings, payments and statement. */
export async function portal(ctx) {
  const { sessions, stopAt, thinkMs } = ctx;
  const today = new Date().toISOString().slice(0, 10);
  await Promise.all(sessions.portal.map(async (s) => {
    for (let n = 0; Date.now() < stopAt; n++) {
      switch (n % 4) {
        case 0:
          await s.call('portal.account', 'read', 'GET', '/portal/account');
          break;
        case 1: {
          const b = await s.call('portal.billings', 'read', 'GET', '/portal/billings?page=0&size=25');
          const first = b.json?.items?.[0] ?? b.json?.content?.[0];
          if (first) await s.call('portal.billing', 'read', 'GET', `/portal/billings/${first.id}`);
          break;
        }
        case 2:
          await s.call('portal.receipts', 'read', 'GET', '/portal/receipts?page=0&size=25');
          break;
        default:
          await s.call('portal.statement', 'read', 'GET', `/portal/statement?from=2025-01-01&to=${today}`);
      }
      await think(thinkMs[0], thinkMs[1]);
    }
  }));
}
