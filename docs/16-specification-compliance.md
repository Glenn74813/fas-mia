# 16 — Compliance with the development specification

Date: 2026-10-23. This review compares the repository with the master development specification (the
original prompt, §1–§29). MIAA's TOR itself has not been supplied (C-00); the only TOR on the development
machine belongs to another agency's procurement and was not used.

**Method.** Four independent read-only reviews went through every requirement (each bullet or table row) of
§1–4 and §6–8, §5 and §9–10, §11, and §12–29. Each was checked against the code, migrations, configuration and
tests, not against this project's own claims. Each requirement was rated:
* **Complies**
* **Partly** (the missing part is named)
* **Not yet**
* **Blocked** (waiting for a named clarification)
* **Process/contract** (not a software deliverable)

The detailed review tables are summarised below. Where this document and an older report disagree, this one
is current.

## 1. Verdict by section

| § | Area | Verdict | Main shortfalls |
|---|---|---|---|
| 1 | Objective | Mostly complies | Built on a restatement of the TOR, not the TOR itself (C-00) |
| 2 | Technology stack | Mostly complies | JDBC persistence now recorded in ADR-006; Playwright in CI (not yet run); no Mockito; no OTLP exporter; no CD pipeline; object storage not implemented (C-26) |
| 3 | Architecture | Complies for separation; partly for scalability | Per-instance rate limiting; some batch work synchronous; only dev and CI environments exist |
| 4 | Scope | 14 of 26 items complete, 11 partly, 1 blocked (C-07) | Controlling, revaluation/impairment/transfers, inventory price changes, archival (organisation administration added in §3f) |
| 5 | Accounting controls | Complies | Close schedule and day/month/quarter/year-end checklists built (§3h); the real close calendar is C-33; JEV print form pending C-23; FX pending C-21 |
| 6 | Database | Rules comply; entity list partly | About 20 named entities have no equivalent (§3 below); retention and archival not built (C-05, C-13). Data dictionary and ERD now cover V1–V41 (docs/04, generated) |
| 7 | Backend architecture | Partly | Layering not enforced; one controller with SQL; no domain events or mappers; consistency of payment allocation not documented |
| 8 | API | Mostly complies | Paging, idempotency keys and a deprecation policy added (§3c, docs/17). Remaining arrays are bounded by design and listed in docs/17 §3 |
| 9 | Security | Mostly complies | Gap 6 added log masking, file encryption, general rate limiting and an SMTP sender (§3d). Still open: malware engine (C-25), access reviews, key rotation |
| 10 | Audit | Mostly complies | Actor roles and security alerting added (§3d). WORM export and retention not built (C-05, C-13) |
| 11 | Functional modules | Core complete; many secondary features missing | See §3 below |
| 12 | Frontend | Mostly complies | Role dashboards and charts added (§3a); approval queue only for vouchers; no printable JEV/DV; sorting on one page only |
| 13 | Migration | Tooling complies; process blocked | Plan, mapping, test migrations and cutover need C-12 |
| 14 | Integrations | Partly | Only bank files and BIR (sandbox) exist; no service-to-service authentication; no failure alerts |
| 15–16 | Infrastructure, backup and DR | Design and dev kit only | Production backups, encryption, monitoring, failover runbooks (C-04) |
| 17 | Performance | Kit complies; acceptance pending | Workstation results only; volumes (C-12) and thresholds (C-02) to be agreed |
| 18 | Testing | Mostly complies | Gap 12 added the e2e, injection, XSS and log tests (§3j). Open: the penetration test (MIAA) |
| 19 | DevOps | Partly | Deployment and rollback guide written (docs/19). Open: CI never executed; no CD pipeline or release versioning |
| 20 | Regulatory | Partly | Compliance matrix expanded to 65 rows (docs/18). PPSAS, DBM/GCG reporting await C-07; ISO mapping is high-level |
| 21–25 | Governance, SLA, training, IP, budget | Mostly process/contract | Manuals and SBOM written (docs/manuals, docs/sbom). Open: exit/export mechanism, cost model, training material |
| 26 | Clarification register | Complies | — |
| 27 | Deliverables | Mostly complies | Gap 13 delivered the documentation set (§3k). Open: training material |
| 28 | Coding rules | Complies after one fix (§2) | Exact-decimal rule was broken on one page, now fixed |
| 29 | Initial execution | Complies | Phase 5 closing report written (docs/20) |

## 2. Fixed during this review

| Finding | Fix | Verified by |
|---|---|---|
| The close checklist still reported bank, sub-ledger and depreciation checks as "module not yet implemented", although those modules exist (§5.4) | Real waivable checks: `BANK_RECONCILIATIONS` (approved reconciliation through the period end for every active bank account with activity), `DEPRECIATION_POSTED` (no asset with depreciation due), `AP_RECONCILED`, `AR_RECONCILED`, `FA_RECONCILED`, `INVENTORY_RECONCILED`. Each check is contributed by its own module | `PeriodCloseIT` (checks present, placeholder gone); full suite 135 tests, 0 failures |
| The dev-only sign-in shortcut accepted the *default* profile. `spring.profiles.default` is `dev`, so a server started without any profile would have skipped two-step verification for `admin.dev` (§9.1) | The shortcut now requires `dev` to be explicitly active (and never `prod`) | `DevMfaBypassTest`, `AuthFlowIT` |
| The stock-on-hand page summed money as floating point (§28 rule 9) | Exact-decimal sum through `utils/money` | typecheck, lint |
| The README said "Phase 2", and the architecture doc listed packages that do not exist (§28 rule 16) | Both updated | — |

Remaining test gap: the new close checks are covered for presence, not for each pass/fail case. The shared test
database holds other tests' assets and balances, so outcomes depend on run order.

## 3. Gaps that can be built now (no MIAA decision needed)

Ordered by value to MIAA's users and auditors.

> **Progress (23 Oct 2026):** items 1–3 are built, with one exception: the variance analysis report is still
> open. Details are in §3a below.

1. **Dashboards and charts** (§11.3, §11.8, §12): role-specific dashboards with revenue, collection, payables,
   budget-utilisation and cash indicators. The KPI *definitions* need MIAA's approval, but the mechanism and
   obvious ledger-derived figures do not.
2. **Missing reports and exports** (§11.4, §11.8):
   * a Cash Disbursement Journal and a collection summary;
   * GL and subsidiary-ledger reports as exportable jobs, plus the DCR, bank reconciliation statement and
     withholding schedule as jobs;
   * a variance analysis report and scheduled (recurring) report runs;
   * customer, supplier and cost-centre filters on report jobs.
3. **Concessionaire portal** (§11.3): download billing documents; submit payment references and inquiries.
4. **Approvals and notifications** (§8, §12): one approvals inbox across vouchers, invoices, DVs, billings,
   deposits, assets and budgets, plus in-app notifications. **Built** (§3b).
5. **API completeness** (§6.3, §8):
   * pagination on the about 43 unpaged list endpoints;
   * idempotency keys on receipts, payment instructions and allocations;
   * an API deprecation and compatibility policy.

   **Built** (§3c).
6. **Security hardening** (§9.4, §10):
   * log masking;
   * application-level encryption of stored documents;
   * general API rate limiting;
   * alerts on HIGH-severity security events and dead integrations;
   * an SMTP e-mail sender for production (the only sender today logs in dev);
   * the actor's role recorded on audit events.

   **Built** (§3d).
7. **Access governance** (§9.3): periodic access reviews (the table exists; no service or UI); per-role approval
   limits (the values await C-24); maker-checker for financial role grants (C-19).

   **Built** (§3e).
8. **Organisation administration** (§4 item 18): API and screens for organisations, offices, departments and user
   assignments (tables exist since V1).

   **Built** (§3f).
9. **Fixed assets and inventory** (§11.5, §11.7): asset transfers between units with history; an annual run mode;
   inventory price adjustment (revaluation of the average-cost pool); a movement import interface.

   **Built** (§3g).
10. **General ledger** (§11.1, §5.4): open-item clearing (FR-GL-17); a closing schedule; day-end and quarter-end
    checklists.

    **Built** (§3h).
11. **Tickets** (§11.11, §22): attachments, severity and category, requester closure confirmation; release notes,
    staging validation and rollback plan on change requests.

    **Built** (§3i).
12. **Tests and CI** (§18, §19):
    * e2e tests for budget validation, period closing and auditor read-only access (the change-request flow is covered since gap 11);
    * SQL injection, XSS and sensitive-log tests;
    * Playwright and backend static analysis in CI;
    * a real CI run once the repository exists.

    **Built** (§3j), except the real CI run, which needs the repository.
13. **Documentation** (§6.2, §23, §24, §27):
    * the data dictionary and ERD for V9–V29, and an updated threat model;
    * end-user and administrator manuals, and a production deployment and rollback guide;
    * a third-party licence inventory (SBOM), an expanded compliance matrix, and an ADR for the JDBC-based
      persistence;
    * the Phase 5 closing report.

    **Built** (§3k).
14. **Statement engine** (§11.8): movement-based statement lines, so a cash flow statement and a statement of
    changes in equity can be configured. Their *formats* still await C-07.

    **Built** (§3l).

## 3a. Gaps 1–3: what was built

| Gap | Built | Verified by |
|---|---|---|
| 1. Dashboards | `GET /api/v1/dashboard` returns only the sections the user's permissions allow. **Cash** (TREASURY_VIEW): bank and on-hand book balances, and undeposited collections. **Receivables** (AR_VIEW): aging, top customers, 6 months of collections, unapplied credits. **Payables** (AP_VIEW): aging, amounts due within 30 days, top suppliers, 6 months of disbursements. **Budget** (BUDGET_VIEW): allotment, reserved, actual, available and highest utilisation for the current approved budget. **Tickets**. A **"Waiting for you"** work queue counts items the user may act on, excluding their own documents (segregation of duties). Concessionaires are refused. Figures come from posted records; the dashboard says the indicators are proposed until MIAA approves its KPIs. Charts are hand-built SVG: one series each, a validated colour palette, hover or focus tooltips with the exact amount, and a table view | `DashboardAndSchedulesIT`; Playwright `reporting.spec.ts` |
| 2. Reports and exports | Seven new job types: Cash Disbursement Journal, collection summary, general ledger (account and cost-centre filters), subsidiary ledger (one account or a whole SL type), daily collection report, bank reconciliation statement, and expanded withholding schedule (by quarter). AP and AR aging take a supplier or customer filter. Each type is gated by its module permission at request and download. Ledger reports are capped by `cas.reports.ledger-row-limit`. **Scheduled runs** (`/api/v1/report-schedules`): daily, weekly or monthly in Manila time, with relative-date tokens resolved at run time. Each due run queues an ordinary job for the owner. A schedule suspends itself, with an audit event, if the owner is inactive or has lost the permission | `OperationalReportsIT`, `DashboardAndSchedulesIT`; Playwright `reporting.spec.ts` |
| 3. Portal | PDF of the concessionaire's own posted billing and statement, marked "not an official invoice or receipt" (C-06, C-29). Each download is audited. Inquiries (`INQ-YYYY-NNNNNN`): questions and payment references, with optional proof attached. A payment reference records nothing in the ledger, and the cashier still posts the receipt. Staff holding PORTAL_INQUIRY_RESPOND (Accountant, Cashier) reply on **Receivables → Inquiries**, and the concessionaire is e-mailed. Another customer's billing or inquiry is reported as not found | `PortalSelfServiceIT`; Playwright `receivables.spec.ts` |

Still open from these items:
* a variance analysis report beyond budget vs actual (its layout needs MIAA input);
* the KPI definitions (dashboard indicators remain "proposed");
* e-mailing a finished scheduled report (today it appears in **My reports**);
* online payment through the portal.

## 3b. Gap 4: approvals inbox and notifications

| Part | Built | Verified by |
|---|---|---|
| Inbox | **Inbox** in the main menu (`/inbox`, `GET /api/v1/inbox`) lists every document across modules waiting for a step the user may take, oldest first, filterable by queue. It covers vouchers, invoices, DVs, billings, budgets and adjustments, reconciliations, receipts, deposits, assets, depreciation, disposals, inventory, period reopen requests, portal access, inquiries and migration. Each source applies its module's own rule: workflow documents go through the same `canAct` check as the document page, and the user's own documents are never listed. It replaces the voucher-only Approvals page, and `/approvals` redirects to it. The dashboard's "Waiting for you" is the inbox grouped by queue, so the two always agree | `ApprovalInboxIT` (segregation of duties, queue moves after approval, every source's query runs for a user holding all roles); Playwright `journal-voucher.spec.ts` |
| Notifications | A bell with the unread count in the internal app and the portal. The preparer hears when someone else approves, returns (with the comment), rejects, pays or posts their document. Requesters hear the outcome of reopen, portal-access, budget-adjustment and migration requests. Concessionaires hear when an inquiry is answered. Report owners hear when a report finishes or fails, or a schedule is suspended. Notifications are written in the same transaction as the change, are never sent for one's own action, and skip the automatic ledger entry of a sub-ledger document | `ApprovalInboxIT`; Playwright `journal-voucher.spec.ts` |

Each source lists at most its oldest 200 items (500 for journal vouchers), and the page says when a queue was cut.
A search by number, description, counterparty or preparer runs on the server before that limit, so any waiting
document can be found. The dev database holds about 1,500 submitted load-test vouchers from the performance kit, which
is how the limit showed up.

Not done: bulk approval (deliberately: each document is reviewed on its own page), e-mail digests, and a retention
period for notifications (C-05). Queue counts on the dashboard stop at the per-source limit.

**Bug found and fixed along the way:** a parallel run crashed with a server error whenever an earlier fiscal year had
not been closed. The trial balance carries that year's result as a row without an account, which the comparison did
not expect. On the closing basis it is now a CAS-only line coded `UNCLOSED`, which must be explained (or the year-end
close posted) before sign-off; V33 allows that line. Covered by
`ParallelRunIT.theUnclosedResultOfPriorYearsIsACasOnlyLineOnTheClosingBasis`.

## 3c. Gap 5: API completeness

| Part | Built | Verified by |
|---|---|---|
| Paging | 23 collections that grow with use now return `{ items, page, size, totalItems, totalPages }`, paged in the database. Before, several were silently cut at 100–1,000 rows. They are: customers, suppliers, inventory items, subsidiary accounts and fixed assets (all searchable with `q`); budgets, recurring journals, depreciation runs, disposals, item movements, inventory documents, deposits, bank statements, bank reconciliations, payment files, tickets, report jobs, integration transmissions, portal inquiries (both sides), portal link requests, migration batches and parallel runs. Forms now choose customers, suppliers, items and subsidiary accounts through a search picker (`SearchSelect`) instead of loading the whole list. The 32 endpoints that still return arrays are classified in docs/17 §3 (configuration, children of one record, work sets that must be complete, report outputs) | `ApiConventionsIT.collectionsArePagedAndSearchedOnTheServer`, the updated module ITs, Playwright (all specs) |
| Idempotency keys | `Idempotency-Key` is now required on recording a receipt, applying a receipt to invoices, a DV payment instruction and an ADA payment-file batch, as it already was on journal posting. A retry replays the stored response. A reused key with a different request is a 422. Concurrent retries are serialised by a per-key lock in the same transaction. The SPA keeps one key per user action, and the perf kit and e2e send keys | `ApiConventionsIT.aRetriedReceiptIsRecordedOnce…`, `…RequireAKey` |
| Deprecation policy | docs/17: compatible and breaking changes, a six-month notice after go-live, and the `Deprecation` / `Sunset` / `Link` headers set by `@ApiDeprecation`, with every call logged. First use: `GET /journal-vouchers/approval-queue`, replaced by `/inbox` (sunset 2027-04-30). The perf kit now uses the inbox | `ApiConventionsIT.aDeprecatedEndpointAnnouncesItsSunsetAndSuccessor` |

On the inventory items page, the "total inventory" footer is a true total only on a single page. With more items it
says it is the page total and points to the inventory valuation report.

## 3d. Gap 6: security hardening

| Part | Built | Verified by |
|---|---|---|
| Log masking | `LogMasker` masks JWTs, Authorization headers, password, token, OTP and cookie values, Philippine TINs, account numbers (last four kept) and e-mail addresses (first letter and domain kept). It is applied to every message and stack trace: through `logback-spring.xml` converters in plain-text logs (dev, test), and through a structured-logging value processor in the ECS JSON production logs. Only the dev profile's logged e-mails are exempt, so reset links stay usable locally | `LogMaskerTest` (including the real ECS encoder) |
| Encryption of stored files | Supporting documents, generated report files and ADA payment files (which carry payee bank account numbers) are encrypted by the application with AES-256-GCM. Each ciphertext is bound to its row. A column records how each row is stored (V35), and `StoredContentEncryptionJob` encrypts files stored before, in audited batches. Reported file sizes exclude the 29 bytes of overhead | `DocumentsAndRecurringIT`, `FinancialReportingIT`, `PaymentFileIT` |
| API rate limiting | Every API call counts against a limit per user, or per client address when unauthenticated (600 / 120 per minute by default). Counts are kept in one-minute windows in an UNLOGGED table, so the limit holds across all instances. Responses carry `RateLimit-*` headers; a refusal is a 429 with `Retry-After`. If the store fails, calls are allowed. The sign-in limit per address is unchanged | `ApiRateLimitIT` (two filters sharing one database) |
| Alerts | A HIGH-severity security event (account lock, refresh-token reuse, …) alerts every active user holding SECURITY_EVENT_VIEW. A dead or rejected integration transmission alerts INTEGRATION_MANAGE holders. Alerts go in the app, by e-mail, to the `cas.alerts` metric for external paging, and to a WARN log line. One alert is sent per key and quiet period (15 minutes for security, an hour per channel), and repeats are counted into the next. Failing e-mail delivery alerts in the app only | `SecurityAlertsIT`, `IntegrationsIT` |
| SMTP sender | `SmtpEmailSender` delivers the outbox over SMTP with mandatory STARTTLS and server-identity checks. Credentials come from the environment, and it is off unless `CAS_SMTP_ENABLED=true` with `CAS_MAIL_FROM` set. Every template is plain text with absolute links. Subjects are stripped of line breaks and addresses parsed strictly, so model values cannot add headers or recipients | `SmtpEmailSenderTest` |
| Actor's role | Audit and security events record the actor's roles (V34): the roles the request carried, or the user's assignments for work done on their behalf. The roles join the hash chain only when present, so rows written before still verify. They appear on the audit page and in the audit-trail export | `SecurityAlertsIT.eventsRecordTheActorsRolesAndTheChainStillVerifies` |

Open: rotation of the data encryption key (one key today; a re-encryption job like the backfill would apply), the
relay provider and sender address (C-16), and which events beyond HIGH severity MIAA wants alerted.

## 3e. Gap 7: access governance

| Part | Built | Verified by |
|---|---|---|
| Maker-checker for financial access (C-19, as proposed in the register) | Any role change that adds a role carrying financial permissions waits for a second person holding ACCESS_GRANT_APPROVE (proposed: Finance Manager, Chief Accountant). This applies to new users too, whose other roles apply at once. Neither the requester nor the user concerned can decide it, and a database CHECK enforces the same. Approval re-checks that the user's roles are unchanged and that the combination passes SoD. Requests appear in the inbox (**Administration → Access grants**), and the requester is notified of the outcome. When nobody can approve yet (first setup), the grant applies and a HIGH security event alerts the reviewers. On by default; production refuses to start with it off. The dev profile turns it off so the e2e runs can create users in one step (`CAS_FINANCIAL_GRANT_APPROVAL=true` turns it on). New SoD rules stop administrators from also approving grants or setting limits | `AccessGovernanceIT.financialRolesWaitForASecondPersonsApproval` |
| Approval limits (C-24) | `approval_limits` holds a maximum per role and document type: journal vouchers, supplier invoices, DVs, billings, memos, budgets and bank reconciliations. A user may approve up to the highest limit among their roles that have one; a user none of whose roles has one is not limited. Enforced on the server for both the document workflow and journal vouchers, and over-limit documents drop out of the approver's inbox and actions. Set by APPROVAL_LIMIT_MANAGE (proposed: Finance Manager), audited, and visible to auditors and role viewers (**Administration → Approval limits**). No amounts are seeded | `AccessGovernanceIT.approvalLimitsBindApproversByRole` |
| Access reviews (FR-RBAC-08) | ACCESS_REVIEW_MANAGE (proposed: Internal Auditor) starts a cycle that snapshots every active internal user's roles, financial access first. ACCESS_REVIEW_PERFORM holders record retain, modify or revoke, never for their own access, and must explain a change. User administrators apply changes through the normal role screens and record them; the server checks the roles actually changed, or for a revoke that the account has no roles or is inactive. A cycle closes only when every review and change is done. Reviews and changes appear in the inbox (**Administration → Access reviews**) | `AccessGovernanceIT.anAccessReviewCycleClosesOnlyWhenEveryReviewAndChangeIsDone` |

Open: MIAA's sign-off of who approves grants, reviews access and sets limits (C-08, C-19), the limit amounts
(C-24), how often reviews run, and an export of a closed cycle for the auditors.

## 3f. Gap 8: organisation administration

| Part | Built | Verified by |
|---|---|---|
| Structure | **Administration → Organisation** (`/api/v1/organizations`, offices, departments) maintains MIAA's offices and departments under the seeded organisation. Codes are validated and unique, and fixed once created. Names and status can change. A unit cannot be deactivated while active units or assigned users sit under it. A department can name the cost centre its work is usually charged to (V38). Every change is audited. Holders of the new ORGANIZATION_ADMINISTER (proposed: System Administrator) maintain it; USER_VIEW holders can read it. None of MIAA's actual structure is seeded | `OrganizationIT` |
| User assignments | Each internal user has at most one current office (and optional department) assignment, enforced by a unique index. A transfer closes the previous assignment the day before the new one starts; an assignment can be ended, and the history is kept. Shown and changed on the user's page; users can read their own | `OrganizationIT` |

Not yet used: routing documents or defaulting cost centres by the preparer's office or department. That needs MIAA's structure and rules first.

## 3g. Gap 9: fixed assets and inventory

| Part | Built | Verified by |
|---|---|---|
| Asset transfers | Transfer an asset in service to another cost centre, location or custodian (V39). One person prepares it and a different person posts it; it appears in the inbox. Posting checks the asset is unchanged since preparation. When the cost centre changes and the category's asset or accumulated-depreciation account carries cost centres, posting reclassifies the cost and accumulated depreciation in the ledger; otherwise it updates the register only. The history is kept per asset, and a pending transfer blocks a disposal and vice versa | `FixedAssetsIT` |
| Cost centres on depreciation and disposal (fix) | Depreciation credited accumulated depreciation, and disposal credited cost and accumulated depreciation, without the asset's cost centre. With a chart where those accounts require cost centres, both postings would have been rejected. They now apply the same rule as acquisition | `FixedAssetsIT` |
| Annual depreciation mode | `cas.fixed-assets.depreciation-frequency=ANNUAL` accepts a run only in the fiscal year's last regular period, where the monthly calculation catches up the whole year. MONTHLY stays the default; the policy is C-30 | `AnnualDepreciationModeIT` |
| Inventory price adjustment | A `REVALUATION` document restates an item's average-cost pool at a new unit cost, without changing quantities. The difference posts between the inventory account and the new INVENTORY_REVALUATION mapping, after a second person reviews it. One line per item; an item without stock cannot be revalued. The movement shows in the item's history without a warehouse | `InventoryIT` |
| Movement import | Upload a CSV of lines for any document kind: `item_code`, `warehouse_code` and `quantity`, plus `unit_cost` for receipts (and optionally counts) and `to_warehouse_code` for transfers; a price adjustment needs only `item_code` and `unit_cost`. Every row is validated, with problems reported by row number, and nothing is created on any error. The result is a normal draft, posted by a second person. The file's SHA-256 is audited | `InventoryIT` |

The development seeder maps INVENTORY_REVALUATION to the placeholder count-adjustments account; MIAA's chart must
supply the real one.

## 3h. Gap 10: open-item clearing and the close calendar

| Part | Built | Verified by |
|---|---|---|
| Open-item clearing | An account administrator marks a postable asset or liability account as open-item managed (V40). Every posted line on it stays open until it is cleared with other lines of the same subsidiary account that net to zero. Clearing posts nothing and never changes a posted line. Manual clearing picks the lines; automatic clearing proposes groups by reference (the same line description, ignoring case) or by amount (an equal and opposite pair, oldest first), shows them first and applies them only when asked. A clearing can be reversed with a reason, which re-opens its items. One clearing per account runs at a time (advisory lock), and a line is in at most one active clearing (unique index). New permission GL_CLEARING (accountants, chief accountants) | `ClearingIT` |
| Aged open items | A waivable period-close check OPEN_ITEMS_AGED lists items open more than 90 days at the period end | `ClearingIT`, `PeriodCloseIT` |
| Close checklists | One checklist per day, period, quarter or fiscal year. Each combines automatic checks (listing the exceptions) with the manual tasks of the close calendar. Day end: posted collections not yet deposited, recorded collections not posted, paid DVs not posted, dead or rejected transmissions. Month end: the period-close checks. Quarter end: the three months closed, bank reconciliations at the quarter end, the expanded withholding schedule produced. Year end: all months closed, the closing entry posted, the fiscal year closed, the sub-ledgers reconciled | `CloseCalendarIT`, e2e `close-calendar.spec.ts` |
| Close tasks | Each task names the permission responsible for it and a due offset after the scope's end. Only a holder of that permission ticks it off, once per scope, as done or not applicable, with a note; it is audited. Chief accountants maintain the tasks (CLOSE_TASK_MANAGE). Four [PLACEHOLDER] tasks are seeded until MIAA supplies its close calendar (C-33) | `CloseCalendarIT`, e2e `close-calendar.spec.ts` |
| Closing schedule | The due date and status of every task occurrence over a range of up to three months; day-end tasks fall on weekdays (public holidays are not modelled). Open tasks due in the last seven days appear in the owner's inbox, overdue ones marked | `CloseCalendarIT`, e2e `close-calendar.spec.ts` |
| Sub-ledger close check (fix) | When a control account was not mapped, the sub-ledger reconciliation failed inside the period-close transaction. The check reported "not available", but the transaction had already been marked rollback-only, so closing the period failed with an internal error. It appeared only when the test ran alone. Each reconciliation now runs in its own read-only transaction | `PeriodCloseIT` (run alone) |

## 3i. Gap 11: tickets

| Part | Built | Verified by |
|---|---|---|
| Severity and category | Every new ticket gives a severity (S1–S4, business impact, separate from the priority that drives the SLA) and an active category (V41). Support reclassifies with a reason, recorded in the history. The list filters by both. Categories are maintained by TICKET_ADMIN on the service-levels page; six [PLACEHOLDER] categories and the severity wording await MIAA's service catalogue (C-34). Tickets raised before V41 keep no severity or category | `TicketsIT` |
| Attachments | The requester and support attach screenshots and files (the shared document store: type and signature checks, malware-scan status, encryption at rest, audited download) until the ticket is closed, rejected or cancelled. Attachments appear in the ticket history. Others cannot see the ticket or its files | `TicketsIT`, e2e `tickets.spec.ts` |
| Closure confirmation | Support resolves; only the requester closes, which confirms the resolution, with an optional 1–5 satisfaction rating. A resolution neither confirmed nor reopened within the reopen window (`cas.tickets.reopen-days`, 7) closes automatically and is marked unconfirmed | `TicketsIT`, e2e `tickets.spec.ts` |
| Change control | A change request or enhancement needs a rollback plan to be submitted for approval; the approver sees it. After work starts it must be validated on staging by the requester or an approver, never the assignee, before it can be resolved; resolving needs release notes and optionally a release reference. A reopened change must be validated again | `TicketsIT`, e2e `tickets.spec.ts` |

## 3j. Gap 12: tests and CI

| Part | Built | Verified by |
|---|---|---|
| Static analysis | SpotBugs 4.9 with the FindSecBugs 1.14 rules, bound to `mvn verify` (so it runs in CI) and failing on any unreviewed finding. The first run reported 657 findings. 582 were EI_EXPOSE_REP/EI_EXPOSE_REP2, which are noise for records and Spring beans. All 28 SQL_INJECTION_SPRING_JDBC sites were reviewed one by one: the concatenated parts are constants, code-chosen table or column names, or clauses of `?` placeholders, and user input is always bound. Fixed: constant-time comparison of idempotency request hashes and migration file hashes, an explicit charset in TOTP checking, four `catch (NullPointerException)` blocks replaced by null checks, and an unboxing-reboxing. Every remaining suppression in `backend/spotbugs-exclude.xml` carries its reason | `mvn verify`: 0 open findings |
| SQL injection | Eight classic payloads (tautologies, stacked queries, UNION, comment and wildcard tricks) against every free-text search, filter and sort parameter on 17 endpoints, and the sign-in identifier. No server error, no widened result, no schema change | `SqlInjectionIT` |
| XSS | Stored markup comes back as inert JSON with nosniff and a CSP without `unsafe-inline` scripts or `unsafe-eval`. Errors are `application/problem+json`. HTML, SVG, JavaScript, and HTML disguised as PDF are refused as uploads; file names are sanitised; downloads are attachments with an explicit type. In the client, markup in data renders as text, and a source scan forbids `dangerouslySetInnerHTML`, `innerHTML`, `insertAdjacentHTML`, `document.write`, `eval`/`new Function` and `javascript:` URLs | `XssIT`; Vitest `security/xss.test.tsx` |
| Sensitive data in logs | Sign-in (failed and successful, with MFA), password recovery and reset, and entry of a supplier TIN and bank account, with application and Spring Security logging at DEBUG. The captured output contains none of the passwords, TOTP secret, reset token, access or refresh tokens, TIN or account number | `SensitiveLogIT` |
| E2E: budget validation | A budget officer prepares a budget. Duplicate account/cost-centre lines and mixed account-level and cost-centre lines are refused with the reason. The officer cannot approve their own budget; a finance manager does. A realignment larger than the source's available budget cannot be approved; one within it updates both allotments. Runs in a future fiscal year (one budget per year; development uses WARN enforcement, so the BLOCK policy stays covered by `BudgetControlIT`) | Playwright `budget.spec.ts` |
| E2E: period close | A month of a future fiscal year is opened and closed only after every failing waivable check is waived with a justification. Posting into it is then refused. Reopening is requested by the chief accountant, who cannot approve it, and approved by a finance manager | Playwright `period-close.spec.ts` |
| E2E: auditor read-only | The internal auditor's menu includes every financial area but no administration or migration. Every page in it, plus a voucher and an invoice, shows no control that changes data. Direct API writes (voucher, supplier, customer, budget, period close, cost centre, fiscal year) answer 403 | Playwright `auditor-readonly.spec.ts` |
| Spec robustness (fixes) | `fixed-assets.spec.ts` now ignores periods that have not started (the period-close spec leaves a future month open after its reopen step). `close-calendar.spec.ts` waits for the managed-account list before deciding whether to add GR/IR | Playwright |
| CI | `ci.yml` gains an `e2e` job: PostgreSQL service, the backend jar with the dev profile, run-only secrets, `scripts/e2e-bootstrap.mjs` to enrol the bootstrap administrator, the Vite server, then all Playwright specs, with reports and logs uploaded on failure. Images now wait for e2e. The YAML parses; the workflow itself has not run, because there is no repository yet | YAML parse only |

## 3k. Gap 13: documentation

| Document | Content | Verified by |
|---|---|---|
| [04-data-model.md](04-data-model.md) | Conventions; migration index V1–V41; Mermaid ERDs for 15 domains; data dictionary of all 112 tables with columns, constraints, partial indexes and triggers | Generated from the migrated database and migration comments by `scripts/docs/gen-data-dictionary.py`, so it cannot drift from the schema when rerun |
| [05-security-design.md](05-security-design.md) | Sections 1–5 corrected: dev MFA bypass, cluster-wide API limit, 14 roles, 136 SoD rules by family, financial-grant maker-checker. §6: context and trust boundaries, assets and actors, STRIDE by component with implementing classes and tests, OWASP Top 10 mapping, abuse cases, open risks | Every cited class and test was checked to exist |
| [18-compliance-matrix.md](18-compliance-matrix.md) | 65 requirements across identity, data protection, audit, the Data Privacy Act (general obligations), government accounting and tax, availability, and quality and delivery | Statuses counted from the table |
| [19-deployment-and-rollback.md](19-deployment-and-rollback.md) | Artefacts, configuration reference of every `CAS_*` variable, secrets, first deployment, release procedure tied to the ticket change control, smoke checks, expand/contract migration rule, application and data rollback, checklist | Configuration taken from `application*.yml`; not exercised in a production-like environment |
| [manuals/](manuals/README.md) | User manual (sign-in, inbox, every area, portal) and administrator manual (users, access governance, organisation, finance configuration, close tasks, service levels, integrations, audit) | Screen labels extracted from the frontend source; uncertain points checked against the code |
| [sbom/](sbom/README.md) | CycloneDX 1.6 SBOMs (backend 137 runtime / 177 with tests; frontend 37 shipped / 264 with tooling), licence summary, weak-copyleft list for legal review | Regenerated with the recorded commands; counts reproduced |
| [adr/ADR-006-jdbc-persistence.md](adr/ADR-006-jdbc-persistence.md) | Why JdbcTemplate is the main persistence approach (22 JPA repositories remain; 87 classes use JdbcTemplate) | Counts from the source |
| [20-phase-5-report.md](20-phase-5-report.md) | Phase 5 closing report | — |

Findings while writing:
* The RTM had two rows with the id NFR-SEC-08; the gap 12 row is now NFR-SEC-10.
* The external-auditor access expiry can be set only through the users API, not on the user screens.
* `deploy/db/post-migrate-grants.sql` protects only the audit tables at the grant level; the other append-only
  tables rely on triggers (docs/19 §9).
* ADR-004 predates the cluster-wide API limit; the ADR index now says so.

## 3l. Gap 14: movement-based statements

| Part | Built | Verified by |
|---|---|---|
| Statement types | Templates may now be a cash flow statement (SCF) or a statement of changes in equity (SCE), as well as SFP and SFPERF (V42). They are versioned and activated by a second administrator, like the others | `MovementStatementsIT` |
| Line types | OPENING: balance before the fiscal year. CLOSING: balance at the period end. MOVEMENT: the year-to-date change in the accounts of the line's statement line codes, restricted by *counterpart codes*, the statement line codes of the other lines of each entry. Every entry balances, so a change splits exactly by counterpart code, with no apportioning. `*` takes everything the group's other lines do not name. No counterpart codes takes the whole change. SURPLUS on an SCE is the year's result not yet closed | `MovementStatementsIT` |
| Completeness | A part of a change that no line classifies is listed as *unclassified* (on screen, in the notes and in exports), as unmapped accounts are for SFP and SFPERF. Templates are refused when a counterpart code is classified twice, when a whole-change line overlaps classified lines, when `*` is combined with codes, or when a movement names its own codes | `MovementStatementsIT.movementTemplatesAreValidated` |
| Placeholder templates | `PLC-SCF`: direct method, with collections, payments, taxes, PPE, equity and other, and a check that opening cash plus the net movement equals closing cash. `PLC-SCE`: opening, surplus closed to equity, surplus not yet closed, other changes, and a check against equity on the SFP. Seeded in development per code, so existing databases get them. The layouts are illustrative until C-07 | `MovementStatementsIT`, Playwright `reporting.spec.ts` (both BALANCED on live dev data) |
| Screen and exports | Statement-type column on the templates list; unclassified movements shown in red; exports in CSV, XLSX and PDF through the existing job | `MovementStatementsIT` (CSV), Playwright |

Limits:
* The direct method classifies by the chart's statement line codes. An indirect-method presentation (reconciling
  the surplus to operating cash) is not built.
* The SCE opening balance covers equity accounts only. When an earlier fiscal year has not been closed, its result
  is in the SFP's cumulative surplus but in neither the SCE's opening balance nor the year's surplus.

## 4. Blocked on MIAA decisions

| Clarification | Blocks |
|---|---|
| C-00 TOR text and annexes | Clause-by-clause TOR traceability; Annex F migration plan |
| C-04 Hosting and residency | Production infrastructure, geographic DR, backup storage |
| C-06 BIR registration, EIS | EIS schema mapping, production transmission, printable 2307, other BIR reports |
| C-07 Reporting framework | Official statement formats, notes, PPE lapsing format, COA/DBM/GCG reports |
| C-08 Role matrix | Final role-permission assignments |
| C-10 Budget definitions | Obligation stage, budget versions, forecasting, overhead allocation rules |
| C-11 Bank formats | Bank-specific statement and payment layouts |
| C-12 Migration scope | Legacy mapping, test migrations, realistic performance volumes, the parallel run itself |
| C-13 / C-05 Retention | Archival tiers, legal hold, purge, sealed WORM exports |
| C-15 SLA values | Real SLA policies |
| C-16 OTP providers | E-mail and SMS one-time codes |
| C-21 FX policy | Foreign-currency transactions |
| C-22 Approved chart | Replacing the placeholder chart |
| C-23, C-29 Numbering and receipts | Official voucher and receipt series and printed forms |
| C-24 Approval thresholds | Amount-based approval steps |
| C-25 Malware engine | Scanning uploads |
| C-27, C-28 Credit and contract rules | Credit blocks, proration, escalation, penalties |
| C-30 Depreciation policy | Final useful lives and conventions; revaluation and impairment policy |
| C-31 Supplier advances | Paying and liquidating advances |

Added to the register as **C-32**: RIAR / request for payment and payment scheduling (§11.2). What MIAA means by
these must be confirmed before building them.

## 5. Infrastructure, process and contract

These are the deployment environment, MIAA's activities, or contract terms, not code:
* production and DR infrastructure and monitoring, encrypted and immutable backups;
* the penetration test, UAT execution, training and knowledge transfer;
* monthly health reports and quarterly reviews, warranty support;
* the budget and licensing model and the laptop question (C-01).

The repository already holds the tooling that supports several of these:
* the DR exercise kit;
* the performance kit;
* the UAT scripts with the pass-rate check;
* the parallel-run sign-off.
