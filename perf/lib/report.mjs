/**
 * Judges a run against perf/targets.json and renders report.md. A target kind passes when its p95 (server-side
 * where the server reported it, otherwise client-side) is within the limit, it had no server errors and its rate of
 * unexpected responses is within the threshold. The run passes only if every measured kind passes, the intended
 * concurrency was reached and the correctness checks found nothing.
 */
export function report(r) {
  const t = r.targets;
  const kinds = ['entry', 'read', 'report', 'batch'].filter((k) => r.stats.kinds[k]);
  const rows = kinds.map((k) => {
    const s = r.stats.kinds[k];
    const useServer = s.server && s.server.p95 != null && (k === 'entry' || k === 'read');
    const p95 = useServer ? s.server.p95 : s.client.p95;
    const limit = t.targets[k].maxMs;
    const pass = p95 <= limit && s.serverErrors <= t.errors.maxServerErrors && s.errorRate <= t.errors.maxErrorRate;
    return { kind: k, count: s.count, p95, basis: useServer ? 'server' : 'client', limit, errors: s.errors, errorRate: s.errorRate,
      serverErrors: s.serverErrors, pass, targetBasis: t.targets[k].basis };
  });
  const concurrency = [];
  if (r.phases.some((p) => p.phase === 'mixed' || p.phase === 'internal')) {
    concurrency.push({ group: 'internal', target: t.concurrency.internal.users, reached: r.options.internalUsers });
  }
  if (r.phases.some((p) => p.phase === 'mixed' || p.phase === 'portal')) {
    concurrency.push({ group: 'portal', target: t.concurrency.portal.users, reached: r.options.portalUsers });
  }
  const concurrencyOk = concurrency.every((c) => c.reached >= c.target);
  const correctnessOk = r.correctness.problems.length === 0;
  const phasesOk = r.phases.every((p) => !p.failed);
  const overall = rows.every((x) => x.pass) && concurrencyOk && correctnessOk && phasesOk ? 'PASS' : 'FAIL';
  const verdict = { overall, kinds: rows, concurrency, concurrencyOk, correctnessOk, phasesOk };

  const ms = (v) => (v == null ? '—' : v >= 10000 ? `${(v / 1000).toFixed(1)} s` : `${Math.round(v)} ms`);
  const e = r.environment;
  const lines = [];
  lines.push(`# CAS performance run: ${r.scenario} (${overall})`, '');
  lines.push(`Started ${r.startedAt}. Phases: ${r.phases.map((p) => `${p.phase} ${p.seconds} s`).join(', ')}.`, '');
  lines.push('> Results on a developer workstation are indicative only. Acceptance requires the documented target environment, '
    + 'MIAA-agreed volumes (C-12) and the measurement definition and thresholds marked PROPOSED below (C-02, C-17).', '');
  lines.push('## Verdict against the MS §17 targets', '', '| Kind | Requests | p95 | Measured | Limit | Errors (rate) | 5xx | Result | Basis |', '|---|---|---|---|---|---|---|---|---|');
  for (const x of rows) {
    lines.push(`| ${x.kind} | ${x.count} | ${ms(x.p95)} | ${x.basis} | ${ms(x.limit)} | ${x.errors} (${(x.errorRate * 100).toFixed(2)} %) | ${x.serverErrors} | `
      + `${x.pass ? 'PASS' : 'FAIL'} | ${x.targetBasis} |`);
  }
  lines.push('', `Error thresholds: ${t.errors.basis}`, '');
  if (concurrency.length) {
    lines.push('Concurrency: ' + concurrency.map((c) => `${c.group} ${c.reached} of ${c.target} required`).join('; ')
      + (concurrencyOk ? '.' : ' — below the §17 figure, so the run cannot pass.'), '');
  }
  lines.push(`Correctness under load: ${correctnessOk ? 'no problems found' : r.correctness.problems.join(' ')}.`, '');
  for (const p of r.phases.filter((x) => x.failed)) lines.push(`**Phase ${p.phase} failed**: ${p.failed}`, '');
  lines.push('## Test environment', '', `| Item | Value |`, '|---|---|',
    `| Host | ${e.host} (${e.platform}) |`, `| CPU / memory | ${e.cpus} / ${e.memoryGb} GB |`, `| Java / Node | ${e.java} / ${e.node} |`,
    `| API | ${e.api}${e.loadGeneratorOnSameHost ? ' (load generator on the same host: no network in the client figures)' : ''} |`,
    `| Seed data | tag ${r.seed.tag}, ${r.seed.createdAt}; ${JSON.stringify(r.seed.volumes)}; ${r.seed.portalAccounts} portal accounts |`,
    `| Think time | ${r.options.thinkMs.join('–')} ms between actions per user |`, '');
  for (const p of r.phases.filter((x) => x.workload)) lines.push(`Batch workload: ${p.workload}`, '');
  lines.push('## Operations', '', '| Kind | Operation | Count | Errors | Client p50 | p95 | p99 | max | Server p50 | p95 | p99 |', '|---|---|---|---|---|---|---|---|---|---|---|');
  for (const o of r.stats.ops) {
    const sv = o.server ?? {};
    lines.push(`| ${o.kind} | ${o.op} | ${o.count} | ${o.errors}${o.errors ? ' ' + JSON.stringify(o.errorStatuses) : ''} | ${ms(o.client.p50)} | `
      + `${ms(o.client.p95)} | ${ms(o.client.p99)} | ${ms(o.client.max)} | ${ms(sv.p50)} | ${ms(sv.p95)} | ${ms(sv.p99)} |`);
  }
  const goal = t.subSecondGoal;
  const goalOps = r.stats.ops.filter((o) => goal.operations.includes(o.op));
  if (goalOps.length) {
    lines.push('', `Sub-second goal (${goal.basis}): ` + goalOps.map((o) => {
      const p50 = (o.server ?? o.client).p50;
      return `${o.op} p50 ${ms(p50)} ${p50 <= goal.p50Ms ? 'meets' : 'misses'} it`;
    }).join('; ') + '.');
  }
  lines.push('', '409 responses on review, approval and posting are expected when two users act on the same voucher; they are counted as successes. '
    + 'Kinds batch-step and report-step are the individual calls inside a batch or report measurement and are not judged.', '');
  if (r.jobs?.length) {
    lines.push('## Report jobs', '', 'Queue time is mostly the background worker\'s poll interval (`cas.reports.poll-interval`, default 5 s) and, '
      + 'for concurrent requests, waiting behind earlier jobs: one worker renders jobs one at a time.', '',
    '| Report | Run | Status | Queued | Rendering | Size |', '|---|---|---|---|---|---|',
    ...r.jobs.map((j) => `| ${j.type} | ${j.run} | ${j.status} | ${j.queuedSeconds ?? '—'} s | ${j.renderSeconds ?? '—'} s | ${j.sizeBytes ?? '—'} B |`), '');
  }
  const res = r.resources;
  lines.push('## Resources', '', '| Measure | Value |', '|---|---|',
    `| Max DB connections (active queries) | ${res.maxConnections ?? '—'} (${res.maxActiveQueries ?? '—'}) |`,
    `| Commits per second | ${res.commitsPerSecond ?? '—'} |`, `| Rollbacks / deadlocks | ${res.rollbacks ?? '—'} / ${res.deadlocks ?? '—'} |`,
    `| Buffer cache hit ratio | ${res.cacheHitRatio ?? '—'} |`, `| Database size | ${res.databaseSizeMb ?? '—'} MB |`,
    `| Backend JVM max CPU / RSS | ${res.maxJvmCpuPercent ?? '—'} % / ${res.maxJvmRssMb ?? '—'} MB |`, `| Max 1-min load average | ${res.maxLoad1} |`, '');
  if (res.notes?.length) lines.push(...res.notes.map((n) => `* ${n}`), '');
  lines.push('## Correctness checks', '', '```json', JSON.stringify({ before: r.correctness.before, after: r.correctness.after }, null, 2), '```', '');
  if (r.notes.length) lines.push('## Notes', '', ...r.notes.map((n) => `* ${n}`), '');
  return { markdown: lines.join('\n'), verdict };
}
