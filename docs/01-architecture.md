# 01 — Executive Technical Architecture

> Status: **Draft for MISD / Accounting Division review.** Derived from the CAS master
> development specification. The full TOR text (Sections 5–9, Annexes A–I) was **not**
> supplied to the development team with this specification; every TOR reference in these
> documents points at the master-specification section that restates it and must be
> re-mapped to the TOR clause numbers during requirements confirmation (see
> [03-clarification-register.md](03-clarification-register.md), item C-00).

## 1. Architectural style

A **modular monolith** backend (Spring Boot 3.5, Java 17) and an **independently deployed
SPA** (React 19 + TypeScript + Vite). The two share nothing but the versioned REST contract
under `/api/v1`, documented with OpenAPI 3.

```
 Browser (internal users)        Browser (concessionaires)
          │  HTTPS (TLS 1.3 target)        │
          ▼                                ▼
 ┌──────────────────────────────────────────────────────┐
 │  WAF / NGINX gateway  (TLS termination, headers,     │
 │  request-size limits, static SPA, /api reverse proxy)│
 └───────────────┬──────────────────────────────────────┘
                 │ HTTP inside private subnet (mTLS optional)
     ┌───────────▼───────────┐   ┌───────────────────────┐
     │ cas-backend  (N ≥ 2)  │…  │ cas-backend instance  │  stateless, horizontally scaled
     │ Spring Boot REST API  │   │                       │
     └───┬─────────┬─────────┘   └───────────────────────┘
         │         │
         │         ├── Private object storage (documents, report outputs, archives)
         │         ├── Notification outbox → approved e-mail / SMS providers
         │         └── Integration adapters → BIR EIS middleware, banks, FMIS (Phase 4)
         ▼
 ┌───────────────────────────┐      WAL streaming       ┌──────────────────────────┐
 │ PostgreSQL primary         │ ───────────────────────► │ Standby (other zone) +   │
 │ (HikariCP pools per node) │      WAL archive (PITR)  │ DR replica (DR site)     │
 └───────────────────────────┘                           └──────────────────────────┘
```

## 2. Backend module map (`com.miaa.cas`)

| Module | Responsibility | Phase |
|---|---|---|
| `common` | Error model, correlation IDs, paging DTOs, money/time utilities, crypto helpers | 1 |
| `config` | Security, OpenAPI, Jackson, clock, properties binding | 1 |
| `security` | JWT issuing/validation, refresh-token rotation, MFA (TOTP), password policy, lockout, rate limiting | 1 |
| `identity` | Users, roles, permissions, SoD conflict rules, sessions | 1 |
| `identity` (organization tables) | Organizations, offices, departments, user-office assignments: schema only, no service or API yet | 1 (schema) |
| `audit` | Append-only, hash-chained `audit_events` and `security_events`; integrity verification | 1 |
| `notifications` | Transactional outbox and provider dispatchers | 1 (outbox) |
| `generalledger`, `journal`, `tax` | Chart of accounts, periods and close, JEV workflow, posting engine, ledger balances, tax codes | 2 |
| `approvals` | Configurable document workflow steps and decisions (maker-checker) shared by the sub-ledgers | 2 |
| `documents` | Supporting documents (no versions), malware-scanning hook (not integrated, C-25), storage in PostgreSQL (C-26) | 2 |
| `accountspayable`, `accountsreceivable` (incl. billing and the portal), `treasury` (incl. bank reconciliation and payment files), `fixedassets`, `budgeting`, `inventoryaccounting` | Financial sub-ledgers posting through the journal engine. Controlling (cost and overhead allocation) is not built | 3 |
| `financialreporting`, `integrations` | Statutory/management reports, background report jobs, BIR/bank/FMIS adapters | 4 |
| `migration`, `tickets` | Opening-balance migration and the parallel-run comparison; ticketing with SLA timers. System administration lives in `identity` | 4–5 |

**Dependency rule:** controllers → application services → domain/repositories. Modules
may call another module only through its public application service (never its
repositories). Every financial sub-ledger posts to the GL **only** through the journal
posting service, which is the single writer of `journal_entries` / `journal_entry_lines`.

## 3. Key architecture decisions

| ADR | Decision |
|---|---|
| [ADR-001](adr/ADR-001-modular-monolith.md) | Modular monolith + separately deployed SPA |
| [ADR-002](adr/ADR-002-token-design.md) | RS256 access JWT (10 min, minimal claims, permissions resolved server-side per request) + rotating opaque refresh token in `HttpOnly` cookie |
| [ADR-003](adr/ADR-003-audit-integrity.md) | Append-only audit tables enforced by DB triggers + grants, SHA-256 hash chain, serialized by advisory lock |
| [ADR-004](adr/ADR-004-rate-limiting.md) | Bucket4j per-instance limits on auth endpoints, DB-backed lockout as the cluster-wide control. Since gap 6 the general API limit is cluster-wide in the database (`ApiRateLimiter`, V36) |
| [ADR-005](adr/ADR-005-test-database.md) | Testcontainers PostgreSQL by default; `CAS_TEST_JDBC_URL` override for hosts without Docker |
| [ADR-006](adr/ADR-006-jdbc-persistence.md) | `JdbcTemplate` with bound-parameter SQL as the main persistence approach; JPA kept for identity, GL configuration, vouchers and masters; Flyway owns the schema |

## 4. Statelessness and scaling

* No HTTP session (`SessionCreationPolicy.STATELESS`). All session state (refresh-token
  families, lockout counters, MFA replay counters) lives in PostgreSQL.
* JWT signing key pair is loaded from mounted secrets, so every instance validates every
  token; key rotation keeps the previous public key for verification (`kid` header).
* Long-running work (reports, imports, depreciation runs) will run as DB-queued jobs
  (`report_jobs`, `SELECT … FOR UPDATE SKIP LOCKED`) so any instance can pick them up (Phase 2+).
* Scaling path to 1,000+ users: add backend instances behind the load balancer, PgBouncer
  in transaction mode in front of PostgreSQL, read replica for reporting queries, and a
  shared Bucket4j backend (PostgreSQL or Redis) — see [07-infrastructure-dr.md](07-infrastructure-dr.md).
