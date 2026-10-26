#!/usr/bin/env node
// Runs performance scenarios against CAS and writes perf/results/<timestamp>/ (summary.json, report.md, samples.csv).
//
//   node perf/run.mjs [--scenario mixed|internal|portal|batch|reports|all] [--duration 300] [--think 2000,5000]
//                     [--internal-users 20] [--portal-users 60] [--jev-lines 500] [--ar-items 5000]
//
// "mixed" is the §3.3 / §18 test: 20 internal and 60 portal users at the same time. "all" runs mixed, then batch,
// then reports. Requires perf/seed.mjs to have been run against the same environment.
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { compare, correctness } from './lib/checks.mjs';
import { Session, sleep } from './lib/client.mjs';
import { args, PERF_DIR, settings, STATE_FILE } from './lib/config.mjs';
import { report } from './lib/report.mjs';
import { environment, ResourceSampler } from './lib/resources.mjs';
import { Recorder } from './lib/stats.mjs';
import { nextWindow } from './lib/totp.mjs';
import { batch } from './scenarios/batch.mjs';
import { internal } from './scenarios/internal.mjs';
import { portal } from './scenarios/portal.mjs';
import { reports } from './scenarios/reports.mjs';

const a = args();
const scenario = a.scenario ?? 'mixed';
const plan = { mixed: ['mixed'], internal: ['internal'], portal: ['portal'], batch: ['batch'], reports: ['reports'], all: ['mixed', 'batch', 'reports'] }[scenario];
if (!plan) throw new Error(`Unknown scenario ${scenario}`);
if (!existsSync(STATE_FILE)) throw new Error('No seed state: run node perf/seed.mjs first.');
const state = JSON.parse(readFileSync(STATE_FILE, 'utf8'));
const targets = JSON.parse(readFileSync(join(PERF_DIR, 'targets.json'), 'utf8'));
const cfg = settings();
const opts = {
  durationSeconds: Number(a.duration ?? 300),
  thinkMs: String(a.think ?? '2000,5000').split(',').map(Number),
  internalUsers: Number(a['internal-users'] ?? targets.concurrency.internal.users),
  portalUsers: Number(a['portal-users'] ?? targets.concurrency.portal.users),
  workload: { jevLines: Number(a['jev-lines'] ?? 500), arItems: Number(a['ar-items'] ?? 5000) },
};
const log = (m) => console.log(`[perf ${new Date().toISOString().slice(11, 19)}] ${m}`);
const recorder = new Recorder();
const notes = [];
const jobs = [];

// Sign-ins are spaced so the per-address auth rate limit (two requests per sign-in) is never hit; in production
// users come from many addresses. Each user signs in once per run (a TOTP code is accepted once per step).
const spacingMs = Number(a['login-spacing'] ?? 1100);
async function signIn(users) {
  const out = [];
  for (const u of users) {
    out.push(await new Session(cfg.api, u, recorder).login());
    await sleep(spacingMs);
  }
  return out;
}

const needInternal = plan.some((p) => p !== 'portal');
const needPortal = plan.some((p) => p === 'mixed' || p === 'portal');
log(`scenario ${scenario}; waiting for a fresh authenticator step`);
await nextWindow();
const sessions = { internal: [], portal: [], support: {} };
if (needInternal) {
  log(`signing in ${opts.internalUsers} internal users`);
  sessions.internal = await signIn(state.internal.slice(0, opts.internalUsers));
}
if (needPortal) {
  log(`signing in ${opts.portalUsers} portal users`);
  sessions.portal = await signIn(state.portal.slice(0, opts.portalUsers));
}
if (plan.includes('reports')) [sessions.support.auditor] = await signIn([state.support.auditor]);
const checker = sessions.internal.find((s) => s.user.role === 'approver') ?? (await signIn([state.support.chief]))[0];
const today = new Date().toISOString().slice(0, 10);
const period = state.periods.filter((p) => p.status === 'OPEN' && p.start <= today).at(-1);

const before = await correctness(checker, cfg.db, period.id);
const sampler = new ResourceSampler(cfg.db);
await sampler.start();
const phases = [];
for (const p of plan) {
  const started = Date.now();
  const ctx = { state, sessions, recorder, notes, jobs, thinkMs: opts.thinkMs, workload: opts.workload, stopAt: Date.now() + opts.durationSeconds * 1000 };
  log(`phase ${p} started`);
  let failed = null;
  try {
    if (p === 'mixed') {
      await Promise.all([internal({ ...ctx, sessions: { ...sessions } }), portal(ctx)]);
    } else if (p === 'internal') {
      await internal(ctx);
    } else if (p === 'portal') {
      await portal(ctx);
    } else if (p === 'batch') {
      await batch(ctx);
    } else {
      await reports(ctx);
    }
  } catch (e) {
    // A failed phase fails the run, but the measurements so far are still reported.
    failed = e.message;
    notes.push(`Phase ${p} stopped with an error: ${e.message}`);
    log(`phase ${p} failed: ${e.message}`);
  }
  phases.push({ phase: p, seconds: Math.round((Date.now() - started) / 1000), workload: ctx.workloadNote, failed });
  log(`phase ${p} finished`);
}
const resources = await sampler.stop();
const after = await correctness(checker, cfg.db, period.id);

const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
const dir = join(PERF_DIR, 'results', `${stamp}-${scenario}`);
mkdirSync(dir, { recursive: true });
const result = {
  scenario, startedAt: new Date(recorder.startedAt).toISOString(), options: opts, phases, environment: await environment(cfg.api),
  seed: { tag: state.tag, createdAt: state.createdAt, volumes: state.volumes, portalAccounts: state.portal.length },
  stats: recorder.summary(), resources, correctness: { before, after, problems: compare(before, after) }, notes, jobs, targets,
};
const { markdown, verdict } = report(result);
result.verdict = verdict;
writeFileSync(join(dir, 'summary.json'), JSON.stringify(result, null, 2));
writeFileSync(join(dir, 'report.md'), markdown);
writeFileSync(join(dir, 'samples.csv'), 't_ms,kind,op,status,ok,client_ms,server_ms\n'
  + recorder.samples.map((s) => [s.t, s.kind, s.op, s.status, s.ok, s.ms.toFixed(1), s.serverMs ?? ''].join(',')).join('\n') + '\n');
log(`verdict: ${verdict.overall}; report in ${dir}`);
process.exitCode = verdict.overall === 'PASS' ? 0 : 1;
