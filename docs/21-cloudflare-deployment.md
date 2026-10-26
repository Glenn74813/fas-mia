# 21 — Public demo on Cloudflare (Worker + tunnel)

Date: 2026-10-26. This runs CAS as a **public demonstration**:
* the web app on Cloudflare Workers (`https://fas-mia.pg1992-gp.workers.dev`);
* the backend and PostgreSQL on the demo PC, reached through a Cloudflare Tunnel.

It is not the production design: production follows [07](07-infrastructure-dr.md) and [19](19-deployment-and-rollback.md),
and its hosting is C-04. The data is development and test data, not MIAA's books.

```
Browser ──HTTPS──► Cloudflare Worker "fas-mia"
                     ├─ /, /assets/*, any page ─► built SPA (frontend/dist) + security headers
                     └─ /api/*  ── adds X-CAS-Proxy-Secret and X-CAS-Client-IP ──► BACKEND_ORIGIN
                                                                                   (Cloudflare Tunnel URL)
                                                                                        │
                                       demo PC: cloudflared ──► 127.0.0.1:8080 CAS backend (prod mode)
                                                                         └──► PostgreSQL miacas_db (local)
```

## What keeps it safe

| Risk | Control |
|---|---|
| Someone finds the tunnel URL and calls the API directly | The backend refuses every `/api` request without the shared secret (`ProxyGatewayFilter`, `cas.proxy.shared-secret`). Only the Worker knows it, and it is a Cloudflare *secret* |
| A browser forges the secret or its own IP | The Worker deletes `X-CAS-*` and `X-Forwarded-*` headers from incoming requests before adding its own |
| Signing in without the authenticator | Demo mode is the `prod` profile: the dev MFA bypass is off, so every account needs its 6-digit code |
| Machines on your network reaching the backend | `run-backend-demo.sh` binds the API and management ports to `127.0.0.1`; only the tunnel connects |
| API documentation exposed | Off in demo mode (`CAS_OPENAPI_ENABLED=false`) |
| Rate limits and audit trail seeing only the tunnel | The backend takes the real visitor address from the Worker, and only when the secret matches |
| Missing browser security headers | The Worker adds CSP, HSTS, `X-Frame-Options: DENY`, nosniff, a referrer policy and a permissions policy, as NGINX did |
| Cookies across sites | One origin (the Worker) for pages and API, so the `SameSite=Strict` refresh cookie and CSRF protection work unchanged |

Tested locally with `wrangler dev`, the production-mode script on spare ports, and `ProxyGatewayIT`. Verified:
* pages and deep links are served with the headers;
* `/api` is proxied;
* direct calls without the secret get 403, and calls through the Worker succeed;
* `admin.dev` must give an MFA code.

## 1. Cloudflare Worker settings (once)

Worker **fas-mia** → **Settings** → **Build**:

| Setting | Value |
|---|---|
| Root directory | `frontend` |
| Build command | `npm ci && npm run build` |
| Deploy command | `npx wrangler deploy --keep-vars` |

`--keep-vars` matters: without it, every deploy clears the variables set in the dashboard.

Worker → **Settings** → **Variables and Secrets**:

| Name | Type | Value |
|---|---|---|
| `PROXY_SHARED_SECRET` | **Secret** | A long random value, e.g. from `openssl rand -hex 32`. The same value goes into `.env` as `CAS_PROXY_SHARED_SECRET` |
| `BACKEND_ORIGIN` | Text | The tunnel URL from step 4, e.g. `https://abc-def.trycloudflare.com` (no trailing slash) |

Push the repository (`frontend/wrangler.jsonc`, `frontend/worker/`) to trigger a build.

## 2. Backend settings (`.env` on the demo PC)

```
CAS_PROXY_SHARED_SECRET=<the same value as the Worker secret>
CAS_FRONTEND_BASE_URL=https://fas-mia.pg1992-gp.workers.dev
```

The database settings stay as they are (`miacas_db` on the local PostgreSQL).

## 3. Accounts before going public

* Sign-in now needs each account's authenticator code. For `admin.dev`, add its TOTP secret (`CAS_DEV_ADMIN_TOTP_SECRET` in `.env`) to your authenticator app with "enter a setup key". Then change its password from `admin123` (**Change password** on the dashboard).
* The database holds many e2e test users. Their passwords were random and are not kept, so they cannot be used. Deactivate any you don't need under **Users**, including `ui.review.*`.

## 4. Run it

1. Stop the development backend (`scripts/run-backend-dev.sh`); both use port 8080.
2. Start the backend in demo mode:
   ```bash
   bash scripts/run-backend-demo.sh
   ```
3. Install `cloudflared` from Cloudflare's official package repository or GitHub releases, then start a tunnel to the backend:
   ```bash
   cloudflared tunnel --url http://127.0.0.1:8080
   ```
   It prints an address like `https://abc-def.trycloudflare.com`. Put it in the Worker's `BACKEND_ORIGIN`; saving a variable redeploys the Worker.
4. Open `https://fas-mia.pg1992-gp.workers.dev` and sign in.

**Quick-tunnel limits:** the address changes every time `cloudflared` restarts (update `BACKEND_ORIGIN` each time), it has no uptime guarantee, and it stops when the PC sleeps or goes offline. That is fine for a demo.

**Lasting setup:** add a domain to your Cloudflare account. Then create a named tunnel with a fixed hostname, for example `api.yourdomain`:
* `cloudflared tunnel login`, `cloudflared tunnel create cas`, `cloudflared tunnel route dns cas api.yourdomain`, then run the tunnel as a service;
* optionally, protect that hostname with Cloudflare Access (a service token that the Worker also sends);
* set `BACKEND_ORIGIN=https://api.yourdomain` once.

A DuckDNS name cannot be used for this, because its DNS is not on Cloudflare.

## 5. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Blank page; the console shows `/src/main.tsx` MIME error | The unbuilt source was deployed | Build settings in step 1 |
| Sign-in shows "The CAS backend is not configured" (503) | `BACKEND_ORIGIN` empty | Set it (step 4.3) |
| "The CAS backend could not be reached" (502) | The tunnel or backend is down, or the tunnel URL changed | Restart `run-backend-demo.sh` and `cloudflared`; update `BACKEND_ORIGIN` |
| "This API is only reachable through the CAS web application" (403) | The secrets differ | Make `PROXY_SHARED_SECRET` (Worker) and `CAS_PROXY_SHARED_SECRET` (`.env`) identical; restart the backend |
| Signed out on every reload | Cookies not kept | Use the `workers.dev` address over HTTPS (secure cookies); check `CAS_FRONTEND_BASE_URL` |
| `run-backend-demo.sh` stops with a message | A required `.env` value is missing, or the secret is shorter than 32 characters | Add it as in step 2 |
