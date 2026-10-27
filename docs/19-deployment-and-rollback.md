# 19 — Production Deployment and Rollback Guide

Date: 2026-10-26. Status: **procedure defined, not yet exercised in a production-like environment.** No
staging or production environment exists yet, because hosting and residency are pending (C-04). What has been
exercised is noted per step. Topology, backups and DR are designed in
[07-infrastructure-dr.md](07-infrastructure-dr.md); the development stack is described in [09-dev-setup.md](09-dev-setup.md).

## 1. What is deployed

| Component | Artefact | Notes |
|---|---|---|
| Backend API | `backend/Dockerfile`: Temurin 17 JRE, non-root user `cas`, `SPRING_PROFILES_ACTIVE=prod` | Ports 8080 (API) and 8081 (management: `/actuator/health/liveness`, `/actuator/health/readiness`) |
| Frontend SPA | `frontend/Dockerfile`: static build served by unprivileged NGINX 1.29 | Port 8080; proxies `/api` to the backend (`frontend/nginx.conf`) |
| Gateway | `deploy/nginx/gateway.conf` | TLS termination and routing in front of both |
| Database | PostgreSQL 17 (Patroni HA and pgBackRest per 07) | Schema migrated by Flyway on application start |
| Database roles | `deploy/db/roles.sql`, `deploy/db/post-migrate-grants.sql` | `cas_owner` runs migrations; `cas_app` is the runtime role and may only INSERT/SELECT the audit tables |

CI (`.github/workflows/ci.yml`) builds both images, scans them with Trivy (HIGH/CRITICAL fail the build) and
runs the backend, frontend, e2e and security jobs. The workflow has not run yet, because there is no
repository. Signed images in an approved registry, and a deployment pipeline, are not built (C-04).

## 2. Configuration reference

All settings are environment variables read by `backend/src/main/resources/application.yml` and
`application-prod.yml`. Production uses the `prod` profile, set in the image. Values marked *secret* come from
the secrets manager, never from files in the repository or the image.

| Variable | Purpose | Prod | Default | Secret |
|---|---|---|---|---|
| `CAS_DB_URL` | JDBC URL of the database. A database on another host must use `sslmode=verify-full` (or `verify-ca`): production refuses to start otherwise | Required | `jdbc:postgresql://localhost:5432/cas` | |
| `CAS_TLS_PROTOCOLS` | Outbound TLS versions (SMTP, BIR EIS, JVM). Must match the JVM flag `-Djdk.tls.client.protocols`, which the image sets to TLSv1.3 | Optional | `TLSv1.3` | |
| `CAS_TLS_LEGACY_JUSTIFICATION` | Required reason when `CAS_TLS_PROTOCOLS` adds TLSv1.2 for a listed legacy integration (DICT DC No. 007, s. 2020) | Conditional | none | |
| `CAS_DB_USER` / `CAS_DB_PASSWORD` | Runtime role (`cas_app`) | Required | `cas_app` / empty | password |
| `CAS_FLYWAY_USER` / `CAS_FLYWAY_PASSWORD` | Schema owner (`cas_owner`) used only for migrations | Required | the runtime role | password |
| `CAS_DB_POOL_SIZE` | Connection pool size per instance | Optional | 20 | |
| `CAS_DATA_ENCRYPTION_KEY` | Base64 of 32 random bytes; AES-256-GCM key for MFA secrets, supplier bank accounts and stored files | Required | none | **yes, irreplaceable** |
| `CAS_JWT_PRIVATE_KEY_PATH` / `CAS_JWT_PUBLIC_KEY_PATH` | RS256 key pair for access tokens (PEM files) | Required (ephemeral keys are refused in prod) | none | private key |
| `CAS_JWT_PREVIOUS_PUBLIC_KEY_PATH` | Previous public key, accepted during a key rotation | Optional | none | |
| `CAS_JWT_ISSUER` | Token issuer | Optional | `https://cas.miaa.gov.ph` | |
| `CAS_FRONTEND_BASE_URL` | Base URL used in e-mailed links (password reset) | Required | `http://localhost:5173` | |
| `CAS_CORS_ALLOWED_ORIGINS` | Extra allowed origins (normally none: the SPA and API share an origin) | Optional | none | |
| `CAS_PORT` / `CAS_MANAGEMENT_PORT` | API and management ports | Optional | 8080 / 8081 | |
| `CAS_OPENAPI_ENABLED` | Serves `/v3/api-docs` and Swagger UI | Keep off | false in prod | |
| `CAS_API_REQUESTS_PER_MINUTE` / `CAS_ANONYMOUS_REQUESTS_PER_MINUTE` | Cluster-wide API rate limits | Optional | 600 / 120 | |
| `CAS_BUDGET_ENFORCEMENT` | `BLOCK` (refuse uncovered expenditure) or `WARN` | Keep `BLOCK` (C-10) | `BLOCK` | |
| `CAS_FINANCIAL_GRANT_APPROVAL` | Maker-checker on roles carrying financial permissions (C-19) | Keep true | true | |
| `CAS_SMTP_ENABLED`, `CAS_SMTP_HOST`, `CAS_SMTP_PORT`, `CAS_SMTP_AUTH`, `CAS_SMTP_USERNAME`, `CAS_SMTP_PASSWORD`, `CAS_MAIL_FROM` | E-mail delivery (relay is C-16) | Required once e-mail is used | disabled; port 587; auth on | password |
| `CAS_TRACE_SAMPLING` | Trace sampling ratio | Optional | 0.1 | |
| `CAS_BOOTSTRAP_ADMIN_USERNAME`, `_EMAIL`, `_PASSWORD` | Creates the first System Administrator when none exists | First start only, then remove | none | password |

Development-only settings must never be set in production. They are refused or ignored outside the `dev` profile:
* `cas.mfa.dev-bypass-users`: startup fails if it is set without `dev`;
* `CAS_COOKIE_SECURE=false`: prod forces secure cookies;
* the `Server-Timing` header.

## 3. Secrets

* **Data encryption key.** Generate once per environment (`openssl rand -base64 32`) and store it in the secrets
  manager with a sealed offline copy. If it is lost, MFA enrolments, supplier bank accounts and every stored
  document, report file and payment file become unreadable. Rotation (re-encrypting with a new key) is not built.
* **JWT keys.** RS256, 3072-bit. To rotate:
  1. Deploy the new key pair, with the old public key in `CAS_JWT_PREVIOUS_PUBLIC_KEY_PATH`.
  2. After the access-token lifetime (10 minutes), remove the previous key.
* **Database credentials.** `cas_owner` and `cas_app` passwords are passed to `roles.sql` as psql variables and
  then held in the secrets manager.
* Secret values are never logged: log masking is covered by `SensitiveLogIT`.

## 4. First deployment

1. **Database.** Create the cluster per 07. As a DBA, run
   `psql -v owner_password=… -v app_password=… -f deploy/db/roles.sql`.
2. **Secrets.** Load the variables of §2 into the secrets manager; mount the JWT PEM files read-only.
3. **Migrate and start.** Start one backend instance with `CAS_FLYWAY_USER=cas_owner`. Flyway applies V1–V41
   (`flyway clean` is disabled outside tests), then the application starts.
4. **Grants.** As `cas_owner`, run `deploy/db/post-migrate-grants.sql`, which keeps the audit tables
   append-only for `cas_app`. Repeat after every release that adds migrations.
5. **First administrator.** Start with `CAS_BOOTSTRAP_ADMIN_*` set. Sign in, change the temporary password and
   enrol the authenticator. Then remove the variables and restart; the bootstrap does nothing once an
   administrator exists. (`scripts/e2e-bootstrap.mjs` performs the same first sign-in for CI.)
6. **Scale out.** Start the remaining instances and the frontend; put the gateway in front.
7. **Configure.** Done by MIAA administrators in the application, per the [administrator manual](manuals/admin-manual.md):
   * the approved chart of accounts (C-22) and account mappings;
   * bank accounts, offices and departments;
   * the role matrix (C-08), approval limits (C-24), SLA policies (C-15), close tasks (C-33) and ticket categories (C-34).
8. **Verify.** Run the smoke checks of §6.

## 5. Release procedure

Every production change is a change-request ticket. The ticket system enforces the control: a rollback plan is
needed to submit for approval; approval is by someone other than the requester and the assignee; staging
validation is by the requester or an approver, never the assignee; and release notes are needed to resolve
(`TicketService`, V41).

| # | Step | Who | Evidence |
|---|---|---|---|
| 1 | CI green on the release commit: backend `verify` (tests and SpotBugs), frontend, e2e, Trivy scans | Developer | CI run |
| 2 | Change request raised with impact and rollback plan; approved | Requester, approver | Ticket |
| 3 | Deploy to staging with the release images; run the smoke checks and the affected UAT scripts (`docs/uat/`) | Support | Staging validation recorded on the ticket |
| 4 | Check migrations are expand-only (§7.2); plan the post-migrate grants | Developer | Release notes |
| 5 | Pre-deployment backup: pgBackRest full or differential backup, plus a named restore point (`SELECT pg_create_restore_point('release-<version>')`) | DBA | Backup log |
| 6 | Deploy the backend: one instance first (it migrates), then the rest rolling; then the frontend | Support | Deployment log |
| 7 | Run `post-migrate-grants.sql`; run the smoke checks of §6 | DBA, support | Checklist |
| 8 | Resolve the ticket with the release notes and reference; the requester confirms closure | Support, requester | Ticket |

## 6. Smoke checks

| Check | How | Expected |
|---|---|---|
| Liveness and readiness | `GET :8081/actuator/health/readiness` on each instance | `UP` |
| Schema version | `SELECT max(installed_rank), max(version) FROM flyway_schema_history WHERE success` | Release's last migration, all successful |
| Sign-in | Sign in with a support account and its authenticator | Dashboard loads |
| Audit chains | `POST /api/v1/audit-events/verification` as a user with AUDIT_LOG_VIEW | Both chains intact |
| Ledger | Trial balance for the current period | Debits equal credits; no balance-cache differences |
| Background work | A small report job; the integrations page | Job completes; no new DEAD transmissions |
| Security headers | `curl -I` the site | CSP, HSTS, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY` |

## 7. Rollback

### 7.1 Deciding

Roll back when a smoke check fails, or when a defect affects posting, balances, access control or the audit
trail. The change ticket's rollback plan names the steps for that release. Prefer an application rollback;
restoring data is the last resort, because it loses every transaction since the restore point.

### 7.2 Migrations are forward-only

Flyway never undoes a migration, and an applied migration is never edited. Releases therefore follow
**expand/contract**:
* A release may add tables, columns (nullable or with defaults), indexes and constraints that the *previous*
  release's code tolerates.
* Removing or renaming anything the previous release reads happens one release later, after that release is
  final.
* With that rule, the previous application version runs against the new schema, and an application rollback
  needs no database change.
* A migration that cannot follow the rule (a data rewrite, a tightened constraint) must say so in the release
  notes, and its rollback plan must be a data restore (§7.4).

V1–V41 were written as additive migrations, but they have not been reviewed against this rule release by
release, because no release has been made.

### 7.3 Application rollback (normal case)

1. Redeploy the previous backend and frontend images, rolling; the newer schema stays.
2. Run the smoke checks.
3. Record the rollback on the change ticket. Fix forward in a new release, with a new migration if the schema
   needs correcting.

### 7.4 Data rollback (last resort)

1. Stop the application tier.
2. Restore the database to the pre-deployment restore point with pgBackRest PITR, per 07. The development
   equivalent is `scripts/dr/restore.sh <base> name release-<version>`.
3. Deploy the previous images; run the smoke checks, including audit-chain verification.
4. Every transaction after the restore point is lost and must be re-entered from source documents. Integrations
   sent in that window (bank files, BIR transmissions) must be reconciled by hand. This is why §7.3 is
   preferred.

The restore and verification steps were exercised on a workstation by `scripts/dr/exercise.sh` (Phase 5d: PASS,
fingerprint identical, chains intact). The production exercise on Patroni and pgBackRest has not been done (C-04).

### 7.5 Never

* Run `flyway clean`, `repair` or edit `flyway_schema_history` in production.
* Edit or delete an applied migration file.
* Grant `cas_app` UPDATE or DELETE on the audit tables, or disable triggers to "fix" data. Corrections are new
  documents.
* Set any development-only setting of §2.

## 8. Checklist

| Item | First deployment | Each release |
|---|---|---|
| Change ticket with rollback plan, approval and staging validation | | ✓ |
| CI green, images scanned | ✓ | ✓ |
| Secrets present; encryption key escrowed | ✓ | |
| Pre-deployment backup and restore point | ✓ | ✓ |
| Migrations expand-only | | ✓ |
| `post-migrate-grants.sql` run | ✓ | ✓ if migrations |
| Bootstrap administrator removed | ✓ | |
| Smoke checks passed | ✓ | ✓ |
| Ticket resolved with release notes; requester confirmation | | ✓ |

## 9. Open points

* Hosting, registry, DNS and monitoring: C-04.
* The production DR exercise and the weekly automated restore test (07).
* Data encryption key rotation is not implemented.
* `post-migrate-grants.sql` protects only the audit tables at the grant level. The other append-only tables
  (journal entries and lines, approval decisions, inventory movements, ticket events, migration batch rows)
  rely on their triggers. Revoking UPDATE and DELETE on them from `cas_app` would add the same second layer.
  Checking that the application never needs those privileges is a follow-up.
