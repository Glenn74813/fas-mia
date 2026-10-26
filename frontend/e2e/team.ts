import { request, type APIRequestContext } from '@playwright/test';
import { totp } from './totp';

export interface Member {
  username: string;
  password: string;
  secret: string;
}

const API = process.env.E2E_API_URL ?? 'http://localhost:8080';

async function json<T>(ctx: APIRequestContext, path: string, body: unknown, token?: string): Promise<T> {
  const res = await ctx.post(`${API}/api/v1${path}`, {
    data: body,
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  if (!res.ok()) throw new Error(`${path} -> ${res.status()} ${await res.text()}`);
  return (res.status() === 204 ? undefined : await res.json()) as T;
}

/** Signs the enrolled test administrator in through the API and returns an access token. */
export async function adminToken(username: string, password: string, secret: string): Promise<string> {
  const ctx = await request.newContext();
  const step = await json<{ mfaToken: string }>(ctx, '/auth/login', { identifier: username, password });
  const tokens = await json<{ accessToken: string }>(ctx, '/auth/mfa/verify', { mfaToken: step.mfaToken, code: totp(secret) });
  await ctx.dispose();
  return tokens.accessToken;
}

/** Creates a user with the given roles and completes their MFA enrolment and password change. */
export async function onboard(admin: string, prefix: string, roles: string[], userType = 'INTERNAL'): Promise<Member> {
  const ctx = await request.newContext();
  const username = `${prefix}.${Date.now().toString(36)}`;
  const temporary = `Temp-${crypto.randomUUID().slice(0, 8)}-Aa1!`;
  const password = `Final-${crypto.randomUUID().slice(0, 8)}-Aa1!`;
  await json(ctx, '/users', { username, email: `${username}@e2e.miaa.local`, displayName: `E2E ${prefix}`,
    userType, temporaryPassword: temporary, roleCodes: roles }, admin);
  const step = await json<{ mfaToken: string }>(ctx, '/auth/login', { identifier: username, password: temporary });
  const enrol = await json<{ secret: string }>(ctx, '/auth/mfa/totp/enroll', { mfaToken: step.mfaToken });
  const tokens = await json<{ accessToken: string }>(ctx, '/auth/mfa/totp/activate', { mfaToken: step.mfaToken, code: totp(enrol.secret) });
  await json(ctx, '/auth/password/change', { currentPassword: temporary, newPassword: password }, tokens.accessToken);
  await ctx.dispose();
  return { username, password, secret: enrol.secret };
}

/** A TOTP step can be used once per user; wait for the next 30-second window. */
export async function nextTotpWindow(): Promise<void> {
  await new Promise((r) => setTimeout(r, 30_000 - (Date.now() % 30_000) + 500));
}
