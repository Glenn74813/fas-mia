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
month. It then moves the setup onto COA's **Revised Chart of Accounts for Government Corporations (2019)**
(`seed/coa-rca-gc-2019.csv`; `DevOfficialChart`): account mappings, tax codes, bank accounts, asset categories,
stock items and similar settings follow `seed/plc-to-rca-gc-2019.csv`, the `RCA-` statement templates replace the
`PLC-` ones, and the placeholder accounts are deactivated. Their posted history stays and is reported under the
official statement lines. One changeover entry (source `SL_CHART_CHANGEOVER`, attributed to the service user
`system.conversion`, which cannot sign in) moves the balance-sheet balances onto the official accounts per cost centre
and sub-ledger account, so the payables and receivables control accounts reconcile; income and expense stay where
they were posted. The entry is written outside the voucher approval workflow, which the audit trail records. Integration tests keep using the placeholder chart. Never enable
`cas.accounting.allow-placeholder-accounts` outside development and testing.

The public demo (`scripts/run-backend-demo.sh`) runs budget control in WARN mode because its approved budget
was entered on the placeholder accounts; production keeps BLOCK.

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

Browser end-to-end tests run on their own stack and database, so the users and documents every run creates never
reach the development or demo database. Set `CAS_E2E_DB_USER` and `CAS_E2E_DB_PASSWORD` in `.env` (the
`scripts/dev-db.sh` cluster login), build the backend jar, then:

```bash
scripts/e2e.sh start
```

```bash
scripts/e2e.sh test e2e/journal-voucher.spec.ts
```

`start` creates the `cas_e2e` database, runs the backend on port 8092 (dev profile, MFA bypass off) and the frontend
on 5175, and onboards the test administrator once (credentials in `.devdata/e2e-admin.env`). `test` runs Playwright
with one worker (the specs share the administrator's authenticator, and a code works once per 30-second window).
`stop` stops the stack; `reset` also drops the database. The backend runs from a copy of the jar
(`.devdata/cas-backend-e2e.jar`, as `run-backend-dev.sh` and `run-backend-demo.sh` do): Spring Boot loads classes from
the jar file as they are first used, so rebuilding `backend/target` under a running backend makes later requests die
without a response. The frontend is a production build served by `vite preview` on 5175 (no hot reload can
reload a page mid-test). Both are built or copied at `start`, so after changing code, `stop` and `start` again to test it. Playwright's browsers: `cd frontend && npx playwright install chromium`.

## Demo users

`scripts/demo-users.py` prepares the demo database's users through the user-administration API: it deactivates every
account except the administrator, the demo super administrator and the conversion service user, and creates two
onboarded users per role (`demo.<role>.1` and `.2`; Demo Super Administrator excepted). They share the demo super
administrator's password (`CAS_DEMO_SUPERADMIN_PASSWORD`); each has its own authenticator. Passwords and authenticator secrets go to `.devdata/demo-users.csv` (gitignored, mode 600); add a secret to an authenticator app to
sign in. Concessionaire accounts still need linking to a customer under **Receivables → Portal access**.

```bash
(set -a; . ./.env; set +a; python3 scripts/demo-users.py http://127.0.0.1:8091 .devdata/demo-users.csv)
```

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
