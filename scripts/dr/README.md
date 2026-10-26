# DR exercise kit (Phase 5d)

Scripts for backup, point-in-time restore and a timed, verified DR exercise against the MS §17 targets (RPO ≤ 1 h,
RTO ≤ 4 h) and the §18 acceptance condition ("successful backup restoration and disaster recovery tests").

They are the plain-PostgreSQL reference for a development or test host. Production uses Patroni and pgBackRest
([docs/07-infrastructure-dr.md](../../docs/07-infrastructure-dr.md)) with the same steps: continuous WAL archiving, a base
backup, restore to a point in time into an isolated environment, verification, and a timed report. The
verification part (`fingerprint.sql` and `api.mjs`) works the same way on any infrastructure.

| Script | Does |
|---|---|
| `enable-archiving.sh` | Turns on WAL archiving to `.devdata/dr/wal-archive` with `archive_timeout = 300s` and restarts the source cluster |
| `base-backup.sh` | Compressed base backup into `.devdata/dr/base/<time>/`, with WAL replayed from the archive |
| `restore.sh <base> name <point>` or `restore.sh <base> time '<timestamp>'` | Restores into an isolated cluster on port 55433 with archiving off, replays WAL to the target, promotes it |
| `fingerprint.sql` | Row counts, ledger totals, a digest of every account balance, the last journal number per year, both audit chain heads, a digest of stored documents |
| `api.mjs` | Marker writes and checks as the administrator; audit-chain verification as an auditor |
| `exercise.sh [--keep]` | The whole drill (below) with a report in `.devdata/dr/results/<time>/report.md` |

## The exercise

```bash
scripts/dr/exercise.sh
```

1. Archiving on; a base backup is taken.
2. A "before" marker user is created through the API. The books are fingerprinted, a named restore point is set,
   and the script measures how long the archive takes to hold it.
3. An "after" marker is created; the restore must not contain it.
4. Disaster declared: restore to the restore point in an isolated cluster, then start a second backend on port
   8081 against it. Outbound e-mail and integrations are off, so nothing is sent twice.
5. Checks: identical fingerprint, "before" present, "after" absent, both audit hash chains intact. The report
   gives the RTO from disaster to verified service and the RPO evidence.

The restored stack is stopped afterwards unless `--keep` is given. Verifying the audit chains needs a user with
`AUDIT_LOG_VIEW`, which administrators do not hold. Set `DR_AUDITOR_USERNAME`, `DR_AUDITOR_PASSWORD` and
`DR_AUDITOR_TOTP_SECRET`, or seed the performance kit (`node perf/seed.mjs`) and its internal auditor is used.

## What a workstation run does not show

* The RTO measured here is restore plus start-up plus verification. A real failover also needs detection, the
  decision to fail over, DNS or gateway changes and communication, all within the 4-hour budget.
* The RPO is bounded by `archive_timeout` (5 minutes) only while the archive is off-site. On MIAA's
  infrastructure the archive goes to separate, immutable object storage (C-04); here it is a local folder.
* Twice-yearly exercises, the restore approval ticket and the weekly automated restore test are operational
  procedures for MISD (07-infrastructure-dr).
