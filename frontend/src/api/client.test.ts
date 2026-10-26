import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, api, refreshSession, session } from './client';

type Handler = (url: string, init?: RequestInit) => Response;

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function mockFetch(handler: Handler) {
  const fn = vi.fn((input: RequestInfo | URL, init?: RequestInit) => Promise.resolve(handler(String(input), init)));
  vi.stubGlobal('fetch', fn);
  return fn;
}

const tokens = (access: string) => ({
  accessToken: access, tokenType: 'Bearer', expiresIn: 600, sessionExpiresAt: '2026-10-22T12:00:00Z',
  user: { id: 'u1', username: 'j.cruz', displayName: 'J', email: 'j@x', userType: 'INTERNAL', roles: [], permissions: [],
    mustChangePassword: false, mfaEnrolled: true, lastLoginAt: null },
});

describe('api client', () => {
  beforeEach(() => session.setAccessToken(null));
  afterEach(() => vi.unstubAllGlobals());

  it('attaches the bearer token and parses JSON', async () => {
    session.setAccessToken('abc');
    const fetchMock = mockFetch(() => json(200, { ok: true }));
    await expect(api.get('/auth/me')).resolves.toEqual({ ok: true });
    const init = fetchMock.mock.calls[0]![1]!;
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer abc');
    expect(init.credentials).toBe('same-origin');
  });

  it('refreshes once on 401 with the CSRF header, then retries', async () => {
    session.setAccessToken('expired');
    let meCalls = 0;
    const fetchMock = mockFetch((url, init) => {
      if (url.endsWith('/auth/csrf')) return json(200, { headerName: 'X-XSRF-TOKEN', token: 'csrf-1' });
      if (url.endsWith('/auth/refresh')) {
        expect((init!.headers as Record<string, string>)['X-XSRF-TOKEN']).toBe('csrf-1');
        return json(200, tokens('fresh'));
      }
      meCalls += 1;
      const auth = (init!.headers as Record<string, string>).Authorization;
      return auth === 'Bearer fresh' ? json(200, { me: true }) : json(401, { code: 'UNAUTHENTICATED' });
    });
    await expect(api.get('/auth/me')).resolves.toEqual({ me: true });
    expect(meCalls).toBe(2);
    expect(fetchMock.mock.calls.filter((c) => String(c[0]).endsWith('/auth/refresh'))).toHaveLength(1);
  });

  it('signals session end when refresh fails', async () => {
    const ended = vi.fn();
    session.onSessionEnded(ended);
    mockFetch((url) => {
      if (url.endsWith('/auth/csrf')) return json(200, { headerName: 'X-XSRF-TOKEN', token: 't' });
      if (url.endsWith('/auth/refresh')) return json(401, { code: 'AUTH_SESSION_INVALID' });
      return json(401, { code: 'UNAUTHENTICATED', detail: 'Authentication is required.' });
    });
    await expect(api.get('/users')).rejects.toBeInstanceOf(ApiError);
    expect(ended).toHaveBeenCalledOnce();
    session.onSessionEnded(null);
  });

  it('deduplicates concurrent refreshes (refresh tokens are single-use)', async () => {
    const fetchMock = mockFetch((url) => url.endsWith('/auth/csrf')
      ? json(200, { headerName: 'X-XSRF-TOKEN', token: 't' }) : json(200, tokens('x')));
    const [a, b] = await Promise.all([refreshSession(), refreshSession()]);
    expect(a?.accessToken).toBe('x');
    expect(b?.accessToken).toBe('x');
    expect(fetchMock.mock.calls.filter((c) => String(c[0]).endsWith('/auth/refresh'))).toHaveLength(1);
  });

  it('exposes problem details on errors', async () => {
    mockFetch(() => json(400, { code: 'PASSWORD_POLICY_VIOLATION', detail: 'The password does not meet the policy.',
      violations: ['Too short'], correlationId: 'req-1' }));
    const err = await api.post('/auth/password/change', {}, { auth: false }).catch((e: unknown) => e) as ApiError;
    expect(err.code).toBe('PASSWORD_POLICY_VIOLATION');
    expect(err.violations).toEqual(['Too short']);
    expect(err.correlationId).toBe('req-1');
  });
});
