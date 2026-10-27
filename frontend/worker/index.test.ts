import { describe, expect, it, vi } from 'vitest';
import worker from './index';

function request(path: string, tlsVersion?: string): Request {
  const r = new Request(`https://fas-mia.example${path}`);
  if (tlsVersion !== undefined) Object.defineProperty(r, 'cf', { value: { tlsVersion } });
  return r;
}

const env = () => ({ ASSETS: { fetch: vi.fn(async () => new Response('<html>app</html>', { headers: { 'Content-Type': 'text/html' } })) } });

describe('worker TLS policy', () => {
  it('refuses connections below TLS 1.3 with an explanation, before serving anything', async () => {
    for (const old of ['TLSv1.2', 'TLSv1.1', 'TLSv1']) {
      const e = env();
      const res = await worker.fetch(request('/', old), e);
      expect(res.status).toBe(426);
      expect(await res.text()).toContain('TLS 1.3');
      expect(res.headers.get('Strict-Transport-Security')).toContain('max-age');
      expect(e.ASSETS.fetch).not.toHaveBeenCalled();
    }
    const api = env();
    expect((await worker.fetch(request('/api/v1/auth/csrf', 'TLSv1.2'), { ...api, BACKEND_ORIGIN: 'https://backend.example' })).status).toBe(426);
  });

  it('serves TLS 1.3 connections, and local development without TLS', async () => {
    expect((await worker.fetch(request('/', 'TLSv1.3'), env())).status).toBe(200);
    expect((await worker.fetch(request('/'), env())).status).toBe(200);
  });
});
