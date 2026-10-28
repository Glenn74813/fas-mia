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

let lastAdminWindow = -1;

/**
 * Signs the enrolled test administrator in through the API and returns an access token. A code is accepted once per
 * 30-second window, so a second sign-in in the same window (the next spec, or a restarted worker) waits for the next.
 */
export async function adminToken(username: string, password: string, secret: string): Promise<string> {
  const ctx = await request.newContext();
  try {
    for (let attempt = 0; ; attempt++) {
      if (Math.floor(Date.now() / 30_000) === lastAdminWindow) await nextTotpWindow();
      const step = await json<{ mfaToken: string }>(ctx, '/auth/login', { identifier: username, password });
      lastAdminWindow = Math.floor(Date.now() / 30_000);
      try {
        const tokens = await json<{ accessToken: string }>(ctx, '/auth/mfa/verify', { mfaToken: step.mfaToken, code: totp(secret) });
        return tokens.accessToken;
      } catch (e) {
        if (attempt > 0 || !String(e).includes('auth-mfa-invalid')) throw e;
      }
    }
  } finally {
    await ctx.dispose();
  }
}

/** Signs a member in through the API (once per 30-second window per member) and returns an access token. */
export async function tokenFor(m: Member): Promise<string> {
  const ctx = await request.newContext();
  try {
    const step = await json<{ mfaToken: string }>(ctx, '/auth/login', { identifier: m.username, password: m.password });
    return (await json<{ accessToken: string }>(ctx, '/auth/mfa/verify', { mfaToken: step.mfaToken, code: totp(m.secret) })).accessToken;
  } finally {
    await ctx.dispose();
  }
}

/** GET and POST as the holder of {@code token}, failing loudly on any error status. */
export async function apiAs(token: string) {
  const ctx = await request.newContext({ extraHTTPHeaders: { Authorization: `Bearer ${token}` } });
  const check = async (res: Awaited<ReturnType<APIRequestContext['get']>>, what: string) => {
    if (!res.ok()) throw new Error(`${what} -> ${res.status()} ${await res.text()}`);
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    return (res.status() === 204 ? undefined : await res.json()) as any;
  };
  return {
    get: async (path: string) => check(await ctx.get(`${API}/api/v1${path}`), `GET ${path}`),
    post: async (path: string, body: unknown) => check(await ctx.post(`${API}/api/v1${path}`, { data: body }), `POST ${path}`),
    dispose: () => ctx.dispose(),
  };
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
