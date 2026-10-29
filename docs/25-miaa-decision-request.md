# 25 — Decisions and Files Requested from MIAA

**To:** MIAA Project Management Office, for distribution to the offices named below
**Subject:** Open decisions for the Computerized Accounting System (CAS)
**Status:** 29 October 2026. Every buildable gap in the TOR is built ([22](22-tor-gap-analysis.md)); the remaining work
waits on the answers below.

## How to answer

* Each item has an ID from the [clarification register](03-clarification-register.md). Please answer in writing,
  quoting the ID, from the office named. A short "agreed as proposed" is enough where CAS already does what is proposed.
* Until an item is answered, CAS keeps the interim behaviour described, and nothing is decided silently in code.
* Where a file is requested (a chart, a layout, a schedule), send the file itself; a description is not enough to
  build against.
* **Needed before** is the project gate the answer blocks. Items needed **before UAT** come first: UAT scripts cannot
  be finalised while they are open.

## Summary by office

| Office | Items | Earliest need |
|---|---|---|
| Chief Accountant (with COA, Internal Audit, Commercial, Tax, Procurement, Treasury) | C-06, C-07, C-08, C-12, C-19, C-22, C-23, C-28, C-29, C-30, C-31, C-32, C-33, C-35, C-36 | Before UAT |
| MIAA management, Chief Accountant and PMO | C-38 | Before UAT scripts |
| Finance Manager | C-24, C-27 | Before UAT |
| Budget Division | C-10 | Before UAT |
| Treasury and partner banks | C-09, C-11, C-21 | Before UAT |
| Records Officer and Legal (with the COA resident auditor) | C-05, C-13 | Before purge is enabled |
| MISD and Information Security | C-02, C-03, C-04, C-16, C-17, C-20, C-25, C-26, C-34 (with the Chief Accountant) | Infrastructure design |
| HR/Payroll, Budget Division (FMIS), Property and Supply | C-37 | Before interface testing |
| Commercial Department and Accounting | C-18 | Before portal go-live |
| Procurement and PMO | C-01, C-14, C-15 | Contract |

## Files to send

These unblock the most work. Each is described in its item below.

1. MIAA's own sub-accounts under COA's codes, and confirmation of the proposed account flags (C-22).
2. The signed role and permission matrix (C-08).
3. Approval amount limits per role and document type (C-24).
4. The close calendar: tasks, owners and due days (C-33).
5. Each depository bank's statement and payment file layouts (C-11).
6. The payroll, FMIS and asset-system export layouts (C-37).
7. A profile of the legacy data: systems, years and record counts (C-12).
8. The current records disposition schedule for accounting records (C-13).
9. BIR's EIS technical specification and MIAA's registration status (C-06).

## Chief Accountant

| ID | Decision or file needed | What CAS does until then | Needed before |
|---|---|---|---|
| C-07 | Confirm PFRS as the framework (MIAA's 2023 COA audit report says so) and the statement formats COA expects: lapsing schedules, notes. | Statements of Financial Position, Comprehensive Income, Changes in Equity and Cash Flows on COA's chart headings; groupings proposed. | UAT |
| C-22 | MIAA's sub-accounts under COA's Revised Chart of Accounts for Government Corporations (2019); confirm the proposed statement groupings, cost-centre and budget flags, sub-ledger types and the 15 nearest-match moves marked `confirm` in `seed/plc-to-rca-gc-2019.csv`. | COA's 1,140 accounts are loaded in development with proposed flags. | UAT |
| C-08 | Sign off the role and permission matrix (who creates, reviews, approves, posts, reverses, reopens), with Internal Audit. | Seeded roles are proposals. | UAT |
| C-19 | Confirm who approves grants of roles that carry financial approval permissions. | Built as proposed: a second approver from Finance Manager or Chief Accountant. | UAT |
| C-23 | The official voucher number format, with COA. | `JEV-YYYY-NNNNNN`, gap-free per fiscal year; prefix configurable. | Printing |
| C-28 | Contract billing rules, with Commercial: proration of partial months, escalation, percentage-of-sales rent, late-payment penalties. | A full month for any month a contract covers; other rules entered as manual invoices or debit memos. | UAT |
| C-29 | The official receipt series and form, with Tax: BIR-registered ORs or a CAS acknowledgement. | `RCPT-YYYY-NNNNNN`, gap-free; not an official receipt. | Production |
| C-30 | Depreciation policy per asset class: useful lives, residual values, start month (acquisition or next), disposal-month charge; whether assets are carried at cost or at revalued amounts (PAS 16), and how often they are revalued. | Straight-line monthly from the month after acquisition; no charge in the disposal month; class lives are placeholders. Impairment is available; revaluation is built but off until MIAA confirms the revaluation model. | UAT |
| C-31 | Supplier and contractor advances, with Procurement: which are allowed, how they are recouped, approvals, monitoring of unliquidated balances. | Advances outstanding at cutover load into a register that reconciles per supplier; CAS does not pay or liquidate advances. | Go-live |
| C-32 | Payment scheduling, with Treasury: how payment dates are scheduled and approved. | Narrowed by the TOR: invoices record the Request for Payment and RIAR numbers, DVs the Request for Payment number, scans as attachments. Invoices carry due dates. | UAT |
| C-33 | The close calendar: day-end, month-end, quarter-end and year-end tasks, the owning role of each, how many days after each is due, and whether holidays shift day-end tasks. | Four [PLACEHOLDER] tasks; the real calendar is entered in **Close calendar**, not coded. | UAT |
| C-06 | BIR, with the BIR liaison: MIAA's registration (Acknowledgement Certificate under RMC 5-2021), the EIS technical specification, the Permit to Transmit, and whether concessionaire statements of account are official BIR documents. E-invoicing by CAS users is due by 31 December 2026 (RR 11-2025 as extended by RR 26-2025). | A sandbox EIS adapter and transmission log; tax codes are [PLACEHOLDER]. | Production |
| C-12 | Legacy data, with MISD: source systems, years of history to migrate versus keep read-only, record counts. | Opening balances (GL, AR, AP, assets, stock) load through CAS templates with two-person approval. | Migration week 1 |
| C-35 | Confirm that Annex B.8 work-in-progress and production cost variances, and B.5 "company code" filtering, can be dropped: MIAA is a single GOCC with no production. | Not built. | UAT |
| C-36 | Confirm whether a machine-learning component is required for remittance extraction and cash application (B.6, B.11). | Rule-based matching covers the outcome. | UAT |

## MIAA management, Chief Accountant and PMO

| ID | Decision or file needed | What CAS does until then | Needed before |
|---|---|---|---|
| C-38 | Since 14 September 2024 New NAIA Infra Corp. operates NAIA under a concession, with MIAA as regulator. Confirm whether the TOR predates the concession, and restate which billing, receivables, concessionaire portal and FMIS functions remain MIAA's. Confirm whether the concession receipts (upfront, annual fixed, revenue share) are billed and received in CAS. | Billing, receivables and the portal as the TOR describes them. | UAT scripts |

## Finance Manager

| ID | Decision or file needed | What CAS does until then | Needed before |
|---|---|---|---|
| C-24 | Approval amount thresholds (extra workflow steps above an amount) and approval limits per role and document type. | Supported; none set, so approvals are unlimited. | UAT |
| C-27 | Customer credit policy: whether exceeding the credit limit blocks billing or only warns, and who may override. | A warning to the approver; no block. | UAT |

## Budget Division

| ID | Decision or file needed | What CAS does until then | Needed before |
|---|---|---|---|
| C-10 | The meaning of reservation, commitment and obligation in MIAA's practice, the COB line structure (DBM Corporate Budget Circular No. 24), overhead allocation keys, and any forecasting method beyond due dates. | Available = allotment − (reservations + obligations + actuals not yet linked to an obligation). Allocation cycles and the cash forecast are built; keys are entered, not coded. | UAT |

## Treasury and partner banks

| ID | Decision or file needed | What CAS does until then | Needed before |
|---|---|---|---|
| C-09 | Confirm the DV states. | Built as proposed: APPROVED → PAYMENT_INSTRUCTED (check issued or ADA) → PAID → POSTED. | UAT |
| C-11 | Each depository bank's statement and payment file layouts (for example the bank's LDDAP-ADA upload format), and confirm that CAS exports files only and never executes payments. | Statement import in CSV, MT940 and CAMT.053; payment-file export; no live payment execution. | UAT |
| C-21 | Currency and FX policy: rate source, revaluation, rounding account. | PHP only. | UAT if foreign-currency transactions are in scope |

## Records Officer and Legal

| ID | Decision or file needed | What CAS does until then | Needed before |
|---|---|---|---|
| C-13 | The retention period per record class from the current National Archives disposition schedule (NAP General Circulars 1 and 2 of 2009, or the newer General Circular No. 5 of 2023 if it replaces them), and the disposal procedure CAS must follow. | 10-year retention per fiscal year, legal holds, and a two-person archive export with checksums. | Purge |
| C-05 | With the COA resident auditor: confirm purge by whole sealed archive segments under dual approval, with a purge certificate in the audit trail. | No purge: posted records and the audit trail stay immutable. | Purge |

## MISD and Information Security

| ID | Decision or file needed | What CAS does until then | Needed before |
|---|---|---|---|
| C-04 | Hosting: MIAA's own facility, GovCloud or an accredited provider, given Philippine data residency and a geographically separate DR site; MIAA's data classification under DICT's Cloud First Policy. | Deployment, DR and rollback kits are provider-neutral. | Infrastructure design |
| C-26 | Document storage: database or object storage. | PostgreSQL, swappable. | Infrastructure design |
| C-03 | Confirm how 99.9 % monthly uptime is measured. | Proposed: a public health probe every 60 s, excluding announced maintenance of at most 4 hours a month. | SLA annex |
| C-02, C-17 | Confirm the performance acceptance criteria. | Proposed: 5 s at the 95th percentile, server-side; sub-second median as a goal for saving and posting a voucher; external calls measured apart (`perf/targets.json`). | Performance test plan |
| C-16 | Approve the e-mail relay for one-time codes, and name an SMS gateway or confirm SMS is not needed. | Authenticator apps and passkeys; e-mail codes built, off until a relay is approved; no SMS. | Production |
| C-20 | Confirm the 10-second refresh-token reuse window, or require strict rotation. | 10 seconds. | Production |
| C-25 | The approved anti-malware engine for uploads. | Uploads are marked NOT_SCANNED; a ClamAV-compatible scanner plugs in. | Production |
| C-34 | With the Chief Accountant: ticket severities S1–S4, the service catalogue of categories, and whether severity affects the SLA. | [PLACEHOLDER] severities and six categories, maintained in the application; only priority drives the SLA. | UAT |

## HR/Payroll, Budget Division (FMIS), Property and Supply

| ID | Decision or file needed | What CAS does until then | Needed before |
|---|---|---|---|
| C-37 | The payroll summary, FMIS and asset-system export layouts (fields, format, transfer method) and how each maps to accounts. See also C-38: some FMIS billing may now be the concessionaire's. | Accepts CSV, JSON and XML with proposed field names ([admin manual](manuals/admin-manual.md) §8.2). | Interface testing |

## Commercial Department and Accounting

| ID | Decision or file needed | What CAS does until then | Needed before |
|---|---|---|---|
| C-18 | How concessionaire users are identified and linked to their account. | Self-registration is off; an internal user links a portal user to a customer and a second person approves. | Portal go-live |

## Procurement and PMO

| ID | Decision or file needed | What CAS does until then | Needed before |
|---|---|---|---|
| C-01 | The number of laptops: 12 in the narrative or 15 in the cost tables (Annex D). | No software dependency. | Hardware procurement |
| C-14 | Confirm post-warranty maintenance is priced as a separate optional line. | — | Contract |
| C-15 | Annex G's resolution commitments, to check against the warranty SLA and escalation procedure. | SLA timers are configurable per contract phase. | Ticketing go-live |

## Closed or narrowed

* **C-00** resolved 27 October 2026: the TOR was received and compared ([22](22-tor-gap-analysis.md)).
* **C-32** narrowed by the TOR: Request for Payment and RIAR references are built; only payment scheduling remains.
