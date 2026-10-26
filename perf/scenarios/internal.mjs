import { think } from '../lib/client.mjs';
import { PDF } from '../lib/config.mjs';

/**
 * 20 internal users working the journal workflow and inquiries at the same time, each in their seeded role:
 * preparers create, document and submit vouchers; reviewers and approvers clear their approval queues; posters
 * post; the reporting user runs inquiries. Concurrent queue work means two people sometimes act on the same
 * voucher: the loser gets 409 (optimistic locking), which is expected behaviour and counted separately.
 */
export async function internal(ctx) {
  const { state, sessions, stopAt, thinkMs } = ctx;
  const accounts = (await sessions.internal[0].must('setup.accounts', 'setup', 'GET', '/accounts')).accounts;
  const id = (code) => accounts.find((a) => a.code === code).id;
  const costCenters = await sessions.internal[0].must('setup.cost-centers', 'setup', 'GET', '/cost-centers');
  const today = new Date().toISOString().slice(0, 10);
  const open = state.periods.filter((p) => p.status === 'OPEN' && p.start <= today);
  const current = open.at(-1);
  const customers = state.customers;
  const pick = (list) => list[Math.floor(Math.random() * list.length)];

  const preparer = async (s, n) => {
    const amount = (100 + Math.random() * 9900).toFixed(2);
    const v = await s.call('jev.create', 'entry', 'POST', '/journal-vouchers', { voucherType: 'STANDARD', accountingDate: today, adjustmentPeriod: false,
      description: `Load test voucher ${s.user.username} ${n}`, lines: [
        { accountId: id('PLC-5201'), costCenterId: pick(costCenters).id, description: 'Utilities', debit: amount, credit: '0' },
        { accountId: id('PLC-1102'), description: 'Cash in bank', debit: '0', credit: amount }] });
    if (!v.ok) return;
    await s.call('jev.attach', 'entry', 'POST', `/journal-vouchers/${v.json.id}/documents`, undefined, { multipart: { file: PDF } });
    await s.call('jev.submit', 'entry', 'POST', `/journal-vouchers/${v.json.id}/submit`, { version: v.json.version });
    await s.call('jev.list', 'read', 'GET', '/journal-vouchers?page=0&size=25');
    if (n % 4 === 0) await s.call('gl.trial-balance', 'read', 'GET', `/general-ledger/trial-balance?periodId=${current.id}`);
  };

  const queueWorker = (op, action) => async (s) => {
    // The approvals inbox replaced /journal-vouchers/approval-queue (deprecated, sunset 2027-04-30). Inbox items carry
    // no version, so the worker opens the voucher first, as a reviewer does.
    const q = await s.call('inbox.read', 'read', 'GET', '/inbox');
    const mine = (q.json?.items ?? []).filter((i) => i.documentType === 'JOURNAL_VOUCHER' && (action === 'post') === (i.task === 'Post'));
    if (!q.ok || !mine.length) return;
    const d = await s.call('jev.detail', 'read', 'GET', `/journal-vouchers/${pick(mine.slice(0, 10)).id}`);
    if (!d.ok) return;
    const v = d.json;
    if (action === 'post') {
      await s.call(op, 'entry', 'POST', `/journal-vouchers/${v.id}/post`, { version: v.version },
        { expect: [200, 409], headers: { 'Idempotency-Key': `perf-${crypto.randomUUID()}` } });
    } else {
      await s.call(op, 'entry', 'POST', `/journal-vouchers/${v.id}/approve`, { version: v.version, comment: 'Load test' }, { expect: [200, 409] });
    }
  };

  const reporter = async (s, n) => {
    const c = pick(customers);
    const calls = [
      () => s.call('gl.trial-balance', 'read', 'GET', `/general-ledger/trial-balance?periodId=${current.id}`),
      () => s.call('gl.account-ledger', 'read', 'GET', `/general-ledger/accounts/${id('PLC-1102')}/ledger?from=2026-01-01&to=${today}`),
      () => s.call('ar.aging', 'read', 'GET', `/accounts-receivable/aging?asOf=${today}`),
      () => s.call('ar.statement', 'read', 'GET', `/customers/${c.id}/statement?from=2025-01-01&to=${today}`),
      () => s.call('ar.customers', 'read', 'GET', '/customers?page=0&size=25'),
    ];
    await calls[n % calls.length]();
  };

  const roles = { preparer, reviewer: queueWorker('jev.review', 'approve'), approver: queueWorker('jev.approve', 'approve'),
    poster: queueWorker('jev.post', 'post'), reporter };
  await Promise.all(sessions.internal.map(async (s) => {
    const work = roles[s.user.role];
    for (let n = 0; Date.now() < stopAt; n++) {
      await work(s, n);
      await think(thinkMs[0], thinkMs[1]);
    }
  }));
}
