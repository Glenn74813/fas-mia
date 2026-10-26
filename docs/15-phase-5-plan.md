# 15 — Phase 5 Plan: Migration and Acceptance

Date: 2026-10-22. Phase 4 report: [14-phase-4-report.md](14-phase-4-report.md). Its approval criteria (MIAA
decisions on C-06, C-07, C-11, C-15, C-18 and the role matrix) are still open, so this phase builds only what
does not depend on them.

The roadmap's Phase 5 is migration tooling and two test migrations, load tests, a penetration test, a DR
exercise, UAT, a one-month parallel run and cutover. The penetration test, UAT, parallel run and cutover are
MIAA activities. CAS can supply the tooling and evidence for them, but cannot perform them. The legacy
systems, their extracts and the history to migrate are unknown (C-12). The import formats below are
therefore **CAS templates**. Mapping each legacy extract onto them is migration-week work, and no legacy
layout is assumed.

| Slice | Scope | Buildable now? | Depends on |
|---|---|---|---|
| **5a Opening balances: GL, AR, AP** (delivered 2026-10-22: V26, `migration/*`, Opening balances pages; `MigrationIT`, `migration.spec.ts`) | Migration batches: upload a CAS-template CSV, validate every row, keep the file and its SHA-256. A GL opening trial balance becomes a draft OPENING journal voucher that then follows the normal JEV workflow. AR and AP open items are tied to the posted opening entry and must reconcile with it per customer or supplier. A second person applies them; items are never posted twice. | Yes | C-12 (extracts), C-22 (approved chart) |
| **5b Opening balances: assets, stock, advances** (delivered 2026-10-23: V27, `migration/*Rules`, advance register; `MigrationSubledgersIT`, `migration.spec.ts`) | Fixed-asset register with cost and accumulated depreciation; stock on hand at cost per item and warehouse; unapplied collections and supplier advances. Each reconciles with the opening entry. | Yes | C-12, C-30 |
| **5c Performance test kit** (delivered 2026-10-23: `perf/`, `ServerTimingFilter`, V28) | Scripted API load scenarios with the MS §17 targets, and a seeded volume data set. Results on a developer host are indicative only. | Kit yes; results need the target environment | C-02, C-17 |
| **5d DR exercise kit** (delivered 2026-10-23: `scripts/dr/`) | Backup and restore scripts, a restore check (audit hash chain, trial balance and document counts compared before and after), and the RPO/RTO measurement procedure | Kit yes; the exercise needs MIAA infrastructure | 07-infrastructure-dr |
| **5e Acceptance support** (delivered 2026-10-23: `docs/uat/`, `scripts/uat/coverage.mjs`, V29, Parallel run pages) | UAT scripts traced to the RTM; a parallel-run comparison (legacy trial balance imported and compared with CAS by account and period) | Yes | C-12 |

Exit gate (roadmap): MS §18 acceptance conditions and MIAA sign-off. Neither can be met by development alone.

## Design notes for 5a

* The **opening trial balance is not posted by the migration tool.** It creates an OPENING voucher prepared
  by the uploader, with the source file attached as its supporting document. Approval and posting are the
  JEV workflow's, with all its SoD checks. The voucher must fall in period 1 of an open fiscal year.
* **Open items do not post anything.** The control-account balances are already in the opening entry. Each
  migrated invoice records that entry as its posted entry. Aging, statements, the portal, collections,
  payments and reconciliation then treat it like any posted invoice. No second journal entry arises, so the
  GL cannot be double-counted.
* **Per-party reconciliation is a hard gate.** For every customer (or supplier), the batch total must equal
  that party's subsidiary balance on the AR (AP) control account in the opening entry. A batch with any
  difference cannot be applied. The difference report lists each party on either side.
* **Two people.** The uploader (MIGRATION_PREPARE) cannot apply. A second person (MIGRATION_APPROVE) applies
  the whole batch in one transaction, or nothing. An applied batch is final; corrections are made with
  normal documents (credit memos, debit memos, journal vouchers).
* **Idempotence.** A file already applied (same SHA-256 and kind) is refused, an item number already present
  for the same party is refused, and an opening entry can carry only one applied batch of each kind.

## Progress

* 5a delivered. Backend suite 129 tests, 0 failures; frontend typecheck, lint and 23 unit tests pass; the
  `migration` browser test passed against the live dev stack (trial balance → draft opening voucher →
  submitted, reviewed, approved and posted by four different people → open invoices uploaded, reconciled
  and applied by the chief accountant → the customer's open balance equals the migrated items).
* The live test caught only test defects: a confirm-dialog label, waiting for the batch page, a reused
  one-time code, the posting idempotency key and an ambiguous field label. No product changes were needed.
* Limits: migrated items carry no VAT or withholding breakdown (the opening entry already holds those
  balances); items load at the outstanding amount, not the original amount less payments.
* 5b delivered. Each kind now has its own rules class (validate, reconcile, apply) behind one orchestrator;
  the 5a behaviour is unchanged (its tests pass as before).
  * Unapplied collections are posted receipts with payment method MIGRATED and only an unapplied amount.
    They can be applied to invoices later, and are excluded from deposits, the undeposited list, cash
    position and the daily collection report, since the cash was handled in the legacy system.
  * Supplier advances: CAS had no advance concept. They load into a new register that reconciles per
    supplier with the account mapped to SUPPLIER_ADVANCES (placeholder PLC-1206, supplier sub-ledger).
    Paying and liquidating advances is not built: that is policy (new clarification C-31).
  * Fixed assets keep the legacy accumulated depreciation and remaining life; depreciation continues from
    the cutover month over the remaining life rather than being recomputed from the original in-service
    date, so legacy policy or rounding differences are not re-charged. The asset register, depreciation
    runs, disposals, PPE schedule and FA reconciliation all include the opening amount.
  * Stock: one posted OPENING inventory document per batch sets each item's average-cost pool and
    warehouse quantities, with one immutable movement per row. Only items with no history in CAS qualify;
    this is checked again under lock when the batch is applied.
* Tests: backend suite 131 tests, 0 failures; frontend typecheck, lint and 23 unit tests pass; both
  `migration.spec.ts` tests passed against a freshly rebuilt dev stack.
* Defect found by the new integration test: `fixed_assets.acquisition_source` was VARCHAR(8), too short
  for MIGRATION; widened in V27 before V27 was applied anywhere but the throwaway test database.
* Not covered: assets under construction (AUC project balances), employee advances, and depreciation runs
  of migrated assets end to end. The run arithmetic is unit-tested and the register values are checked in
  the integration and browser tests, but no test posts a run on migrated assets, because runs cover every
  asset in a period and would entangle the shared test data.
* Environment: the machine rebooted on 2026-10-23 and the dev database, which lived under `/tmp`, was lost.
  It now lives in `.devdata/` (gitignored) via `scripts/dev-db.sh`. Dev data was re-seeded and the dev
  administrator re-enrolled; the earlier demo chief accountant and test users no longer exist.
* 5c delivered. `perf/` is a dependency-free Node kit; its README describes what is measured and how.
  * `seed.mjs` builds the volume data set through the API and migration batches: 126 users, 100 customers
    with linked portal accounts, 50 suppliers, 3,000 open AR and 1,000 open AP invoices, 500 posted
    vouchers and 200 collections.
  * `run.mjs` runs the scenarios (mixed 20 + 60 users, batch, reports) and writes `report.md`,
    `summary.json` and `samples.csv`.
  * A dev-profile `Server-Timing` header gives server-side times, with the commit included, per C-02. It is off
    by default and in production.
  * Correctness is checked before and after every run: the trial balance balances, the sub-ledger
    differences are unchanged, and journal numbers stay unique and gap-free.
* Workstation run (indicative only), 20 internal and 60 portal users for 5 minutes: entry p95 22 ms and
  read p95 164 ms (server-side), report jobs within 10.3 s from request to ready, batch within 0.7 s, no
  errors, correctness checks clean.
* Defect found by the kit: AR aging looked up each invoice's credit memos through an unindexed column, so it
  was quadratic. It took 3.9 s for 8,000 documents and would breach the 5 s target at real volumes. V28 adds
  a partial index and the query now takes 46 ms.
* Observations, not changed: report-job queue time is dominated by the 5 s worker poll interval, and one worker
  renders jobs one at a time, so five concurrent jobs wait for each other. Both are within the 60 s target
  here and should be watched at MIAA volumes.
* Kit defects fixed during the runs: the batch scenario re-sent an identical file on its second run, which CAS
  correctly refused as already applied; a failing phase also lost the whole run's measurements. Batch files
  now carry the run tag, and a failed phase is reported as a failure while the rest is still written out.
* Development convenience (not a product feature): `cas.mfa.dev-bypass-users` (dev profile only; startup
  refuses it otherwise) lets the named users sign in without the authenticator code. It is set to `admin.dev`;
  every bypass is a security event. Everyone else, including the browser tests and performance users, still
  uses two-step verification (FR-AUTH-07).
* After 5c: backend suite 134 tests, 0 failures (adds `ServerTimingFilterTest`, `DevMfaBypassTest`).
* 5d delivered. `scripts/dr/` turns on WAL archiving, takes base backups, restores to a named point or a time
  in an isolated cluster, and runs the full exercise (README in that folder). The exercise:
  * writes before and after a restore point, fingerprints the books, restores, and starts a second backend
    on the copy with outbound e-mail and integrations off;
  * checks that the fingerprint matches, that the earlier write survives and the later one does not, and that
    both audit hash chains are intact;
  * times the recovery.
* Workstation exercise: PASS. 22 fingerprint measures identical, chains intact (12,019 audit and 862 security
  events), 13 s from the declared disaster to verified service. This is indicative only; detection,
  decision, DNS and communication are not included and belong in the runbook's 4-hour budget.
* Kit defects found on the first run: auditing the restored trail needs AUDIT_LOG_VIEW, which administrators
  rightly lack, so the verifier now signs in as an auditor. A failed run left the restored stack running, so
  cleanup is now on exit.
* Not done here: the production exercise on Patroni and pgBackRest, off-site immutable WAL storage (C-04), the
  weekly automated restore test, and failover of the application tier and DNS.
* 5e delivered.
  * UAT: 58 scripts with 205 steps in `docs/uat/`, each with its matrix references, roles, preconditions,
    steps, expected results and a result line. `scripts/uat/coverage.mjs` checks traceability in both
    directions, generates `coverage.md` and `tracker.csv`, and computes the §18 pass rate from a filled-in
    tracker (at least 95 %, all required cases passed, no critical defects). 45 requirements are covered by
    scripts, 6 technical ones by automated tests only (listed with their evidence in the UAT README), and 6
    are not built yet.
  * Parallel run: a legacy trial balance is compared with CAS per account (V29, `ParallelRunService`, Parallel
    run pages). Differences, CAS-only, legacy-only and unmapped lines each need an explanation. Recompare keeps
    an explanation only while its difference is unchanged. Sign-off is by a second person, and only when
    nothing is unexplained (the §18 "no unexplained discrepancies" evidence).
* Tests: backend suite 135 tests, 0 failures (adds `ParallelRunIT`); the browser test `parallel-run.spec.ts` passes; the coverage checker's failure paths
  and pass-rate calculation were exercised with a deliberately broken script and a filled-in tracker.
* Test defects found by rerunning the browser suite: the asset migration test sent an identical file on every
  run, which CAS correctly refused the second time; it now includes the run tag. The new test checked the
  status text "SIGNED_OFF" where the page shows "SIGNED OFF".
* The UAT scripts state behaviour at business level; a few screen names may need adjusting during the UAT
  walk-through with MIAA's testers. Roles follow the proposed matrix (C-08).
