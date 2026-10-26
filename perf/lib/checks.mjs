import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

const run = promisify(execFile);

/**
 * Financial correctness under load (§17: never sacrifice correctness, auditability or isolation for speed): the
 * trial balance stays balanced, the AR and AP sub-ledgers still agree with their control accounts (differences
 * unchanged by the run), and journal entry numbers stay gap-free and unique.
 */
export async function correctness(s, db, periodId) {
  const tb = await s.call('check.trial-balance', 'check', 'GET', `/general-ledger/trial-balance?periodId=${periodId}`);
  const ar = await s.call('check.ar-reconciliation', 'check', 'GET', '/accounts-receivable/reconciliation');
  const ap = await s.call('check.ap-reconciliation', 'check', 'GET', '/accounts-payable/reconciliation');
  const out = {
    trialBalanceBalanced: tb.json?.balanced ?? null,
    trialBalanceClosing: tb.json ? { debit: tb.json.totalClosingDebit, credit: tb.json.totalClosingCredit } : null,
    arDifference: ar.json?.receivables?.difference ?? null,
    unappliedDifference: ar.json?.unappliedCollections?.difference ?? null,
    apDifference: ap.json?.difference ?? null,
  };
  if (db) {
    const { stdout } = await run('psql', ['-h', db.host, '-p', String(db.port), '-U', db.user, '-d', db.database, '-At', '-F', '|', '-c',
      `SELECT split_part(entry_number, '-', 2), count(*), count(DISTINCT entry_number),
              max(split_part(entry_number, '-', 3)::int) - min(split_part(entry_number, '-', 3)::int) + 1
       FROM journal_entries GROUP BY 1 ORDER BY 1`], { env: { ...process.env, PGPASSWORD: db.password } });
    out.entryNumbering = stdout.trim().split('\n').filter(Boolean).map((l) => {
      const [year, count, distinct, span] = l.split('|');
      return { year, entries: Number(count), unique: Number(count) === Number(distinct), gapFree: Number(count) === Number(span) };
    });
  }
  return out;
}

/** The run passes the correctness gate only if nothing that held before it stopped holding. */
export function compare(before, after) {
  const problems = [];
  if (after.trialBalanceBalanced !== true) problems.push('The trial balance is not balanced after the run.');
  for (const k of ['arDifference', 'unappliedDifference', 'apDifference']) {
    if (before[k] != null && after[k] != null && Number(before[k]) !== Number(after[k])) {
      problems.push(`${k} changed during the run (${before[k]} -> ${after[k]}).`);
    }
  }
  for (const n of after.entryNumbering ?? []) {
    if (!n.unique) problems.push(`Journal entry numbers for ${n.year} are not unique.`);
    if (!n.gapFree) problems.push(`Journal entry numbers for ${n.year} have gaps.`);
  }
  return problems;
}
