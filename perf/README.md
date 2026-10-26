# CAS performance test kit (Phase 5c)

Scripted API load scenarios measured against the master specification's §17 targets, with a seeded volume data
set and correctness checks. It needs Node 20 or later and nothing else (no npm packages). `psql` on the path adds
database resource figures and the journal-numbering check.

> **Development and test environments only.** The seeder creates users with generated passwords and authenticator
> secrets and stores them in `perf/.state/seed.json` (gitignored). Never point it at production.

## What is measured, and against what

| Kind | Target | Source |
|---|---|---|
| `entry`: create, attach, submit, review, approve and post journal vouchers | p95 ≤ 5 s | §17 Individual financial entry processing |
| `read`: inquiries, approval queues and portal pages | p95 ≤ 5 s | **Proposed** (C-02): §17 sets no figure for reads |
| `report`: report jobs from request to ready plus download; synchronous statements | ≤ 60 s | §17 Standard financial report generation |
| `batch`: one 500-line voucher through the full workflow; a 5,000-item open-invoice batch | ≤ 60 s | §17 Batch financial processing ("agreed test workload" not yet agreed) |
| Errors | no 5xx; ≤ 1 % unexpected responses per kind | **Proposed** (C-02): §17 asks for thresholds but sets none |
| Concurrency | 20 internal + 60 portal users at once | §17, §3.3, §18 |

The figures live in [targets.json](targets.json). **Measurement** follows C-02 / C-17. The backend, started with
`cas.observability.server-timing=true` (on in the `dev` profile, off by default), returns
`Server-Timing: app;dur=<ms>`, timed from its first filter to the end of request processing. That covers
authentication, authorisation, business logic and the database commit, but not network transfer or the client.
Entry and read targets are judged on that server time; the client time is reported alongside. Report and batch
targets are end-to-end times measured by the client, because they span several requests and background work. No
scenario calls an external service.

Correctness is a gate of its own (§17: never trade correctness for speed). After the run, the trial balance must
still balance, and the AR, unapplied-collections and AP sub-ledger differences must be unchanged. Journal entry
numbers must stay unique and gap-free.

## Running it

```bash
scripts/dev-db.sh start                 # dev database, if it is not running
scripts/run-backend-dev.sh              # backend with the dev profile (Server-Timing on)
node perf/seed.mjs                      # volume data set; see the options at the top of seed.mjs
node perf/run.mjs --scenario all        # mixed 20 + 60 users, then batch, then reports
```

Scenarios: `mixed` (20 internal and 60 portal users together, the §18 test), `internal`, `portal`, `batch`,
`reports` and `all`. Options: `--duration` seconds per load phase (default 300), `--think min,max` milliseconds
between a user's actions (default 2000,5000), `--internal-users`, `--portal-users`, `--jev-lines`, `--ar-items`.

Each run writes `perf/results/<time>-<scenario>/` (gitignored):

* `report.md`: the verdict per target kind, the test environment, volumes and workload, per-operation
  percentiles, resources and correctness checks;
* `summary.json`: the same data, machine-readable;
* `samples.csv`: every request, for further analysis.

The exit code is 0 only when the run passes.

Settings come from the environment, falling back to the repository's development `.env`: `PERF_API_URL`
(default `http://127.0.0.1:8080`), `PERF_ADMIN_USERNAME` / `PERF_ADMIN_PASSWORD` / `PERF_ADMIN_TOTP_SECRET`, and
`PERF_DB_URL` / `PERF_DB_USER` / `PERF_DB_PASSWORD` for the resource sampler.

## How the users behave

* **Internal (20):** 10 preparers create a voucher with a supporting document and submit it; 4 reviewers and
  3 approvers work their approval queues; 2 posters post; 1 reporting user runs the trial balance, an account
  ledger, AR aging, a customer statement and the customer list.
* **Portal (60):** concessionaires view their account summary, billings and one billing in detail, payments and
  statement.

Every user signs in with password and TOTP once per run. Sign-ins are spaced about 1.1 s apart so that one load
generator stays under the per-address authentication rate limit; real users come from many addresses.

## Limits of this kit

* A developer workstation, with the load generator, backend and database on one host, gives indicative figures
  only. Acceptance needs the documented target environment.
* Volumes are placeholders until MIAA's are known (C-12). Raise them with the seeder options on the target
  environment.
* Concurrent budget reservations are proven by `BudgetConcurrencyIT`, not by this kit, because no budgets are
  seeded in development. Failover and recovery are 5d's DR exercise.
* The batch scenario adds one opening entry and its open invoices to the database on every run, so use it only on
  test data.

## Rate limits

The API limits each user to `CAS_API_REQUESTS_PER_MINUTE` calls (default 600) across all instances. A load run that
drives one user harder than that is measuring the limiter, not the system. Raise the limit for the run, for example
`CAS_API_REQUESTS_PER_MINUTE=100000`, or spread the load over more users. Record the setting in the report.
