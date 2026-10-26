import { sleep } from '../lib/client.mjs';

/**
 * Standard report generation (§17: within 60 s). Report jobs are timed from request to ready (queue time and
 * rendering included) plus the download; synchronous statements and the trial balance are timed per request. Jobs
 * run one at a time first, then all at once to show queueing behind the background worker.
 */
export async function reports(ctx) {
  const { state, sessions, recorder } = ctx;
  const chief = sessions.internal.find((s) => s.user.role === 'approver');
  const auditor = sessions.support.auditor;
  const today = new Date().toISOString().slice(0, 10);
  const current = state.periods.filter((p) => p.status === 'OPEN' && p.start <= today).at(-1);
  const year = today.slice(0, 4);
  const jobs = [
    [chief, 'TRIAL_BALANCE', { periodId: current.id }],
    [chief, 'FINANCIAL_STATEMENT', { template: 'PLC-SFP', periodId: current.id, comparative: 'true' }],
    [chief, 'AR_AGING', { asOf: today }],
    [chief, 'AP_AGING', { asOf: today }],
    [auditor, 'AUDIT_TRAIL', { from: `${year}-01-01`, to: today }],
  ];

  const job = async ([s, type, parameters], label) => {
    const start = performance.now();
    const r = await s.call(`report.${type}.request`, 'report-step', 'POST', '/report-jobs', { reportType: type, format: 'XLSX', parameters });
    if (!r.ok) {
      ctx.notes.push(`${type} job refused: ${r.status} ${r.text.slice(0, 200)}`);
      recorder.add({ op: `report.${type}.${label}`, kind: 'report', ms: performance.now() - start, serverMs: null, status: r.status, ok: false });
      return;
    }
    let j = r.json;
    while (['QUEUED', 'RUNNING'].includes(j.status) && performance.now() - start < 300000) {
      await sleep(500);
      j = (await s.call(`report.${type}.poll`, 'report-step', 'GET', `/report-jobs/${j.id}`)).json ?? j;
    }
    const ok = j.status === 'DONE';
    const secs = (x, y) => (x && y ? Math.round((Date.parse(y) - Date.parse(x)) / 100) / 10 : null);
    ctx.jobs.push({ type, run: label, status: j.status, queuedSeconds: secs(j.requestedAt, j.startedAt),
      renderSeconds: secs(j.startedAt, j.finishedAt), sizeBytes: j.sizeBytes ?? null });
    if (ok) await s.call(`report.${type}.download`, 'report-step', 'GET', `/report-jobs/${j.id}/file`);
    if (!ok) ctx.notes.push(`${type} job ended ${j.status}: ${j.error ?? ''}`);
    recorder.add({ op: `report.${type}.${label}`, kind: 'report', ms: performance.now() - start, serverMs: null, status: ok ? 200 : 0, ok,
      sizeBytes: j.sizeBytes });
  };

  for (const spec of jobs) await job(spec, 'single');
  await Promise.all(jobs.map((spec) => job(spec, 'concurrent')));
  await chief.call('report.statement-sync', 'report', 'GET', `/financial-statements?template=PLC-SFP&periodId=${current.id}&comparative=true`);
  await chief.call('report.trial-balance-sync', 'report', 'GET', `/general-ledger/trial-balance?periodId=${current.id}`);
}
