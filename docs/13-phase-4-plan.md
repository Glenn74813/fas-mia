# 13 — Phase 4 Plan: Reports and Integrations

Date: 2026-10-22. Phase 3 exit gate met (see [12-phase-3-report.md](12-phase-3-report.md)).

Phase 4 per the roadmap covers statutory and management reports, report jobs and exports, the
concessionaire portal, the BIR EIS sandbox adapter, bank files, ERP APIs and ticketing. Several of these
depend on external specifications MIAA has not supplied, so the plan separates what can be built and
verified now from what must wait. Nothing below fabricates a BIR form, schema or endpoint, a bank file
layout, or a statutory statement format.

| Slice | Scope | Buildable now? | Depends on |
|---|---|---|---|
| **4a Financial reporting engine** (delivered 2026-10-22: V19, `financialreporting/*`, Financial statements and My reports pages; `FinancialReportingIT` 2 tests, `reporting.spec.ts` live) | Statement templates as versioned configuration (lines mapped to the chart's statement line codes, totals, sign); Statement of Financial Position and of Financial Performance with comparative period, balance check and an "unmapped accounts" list; background report jobs; CSV / XLSX / PDF export with audited downloads | Yes — the engine. The official statement layouts are configuration loaded later | C-07 (framework and templates) |
| 4b Management reports and audit export (delivered 2026-10-22: V20, `ManagementReportService`; `ManagementReportsIT`, `ReportExportersTest`, `reporting.spec.ts`) | Budget vs actual (by allocation), AR/AP/inventory/FA reports as jobs and exports; audit-trail export (FR-AUD-03) | Yes | C-10 for budget presentation |
| 4c Integration framework (delivered 2026-10-22 within the stated limits: V21–V22, `integrations/*`, supplier bank accounts, ADA payment files; `IntegrationsIT`, `PaymentFileIT`, `integrations.spec.ts`) | Outbox-based transmission log with retries and idempotency; adapter port for BIR EIS (sandbox only, disabled until the spec and PTT exist); generic payment-file export for ADA | Framework yes; EIS payload and bank layouts **no** | C-06, C-11 |
| 4d Ticketing (delivered 2026-10-22: V23–V24, `tickets/*`; `TicketsIT`, `BusinessClockTest`, `tickets.spec.ts`) | Incidents, service requests, change requests, enhancements; configurable SLA timers and escalation | Yes, with SLA values as configuration | C-15 |
| **4e Portal onboarding** (delivered 2026-10-22: V25, `PortalAccessService`, Portal access page; `PortalIT`, `receivables.spec.ts`) | Internal-approver linkage workflow for concessionaire accounts (today: CUSTOMER_MANAGE links by username) | Yes | C-18 |
| ERP APIs | Versioned read APIs for downstream systems | Interface only until consumers are named | — |

Exit gate (roadmap): reports match MIAA reference results; cross-tenant tests pass (portal: done in 3b);
sandbox transmission evidence (needs C-06). Reference results require MIAA's approved chart and
templates, so with placeholder data the gate can only be met for engine correctness, not for content.

## Progress

* 4a delivered. Live check found three test bank accounts without a statement line code; the statement
  listed them as unmapped and reported itself out of balance by exactly their total, as designed. The
  treasury e2e now assigns a line code, and the dev data was corrected. Cash flow statement and statement
  of changes in equity are not yet templated (they need movement-based rather than balance-based lines).
* 4b delivered. Report jobs now check the permission of the report's own module (budget, AP, AR, fixed
  assets, inventory, audit or security log) when requested and again when downloaded, so a revoked
  permission also blocks fetching an older file. Text cells are exported as text in every format; CSV
  cells starting with = + - @ are neutralised. Audit exports are capped (`cas.reports.audit-row-limit`,
  default 100,000 rows) and state whether the full hash chain verified at export time.
* 4c delivered within its limits. Transmission log and worker, BIR EIS port (no production mode exists;
  dev runs LOOPBACK, clearly marked simulated). Payment files needed payee bank details, which CAS did not
  hold: supplier bank accounts are now stored AES-GCM encrypted (last four digits shown), a replacement
  withdraws the old account at once, and nothing can be paid until a second person verifies the new
  details (SoD: verifiers may not release payments). Creating a file issues each voucher's ADA
  instruction through the normal DV controls. The file layout is the CAS generic CSV, not a bank's (C-11).
* 4d delivered. No SLA values are built in; dev seeds placeholder policies flagged as such. The live
  run caught a confirm button labelled "Close" next to the dialog's own Close button; it now reads
  "Close ticket".
* 4e delivered. The direct "link account" action is gone: linking is a request carrying the evidence
  relied on, decided by a second person (PORTAL_ACCESS_APPROVE, proposed for the chief accountant). The
  database itself refuses a decision by the requester. Removal stays immediate and single-person, since
  it only reduces access. Phase 4 report: [14-phase-4-report.md](14-phase-4-report.md).
