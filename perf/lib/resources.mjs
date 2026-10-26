import { execFile } from 'node:child_process';
import os from 'node:os';
import { promisify } from 'node:util';

const run = promisify(execFile);

const DB_SQL = `SELECT (SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()),
  (SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND state = 'active'),
  xact_commit, xact_rollback, blks_read, blks_hit, deadlocks, pg_database_size(current_database())
FROM pg_stat_database WHERE datname = current_database()`;

/**
 * Samples PostgreSQL (connections, commits, cache hits, deadlocks, size) with psql, and the backend JVM's CPU and
 * memory with ps, every few seconds while a scenario runs. Both are optional: without psql access or a local
 * backend process the samples are simply absent from the report.
 */
export class ResourceSampler {
  constructor(db, intervalMs = 5000) {
    this.db = db;
    this.intervalMs = intervalMs;
    this.samples = [];
    this.notes = [];
  }

  start() {
    this.timer = setInterval(() => this.sample().catch((e) => this.note(e.message)), this.intervalMs);
    return this.sample().catch((e) => this.note(e.message));
  }

  async stop() {
    clearInterval(this.timer);
    await this.sample().catch((e) => this.note(e.message));
    return this.summary();
  }

  note(m) {
    if (!this.notes.includes(m)) this.notes.push(m);
  }

  async sample() {
    const s = { t: Date.now() };
    if (this.db) {
      const { stdout } = await run('psql', ['-h', this.db.host, '-p', String(this.db.port), '-U', this.db.user, '-d', this.db.database,
        '-At', '-F', '|', '-c', DB_SQL], { env: { ...process.env, PGPASSWORD: this.db.password } });
      const [conns, active, commits, rollbacks, read, hit, deadlocks, size] = stdout.trim().split('|').map(Number);
      Object.assign(s, { conns, active, commits, rollbacks, read, hit, deadlocks, size });
    }
    try {
      const { stdout: pid } = await run('pgrep', ['-f', 'cas-backend-.*\\.jar']);
      const { stdout } = await run('ps', ['-o', '%cpu=,rss=', '-p', pid.trim().split('\n')[0]]);
      const [cpu, rss] = stdout.trim().split(/\s+/).map(Number);
      Object.assign(s, { jvmCpu: cpu, jvmRssMb: Math.round(rss / 1024) });
    } catch {
      this.note('Backend process not found on this host; JVM CPU and memory not sampled.');
    }
    s.load1 = os.loadavg()[0];
    this.samples.push(s);
  }

  summary() {
    const db = this.samples.filter((s) => s.commits != null);
    const first = db[0];
    const last = db.at(-1);
    const secs = first && last ? (last.t - first.t) / 1000 : 0;
    const max = (k) => Math.max(...this.samples.filter((s) => s[k] != null).map((s) => s[k]));
    const out = { samples: this.samples.length, notes: this.notes };
    if (first && last && secs > 0) {
      const reads = last.read - first.read;
      const hits = last.hit - first.hit;
      Object.assign(out, {
        maxConnections: max('conns'), maxActiveQueries: max('active'),
        commitsPerSecond: Math.round(((last.commits - first.commits) / secs) * 10) / 10,
        rollbacks: last.rollbacks - first.rollbacks, deadlocks: last.deadlocks - first.deadlocks,
        cacheHitRatio: reads + hits ? Math.round((hits / (reads + hits)) * 10000) / 10000 : null,
        databaseSizeMb: Math.round(last.size / 1048576),
      });
    }
    if (this.samples.some((s) => s.jvmCpu != null)) Object.assign(out, { maxJvmCpuPercent: max('jvmCpu'), maxJvmRssMb: max('jvmRssMb') });
    out.maxLoad1 = Math.round(max('load1') * 100) / 100;
    return out;
  }
}

/** The test environment as the report must document it (MS §17: "a documented test environment"). */
export async function environment(api) {
  const env = {
    host: os.hostname(), platform: `${os.type()} ${os.release()}`, cpus: `${os.cpus().length} x ${os.cpus()[0]?.model ?? '?'}`,
    memoryGb: Math.round(os.totalmem() / 1073741824), node: process.version, api,
    loadGeneratorOnSameHost: /localhost|127\.0\.0\.1/.test(api),
  };
  try {
    env.java = (await run('java', ['-version'])).stderr.split('\n')[0];
  } catch {
    env.java = 'unknown';
  }
  return env;
}
