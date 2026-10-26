# 12 — Phase 3 Report: Financial Modules

Date: 2026-10-22. Scope delivered in five slices, each built on the Phase 2 posting engine: payables and
budget control (3a), receivables, billing and the concessionaire portal (3b), treasury and bank
reconciliation (3c), fixed assets (3d) and inventory accounting (3e).

> **All master data in development is placeholder.** The chart (`PLC-` codes), cost centres, tax codes,
> asset categories and their useful lives, the bank account and the warehouse are labelled
> `[PLACEHOLDER]` and loaded only by the `dev` profile seeder or by tests. Production rejects placeholder
> accounts. Every rate, life, threshold or form layout that is MIAA or government policy is configuration
> or an open clarification, never a hard-coded value.

## 1. Delivered

| Slice | Backend | Frontend | Migrations |
|---|---|---|---|
| 3a Payables, budget | `accountspayable/*`, `budgeting/*`, `tax/TaxCodeService`, `treasury/BankAccountService`, `approvals/DocumentWorkflow`, `generalledger/AccountMappingService`, `PostingService.postSubledger` | Suppliers, invoices, DVs (certify, approve, issue check / ADA, confirm, post), payables reports (aging, AP-GL recon, withholding schedule), budgets, finance settings | V9, V10 |
| 3b Receivables, portal | `accountsreceivable/*` (customers, contracts, billing, receipts, reports, portal) | Customers (contracts, portal access, statement), billing (manual and contract runs), collections, AR reports, concessionaire portal | V11, V12 |
| 3c Treasury | `treasury/*` (deposits, DCR, cash position, check register, statement import CSV/MT940/camt.053, reconciliation) | Deposits, daily collections, bank statements, reconciliation workbench, cash position and checks | V13, V14 |
| 3d Fixed assets | `fixedassets/*` (categories, AUC, register, depreciation, disposals, PPE schedule, FA-GL recon) | Asset register, registration, asset detail with disposal, depreciation runs, setup, PPE reports | V15, V16 |
| 3e Inventory | `inventoryaccounting/*` (items, warehouses, receipts/issues/counts/transfers at WAC, movements, GR/IR, recon) | Stock on hand, item movements, stock documents, inventory reports with GR/IR matching | V17, V18 |

Totals now: 196 backend classes, 34 backend test classes, 89 frontend source files, 7 Playwright specs.

## 2. Controls and where they are enforced

| Control | Where |
|---|---|
| Every sub-ledger document reaches the ledger only through `PostingService.postSubledger`: balanced, period-checked, numbered, immutable, idempotent per source document (unique source type + reference), and posted by someone other than the preparer | `journal/PostingService`, DB unique index `uq_je_subledger_source`, immutability triggers |
| Preparer ≠ approver ≠ poster on supplier invoices, DVs, billings, receipts, deposits, reconciliations, asset activation, depreciation runs, disposals and inventory documents | Service checks + `DocumentWorkflow` + role-level SoD rules (V10–V18) |
| Collections deposited once; allocations never exceed open balances; credit memos never exceed what is open and uncommitted | Row locks + partial unique indexes (`uq_deposit_item_live_receipt`) + service checks |
| Budget reservation from submission to posting; blocks or warns by configuration | `budgeting/BudgetControl` (`cas.budget.enforcement`) |
| Concessionaires see only their own posted records; other customers' ids answer 404 | `PortalService` resolves the customer from `users.customer_id`; `PortalIT` |
| Bank statements must balance, match the account, not repeat or overlap; reconciliation submitted only at zero difference; matches frozen after submission | `StatementImportService`, `BankReconciliationService` |
| XML statement import refuses DOCTYPE (XXE) | `Camt053Parser` |
| Depreciation never charged twice and caught up when a month is missed; final month absorbs rounding | `DepreciationMath`, unique index `uq_depreciation_asset_period` |
| Inventory never negative; average cost pool never keeps a rounding residue; movements immutable | `InventoryDocumentService`, table CHECKs, `trg_inventory_movements_immutable` |
| Sub-ledgers reconcile to their control accounts | AP, AR + unapplied, FA cost and accumulated depreciation, inventory and GR/IR reconciliation endpoints; each integration test asserts the difference is unchanged by its postings |

## 3. Tests executed and actual results

| Suite | Result |
|---|---|
| Backend unit + integration (`mvn test`, PostgreSQL 18.6) | **111 run, 0 failures** (Phase 3 adds `PayablesIT`, `BudgetControlIT`, `BudgetConcurrencyIT`, `ReceivablesIT`, `PortalIT`, `StatementParsersTest`, `TreasuryIT`, `DepreciationMathTest`, `FixedAssetsIT`, `InventoryIT`) |
| Budget concurrency (exit gate) | `BudgetConcurrencyIT`: six simultaneous submissions of 2,000 against a 10,000 allotment — exactly five reserve, one is refused, reservations total exactly 10,000; reservations on two budget lines taken in opposite orders complete without deadlock. Mutation check: with the allocation row lock removed the first test fails (six reserve), so it detects the race. |
| Frontend typecheck, lint, production build | pass (bundle > 500 kB warning; route-level code splitting still pending) |
| Frontend unit (Vitest) | 23 passed (no new Phase 3 unit tests; Phase 3 screens are covered by Playwright) |
| Playwright, live stack | each Phase 3 spec passed on its final run: `payables`, `receivables` (billing → collection with tax withheld → reconciliation → portal), `treasury` (deposit → statement import → auto-match → two-person approval), `fixed-assets` (register → activate → depreciate → dispose), `inventory` (receipt → issue at average cost) |

## 4. Defects found and fixed during this phase

1. Bean cycle between `DocumentService` and owner policies — resolved with lazy lookup.
2. DV numbering failed without a fiscal year — numbered by calendar year.
3. Disbursement officer could not attach documents — permission added (V10).
4. Dev seeder broke on comment lines of the placeholder chart CSV (found when restarting the dev server).
5. Reconciliation: matches were frozen by statement rather than by the reconciliation that made them, so a
   correct new match against an older bank line could not be undone — matches now record their reconciliation.
6. Fixed assets: `DepreciationService` read a field through a Spring proxy (always null), silently using
   same-month depreciation — replaced with a method; caught by `FixedAssetsIT`.
7. SQL built from text blocks lost a trailing space (`ANDcoalesce`) — fixed.
8. Inventory: storekeepers could not list suppliers for goods receipts (found by the live e2e) — read access granted.

## 5. Not delivered / known limitations

* Roadmap items moved out of Phase 3: organisation/office API (FR-ORG-01), open-item clearing (FR-GL-17),
  WebAuthn, and foreign currency (C-21; all modules are PHP-only).
* Posted receipts, deposits, depreciation and inventory movements cannot yet be reversed from their source
  document; correction is by a new document or a journal voucher.
* Pending policy (clarification register): official receipt series and DCR layout (C-29), credit-limit
  policy (C-27), contract proration and escalation (C-28), depreciation policy (C-30), bank file variants
  (C-11), BIR 2307 print layout (C-06), DV vs payment (C-09), budget definitions (C-10), role matrix (C-08 —
  including the new proposed INVENTORY_CLERK role).
* Not built: AR receipt reversal; revaluation/impairment and partial disposal of assets; inventory price
  changes, purchase orders and three-way match; reorder levels; stale-check policy.
* Go-live opening balances (open invoices, outstanding checks, asset accumulated depreciation, stock) are
  Phase 5 migration work; until then first reconciliations warn when ledger and source differ.
* Docker images, Compose and CI are still not executed on this host; tests ran against PostgreSQL 18
  (Flyway officially supports up to 17).

## 6. Criteria to approve Phase 4

1. Finance walkthrough of each module with the placeholder data, by the people who will hold each role.
2. Decisions or dated plans for C-06, C-08, C-09, C-10, C-11, C-27–C-30.
3. CI green on a Docker-capable runner.
