# 14 — Phase 4 Report: Reports and Integrations

Date: 2026-10-22. Scope delivered in five slices (plan: [13-phase-4-plan.md](13-phase-4-plan.md)):
financial reporting engine (4a), management reports and audit export (4b), integration framework (4c),
ticketing (4d) and concessionaire portal onboarding (4e).

> **All master data, statement templates, SLA policies and integration settings in development are
> placeholder.** Nothing in this phase is a BIR form or schema, a bank file layout, a statutory statement
> format or an MIAA SLA value. Where those are needed the system holds configuration and the gap is an open
> clarification (C-06, C-07, C-11, C-15, C-18).

## 1. Delivered

| Slice | Backend | Frontend | Migrations |
|---|---|---|---|
| 4a Financial reporting | `financialreporting/*`: versioned statement templates (two-person activation), Statement of Financial Position and of Financial Performance with prior-year comparative, balance check and unmapped-account list; report jobs (SKIP LOCKED worker, retention, SHA-256, owner-only audited download); CSV / XLSX / PDF exporters | Financial statements, My reports | V19 |
| 4b Management reports, audit export | `ManagementReportService`: budget vs actual, AP and AR aging, PPE schedule, inventory valuation, audit-trail and security-log export with row hashes and chain verification | Report requests on each module's reports page; My reports | V20 |
| 4c Integration framework | `integrations/*`: transmission log (idempotency key, attempts, exponential backoff, dead letter, manual retry/cancel, stuck-send recovery); BIR EIS port with DISABLED (default) / LOOPBACK (dev only, refused in prod) / SANDBOX; `SupplierBankService` (AES-GCM, two-person verification); `PaymentFileService` (CAS generic CSV, issues ADA instructions through the DV controls) | Integrations monitor, supplier bank accounts, payment files | V21, V22 |
| 4d Ticketing | `tickets/*`: incidents, service requests, change requests, enhancements; P1–P4; SLA policies as configuration; `BusinessClock` (business hours or 24x7, holidays); pauses on hold or awaiting approval; at-risk and breach escalation via the outbox; append-only history; SLA compliance summary | Tickets list, detail, new ticket, SLA settings | V23, V24 |
| 4e Portal onboarding | `PortalAccessService`: link requests with evidence and attachments, second-person decision, notification on approval; immediate removal kept | Customer page request form and request list, Portal access page | V25 |

Totals now: 217 backend source files, 42 backend test files, 101 frontend source files, 10 Playwright specs.

## 2. Controls and where they are enforced

| Control | Where |
|---|---|
| A statement template becomes active only when someone other than its author activates it; versions are kept | `StatementTemplateService` |
| A statement shows whether it balances and lists every account without a statement line code, so an unmapped account cannot silently drop out | `FinancialStatementService` |
| A report job checks the permission of its own module when requested and again when downloaded; only the requester downloads; every download is audited | `ReportJobService`, `ManagementReportService.permissionFor` |
| Exports keep text as text; CSV cells beginning with `= + - @` are neutralised | `ReportExporters`, `ReportExportersTest` |
| Audit exports carry each row's hash and state whether the whole chain verified at export time; size capped (`cas.reports.audit-row-limit`) | `ManagementReportService`, `AuditService` |
| Each document is transmitted at most once per destination; every attempt is recorded; failures back off and end in a dead-letter state that needs a person | `integration_transmissions` unique idempotency key, `TransmissionService` |
| The simulated EIS mode cannot run under the production profile; there is no production EIS mode | `BirEisAdapter` |
| Payee bank details are encrypted at rest, shown as the last four digits, and unusable until a second person verifies them; verifiers may not release payments | `SupplierBankService`, SoD rules (V22) |
| Only approved vouchers enter a payment file, and creating the file issues each one's ADA instruction through the normal DV checks, so a voucher cannot go into a second file | `PaymentFileService`, `DisbursementService.instruct` |
| Change requests are approved by neither the requester nor the assignee; ticket history is append-only | `TicketService`, `trg_ticket_events_immutable` |
| SLA clocks pause while a ticket is on hold or awaiting approval; breaches are recorded permanently and escalated | `TicketService`, `BusinessClock`, `TicketSlaMonitor` |
| A concessionaire account sees a customer only after a second person approves the link, with the evidence recorded; the requester can never decide | `PortalAccessService`, DB CHECK `decided_by_id <> requested_by_id`, `PortalIT` |

## 3. Tests executed and actual results

| Suite | Result |
|---|---|
| Backend unit + integration (`mvn test`, PostgreSQL 18.6) | **127 run, 0 failures** (Phase 4 adds `FinancialReportingIT`, `ManagementReportsIT`, `ReportExportersTest`, `IntegrationsIT`, `BackoffTest`, `PaymentFileIT`, `TicketsIT`, `BusinessClockTest`, and onboarding cases in `PortalIT`) |
| Frontend typecheck, lint | pass |
| Frontend unit (Vitest) | 23 passed (no new unit tests; Phase 4 screens are covered by Playwright) |
| Playwright, live stack | Full suite (11 tests in 10 specs) after 4e: 10 passed, 1 failed (`integrations`: test defect, see §4 item 6). After the fix, `integrations.spec.ts` passed on its own rerun. Phase 4 specs: `reporting` (2), `integrations`, `tickets`, `receivables` (now with the two-person portal link) |

`PortalIT` onboarding cases: a non-concessionaire user is refused (400); a chief accountant without
CUSTOMER_MANAGE cannot request (403); evidence under 10 characters is refused (400); a duplicate pending
request is refused (409); a pending request grants no portal access; the same person cannot request and
approve (403); the preparer cannot decide (403); approval links the user (200); a decided request cannot be
decided again (409); the approval e-mail is queued; a user already linked cannot be requested for another
customer (409).

## 4. Defects found and fixed during this phase

1. 4a: the live run found three test bank accounts without a statement line code. The statement listed them
   as unmapped and reported itself out of balance by exactly their total, as designed. The treasury e2e now
   assigns a line code and the dev data was corrected.
2. 4a: the PDF and XLSX libraries pulled an older commons-io; it is pinned to 2.21.0.
3. 4b: the navigation e2e expected security reviewers to see no reports menu; they now see My reports for
   the security-log export, and the expectation was corrected.
4. 4d: a confirm button labelled "Close" sat next to the dialog's own Close button (found by the live run);
   it now reads "Close ticket".
5. 4e: the request list imported a download helper that does not exist (caught by the typecheck); it uses
   the existing document download.
6. Test defect found by the full e2e run: the payment-file test clicked the first file in the list before its
   new file appeared, and downloaded the file an earlier run had left. It now selects the row created by
   its own cashier.

## 5. Not delivered / known limitations

* **Statutory content (C-07):** only the engine is verified. The official statement layouts, cash flow
  statement, statement of changes in equity and notes are not built; cash flow and changes in equity need
  movement-based lines.
* **BIR EIS (C-06):** no EIS schema mapping, authentication or production transmission. The adapter sends
  the CAS canonical document, which is explicitly not the EIS format. Sandbox evidence for the exit gate
  needs BIR's specification and Permit to Transmit.
* **Bank files (C-11):** payment files are the CAS generic CSV, not any bank's layout. No bank API.
* **SLA values (C-15):** none are built in; dev seeds placeholder policies flagged as such. Auto-close of
  resolved tickets, ticket attachments and a ticket export job are not built.
* **Portal onboarding (C-18, C-08):** the proofing procedure (what evidence is enough) and the approving
  role are proposals. Online payment in the portal is not built.
* **ERP APIs:** not built; waiting for named consumers.
* Scheduled (recurring) report runs are not built; reports run on request.
* Route-level code splitting is still pending (bundle size warning).
* Docker images, Compose and CI have still not been run on this host; tests ran against PostgreSQL 18
  (Flyway officially supports up to 17).

## 6. Exit gate status

| Gate item (roadmap) | Status |
|---|---|
| Reports match MIAA reference results | Met for engine correctness on placeholder data only; content needs C-07 and MIAA's approved chart and templates |
| Cross-tenant tests pass | Met: `PortalIT` (isolation since 3b, onboarding in 4e) |
| Sandbox transmission evidence | **Not met**: needs C-06 |

## 7. Criteria to approve Phase 5

1. Finance and IT walkthrough of reports, integrations, ticketing and portal onboarding with placeholder data.
2. Decisions or dated plans for C-06, C-07, C-11, C-15 and C-18, and the role matrix (C-08), including
   who holds PORTAL_ACCESS_APPROVE.
3. Acceptance that the sandbox-transmission gate item carries into Phase 5 until BIR's specification arrives.
4. CI green on a Docker-capable runner.
