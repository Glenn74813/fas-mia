/** Collects timed samples and summarises them per operation and per target kind. */
export class Recorder {
  constructor() {
    this.samples = [];
    this.startedAt = Date.now();
  }

  add(s) {
    this.samples.push({ ...s, t: Date.now() - this.startedAt });
  }

  summary() {
    const byOp = new Map();
    for (const s of this.samples) {
      const key = `${s.kind}|${s.op}`;
      if (!byOp.has(key)) byOp.set(key, []);
      byOp.get(key).push(s);
    }
    const ops = [...byOp.entries()].map(([key, list]) => {
      const [kind, op] = key.split('|');
      return { kind, op, ...describe(list) };
    }).sort((a, b) => a.kind.localeCompare(b.kind) || a.op.localeCompare(b.op));
    const kinds = {};
    for (const k of new Set(this.samples.map((s) => s.kind))) kinds[k] = describe(this.samples.filter((s) => s.kind === k));
    const seconds = (Date.now() - this.startedAt) / 1000;
    return { durationSeconds: round(seconds), requests: this.samples.length, throughputPerSecond: round(this.samples.length / seconds), kinds, ops };
  }
}

export function describe(list) {
  const client = list.map((s) => s.ms).sort((a, b) => a - b);
  const server = list.filter((s) => s.serverMs != null).map((s) => s.serverMs).sort((a, b) => a - b);
  const errors = list.filter((s) => !s.ok);
  const statuses = {};
  for (const s of errors) statuses[s.status] = (statuses[s.status] ?? 0) + 1;
  return {
    count: list.length,
    errors: errors.length,
    errorRate: list.length ? round(errors.length / list.length, 4) : 0,
    serverErrors: errors.filter((s) => s.status >= 500).length,
    errorStatuses: statuses,
    client: percentiles(client),
    server: server.length ? percentiles(server) : null,
  };
}

/** Nearest-rank percentiles over the exact sorted sample. */
export function percentiles(sorted) {
  const p = (q) => sorted.length ? sorted[Math.min(sorted.length - 1, Math.ceil(q * sorted.length) - 1)] : null;
  return { p50: round(p(0.5)), p90: round(p(0.9)), p95: round(p(0.95)), p99: round(p(0.99)), max: round(sorted.at(-1) ?? null) };
}

export const round = (v, d = 1) => (v == null ? null : Math.round(v * 10 ** d) / 10 ** d);
