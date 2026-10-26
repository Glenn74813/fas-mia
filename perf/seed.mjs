#!/usr/bin/env node
// Builds the performance-test volume data set through the CAS API, so every record passes the same validation,
// workflow and posting controls as production data. Development and test environments only: it creates users
// with generated passwords and authenticator secrets and keeps them in perf/.state/seed.json (gitignored).
//
//   node perf/seed.mjs [--customers 100] [--suppliers 50] [--ar-items 30] [--ap-items 20] [--vouchers 1000]
//                      [--receipts 2] [--concurrency 6]
//
// Volumes default to a laptop-sized set. MIAA's actual volumes are not yet known (C-12); pass larger figures for the
// target environment.
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';
import { Session, sleep } from './lib/client.mjs';
import { args, PDF, pool, settings, STATE_FILE } from './lib/config.mjs';
import { nextWindow, totp } from './lib/totp.mjs';

const a = args();
const n = (k, d) => Number(a[k] ?? d);
const vol = { customers: n('customers', 100), suppliers: n('suppliers', 50), arItems: n('ar-items', 30), apItems: n('ap-items', 20),
  vouchers: n('vouchers', 1000), receipts: n('receipts', 2), concurrency: n('concurrency', 6) };
const cfg = settings();
const tag = Date.now().toString(36).toUpperCase();
const CUTOVER = '2026-01-01';
const log = (m) => console.log(`[seed ${new Date().toISOString().slice(11, 19)}] ${m}`);

if (!cfg.admin.password || !cfg.admin.secret) throw new Error('Set PERF_ADMIN_PASSWORD and PERF_ADMIN_TOTP_SECRET (or the dev .env values).');
log(`tag ${tag}; volumes ${JSON.stringify(vol)}`);
const admin = await new Session(cfg.api, cfg.admin).login();

// ------------------------------------------------------------------ users

/** Creates a user, enrols TOTP and sets the final password, as a new staff member would. */
async function onboard(prefix, roles, userType = 'INTERNAL') {
  const username = `pf.${prefix}.${tag}`.toLowerCase();
  const temporary = `Temp-${crypto.randomUUID().slice(0, 8)}-Aa1!`;
  const password = `Perf-${crypto.randomUUID().slice(0, 12)}-Aa1!`;
  await admin.must('seed.user', 'seed', 'POST', '/users', { username, email: `${username}@perf.miaa.local`, displayName: `Perf ${prefix}`,
    userType, temporaryPassword: temporary, roleCodes: roles });
  const s = new Session(cfg.api, { username });
  const auth = async (path, body, token) => {
    for (;;) {
      const r = await s.raw('POST', path, body, token ? { Authorization: `Bearer ${token}` } : {});
      if (r.status === 429) {
        await sleep(5000);
        continue;
      }
      if (r.status >= 300) throw new Error(`${path} for ${username} -> ${r.status} ${r.text.slice(0, 200)}`);
      return r.json;
    }
  };
  const step = await auth('/auth/login', { identifier: username, password: temporary });
  const enrol = await auth('/auth/mfa/totp/enroll', { mfaToken: step.mfaToken });
  const tokens = await auth('/auth/mfa/totp/activate', { mfaToken: step.mfaToken, code: totp(enrol.secret) });
  await auth('/auth/password/change', { currentPassword: temporary, newPassword: password }, tokens.accessToken);
  return { username, password, secret: enrol.secret, roles };
}

const plan = [
  ...Array.from({ length: 10 }, (_, i) => ['prep' + (i + 1), ['ACCOUNTANT'], 'preparer']),
  ...Array.from({ length: 4 }, (_, i) => ['rev' + (i + 1), ['ACCOUNTANT'], 'reviewer']),
  ...Array.from({ length: 3 }, (_, i) => ['appr' + (i + 1), ['CHIEF_ACCOUNTANT'], 'approver']),
  ...Array.from({ length: 2 }, (_, i) => ['post' + (i + 1), ['CHIEF_ACCOUNTANT'], 'poster']),
  ['report1', ['FINANCIAL_REPORTING'], 'reporter'],
];
log(`onboarding ${plan.length} internal scenario users and 6 support users`);
const internal = [];
for (const [prefix, roles, role] of plan) internal.push({ ...(await onboard(prefix, roles)), role });
const seedUsers = {};
for (const [k, roles] of [['acct', ['ACCOUNTANT']], ['rev', ['ACCOUNTANT']], ['chief', ['CHIEF_ACCOUNTANT']], ['poster', ['CHIEF_ACCOUNTANT']],
  ['cash', ['CASHIER']], ['auditor', ['INTERNAL_AUDITOR']]]) seedUsers[k] = await onboard('seed' + k, roles);

log(`onboarding ${vol.customers} concessionaire portal accounts`);
const portalUsers = [];
for (let i = 0; i < vol.customers; i++) portalUsers.push(await onboard('tenant' + (i + 1), ['CONCESSIONAIRE'], 'CONCESSIONAIRE'));

await nextWindow();
const S = {};
for (const [k, u] of Object.entries(seedUsers)) S[k] = await new Session(cfg.api, u).login();

// ------------------------------------------------------------------ master data

const pad = (i) => String(i + 1).padStart(4, '0');
log(`creating ${vol.customers} customers and ${vol.suppliers} suppliers`);
const customers = await pool(Array.from({ length: vol.customers }, (_, i) => i), vol.concurrency, (i) =>
  S.acct.must('seed.customer', 'seed', 'POST', '/customers', { code: `PF${tag}C${pad(i)}`, name: `Perf Concessionaire ${tag}-${pad(i)}`,
    customerType: 'CONCESSIONAIRE', paymentTermsDays: 30 }));
const suppliers = await pool(Array.from({ length: vol.suppliers }, (_, i) => i), vol.concurrency, (i) =>
  S.acct.must('seed.supplier', 'seed', 'POST', '/suppliers', { code: `PF${tag}S${pad(i)}`, name: `Perf Supplier ${tag}-${pad(i)}`,
    vatRegistered: false }));

log('linking portal accounts (request by an accountant, approval by a chief accountant)');
await pool(customers.map((c, i) => [c, portalUsers[i]]), vol.concurrency, async ([c, u]) => {
  const r = await S.acct.must('seed.portal-request', 'seed', 'POST', `/customers/${c.id}/portal-link-requests`,
    { username: u.username, evidence: 'Performance test account; identity not applicable' });
  await S.chief.must('seed.portal-approve', 'seed', 'POST', `/portal-link-requests/${r.id}/decision`, { approve: true, note: 'Performance test' });
  u.customerId = c.id;
});

// ------------------------------------------------------------------ opening balances through the migration batches

const money = (cents) => (cents / 100).toFixed(2);
const rnd = (min, max) => Math.floor(min + Math.random() * (max - min));
const arItems = customers.flatMap((c, ci) => Array.from({ length: vol.arItems }, (_, k) => ({ c, number: `PF${tag}-${ci}-${k}`,
  cents: rnd(100000, 5000000), month: 1 + (k % 11) })));
const apItems = suppliers.flatMap((s, si) => Array.from({ length: vol.apItems }, (_, k) => ({ s, number: `PFSI-${tag}-${si}-${k}`,
  cents: rnd(50000, 2000000), month: 1 + (k % 11) })));
const sum = (list, f) => list.reduce((t, x) => t + f(x), 0);
const perCustomer = new Map();
for (const i of arItems) perCustomer.set(i.c.code, (perCustomer.get(i.c.code) ?? 0) + i.cents);
const perSupplier = new Map();
for (const i of apItems) perSupplier.set(i.s.code, (perSupplier.get(i.s.code) ?? 0) + i.cents);
const arTotal = sum(arItems, (i) => i.cents);
const apTotal = sum(apItems, (i) => i.cents);
const cash = 50_000_000_00;
const tb = ['account_code,cost_center_code,subsidiary_code,description,debit,credit', `PLC-1102,,,Cash in bank,${money(cash)},`,
  ...[...perCustomer].map(([code, c]) => `PLC-1201,,${code},Receivable,${money(c)},`),
  ...[...perSupplier].map(([code, c]) => `PLC-2101,,${code},Payable,,${money(c)}`),
  `PLC-3102,,,Accumulated surplus,,${money(cash + arTotal - apTotal)}`].join('\n') + '\n';

async function upload(kind, fields, csv, name) {
  return S.acct.must('seed.migration-upload', 'seed', 'POST', '/migration/batches', undefined,
    { multipart: { kind, description: `Performance volume ${kind} ${tag}`, ...fields, file: { name, type: 'text/csv', content: Buffer.from(csv) } } });
}
log(`opening trial balance: ${tb.split('\n').length - 2} lines`);
let gl = await upload('GL_OPENING', { cutoverDate: CUTOVER }, tb, `perf-tb-${tag}.csv`);
if (gl.summary.status !== 'VALIDATED') throw new Error(`Trial balance invalid: ${JSON.stringify(gl.batchErrors)} ${JSON.stringify(gl.errorRows.slice(0, 3))}`);
gl = await S.acct.must('seed.migration-apply', 'seed', 'POST', `/migration/batches/${gl.summary.id}/apply`, { version: gl.summary.version });
const entryNumber = await postVoucher(gl.summary.journalVoucherId, `perf-opening-${tag}`);

const arCsv = ['customer_code,document_number,document_date,due_date,description,outstanding_amount',
  ...arItems.map((i) => `${i.c.code},${i.number},2025-${String(i.month).padStart(2, '0')}-10,2025-${String(i.month + 1).padStart(2, '0')}-10,Concession rent,${money(i.cents)}`)]
  .join('\n') + '\n';
const apCsv = ['supplier_code,invoice_number,invoice_date,due_date,description,outstanding_amount',
  ...apItems.map((i) => `${i.s.code},${i.number},2025-${String(i.month).padStart(2, '0')}-05,2025-${String(i.month + 1).padStart(2, '0')}-05,Services,${money(i.cents)}`)]
  .join('\n') + '\n';
for (const [kind, csv] of [['AR_OPEN_ITEMS', arCsv], ['AP_OPEN_ITEMS', apCsv]]) {
  const b = await upload(kind, { openingEntryNumber: entryNumber }, csv, `perf-${kind}-${tag}.csv`);
  if (!b.reconciliation?.reconciled) throw new Error(`${kind} does not reconcile: ${JSON.stringify(b.reconciliation ?? b.errorRows.slice(0, 3))}`);
  await S.chief.must('seed.migration-apply', 'seed', 'POST', `/migration/batches/${b.summary.id}/apply`, { version: b.summary.version });
  log(`${kind}: ${b.summary.rowCount} items applied`);
}

// ------------------------------------------------------------------ posted journal vouchers and collections

const accounts = (await S.acct.must('seed.accounts', 'seed', 'GET', '/accounts')).accounts;
const acct = (code) => accounts.find((x) => x.code === code)?.id;
const costCenters = await S.acct.must('seed.cost-centers', 'seed', 'GET', '/cost-centers');
const cc = costCenters.map((c) => c.id);

async function postVoucher(id, key) {
  let v = await S.acct.must('seed.jev', 'seed', 'GET', `/journal-vouchers/${id}`);
  v = await S.acct.must('seed.jev', 'seed', 'POST', `/journal-vouchers/${id}/submit`, { version: v.version });
  v = await S.rev.must('seed.jev', 'seed', 'POST', `/journal-vouchers/${id}/approve`, { version: v.version, comment: 'Reviewed' });
  v = await S.chief.must('seed.jev', 'seed', 'POST', `/journal-vouchers/${id}/approve`, { version: v.version, comment: 'Approved' });
  v = await S.poster.must('seed.jev', 'seed', 'POST', `/journal-vouchers/${id}/post`, { version: v.version }, { headers: { 'Idempotency-Key': key } });
  return v.voucherNumber;
}

log(`posting ${vol.vouchers} journal vouchers through the full workflow`);
let done = 0;
await pool(Array.from({ length: vol.vouchers }, (_, i) => i), vol.concurrency, async (i) => {
  const month = 1 + (i % 10);
  const lines = Array.from({ length: 1 + (i % 4) }, (_, k) => ({ accountId: acct(k % 2 ? 'PLC-5202' : 'PLC-5201'), costCenterId: cc[(i + k) % cc.length],
    description: `Expense ${k + 1}`, debit: money(rnd(10000, 500000)), credit: '0' }));
  const total = sum(lines, (l) => Math.round(Number(l.debit) * 100));
  lines.push({ accountId: acct('PLC-1102'), description: 'Cash in bank', debit: '0', credit: money(total) });
  const v = await S.acct.must('seed.jev', 'seed', 'POST', '/journal-vouchers', { voucherType: 'STANDARD', accountingDate: `2026-${String(month).padStart(2, '0')}-15`,
    adjustmentPeriod: false, description: `Performance volume voucher ${tag}-${i}`, lines });
  await S.acct.must('seed.jev', 'seed', 'POST', `/journal-vouchers/${v.id}/documents`, undefined, { multipart: { file: PDF } });
  await postVoucher(v.id, `perf-seed-${tag}-${i}`);
  if (++done % 100 === 0) log(`  ${done} vouchers posted`);
});

log(`recording and posting ${vol.receipts} collections per customer`);
await pool(customers, vol.concurrency, async (c) => {
  const open = await S.cash.must('seed.open-items', 'seed', 'GET', `/customers/${c.id}/open-items`);
  for (const item of open.slice(0, vol.receipts)) {
    const amount = (Math.floor(Number(item.openBalance) * 50) / 100).toFixed(2);
    const r = await S.cash.must('seed.receipt', 'seed', 'POST', '/receipts', { customerId: c.id, receiptDate: '2026-02-10', paymentMethod: 'CHECK',
      paymentReference: `CHK-${tag}`, amount, taxWithheld: '0', allocations: [{ documentId: item.id, amount }] },
      { headers: { 'Idempotency-Key': `perf-${crypto.randomUUID()}` } });
    await S.poster.must('seed.receipt-post', 'seed', 'POST', `/receipts/${r.id}/post`, { version: r.version });
  }
});

const periods = (await S.acct.must('seed.periods', 'seed', 'GET', '/fiscal-years')).flatMap((y) => y.periods ?? []);
mkdirSync(dirname(STATE_FILE), { recursive: true });
writeFileSync(STATE_FILE, JSON.stringify({ tag, createdAt: new Date().toISOString(), api: cfg.api, volumes: vol, openingEntryNumber: entryNumber,
  internal, support: seedUsers, portal: portalUsers, customers: customers.map((c) => ({ id: c.id, code: c.code })), suppliers: suppliers.map((s) => ({ id: s.id, code: s.code })),
  periods: periods.map((p) => ({ id: p.id, name: p.name, number: p.periodNumber, status: p.status, start: p.startDate })) }, null, 2), { mode: 0o600 });
log(`done; state written to ${STATE_FILE}`);
