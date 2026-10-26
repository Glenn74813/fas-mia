# 11 — Phase 2 Report: Accounting Engine

Date: 2026-10-22. Scope: chart of accounts, fiscal calendar and period control, Journal Entry Voucher
workflow with segregation of duties, double-entry posting, reversals, recurring and year-end closing
vouchers, general/subsidiary ledger inquiry, trial balance, supporting documents and idempotency.

> **The chart of accounts in development and test is a placeholder** (`PLC-` codes, names prefixed
> `[PLACEHOLDER]`, every row flagged `placeholder`). It is loaded only by the `dev` profile seeder and by
> tests, never by a migration. Production configuration (`cas.accounting.allow-placeholder-accounts=false`)
> rejects any voucher touching a placeholder account, and the UI shows a banner while one is active.
> MIAA's approved chart is loaded with `POST /api/v1/accounts/import` (C-07, C-22).

## 1. Delivered

| Area | Main code |
|---|---|
| Chart of accounts, cost centres, subsidiary-ledger parties | `generalledger/AccountService`, `DimensionService`, `ChartOfAccountsController` |
| Fiscal years, periods, close checklist, two-person reopen, fiscal-year close | `FiscalCalendarService`, `PeriodCloseService`, `PeriodCloseContributor` (extension point for Phase 3 checks) |
| Ledger inquiry: trial balance, account/subsidiary ledger, entry drill-down, SL reconciliation, cache rebuild | `LedgerInquiryService`, `LedgerBalanceService`, `GeneralLedgerController` |
| JEV workflow and approvals | `journal/JournalVoucherService`, `WorkflowConfig`, `DecisionLog`, `VoucherQueryService` |
| Posting engine | `journal/PostingService`, `VoucherValidator`, `NumberingService`, `common/idempotency/IdempotencyService` |
| Reversal, recurring and closing vouchers | `JournalVoucherService.createReversal / createClosingVoucher`, `RecurringJournalService` |
| Supporting documents | `documents/*` (validation, SHA-256, scan hook, DB storage, owner policies) |
| Frontend | Journal vouchers (list, editor, detail with workflow/actions/documents/history), approvals queue, recurring journals, trial balance, account ledger, entry detail, chart of accounts (with CSV import), fiscal periods (close checklist with waivers, reopen requests and decisions, closing voucher) |
| Migrations | `V7__general_ledger.sql`, `V8__seed_accounting_permissions_workflows.sql` |

Totals now: 129 backend classes, 22 backend test classes, 64 frontend TS/TSX files.

## 2. Controls and where they are enforced

| Control | Service layer | Database |
|---|---|---|
| Debits = credits, ≥ 2 lines, total > 0 | `VoucherValidator` (submit, every approval step, posting) | Deferred constraint trigger re-checks every entry at COMMIT; header CHECK |
| Posted entries never change | No update path exists | UPDATE/DELETE/TRUNCATE triggers on `journal_entries`, `journal_entry_lines`, `approval_decisions` |
| No duplicate posting | Voucher row lock + status check + Idempotency-Key | `journal_entries.voucher_id` UNIQUE |
| Gap-free JEV numbers | Counter row locked until commit | — |
| Period cannot close mid-posting | Posting takes `FOR SHARE` on the period; close takes `FOR UPDATE` | — |
| Preparer ≠ reviewer ≠ approver; preparer ≠ poster | `JournalVoucherService`, `PostingService` | — |
| Reopen decided by a second person | `PeriodCloseService` | CHECK on `period_status_changes` |
| One reversal per entry | Service check | `journal_entries.reversal_of_entry_id` UNIQUE + partial unique index on live reversal vouchers |
| Balances never become the source of truth | Trial balance reads posted lines; cache differences endpoint and rebuild | — |

## 3. APIs added (`/api/v1`)

`accounts` (list, get, create, update, activate, deactivate, import) · `cost-centers` · `subsidiary-accounts` ·
`fiscal-years` (list, create, close, closing-voucher) · `accounting-periods/{id}` (open, soft-close, close-checklist,
close, history, reopen-requests) · `period-reopen-requests` (list, decision) · `journal-vouchers` (list,
approval-queue, get, create, update, submit, approve, return, reject, cancel, post, documents) ·
`journal-entries/{id}/reversal` · `documents/{id}` (content, remove) · `recurring-journals` (list, create,
pause, resume, run-due) · `general-ledger` (trial-balance, accounts/{id}/ledger, entries/{id},
subsidiary-reconciliation, balance-differences, balances/rebuild).

## 4. Tests executed and actual results

| Suite | Result |
|---|---|
| Backend unit + integration (`mvn test`, PostgreSQL 18.6 local disposable DB) | **87 run, 0 failures** (63 Phase 1 + 24 Phase 2) |
| Frontend typecheck, lint, production build | pass |
| Frontend unit (Vitest) | **23 passed** (adds exact centavo arithmetic and line-editor tests) |
| Playwright e2e, live stack (backend dev profile + Vite) | **2 passed**: `journal-voucher.spec.ts` (four people: draft → out-of-balance warning → attachment → submit → review from the approvals queue → approval → posting → immutable entry → trial balance), and the Phase 1 `admin-onboarding.spec.ts` |

Phase 2 tests cover: the full SoD lifecycle; return and resubmission restarting the approval cycle; final
rejection; stale versions; administrators having no accounting access; unbalanced vouchers; cost-centre,
subsidiary and header-account rules; invalid amounts (3 decimals, negatives); database immutability and the
commit-time balance trigger against a rogue direct insert; trial balance, ledger opening/closing for real
and nominal accounts; balance-cache corruption detection and rebuild; two posters racing (exactly one entry);
gap-free numbering; reversal (once only, own approvals, nets to zero); close checklist and justified waivers;
closed-period posting refusal; two-person reopen; soft-closed periods; year-end closing and fiscal-year close;
chart import all-or-nothing; structural account rules; upload validation (forged PDF, wrong extension, path
traversal names, non-preparer); audited downloads; recurring generation; placeholder guard.

## 5. Defects found and fixed during this phase

1. **Detached entity after `saveAndFlush`.** Spring Data read the `@Version` property through a getter that
   returned 0 instead of null, so new entities with assigned UUIDs were *merged* (copied) rather than persisted
   and later changes went to a detached object. In Phase 1 this was masked; in Phase 2 reversal and closing
   vouchers stayed DRAFT. Fixed by naming the field `rowVersion` (column unchanged) in all ten entities.
2. **Trial balance out of balance when an earlier year was never closed.** Prior-year revenue/expense results
   disappeared from opening balances. The trial balance now shows an explicit "Unclosed result of prior fiscal
   years" line so it stays complete and balanced while signalling the missing year-end close.
3. `CHAR(3)` currency column mapped as VARCHAR (caught by schema validation at startup).
4. Trial balance page defaulted to January instead of the current period.

## 6. Not verified / known limitations

* Placeholder chart only; the approved MIAA chart, fiscal policies and statement mappings are pending (C-07, C-22).
* PHP only; foreign-currency vouchers and revaluation await the FX policy (C-21).
* Uploaded files are labelled `NOT_SCANNED` until an anti-malware engine is integrated (C-25).
* Open-item clearing, organisation/office API, and WebAuthn moved to Phase 3 (documented in the RTM).
* Subsidiary-ledger reconciliation endpoint exists but has no dedicated test; opening-balance vouchers have
  no dedicated test (bulk opening balances are part of Phase 5 migration).
* Audit-trail inserts and the numbering counter serialise their writers; fine at the stated volumes, to be
  measured in Phase 5 load tests.
* Docker images, Compose and CI still not executed on this host (no Docker); tests ran against PostgreSQL 18
  (Flyway officially supports up to 17; target 17).
* The dev-profile auth rate limit is raised to 120/min for e2e onboarding; production stays at 20/min.

## 7. Security implications

New financial permissions are covered by the SoD rules (no technical administrator or auditor can hold them);
transaction-level SoD is enforced per action; every voucher decision is append-only; uploads are validated by
content signature and served only as attachments with `nosniff`; posting is idempotent and duplicate-proof;
placeholder data cannot reach production postings.

## 8. Run

See [09-dev-setup.md](09-dev-setup.md). The dev profile seeds the placeholder chart and the current fiscal year.

## 9. Criteria to approve Phase 3

1. Chief Accountant walkthrough of the voucher workflow, close checklist and reports using the placeholder data.
2. Approved chart of accounts (CSV) and the accounting framework decision (C-07, C-22), or a dated plan for them.
3. Decisions on C-08 (role matrix), C-09 (DV vs payment), C-10 (budget definitions), C-11 (bank interfaces), C-21 (FX).
4. CI green on a Docker-capable runner, including Testcontainers and image scans.
