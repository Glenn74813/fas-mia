# 10 — Phase 1 Report: Foundation and Security

Date: 2026-10-22. Scope: MS §29 Phase 1 — backend and frontend foundation, PostgreSQL connectivity,
migrations, security baseline, JWT authentication, user and permission management, API documentation,
automated tests and deployment configuration.

## 1. What was delivered

| Area | Files (main) |
|---|---|
| Backend (66 Java classes) | `backend/src/main/java/com/miaa/cas/{common,config,security,identity,audit,notifications}` |
| Migrations | `backend/src/main/resources/db/migration/V1`–`V6` |
| Backend tests (15 classes) | `backend/src/test/java/com/miaa/cas/**` |
| Frontend (44 TS/TSX files) | `frontend/src/{api,auth,components,layouts,features,utils,app}`, `frontend/e2e` |
| Deployment | `backend/Dockerfile`, `frontend/Dockerfile`, `frontend/nginx.conf`, `docker-compose.yml`, `deploy/nginx/gateway.conf`, `deploy/db/*.sql`, `.env.example`, `.github/workflows/ci.yml` |
| Documentation | `docs/01`–`10`, `docs/adr/ADR-001`–`005` |

## 2. Database migrations

| Version | Purpose |
|---|---|
| V1 | Organizations, offices, departments, users, roles, permissions, role/user links, SoD rules, office assignments, access reviews |
| V2 | User sessions, refresh tokens (hashes only), MFA methods (encrypted), password-reset tokens |
| V3 | Append-only, hash-chained `audit_events` and `security_events` (triggers), notification outbox |
| V4 | Permission catalogue, 12 proposed roles, SoD rules, MIAA organization row |
| V5 | Binds a pending authenticator secret to its sign-in challenge |
| V6 | Event ids assigned only by the chain trigger (no double sequence draw) |

## 3. APIs implemented (`/api/v1`, documented at `/v3/api-docs`)

* **auth**: `GET csrf`, `POST login`, `POST mfa/totp/enroll`, `POST mfa/totp/activate`, `POST mfa/verify`,
  `POST refresh`, `POST logout`, `GET me`, `POST password/change`, `POST password/forgot`, `POST password/reset`
* **users**: list/search/sort/page, get, create, update, activate, deactivate, unlock, set roles,
  revoke sessions, reset MFA, issue temporary password
* **roles / permissions**: list, get, create, update, set permissions, permission catalogue, SoD rules
* **audit**: search audit events, search security events, verify both hash chains
* **actuator**: health (liveness/readiness), info, prometheus

## 4. Tests executed and actual results

| Suite | Command | Result |
|---|---|---|
| Backend unit + integration (PostgreSQL 18.6, local disposable DB via `CAS_TEST_JDBC_URL`) | `./mvnw test` | **63 run, 0 failures, 0 errors** |
| Frontend typecheck / lint | `npm run typecheck`, `npm run lint` | pass |
| Frontend unit (Vitest + RTL) | `npm test` | **17 passed** |
| Frontend production build | `npm run build` | pass (413 kB JS, 128 kB gzip) |
| Dependency audit (npm) | `npm audit` | 0 vulnerabilities (after pinning Vite 8.3.4 to clear a dev-server advisory in 8.3.1) |
| Browser e2e (Playwright, Chromium) against the live dev stack | `npm run e2e` | **1 passed** — admin MFA sign-in, SoD block, user creation, new-user MFA enrolment, forced password change, permission-restricted page, session resume on reload, sign-out, re-sign-in |
| Manual check in the app's browser pane | — | Full bootstrap-admin onboarding, user list, user creation, audit trail |

Defects found by the live and e2e runs and fixed in this phase (each now covered by a test):
1. Concurrent MFA enrolment requests (React StrictMode double effect) hit a unique-index violation.
2. Serialised duplicate enrolments could show a secret that had already been replaced — pending secrets are now bound to the challenge.
3. `GET /users` without a search term returned 500 (`lower(bytea)` from a null bind) — replaced with a Specification; LIKE wildcards now matched literally.
4. A page reload racing a token rotation revoked the session — 10 s single-use grace window (C-20).
5. Spring Data merged instead of persisting new entities, and sign-in bookkeeping bumped the optimistic-lock version.

## 5. Not verified in this environment (no Docker on the build host)

* Docker image builds, Docker Compose stack, Testcontainers path of the integration tests.
* CI workflow (never executed on a CI runner); gitleaks and Trivy scans not run.
* Prod profile with mounted keys and the separate management port; TLS gateway config.
* `deploy/db/roles.sql` / `post-migrate-grants.sql` (runtime role separation) not executed.

## 6. Known limitations and open dependencies

* MFA: TOTP only. Email/SMS OTP blocked on an approved provider (C-16); WebAuthn planned for Phase 2.
* No production e-mail sender: password-reset mails stay `PENDING` in the outbox outside the `dev` profile (C-16).
* Role grants are applied directly by `USER_ADMINISTER` (audited, SoD-checked); maker-checker for financial access grants waits for the Phase 2 workflow engine (C-19).
* Seeded roles/permissions and SoD rules are proposals pending MIAA sign-off (C-08).
* Rate limiting is per instance (ADR-004); the per-account lockout is cluster-wide.
* Audit trail is tamper-evident, not tamper-proof: a DB superuser can disable triggers; WORM export of sealed segments is Phase 2 (C-05). Audit inserts are serialised by an advisory lock.
* Organization/office/department tables exist without an API yet (Phase 2).
* Blocked-password list is a small seed; replace with an approved breached-password corpus.
* The TOR document itself was not supplied; RTM rows cite the master specification (C-00).
* Flyway 11.7 officially supports PostgreSQL up to 17; target PostgreSQL 17 in all environments.

## 7. Security implications

Deny-by-default filter chain plus per-method permissions; stateless bearer API with CSRF protection only
on the two cookie endpoints; Argon2id password hashing; RS256 tokens with minimal claims and live
permission resolution (revocation is immediate); refresh rotation with reuse detection; TOTP secrets
AES-256-GCM encrypted and bound to the user; generic authentication errors; lockout; security headers
and CSP; append-only hash-chained audit trail with verification; system administrators structurally
cannot hold financial permissions. Residual risks are listed in §6 and in `docs/05-security-design.md` §6.

## 8. How to run

See [09-dev-setup.md](09-dev-setup.md).

## 9. Criteria to approve Phase 2

1. CI workflow green on the MIAA CI runner (including Testcontainers, image builds and scans).
2. `docker compose up` verified on a developer workstation from the README alone.
3. MISD security review of `docs/05-security-design.md` and decisions on C-16, C-19, C-20.
4. Accounting Division sign-off of the proposed role–permission matrix and SoD rules (C-08), or a marked-up version.
5. Decision on the accounting framework and delivery of the approved Chart of Accounts (C-07), which Phase 2 loads.
6. Signed TOR with annexes supplied for RTM re-mapping (C-00).
