# ADR-006 — Persistence with JdbcTemplate

Status: Accepted (recorded retroactively, 2026-10-26).

**Context.** Phase 1 used Spring Data JPA for identity, and Phase 2 for the chart of accounts and fiscal
calendar. From Phase 2's journal posting onwards, the modules mostly need:
* set-based SQL: ledger aggregates, trial balance, aging, reconciliations, report queries;
* explicit row locking: `SELECT … FOR UPDATE`, `FOR UPDATE SKIP LOCKED` for job workers, and transaction-scoped
  advisory locks for numbering, clearing and the audit chain;
* PostgreSQL features: partial unique indexes, JSONB, arrays, deferred constraint triggers;
* append-only tables that an ORM's dirty checking must never update.

**Decision.** `JdbcTemplate` with hand-written SQL is the main persistence approach. JPA stays where it was
already in use and fits: 27 entities and 22 repositories in identity, the general-ledger configuration,
journal vouchers, documents, budgets and the AP/AR masters. New modules use `JdbcTemplate`, which 87
classes across every module now do.
* Every value is a bound parameter. SQL is concatenated only from constants, code-chosen table or column names,
  or clauses built of `?` placeholders.
* Shared helpers keep this uniform:
  * `common/web/JdbcPaging` and `PageResponse` for paged queries;
  * `IdempotencyService` for retried requests;
  * `version` columns compared on every update, answering 409 when stale (`ApiException.staleVersion()`).
* Flyway owns the schema (V1–V41); Hibernate never generates or validates DDL.

**Consequences.**
* (+) SQL is visible and reviewable, and the queries that matter for performance are tuned directly. One
  example is the AR aging fix in V28.
* (+) No lazy-loading or flush-order surprises around posting and locking.
* (−) More mapping code (row mappers, records).
* (−) Two styles coexist where a module mixes a JPA entity with `JdbcTemplate` queries; both run in the same
  Spring transaction.
* (−) SQL injection is a standing risk, managed by the bound-parameter rule. SpotBugs/FindSecBugs runs in
  `mvn verify` and every dynamic-SQL site was reviewed (`backend/spotbugs-exclude.xml`). `SqlInjectionIT`
  sends injection payloads to every search, filter and sort parameter.
* Schema documentation is generated from the database (`scripts/docs/gen-data-dictionary.py`, docs/04).
