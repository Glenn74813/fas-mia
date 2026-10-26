import { totp } from './totp.mjs';

/**
 * One signed-in CAS user: TOTP sign-in, bearer token, refresh-token cookie and CSRF-protected refresh before the
 * access token expires. Every call is timed on the client and, when the server sends Server-Timing, on the server.
 */
export class Session {
  constructor(api, user, recorder) {
    this.api = api.replace(/\/$/, '') + '/api/v1';
    this.user = user;
    this.recorder = recorder;
    this.cookies = new Map();
    this.token = null;
    this.expiresAt = 0;
  }

  async login() {
    for (let attempt = 0; ; attempt++) {
      const step = await this.raw('POST', '/auth/login', { identifier: this.user.username, password: this.user.password });
      if (step.status === 429 && attempt < 10) {
        await sleep(6000);
        continue;
      }
      if (step.status !== 200) throw new Error(`login ${this.user.username} -> ${step.status} ${step.text.slice(0, 200)}`);
      // A development server may let configured users skip the code (cas.mfa.dev-bypass-users).
      const tokens = step.json.status === 'DEV_MFA_BYPASS'
        ? await this.raw('POST', '/auth/mfa/dev-bypass', { mfaToken: step.json.mfaToken })
        : await this.raw('POST', '/auth/mfa/verify', { mfaToken: step.json.mfaToken, code: totp(this.user.secret) });
      if (tokens.status !== 200) throw new Error(`mfa ${this.user.username} -> ${tokens.status} ${tokens.text.slice(0, 200)}`);
      this.accept(tokens.json);
      return this;
    }
  }

  accept(t) {
    this.token = t.accessToken;
    this.expiresAt = Date.now() + t.expiresIn * 1000;
  }

  async refresh() {
    const csrf = await this.raw('GET', '/auth/csrf');
    const r = await this.raw('POST', '/auth/refresh', undefined, { [csrf.json.headerName]: csrf.json.token });
    if (r.status !== 200) throw new Error(`refresh ${this.user.username} -> ${r.status}`);
    this.accept(r.json);
  }

  /**
   * A timed API call recorded under {@code op}. {@code kind} groups it against a target (entry, read, report, batch).
   * Statuses in {@code expect} count as success; anything else is an error (and is returned, not thrown).
   */
  async call(op, kind, method, path, body, { expect = [200, 201], headers = {}, multipart } = {}) {
    if (this.token && Date.now() > this.expiresAt - 60000) await this.refresh();
    const res = await this.raw(method, path, body, { ...headers, Authorization: `Bearer ${this.token}` }, multipart);
    const ok = expect.includes(res.status);
    this.recorder?.add({ op, kind, ms: res.ms, serverMs: res.serverMs, status: res.status, ok, user: this.user.username });
    return { ...res, ok };
  }

  /** Like {@link call}, but throws on an unexpected status; for setup steps whose failure ends the run. */
  async must(op, kind, method, path, body, opts) {
    const r = await this.call(op, kind, method, path, body, opts);
    if (!r.ok) throw new Error(`${op} ${method} ${path} -> ${r.status} ${r.text.slice(0, 300)}`);
    return r.json;
  }

  async raw(method, path, body, headers = {}, multipart) {
    const h = { Accept: 'application/json', ...headers };
    if (this.cookies.size) h.Cookie = [...this.cookies].map(([k, v]) => `${k}=${v}`).join('; ');
    let payload;
    if (multipart) {
      const form = new FormData();
      for (const [k, v] of Object.entries(multipart)) {
        if (v && typeof v === 'object' && 'content' in v) form.append(k, new Blob([v.content], { type: v.type }), v.name);
        else form.append(k, String(v));
      }
      payload = form;
    } else if (body !== undefined) {
      h['Content-Type'] = 'application/json';
      payload = JSON.stringify(body);
    }
    const start = performance.now();
    const res = await fetch(this.api + path, { method, headers: h, body: payload });
    const text = await res.text();
    const ms = performance.now() - start;
    for (const c of res.headers.getSetCookie()) {
      const [pair] = c.split(';');
      const i = pair.indexOf('=');
      const name = pair.slice(0, i);
      const value = pair.slice(i + 1);
      if (value === '' || /max-age=0/i.test(c)) this.cookies.delete(name);
      else this.cookies.set(name, value);
    }
    const st = /app;dur=([\d.]+)/.exec(res.headers.get('server-timing') ?? '');
    let json;
    try {
      json = text ? JSON.parse(text) : undefined;
    } catch {
      json = undefined;
    }
    return { status: res.status, json, text, ms, serverMs: st ? Number(st[1]) : null, headers: res.headers };
  }
}

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Think time between a virtual user's actions: uniformly between min and max milliseconds. */
export const think = (min, max) => sleep(min + Math.random() * (max - min));
