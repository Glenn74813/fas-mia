# User acceptance testing (UAT)

Scripts for MIAA's testers, traced to the requirements traceability matrix
([02-requirements-traceability.md](../02-requirements-traceability.md)). The master specification's §18 acceptance
conditions include:
* 100 % of required functional test cases passed, with no critical defects;
* at least a 95 % UAT pass rate;
* no unexplained financial reconciliation discrepancies (see UAT-MIG-004, the parallel-run sign-off);
* formal MIAA approval of outstanding non-critical defects.

| File | Area |
|---|---|
| [01-access-and-audit.md](01-access-and-audit.md) | Sign-in, two-step verification, passwords, sessions, roles and segregation of duties, audit trail |
| [02-general-ledger.md](02-general-ledger.md) | Chart of accounts, periods, journal vouchers, reversals, inquiries, recurring and closing entries, documents |
| [03-payables-budget-treasury.md](03-payables-budget-treasury.md) | Suppliers, invoices, disbursement vouchers, budgets, deposits, bank reconciliation, payment files |
| [04-receivables-portal.md](04-receivables-portal.md) | Customers, contracts, billing, collections, statements, concessionaire portal |
| [05-assets-inventory.md](05-assets-inventory.md) | Fixed assets and depreciation, inventory at weighted average cost |
| [06-reporting-integration-tickets.md](06-reporting-integration-tickets.md) | Financial statements, report jobs and exports, integrations, tickets |
| [07-migration-parallel-run.md](07-migration-parallel-run.md) | Opening balances and the parallel-run comparison |

## How to run a script

1. Use the UAT environment with the agreed test data. Never use production, and never use real passwords or
   personal data in test records.
2. Each tester signs in as their own UAT user with the roles the script names. Where a script needs two people
   (preparer and approver, for example), two different users must do the steps: the system refuses one person
   doing both, and that refusal is part of what is tested.
3. Follow the steps in order. A step passes when the system behaves exactly as the expected result says,
   including refusals and their messages.
4. Record the result in the tracker: Pass, Fail (raise a defect with its ID and severity) or Blocked (say what
   blocked it).

The tracker is generated from these files, so every script appears once:

```bash
node scripts/uat/coverage.mjs                         # checks traceability, writes docs/uat/coverage.md and tracker.csv
node scripts/uat/coverage.mjs --results tracker.csv   # adds the pass rate from a filled-in tracker
```

`coverage.mjs` fails if a script names a requirement that is not in the matrix, or if a requirement that is
done or in progress has no script and is not listed below.

## Defect severity

| Severity | Meaning |
|---|---|
| Critical | Wrong financial result, data loss, a control that can be bypassed (segregation of duties, authorisation, audit trail), or no workaround for a core task |
| High | A core task fails but a documented workaround exists |
| Medium | A non-core task fails, or a core task works with a significant usability problem |
| Low | Cosmetic or wording |

## Requirements verified by automated tests only

A tester cannot observe these through the user interface. Their evidence is the automated test named in the
matrix, and the acceptance team reviews that evidence instead of running a UAT script.

| Requirement | Reason | Automated evidence |
|---|---|---|
| FR-AUTH-03 | Password hashing algorithm | `PasswordHashingTest` |
| FR-AUTH-04 | Access-token format and claims | `JwtServiceTest` |
| FR-AUTH-05 | Refresh-token rotation and reuse detection | `AuthFlowIT` |
| FR-AUD-04 | Audit chain under concurrent writers | `AuditIntegrityIT.concurrentWritersKeepChainLinear` |
| FR-GL-15 | Balance cache reconciled to and rebuilt from posted lines | `PostingInvariantsIT` |
| FR-ORG-01 | Schema only; no user-facing function yet | — |

Requirements that are blocked or planned (FR-AUTH-08, FR-AUTH-09, FR-RBAC-06 to 08, FR-GL-17) have no scripts until
they are built.
