import type { TokenResponse } from './types';

/** Problem document returned by the backend (RFC 9457 + CAS extensions). */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly correlationId?: string;
  readonly body: Record<string, unknown>;

  constructor(status: number, body: Record<string, unknown>) {
    super(typeof body.detail === 'string' ? body.detail : `Request failed (${status})`);
    this.status = status;
    this.code = typeof body.code === 'string' ? body.code : 'UNKNOWN';
    this.correlationId = typeof body.correlationId === 'string' ? body.correlationId : undefined;
    this.body = body;
  }

  get fieldErrors(): { field: string; message: string }[] {
    return Array.isArray(this.body.errors) ? (this.body.errors as { field: string; message: string }[]) : [];
  }

  get violations(): string[] {
    return Array.isArray(this.body.violations) ? (this.body.violations as string[]) : [];
  }
}

const BASE = '/api/v1';

// The access token lives only in memory. The refresh token is an HttpOnly cookie the SPA never sees.
let accessToken: string | null = null;
let sessionEndedListener: (() => void) | null = null;
let refreshInFlight: Promise<TokenResponse | null> | null = null;

export const session = {
  setAccessToken(token: string | null) {
    accessToken = token;
  },
  hasAccessToken() {
    return accessToken !== null;
  },
  onSessionEnded(listener: (() => void) | null) {
    sessionEndedListener = listener;
  },
};

interface RequestOptions {
  body?: unknown;
  /** Attach the bearer token and transparently refresh once on 401. */
  auth?: boolean;
  headers?: Record<string, string>;
}

async function toError(res: Response): Promise<ApiError> {
  let body: Record<string, unknown>;
  try {
    body = (await res.json()) as Record<string, unknown>;
  } catch {
    body = { detail: `Request failed (${res.status})` };
  }
  return new ApiError(res.status, body);
}

async function send(method: string, path: string, opts: RequestOptions): Promise<Response> {
  const headers: Record<string, string> = { Accept: 'application/json', ...opts.headers };
  const isForm = opts.body instanceof FormData;
  if (opts.body !== undefined && !isForm) headers['Content-Type'] = 'application/json';
  if (opts.auth !== false && accessToken) headers.Authorization = `Bearer ${accessToken}`;
  return fetch(BASE + path, {
    method,
    headers,
    body: opts.body === undefined ? undefined : isForm ? (opts.body as FormData) : JSON.stringify(opts.body),
    credentials: 'same-origin',
  });
}

/** Authenticated binary download (with the same 401 refresh) handed to the browser as a file. */
export async function downloadFile(path: string, fallbackName: string): Promise<void> {
  let res = await send('GET', path, {});
  if (res.status === 401 && (await refreshSession())) res = await send('GET', path, {});
  if (!res.ok) throw await toError(res);
  const blob = await res.blob();
  const disposition = res.headers.get('Content-Disposition') ?? '';
  const match = /filename\*=UTF-8''([^;]+)/i.exec(disposition) ?? /filename="?([^";]+)"?/i.exec(disposition);
  const name = match ? decodeURIComponent(match[1]!) : fallbackName;
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  a.click();
  URL.revokeObjectURL(url);
}

export async function apiRequest<T>(method: string, path: string, opts: RequestOptions = {}): Promise<T> {
  let res = await send(method, path, opts);
  if (res.status === 401 && opts.auth !== false) {
    const refreshed = await refreshSession();
    if (refreshed) {
      res = await send(method, path, opts);
    } else {
      sessionEndedListener?.();
    }
  }
  if (!res.ok) throw await toError(res);
  if (res.status === 204 || res.status === 202) return undefined as T;
  return (await res.json()) as T;
}

async function csrfToken(): Promise<{ headerName: string; token: string }> {
  const res = await fetch(`${BASE}/auth/csrf`, { credentials: 'same-origin' });
  if (!res.ok) throw await toError(res);
  return (await res.json()) as { headerName: string; token: string };
}

/** Sends a CSRF-protected request to a cookie-authenticated auth endpoint (/refresh, /logout). */
async function cookieAuthPost(path: string): Promise<Response> {
  const csrf = await csrfToken();
  return fetch(BASE + path, {
    method: 'POST',
    headers: { Accept: 'application/json', [csrf.headerName]: csrf.token },
    credentials: 'same-origin',
  });
}

async function doRefresh(): Promise<TokenResponse | null> {
  try {
    const res = await cookieAuthPost('/auth/refresh');
    if (!res.ok) {
      accessToken = null;
      return null;
    }
    const body = (await res.json()) as TokenResponse;
    accessToken = body.accessToken;
    return body;
  } catch {
    return null;
  }
}

/**
 * Exchanges the refresh cookie for a new access token. Refreshes are single-flight within a tab and
 * serialised across tabs with the Web Locks API, because the backend treats reuse of a rotated
 * refresh token as theft and revokes the session.
 */
export function refreshSession(): Promise<TokenResponse | null> {
  if (!refreshInFlight) {
    const run = () => doRefresh();
    const locked = typeof navigator !== 'undefined' && navigator.locks
      ? navigator.locks.request('cas-token-refresh', run)
      : run();
    refreshInFlight = locked.finally(() => {
      refreshInFlight = null;
    });
  }
  return refreshInFlight;
}

export async function logoutRequest(): Promise<void> {
  try {
    await cookieAuthPost('/auth/logout');
  } finally {
    accessToken = null;
  }
}

export const api = {
  get: <T>(path: string, opts?: RequestOptions) => apiRequest<T>('GET', path, opts),
  post: <T>(path: string, body?: unknown, opts?: RequestOptions) => apiRequest<T>('POST', path, { ...opts, body }),
  put: <T>(path: string, body?: unknown, opts?: RequestOptions) => apiRequest<T>('PUT', path, { ...opts, body }),
  delete: <T>(path: string, opts?: RequestOptions) => apiRequest<T>('DELETE', path, opts),
  uploadWith: <T>(path: string, file: File, fields: Record<string, string>) => {
    const form = new FormData();
    Object.entries(fields).forEach(([k, v]) => form.append(k, v));
    form.append('file', file);
    return apiRequest<T>('POST', path, { body: form });
  },
  upload: <T>(path: string, file: File) => {
    const form = new FormData();
    form.append('file', file);
    return apiRequest<T>('POST', path, { body: form });
  },
};
