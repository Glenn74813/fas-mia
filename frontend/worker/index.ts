/**
 * Cloudflare Worker for the CAS web application.
 *
 * - Serves the built SPA (./dist) with the security headers NGINX added in the container deployment.
 * - Forwards /api/* to the backend at BACKEND_ORIGIN (for example a Cloudflare Tunnel URL), so the browser keeps a
 *   single origin: the refresh cookie (SameSite=Strict, path /api/v1/auth), CSRF cookie and CSP all keep working.
 * - Proves itself to the backend with PROXY_SHARED_SECRET (backend setting cas.proxy.shared-secret) and passes the
 *   real client address, so the backend refuses direct calls and records who actually called it.
 *
 * - Refuses connections below TLS 1.3 (DICT Department Circular No. 007, s. 2020). Cloudflare cannot raise the minimum
 *   TLS version on a workers.dev address, so the Worker checks the version of every request itself.
 *
 * Configure in Cloudflare: variable BACKEND_ORIGIN, secret PROXY_SHARED_SECRET (see docs/21-cloudflare-deployment.md).
 */

interface Env {
  ASSETS: { fetch(request: Request): Promise<Response> };
  BACKEND_ORIGIN?: string;
  PROXY_SHARED_SECRET?: string;
}

const CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; "
  + "connect-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'";

const SECURITY_HEADERS: Record<string, string> = {
  'Content-Security-Policy': CSP,
  'X-Content-Type-Options': 'nosniff',
  'X-Frame-Options': 'DENY',
  'Referrer-Policy': 'no-referrer',
  'Permissions-Policy': 'camera=(), microphone=(), geolocation=(), payment=()',
  'Strict-Transport-Security': 'max-age=31536000; includeSubDomains',
};

/** Request headers the browser must never be able to set on the way to the backend. */
const EDGE_ONLY = ['x-cas-proxy-secret', 'x-cas-client-ip', 'x-forwarded-for', 'x-forwarded-proto', 'x-forwarded-host', 'x-real-ip'];

function problem(status: number, detail: string): Response {
  return new Response(JSON.stringify({ title: status === 503 ? 'Service Unavailable' : 'Bad Gateway', status, detail, code: 'BACKEND_UNAVAILABLE' }), {
    status, headers: { 'Content-Type': 'application/problem+json', 'Cache-Control': 'no-store' },
  });
}

async function proxy(request: Request, env: Env, url: URL): Promise<Response> {
  const origin = (env.BACKEND_ORIGIN ?? '').trim().replace(/\/+$/, '');
  if (!/^https?:\/\//.test(origin)) {
    return problem(503, 'The CAS backend is not configured for this deployment (BACKEND_ORIGIN).');
  }
  const headers = new Headers(request.headers);
  EDGE_ONLY.forEach((h) => headers.delete(h));
  headers.delete('host');
  const client = request.headers.get('CF-Connecting-IP');
  if (client) headers.set('X-CAS-Client-IP', client);
  headers.set('X-Forwarded-Proto', 'https');
  if (env.PROXY_SHARED_SECRET) headers.set('X-CAS-Proxy-Secret', env.PROXY_SHARED_SECRET);
  const ray = request.headers.get('CF-Ray');
  if (ray && !headers.has('X-Request-Id')) headers.set('X-Request-Id', ray);

  const hasBody = !['GET', 'HEAD'].includes(request.method);
  try {
    const upstream = await fetch(origin + url.pathname + url.search, {
      method: request.method,
      headers,
      body: hasBody ? request.body : undefined,
      redirect: 'manual',
    });
    return upstream;
  } catch {
    return problem(502, 'The CAS backend could not be reached. Try again shortly.');
  }
}

function withSecurityHeaders(response: Response, path: string): Response {
  const res = new Response(response.body, response);
  for (const [k, v] of Object.entries(SECURITY_HEADERS)) res.headers.set(k, v);
  // Hashed build files never change; the HTML shell must always be fresh.
  res.headers.set('Cache-Control', path.startsWith('/assets/') ? 'public, max-age=31536000, immutable' : 'no-store');
  return res;
}

/** The TLS version Cloudflare negotiated with the browser; undefined when there is none (local `wrangler dev` over HTTP). */
export function tlsVersion(request: Request): string | undefined {
  return (request as Request & { cf?: { tlsVersion?: string } }).cf?.tlsVersion || undefined;
}

function tooOldTls(): Response {
  const body = '<!doctype html><meta charset="utf-8"><title>Update your browser</title><body style="font-family:sans-serif;max-width:36rem;'
    + 'margin:4rem auto;line-height:1.5"><h1>Update your browser</h1><p>AVTECH CAS accepts only secure connections using TLS 1.3, '
    + 'as required for government systems. Please update your browser or use a current version of Chrome, Edge, Firefox or Safari.</p>';
  return new Response(body, { status: 426, headers: { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store', Upgrade: 'TLS/1.3',
    ...SECURITY_HEADERS } });
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const tls = tlsVersion(request);
    if (tls !== undefined && tls !== 'TLSv1.3') {
      return tooOldTls();
    }
    const url = new URL(request.url);
    if (url.pathname.startsWith('/api/')) {
      return proxy(request, env, url);
    }
    return withSecurityHeaders(await env.ASSETS.fetch(request), url.pathname);
  },
};
