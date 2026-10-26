import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

export const PERF_DIR = join(dirname(fileURLToPath(import.meta.url)), '..');
export const STATE_FILE = join(PERF_DIR, '.state', 'seed.json');

/** --key value / --flag arguments. */
export function args(argv = process.argv.slice(2)) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    if (!argv[i].startsWith('--')) continue;
    const key = argv[i].slice(2);
    out[key] = argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[++i] : true;
  }
  return out;
}

/** Settings from the environment, falling back to the repository's development .env. */
export function settings() {
  const envFile = join(PERF_DIR, '..', '.env');
  const dot = existsSync(envFile) ? Object.fromEntries(readFileSync(envFile, 'utf8').split('\n').filter((l) => /^[A-Z_]+=/.test(l))
    .map((l) => [l.slice(0, l.indexOf('=')), l.slice(l.indexOf('=') + 1)])) : {};
  const get = (k, ...fallbacks) => process.env[k] ?? fallbacks.map((f) => process.env[f] ?? dot[f]).find((v) => v);
  const jdbc = get('PERF_DB_URL', 'CAS_DB_URL') ?? '';
  const m = /jdbc:postgresql:\/\/([^:/]+)(?::(\d+))?\/([^?]+)/.exec(jdbc);
  return {
    api: get('PERF_API_URL') ?? 'http://127.0.0.1:8080',
    admin: { username: get('PERF_ADMIN_USERNAME', 'CAS_BOOTSTRAP_ADMIN_USERNAME') ?? 'admin.dev',
      password: get('PERF_ADMIN_PASSWORD', 'CAS_DEV_ADMIN_CURRENT_PASSWORD'), secret: get('PERF_ADMIN_TOTP_SECRET', 'CAS_DEV_ADMIN_TOTP_SECRET') },
    db: m ? { host: m[1], port: Number(m[2] ?? 5432), database: m[3], user: get('PERF_DB_USER', 'CAS_DB_USER'),
      password: get('PERF_DB_PASSWORD', 'CAS_DB_PASSWORD') } : null,
  };
}

/** Runs {@code fn} over items with at most {@code n} in flight. */
export async function pool(items, n, fn) {
  const results = new Array(items.length);
  let next = 0;
  await Promise.all(Array.from({ length: Math.min(n, items.length) }, async () => {
    while (next < items.length) {
      const i = next++;
      results[i] = await fn(items[i], i);
    }
  }));
  return results;
}

export const PDF = { name: 'support.pdf', type: 'application/pdf', content: Buffer.from('%PDF-1.4\n% performance test supporting document\n%%EOF\n') };
