import { PDF } from '../lib/config.mjs';

/**
 * Batch financial processing (§17: within 60 s for the agreed test workload; the workload is not yet agreed, so
 * the kit's is stated here and in the report):
 *  1. one journal voucher with {@code jevLines} lines (500 = the API maximum per voucher) taken from creation to
 *     posting through all four workflow steps;
 *  2. an open-invoice migration batch of {@code arItems} items: upload and validation, then application, against a
 *     new opening entry posted for the purpose.
 * Each step is timed on its own and the end-to-end time is recorded as one batch sample.
 */
export async function batch(ctx) {
  const { state, sessions, recorder, workload } = ctx;
  const by = (role) => sessions.internal.find((s) => s.user.role === role);
  const prep = by('preparer');
  const rev = by('reviewer');
  const appr = by('approver');
  const post = by('poster');
  const accounts = (await prep.must('setup.accounts', 'setup', 'GET', '/accounts')).accounts;
  const id = (code) => accounts.find((a) => a.code === code).id;
  const costCenters = await prep.must('setup.cost-centers', 'setup', 'GET', '/cost-centers');
  const today = new Date().toISOString().slice(0, 10);
  const timed = async (op, fn) => {
    const start = performance.now();
    try {
      const r = await fn();
      recorder.add({ op, kind: 'batch', ms: performance.now() - start, serverMs: null, status: 200, ok: true });
      return r;
    } catch (e) {
      recorder.add({ op, kind: 'batch', ms: performance.now() - start, serverMs: null, status: 0, ok: false, error: e.message });
      ctx.notes.push(`${op} failed: ${e.message}`);
      return null;
    }
  };

  const workflow = async (voucherId, key, opPrefix) => {
    let v = await prep.must(`${opPrefix}.get`, 'batch-step', 'GET', `/journal-vouchers/${voucherId}`);
    v = await prep.must(`${opPrefix}.submit`, 'batch-step', 'POST', `/journal-vouchers/${voucherId}/submit`, { version: v.version });
    v = await rev.must(`${opPrefix}.review`, 'batch-step', 'POST', `/journal-vouchers/${voucherId}/approve`, { version: v.version, comment: 'Batch test' });
    v = await appr.must(`${opPrefix}.approve`, 'batch-step', 'POST', `/journal-vouchers/${voucherId}/approve`, { version: v.version, comment: 'Batch test' });
    v = await post.must(`${opPrefix}.post`, 'batch-step', 'POST', `/journal-vouchers/${voucherId}/post`, { version: v.version },
      { headers: { 'Idempotency-Key': key } });
    return v;
  };

  // 1. Large journal voucher.
  const n = workload.jevLines;
  await timed(`batch.jev-${n}-lines.end-to-end`, async () => {
    const lines = Array.from({ length: n - 1 }, (_, i) => ({ accountId: id(i % 2 ? 'PLC-5202' : 'PLC-5201'), costCenterId: costCenters[i % costCenters.length].id,
      description: `Allocation ${i + 1}`, debit: '100.00', credit: '0' }));
    lines.push({ accountId: id('PLC-1102'), description: 'Cash in bank', debit: '0', credit: ((n - 1) * 100).toFixed(2) });
    const v = await prep.must(`batch.jev-${n}.create`, 'batch-step', 'POST', '/journal-vouchers', { voucherType: 'STANDARD', accountingDate: today,
      adjustmentPeriod: false, description: `Batch test: ${n}-line allocation voucher`, lines });
    await prep.must(`batch.jev-${n}.attach`, 'batch-step', 'POST', `/journal-vouchers/${v.id}/documents`, undefined, { multipart: { file: PDF } });
    return workflow(v.id, `perf-batch-${crypto.randomUUID()}`, `batch.jev-${n}`);
  });

  // 2. Open-invoice migration batch against a new opening entry (each run adds one opening entry: test data only).
  const items = workload.arItems;
  const tag = Date.now().toString(36).toUpperCase();
  const per = Math.ceil(items / state.customers.length);
  const rows = [];
  const perCustomer = new Map();
  for (let i = 0; i < items; i++) {
    const c = state.customers[Math.floor(i / per) % state.customers.length];
    const cents = 10000 + (i % 977) * 100;
    rows.push(`${c.code},PB${tag}-${i},2025-12-01,2026-01-31,Batch test rent,${(cents / 100).toFixed(2)}`);
    perCustomer.set(c.code, (perCustomer.get(c.code) ?? 0) + cents);
  }
  const total = [...perCustomer.values()].reduce((a, b) => a + b, 0);
  const tb = ['account_code,cost_center_code,subsidiary_code,description,debit,credit',
    ...[...perCustomer].map(([code, c]) => `PLC-1201,,${code},Receivable ${tag},${(c / 100).toFixed(2)},`),
    `PLC-3102,,,Accumulated surplus ${tag},,${(total / 100).toFixed(2)}`].join('\n') + '\n';
  const upload = (kind, fields, csv) => prep.must(`batch.${kind}.upload`, 'batch-step', 'POST', '/migration/batches', undefined,
    { multipart: { kind, description: `Batch test ${kind} ${tag}`, ...fields, file: { name: `batch-${kind}-${tag}.csv`, type: 'text/csv', content: Buffer.from(csv) } } });
  let gl = await upload('GL_OPENING', { cutoverDate: '2026-01-01' }, tb);
  gl = await prep.must('batch.gl.voucher', 'batch-step', 'POST', `/migration/batches/${gl.summary.id}/apply`, { version: gl.summary.version });
  const entry = (await workflow(gl.summary.journalVoucherId, `perf-batch-${crypto.randomUUID()}`, 'batch.opening')).voucherNumber;
  const csv = ['customer_code,document_number,document_date,due_date,description,outstanding_amount', ...rows].join('\n') + '\n';
  const ar = await timed(`batch.ar-migration-${items}.upload-and-validate`, () => upload('AR_OPEN_ITEMS', { openingEntryNumber: entry }, csv));
  if (ar && !ar.reconciliation?.reconciled) ctx.notes.push(`AR batch did not reconcile: ${JSON.stringify(ar.reconciliation)}`);
  if (ar) {
    await timed(`batch.ar-migration-${items}.apply`, () => appr.must(`batch.ar-${items}.apply`, 'batch-step', 'POST',
      `/migration/batches/${ar.summary.id}/apply`, { version: ar.summary.version }));
  }
  ctx.workloadNote = `One ${n}-line journal voucher through create, attach, submit, review, approve and post; one open-invoice batch of `
    + `${items} items across ${perCustomer.size} customers (upload with validation and reconciliation, then application).`;
}
