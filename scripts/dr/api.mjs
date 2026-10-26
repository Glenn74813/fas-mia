#!/usr/bin/env node
// API steps of the DR exercise:
//   node scripts/dr/api.mjs <api-url> create-user <username>   creates a marker user (as the administrator)
//   node scripts/dr/api.mjs <api-url> user-exists <username>   prints true / false (as the administrator)
//   node scripts/dr/api.mjs <api-url> verify-chains            verifies both audit hash chains (as an auditor); prints JSON
// The administrator comes from the perf settings (PERF_ADMIN_* or the dev .env). Verifying the audit trail needs
// AUDIT_LOG_VIEW, which administrators do not hold (segregation of duties): set DR_AUDITOR_USERNAME,
// DR_AUDITOR_PASSWORD and DR_AUDITOR_TOTP_SECRET, or seed the performance kit, whose internal auditor is used.
import { existsSync, readFileSync } from 'node:fs';
import { Session } from '../../perf/lib/client.mjs';
import { settings, STATE_FILE } from '../../perf/lib/config.mjs';
import { nextWindow } from '../../perf/lib/totp.mjs';

const [api, command, arg] = process.argv.slice(2);

function auditor() {
  if (process.env.DR_AUDITOR_USERNAME) {
    return { username: process.env.DR_AUDITOR_USERNAME, password: process.env.DR_AUDITOR_PASSWORD, secret: process.env.DR_AUDITOR_TOTP_SECRET };
  }
  if (existsSync(STATE_FILE)) return JSON.parse(readFileSync(STATE_FILE, 'utf8')).support.auditor;
  throw new Error('Set DR_AUDITOR_USERNAME, DR_AUDITOR_PASSWORD and DR_AUDITOR_TOTP_SECRET for a user with AUDIT_LOG_VIEW.');
}

/** A code is accepted once per user per 30-second step, so a second sign-in in the same step waits for the next. */
async function signIn(user) {
  try {
    return await new Session(api, user).login();
  } catch (e) {
    if (!/mfa .* 401/.test(e.message)) throw e;
    await nextWindow();
    return new Session(api, user).login();
  }
}

if (command === 'create-user') {
  const s = await signIn(settings().admin);
  await s.must('dr.create-user', 'dr', 'POST', '/users', { username: arg, email: `${arg}@dr.miaa.local`, displayName: `DR marker ${arg}`,
    userType: 'INTERNAL', temporaryPassword: `Dr-${crypto.randomUUID().slice(0, 12)}-Aa1!`, roleCodes: ['MANAGEMENT_VIEWER'] });
  console.log('created');
} else if (command === 'user-exists') {
  const s = await signIn(settings().admin);
  const r = await s.must('dr.users', 'dr', 'GET', `/users?q=${encodeURIComponent(arg)}&page=0&size=50`);
  console.log(String(r.items.some((u) => u.username === arg)));
} else if (command === 'verify-chains') {
  const s = await signIn(auditor());
  console.log(JSON.stringify(await s.must('dr.verify', 'dr', 'POST', '/audit-events/verification')));
} else {
  throw new Error(`unknown command ${command}`);
}
