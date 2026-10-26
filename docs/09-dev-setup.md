# 09 — Development Environment Setup

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 17 (Temurin recommended) | Build uses the Maven wrapper (`backend/mvnw`, Maven 3.9.16) |
| Node.js | 24 LTS | `frontend/package-lock.json` pins exact versions |
| PostgreSQL | 17 | Supported by Flyway 11.7. PostgreSQL 18 works but Flyway warns it is untested |
| Docker + Compose | current | Optional for the full stack; required for Testcontainers-based tests |
| OpenSSL | any | Generates development keys |

## Option A — full stack with Docker Compose

```bash
./scripts/dev-secrets.sh
```

Edit `.env` and set `CAS_BOOTSTRAP_ADMIN_USERNAME`, `CAS_BOOTSTRAP_ADMIN_EMAIL` and a policy-compliant
`CAS_BOOTSTRAP_ADMIN_PASSWORD`, then:

```bash
docker compose up --build
```

SPA: http://localhost:8088 · API: http://localhost:8080 · Swagger UI (dev): http://localhost:8080/swagger-ui.html.
Sign in as the bootstrap administrator, enrol an authenticator app, set a new password, then remove the
`CAS_BOOTSTRAP_ADMIN_*` values from `.env`.

## Option B — run backend and frontend directly

1. Create a database and user (PostgreSQL 17), or use the local development cluster script, which keeps a
   PostgreSQL 18 cluster on port 55432 in `.devdata/` (gitignored; it survives reboots) with the databases
   `cas` and `cas_test`, using `CAS_DB_USER` / `CAS_DB_PASSWORD` from `.env`:

```bash
createdb cas
```

```bash
scripts/dev-db.sh start
```

2. Generate secrets and point `.env` at your database (`CAS_DB_URL`, `CAS_DB_USER`, `CAS_DB_PASSWORD`):

```bash
./scripts/dev-secrets.sh
```

3. Build and start the backend:

```bash
cd backend && ./mvnw -DskipTests package && cd .. && ./scripts/run-backend-dev.sh
```

4. Start the SPA (proxies `/api` to port 8080):

```bash
cd frontend && npm ci && npm run dev
```

## Development data

With the `dev` profile and an empty database, the backend loads the **placeholder** chart of accounts
(`backend/src/main/resources/seed/placeholder-chart-of-accounts.csv`, `PLC-` codes, every account flagged
`placeholder`), placeholder cost centres, and the current fiscal year with periods opened up to the current
month. Never enable `cas.accounting.allow-placeholder-accounts` outside development and testing.

## Tests

Backend unit and integration tests (Testcontainers starts PostgreSQL):

```bash
cd backend && ./mvnw verify
```

Without Docker, point the integration tests at a disposable database (it is wiped with `flyway clean`):

```bash
CAS_TEST_JDBC_URL=jdbc:postgresql://localhost:5432/cas_test CAS_TEST_DB_USER=cas CAS_TEST_DB_PASSWORD=secret ./mvnw verify
```

Frontend:

```bash
cd frontend && npm run lint && npm run typecheck && npm test && npm run build
```

Browser end-to-end (needs the running stack and an enrolled test administrator):

```bash
cd frontend && npx playwright install chromium && E2E_ADMIN_USERNAME=... E2E_ADMIN_PASSWORD=... E2E_ADMIN_TOTP_SECRET=... npm run e2e -- e2e/journal-voucher.spec.ts
```

The specs share the administrator's authenticator, and a code works once per 30-second window, so run
spec files one at a time. `E2E_BASE_URL` (default `http://localhost:5173`) and `E2E_API_URL` (default
`http://localhost:8080`) point at the running stack.

## Diagnostics

* Health: `curl localhost:8080/actuator/health/readiness`
* Every response carries `X-Request-Id`; error bodies include it as `correlationId`. Search the logs for it.
* Metrics: `/actuator/prometheus` (internal management port 8081 under the `prod` profile).
* Audit chain check: `POST /api/v1/audit-events/verification` (needs `AUDIT_LOG_VIEW`).

## Development sign-in shortcut

On a developer workstation, `admin.dev` signs in with its password alone. `cas.mfa.dev-bypass-users` in
`application-dev.yml` lists the users who skip the authenticator code. The backend refuses to start with that
setting outside the dev profile, and every such sign-in is recorded as a security event (`MFA_BYPASSED_DEV`).
All other users still enrol and use an authenticator app. Remove the line to restore two-step verification for
`admin.dev`.
