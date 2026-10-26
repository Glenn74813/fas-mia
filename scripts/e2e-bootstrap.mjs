#!/usr/bin/env node
// CI only: completes the first sign-in of the bootstrap administrator (CAS_BOOTSTRAP_ADMIN_*) so Playwright can
// sign in as an enrolled administrator. Enrols TOTP, replaces the temporary password with a random one and
// exports E2E_ADMIN_USERNAME / E2E_ADMIN_PASSWORD / E2E_ADMIN_TOTP_SECRET to $GITHUB_ENV (masked in the log).
// Usage: node scripts/e2e-bootstrap.mjs <api-url> <username> <temporary-password>
import { createHmac, randomBytes } from 'node:crypto';
import { appendFileSync } from 'node:fs';

const [api, username, temporary] = process.argv.slice(2);
if (!api || !username || !temporary) {
  console.error('Usage: node scripts/e2e-bootstrap.mjs <api-url> <username> <temporary-password>');
  process.exit(2);
}

const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
function base32Decode(input) {
  let bits = '';
  for (const c of input.replace(/=+$/, '').toUpperCase()) bits += ALPHABET.indexOf(c).toString(2).padStart(5, '0');
  const bytes = [];
  for (let i = 0; i + 8 <= bits.length; i += 8) bytes.push(parseInt(bits.slice(i, i + 8), 2));
  return Buffer.from(bytes);
}
function totp(secret) {
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30_000)));
  const h = createHmac('sha1', base32Decode(secret)).update(counter).digest();
  const o = h[h.length - 1] & 0x0f;
  return String((h.readUInt32BE(o) & 0x7fffffff) % 1_000_000).padStart(6, '0');
}
async function post(path, body, token) {
  const res = await fetch(`${api}/api/v1${path}`, { method: 'POST', body: JSON.stringify(body),
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) } });
  if (!res.ok) throw new Error(`${path} -> ${res.status} ${await res.text()}`);
  return res.status === 204 ? undefined : res.json();
}

const password = `E2e-${randomBytes(12).toString('base64url')}-Aa1!`;
const step = await post('/auth/login', { identifier: username, password: temporary });
const enrol = await post('/auth/mfa/totp/enroll', { mfaToken: step.mfaToken });
const tokens = await post('/auth/mfa/totp/activate', { mfaToken: step.mfaToken, code: totp(enrol.secret) });
await post('/auth/password/change', { currentPassword: temporary, newPassword: password }, tokens.accessToken);

const values = { E2E_ADMIN_USERNAME: username, E2E_ADMIN_PASSWORD: password, E2E_ADMIN_TOTP_SECRET: enrol.secret };
if (process.env.GITHUB_ENV) {
  console.log(`::add-mask::${password}`);
  console.log(`::add-mask::${enrol.secret}`);
  appendFileSync(process.env.GITHUB_ENV, Object.entries(values).map(([k, v]) => `${k}=${v}\n`).join(''));
  console.log(`Bootstrap administrator ${username} enrolled; E2E_ADMIN_* exported.`);
} else {
  // Local use: print for the caller to export (do not paste these anywhere shared).
  for (const [k, v] of Object.entries(values)) console.log(`${k}=${v}`);
}
