# 22 — Gap analysis against MIAA's Terms of Reference

Date: 2026-10-27. Source: `docs/TOR.docx`, MIAA's *Terms of Reference for the Procurement of a Computerized
Accounting System*, with Annexes A–I. This is the first comparison against the TOR itself; until now the system was
built from a restatement of it (the master specification), and the gap was open as **C-00**.

Method: every requirement in TOR §1–§9 and Annexes A, B, E, F, G and H was read and checked against the code,
migrations, tests and documents (not against earlier claims). Annex C (laptops), D (cost) and I (bidder
qualification) are procurement matters and are only noted. Status values:

* **Met**: built and covered by a test or named artefact.
* **Partly met**: the missing part is named.
* **Gap**: not built, and buildable now without a MIAA decision.
* **Blocked (C-xx)**: needs the named MIAA decision first.
* **Contract / MIAA**: a service, document or activity, not software.

## 1. Summary

The core accounting system the TOR describes is in place: GL and SL with financial close, AP and disbursements,
billing and AR with the concessionaire portal, asset accounting with AuC, cash and bank management, budget control,
financial reporting, audit trail, tickets with SLAs, migration and parallel-run tooling. The gaps that remain fall in
three groups:

1. **Buildable now (14 items, §4):** e-mail OTP and passkeys (biometric) as second factors; anomaly detection with
   alerts; cash-flow forecasting; a Budget Division step on DVs against the COB; two-person check preparation and
   approval; overhead allocation and expenditure forecasting; an ad hoc report builder; API clients (OAuth 2.0 client
   credentials, API keys) and webhooks; inbound interfaces for payroll, FMIS and asset summaries, with XML; the TOR's
   retention, archive and purge rules; a full data export for MIAA; lower-of-cost inventory valuation; the abstract
   of bills; and a privacy impact assessment draft.
2. **Still blocked on MIAA (§5):** official chart and statement formats, BIR registration and EIS, bank formats,
   migration data, hosting, and a few others. The TOR **resolves or narrows** several of them (§3).
3. **Contract and MIAA activities (§6):** hosting and DR operation, UAT, parallel run, penetration test, training,
   helpdesk, reports and governance.

## 2. Requirements that are met

| TOR | Requirement | In CAS | Evidence |
|---|---|---|---|
| §3.5.1, B.1 | Journal entry processing, posting, GL/SL, real-time balances, trial balance | Voucher workflow, single posting service, ledger balances, trial balance, account ledger | `JournalWorkflowIT`, `PostingInvariantsIT` |
| B.1 | Automatic and manual clearing; recurring entries | Open-item clearing (gap 10); recurring journals | `ClearingIT`, `DocumentsAndRecurringIT` |
| B.1, B.8 | Day-, month-, year-end closing; automatic period close and year-end | Close checklists and calendar, period close, year-end closing entry | `PeriodCloseIT`, `CloseCalendarIT` |
| §3.5.1, A | DV processing with approval workflow, duplicate-payment prevention, aging of payables, supplier SL, CDJ | Invoices and DVs with certify/approve steps; duplicate supplier-invoice check; aging; CDJ job | `PayablesIT`, `OperationalReportsIT` |
| A | Report of Checks Issued (RCI), ADA references | Check register | `PayablesIT` |
| §3.5.1, A, B.6 | Customer invoicing, collections, payment matching, statement of account, aging of receivables, customer SL, credit memos | Billings, receipts with allocation, SOA, aging, memos | `ReceivablesIT` |
| A | Concessionaire sub-module to view invoices and payment history | Portal with billings, payments, SOA, PDF downloads, inquiries | `PortalSelfServiceIT` |
| B.6 | Credit limits; block billing above the limit | Credit limit check on billing (policy: C-27) | `ReceivablesIT` |
| §3.5.1, A, B.2 | Asset master, acquisition, depreciation (monthly/annual), disposal, AuC with settlement, transfers | Fixed assets, depreciation runs, disposals, AuC, transfers with history | `FixedAssetsIT` |
| §3.5.1, A, B.3 | Bank statement import, automated bank reconciliation, BRS, DCR, cash position | Statement import (CSV, camt.053), matching, reconciliation, DCR, cash position | `TreasuryIT` |
| B.3 | GR/IR reconciliation; payments to banks by file | GR/IR matching; payment files (layouts C-11) | `InventoryIT`, `PaymentFileIT` |
| B.4 | Weighted-average stock valuation; price changes posted to accounts | Average-cost inventory; price adjustment (gap 9) | `InventoryIT` |
| §3.5.1, A, B.7 | Budget plans, spending tracking, budget control, variance analysis, utilisation report | COB with allotments, reservations, BLOCK control; variance analysis (gap 16); budget vs actual | `BudgetControlIT`, `VarianceReportIT` |
| §3.5.1, A, B.5 | Trial balance, financial statements incl. cash flow, configurable templates, dashboards with KPIs | Statement engine (gap 14), dashboards (KPIs proposed) | `FinancialReportingIT`, `MovementStatementsIT` |
| §3.6, §4.3 | Audit trail of all actions, user, time, record, access point; tamper protection; ad hoc audit reports; auditor read-only | Hash-chained append-only audit and security events, export job, auditor roles | `AuditIntegrityIT`, Playwright `auditor-readonly.spec.ts` |
| §4.2 | RBAC with the five named roles; segregation of duties | Finance Manager, Chief Accountant, Accountant, Internal/External Auditor, System Administrator (and Budget, Cashier, others); 136 SoD rules; maker-checker everywhere | `SodPolicyTest`, `UserAdminIT` |
| §4.2 | MFA for all users with authenticator apps | TOTP mandatory, recovery codes (gap 15) | `AuthFlowIT`, `MfaRecoveryIT` |
| §4.2, A.3 | AES-256 at rest (application level); TLS 1.3 minimum | AES-256-GCM for secrets, bank details, files; TLS 1.3 policy enforced | `AesGcmEncryptorTest`, `TlsPolicyTest` |
| §4.4, A.3 | ISO 9001, 27001, 22301, BS 10012, 27017, 27018, DICT MC 5 and DC 007 best practices | Mapped in [18](18-compliance-matrix.md) §9 | 18 §9 |
| §4.6 | REST API, JWT, rate limiting, API documentation, CSV/JSON | Versioned REST API, RS256 JWT, cluster-wide rate limits, OpenAPI, CSV/JSON | `ApiConventionsIT`, [17](17-api-policy.md) |
| §3.7, F | Migration with two test cycles and reconciliation | Migration batches with validation and reconciliation; parallel run comparison and sign-off | `MigrationIT`, `ParallelRunIT` |
| §8.1, §8.2.4 | 20 internal + 60 concessionaire concurrent users; 5 s / 60 s targets | Performance kit with exactly that mix | `perf/` (workstation run; acceptance needs the target environment) |
| B.9 | Customisable approval workflows routed by role; responsive UI on desktop, tablet, mobile | Workflow steps by permission with amount thresholds (C-24); responsive layout | `JournalWorkflowIT`, UI |
| E | Ticketing: Incident, Service Request, Change Request, System Enhancement; SLA tracking; e-mail notices; analytics; staging and rollback for changes | Tickets (gap 11) with these four types, SLA timers, staging validation, rollback plan, release notes | `TicketsIT` |
| §3.5.2, §5.1 | Change control: request, impact, approval, test environment, deployment | Change-request tickets; release workflow (gap 20); deployment guide | `TicketsIT`, [19](19-deployment-and-rollback.md) |

## 3. What the TOR settles in the clarification register

| Item | Before | What the TOR says | Now |
|---|---|---|---|
| C-00 | TOR not supplied | Received with Annexes A–I | **Resolved** |
| C-04 Hosting | Residency vs DR unclear | Fully cloud-based (on-premise/hybrid only if regulation requires); primary data, backups and audit logs in Philippine data centres; multi-region with real-time multi-AZ replication; daily geo-redundant backups; DR tests twice a year; RPO ≤ 1 h, RTO ≤ 4 h; 99.9 % uptime; cross-border transfer only with MIAA's written approval | **Narrowed**: the provider must offer two Philippine regions (or MIAA approves cross-border DR in writing) |
| C-05, C-13 Retention | Rules unknown | Minimum 10 years with fast search; at the end, MIAA may purge manually or export to external archive; only authorised roles may purge; audit logs kept "for the period prescribed by law" | **Policy resolved**; the audit-log period and the effect of purging on the hash chain still need a decision |
| C-14 Warranty | Model unclear | One-year warranty included; post-warranty annual maintenance optional (Annex G) | **Resolved** |
| C-15 SLA values | Values unknown | Warranty: Critical 1 h / 24 h, High 4 h / 1 business day, Medium 8 h / 3 business days, Low 1 business day / by priority. Post-warranty (Annex G): 2 h / 8 h / 24 h / 48 h | **Resolved**: configure the SLA policies with these values |
| C-16 MFA | E-mail/SMS OTP "where approved" | MFA must support OTP via SMS/e-mail, authenticator apps, and biometric login if applicable | **Requirement confirmed**: e-mail OTP and passkeys are buildable (§4); SMS needs a gateway provider |
| C-32 RFP / RIAR | Undefined | Request for Payment and RIAR/Supplier Invoice are AP *source documents* from other offices (Annex A); the CAS takes approved inputs only (R2R, §3.2) | **Narrowed**: record their references and attachments on invoices and DVs; no RFP workflow inside CAS |
| C-34 Tickets | Categories unknown | Incident, Service Request, Change Request, System Enhancement; severities Critical/High/Medium/Low with the definitions above | **Resolved** (CAS already uses these four types) |
| C-02 Performance | Sub-second vs 5 s | Still both: §8.1 says 5 s per entry, 60 s per batch and report; Annex A says "within 1 second" and "sub-second" | **Still open** |
| C-01 Laptops | 12 vs 15 | Still both: §5.3 and the notes say 15; Annex D says twelve | **Still open** |
| C-07 Framework | PFRS vs PPSAS | Still both: PPSAS and GAM throughout, PFRS in §2.2.1 and Annex A | **Still open** |

## 4. Gaps buildable now

| # | TOR | Gap | What to build |
|---|---|---|---|
| T1 | §4.2, A.3 | Second factors besides authenticator apps | **Built**: e-mail one-time codes (V47; on when `CAS_MFA_EMAIL_OTP_ENABLED`, with MIAA's mail relay) and passkeys for biometric login (V48, webauthn4j). SMS OTP still waits for a gateway (C-16). `MfaEmailCodeIT`, `PasskeyIT`, Playwright `passkeys.spec.ts` |
| T2 | §2.2.3 | Real-time fraud detection: anomaly detection and automated alerts | **Built** (V49): hourly rule-based detection over the last 90 days (also on demand): possible duplicate invoices (same supplier and payable amount within 30 days) and payments within 7 days of a supplier bank-account change (HIGH); amounts within 5 % below an approval threshold, entries posted more than 45 days after their accounting date, and periods whose first digits deviate from Benford's law (MEDIUM); postings on weekends, holidays or outside 06:00–20:00, and large round-sum manual entries (LOW). Each finding is recorded once; new HIGH or MEDIUM findings alert `ANOMALY_REVIEW` holders in the app and by e-mail. Auditors review each finding as explained or escalated with a note, never one they are involved in. Per-user baselines ("unusual accounts or amounts for a user") are left for when MIAA has a year of history to learn from. `AnomalyDetectionIT`, Playwright `anomalies.spec.ts` |
| T3 | §3.5.1, B.7 | Cash-flow forecasting; expenditure forecasting | **Built**: **Treasury → Cash-flow forecast** (`CashForecastService`, `/treasury/cash-forecast`), by week (Monday to Sunday) or month for up to 53 periods, opening at the book cash position (banks and cash on hand). Inflows are posted billings still unsettled, on their due dates; outflows are approved DVs not yet posted as paid (on their payment date, else their invoices' earliest due date) and posted supplier invoices still unpaid and not on a live DV, on their due dates; overdue items fall in the first period and are shown apart. The expenditure forecast spreads the approved budget not yet used or reserved evenly over the rest of each fiscal year, and can be left out. Each period lists its documents. A forecasting method beyond due dates (collection history, seasonality) waits for MIAA (C-10). `CashForecastIT`, Playwright `cash-forecast.spec.ts` |
| T4 | A (Controlling) | Budget Division evaluates and approves in-process DVs against the COB | **Built** (V46): a BUDGET step first in the DV chain (`DV_BUDGET_CERTIFY`, Budget Officer, who now also reads payables); each DV shows its share of every budget line it pays with the COB allotment, used and available; budget officers see "Disbursement vouchers to budget" in the Inbox. DVs already in the chain kept their step. `DvBudgetAndCheckIT`, Playwright `payables.spec.ts` |
| T5 | A (Treasury) | Checks for processed DVs prepared and approved | **Built** (V46): a check is PREPARED by the cashier and approved (ISSUED) by another person with `CHECK_APPROVE` (Finance Manager), never the check's or the voucher's preparer (also a database constraint); release is refused until then; "Checks to approve" in the Inbox; preparer and approver on the voucher. Three new BLOCK rules separate these duties. `DvBudgetAndCheckIT`, `PayablesIT`, Playwright `payables.spec.ts` |
| T6 | §3.5.1, B.7, B.8 | Overhead allocation and assessment cycles | **Built** (V56): **General ledger → Overhead allocation**. A cycle's rules each move the period's posted balance of a sender account in a sender cost centre to receiving cost centres by fixed percentages (adding to 100) or statistical quantities, optionally onto a different receiver account; a preview shows the lines; a run creates one draft journal voucher dated the period's end (rounding cents to the largest receiver), once per period unless that voucher is cancelled or rejected. Defined by holders of `ALLOCATION_MANAGE` (chief accountant, accountant). The cycles and keys themselves are MIAA's (C-10). `AllocationIT` |
| T7 | B.5 | Ad hoc reports by accounting staff | **Built** (V57): **Reports → Ad hoc reports** over approved datasets (posted ledger lines, supplier invoices, billings, disbursement vouchers, receipts), each needing its area's view permission: choose columns, conditions, grouping with totals and order; preview 200 rows; save, share with others who may see the dataset, and export as CSV, XLSX or PDF through the report job (`AD_HOC`). Only catalogued columns and fixed operators are used and values are bound parameters. `AdHocReportIT` |
| T8 | §4.6, A.4 | OAuth 2.0 and API key management for third-party access; webhooks | **Built** (V52): **Administration → API access**. API clients get 10-minute tokens from `POST /api/v1/auth/token` (OAuth 2.0 client-credentials grant, secret in HTTP Basic or the form body), limited to read scopes (ledger, payables, receivables, treasury, budgets, assets, inventory, reports); secrets are shown once and stored as Argon2id hashes, rotated or revoked by holders of `API_CLIENT_MANAGE` (chief accountant), with immediate effect; each client has its own rate limit and appears as `api:<client id>` in the trails. Webhooks subscribe an HTTPS URL to posting, approval and payment events read from the audit trail; each delivery carries only the event and document reference, is signed with HMAC-SHA-256, retried with back-off for about 21 hours and logged; private and internal addresses are refused. Write access for clients is left for T9's import endpoints. `ApiAccessIT` |
| T9 | §4.6.2, D | Pre-configured interfaces for payroll, FMIS, HR and asset systems; XML exchange | **Built** (V53): **Administration → Inbound files** and `POST /api/v1/interfaces/{PAYROLL,FMIS_BUDGET,ASSETS}/batches` (CSV, JSON or XML; also by API clients with the `INTERFACE_SUBMIT` scope). Each file is checked into a batch (accounts, cost centres, categories, dates, amounts, balance, budget status) and kept with its SHA-256; the same file is taken once. A person with the document's permission accepts it, which creates drafts through the normal services (payroll: a draft journal voucher; FMIS: the year's draft budget allocations; assets: draft fixed assets acquired as OTHER), or rejects it with a reason. Nothing is posted by an import. XML refuses DOCTYPEs and external entities. HR master data is not imported (CAS holds no employee records). The field layouts are proposals until MIAA supplies each system's (C-37). `InboundInterfacesIT`, Playwright `inbound-files.spec.ts` |
| T10 | §4.5, A.9 | 10-year retention, archive export and authorised purge | **Built except purge** (V51): **Administration → Records retention** shows every fiscal year with its keep-until date (end + `cas.records.retention-years`, 10), holds and archives. A closed year is archived through the two-person data export: its transactions by period or date, their lines, decisions and supporting documents, the year's audit trail and the reference data to read them, with a manifest and checksums; the archive's SHA-256 is recorded as `FISCAL_YEAR_ARCHIVED` in the hash-chained audit trail. Legal holds on a year or on everything are placed and released with reasons by holders of `RECORDS_RETENTION_MANAGE` (chief accountant). **Purge is not built**: posted records and the audit trail stay immutable until MIAA decides lawful disposal (C-05, C-13); the page says so. Write-once storage for archives is MIAA's infrastructure. `RecordsRetentionIT`, Playwright `records-retention.spec.ts` |
| T11 | §3.6 | Full access to MIAA's data; return in a machine-readable format at termination | **Built** (V50): **Administration → Data export**. One holder of `DATA_EXPORT` (system administrator or finance manager) asks with a purpose and another approves; a worker writes, from one consistent snapshot, every table as CSV with `dictionary.json`, every supporting document, the decrypted supplier bank account numbers, the audit-chain verification and `manifest.json` with row counts and SHA-256 per file (also in `SHA256SUMS`) into one ZIP, encrypted at rest in 1 MiB AES-GCM chunks; only the requester downloads it, for 7 days. Sign-in secrets, the replay cache and generated report and bank files are left out, and the manifest says so. `DataExportIT`, `ChunkedFileCipherTest`, Playwright `data-export.spec.ts` |
| T12 | B.4 | Lowest inventory value from market prices, coverage and movement rates | **Built** (V55): **Inventory → Cost vs market review** snapshots each item's quantity and average cost on the review date, its last movement, issues in the past twelve months and months of stock, flagging slow movers (no issues, or coverage above `cas.inventory.slow-moving-coverage-months`, 24); market values per unit are entered or imported; where below cost, **Propose write-down** creates a draft price adjustment (REVALUATION) that is posted as usual. Measurement basis and slow-moving rule are proposals for MIAA's policy (C-07). `InventoryIT` |
| T13 | A (Billing) | Abstract of Bills | **Built** (V54): the **Abstract of bills** report job lists every posted invoice, debit memo and credit memo dated in a period by customer, with subtotals per customer and totals per kind (credit memos negative), as CSV, XLSX or PDF. Layout marked PLACEHOLDER until the Billing Section confirms its form. `OperationalReportsIT` |
| T14 | H | Privacy Impact Assessment | **Drafted**: [23-privacy-impact-assessment.md](23-privacy-impact-assessment.md) from the system as built: data subjects, a personal-data inventory (13 categories, with tables, purpose, access, retention and protection), a data-flow diagram, the privacy principles, 11 risks with controls and proposed actions, and the decisions left to MIAA's DPO (lawful basis, retention, TIN masking, breach procedure, hosting). Completion and sign-off are MIAA's |

Smaller items: record **Request for Payment and RIAR references** on invoices and DVs (C-32); SMS as a ticket
notification channel when a gateway exists (Annex E); **company code and currency** dimensions on reports (B.5:
CAS is single-company and PHP-only; FX is C-21).

## 5. Still blocked on MIAA decisions

| Item | TOR reference | Decision needed |
|---|---|---|
| BIR CAS registration, PTU, e-receipts/e-invoices, EIS transmission (RR 9-2009, 8-2022, 11-2025), 2307 | §2.2.1, §3.10, A, B.10 | C-06: EIS schema and Permit to Transmit; registration with BIR. The sandbox adapter and transmission log are ready |
| BIR monthly VAT, withholding and income tax reports; quarterly COA, annual DBM, GCG reports | §4.5, A, B.5 | C-06, C-07: official formats |
| MIAA's sub-accounts and confirmation of the proposed account flags | §2.2.1 | C-22, C-07. The framework is settled (PFRS) and COA's Revised Chart of Accounts for Government Corporations (2019) is loaded in development (§8) |
| PPE lapsing schedule format; revaluation and impairment | A, B.2 | C-07, C-30 |
| Bank file formats and payment channels | B.3, D | C-11 |
| Legacy data, volumes and migration plan | §3.7, F | C-12 |
| Hosting provider with two Philippine regions | §3.4, §4.1 | C-04 |
| SMS gateway | §4.2 | C-16 |
| Anti-malware engine | §4 (security testing) | C-25 |
| Overhead keys, budget definitions, forecasting method | B.7, B.8 | C-10 (COB rules are public: DBM CBC No. 24; the keys are internal) |
| Billing, receivables, portal and FMIS scope after the NAIA concession (September 2024) | A (Billing), §4.6.2 | C-38 |
| Response-time target (1 s vs 5 s), laptop count | §8.1 vs A; §5.3 vs D | C-02, C-01 |

**Not applicable as written:** Annex B.8 asks for work-in-progress calculations and settlement of *production* cost
variances, and B.5 for "company code" filtering. These are manufacturing and multi-company functions of a general
ERP; MIAA is a single regulatory GOCC with no production. Ask MIAA to confirm they can be dropped (proposed C-35).
The same applies to "machine learning" for remittance extraction and cash application (B.6, B.11): the rule-based
matching in CAS covers the outcome; confirm whether a learning component is required (proposed C-36).

## 6. Contract and MIAA activities

Hosting and its operation (99.9 % uptime, multi-region, DR tests twice a year), UAT with ≥ 95 % pass, the one-month
parallel run, penetration testing with zero high-risk findings, BIR PTU, training (on-site, remote, videos,
train-the-trainer with certification, refresher at 3 months and every 6), helpdesk (business hours, on-site for
critical), monthly health and quarterly compliance reports, the project documents (inception report, design
document, work plan, migration plan, training plan, security and compliance assessment report), governance
(Steering Committee approvals), laptops (Annex C) and the post-warranty agreement (Annex G).

The repository already supports several of these: UAT scripts with the pass-rate gate (`docs/uat/`), the parallel-run
comparison and sign-off, the performance and DR kits, manuals, the threat model as penetration-test input, the
architecture and data model documents, and the compliance matrix as the basis of the security and compliance
assessment report.

## 7. Suggested order

1. ~~T4, T5, T1~~ (built 27 Oct 2026) (e-mail OTP, passkeys): the
   TOR names them directly, and they affect daily use and acceptance testing.
2. ~~T2, T3~~ (built 27 Oct 2026) (anomaly alerts, cash-flow forecast): explicit TOR objectives with clear value for audit and treasury.
3. ~~T11, T14, T10~~ (built and drafted 27 Oct 2026; T10 without purge) (full export, PIA draft, retention and archive): data-ownership and privacy obligations.
4. ~~T8, T9~~ (built 27 Oct 2026) (API clients, webhooks, interfaces), then ~~T6, T7, T12, T13~~ (built 27 Oct 2026).

## 8. Public sources checked (27 October 2026)

The open items in §5 were searched in public sources; the findings and sources are recorded in the
[clarification register](03-clarification-register.md#public-sources-checked-27-october-2026). In short:

* **Framework (C-07): settled.** MIAA's 2023 COA Annual Audit Report states its statements follow PFRS; it reports as
  a government business enterprise (COA Circulars 2015-003 and 2022-003), not under PPSAS. Its statements are the
  Statements of Financial Position, Comprehensive Income, Changes in Equity and Cash Flows, with headings from
  COA's government chart of accounts (Business and Service Income, Personnel Services, Maintenance and Other
  Operating Expenses, Non-Cash Expenses, Subsidy to Other Funds and so on). The account codes are COA's Revised
  Chart of Accounts for Government Corporations (2019), Circulars 2020-002 and 2021-005, now loaded in development
  with statement templates over it; MIAA's sub-accounts and the proposed flags remain to confirm (C-22). The
  lower-of-cost-and-NRV review (T12) matches PFRS.
* **NAIA concession (new C-38).** Since 14 September 2024 New NAIA Infra Corp. operates NAIA; MIAA is the regulator
  and receives an upfront payment, a fixed annual payment and 82.16% of covered gross revenues. If the TOR predates
  this, the billing, receivables, concessionaire portal and FMIS interface scope should be restated, and the
  concession receipts modelled.
* **FMIS (C-37).** "FMIS" is MIAA's own system (contracted 2018, live 2020) for aeronautical and concession billing,
  with COA findings in 2023; its layouts come from its developer or MISD.
* **Budgets (C-10).** COB rules are public (DBM Corporate Budget Circular No. 24, EO 518); allocation keys are internal.
* **Retention (C-05, C-13).** RA 9470 and the National Archives' General Records Disposition Schedule (2009; a
  2023 circular also exists) govern disposal; MIAA's Records Officer must adopt the periods per record class.
* **Banks (C-11).** Government ADA payments use the LDDAP-ADA and SLIIE; each bank's electronic layout is not public.
* **BIR (C-06).** No Permit to Use since RMC 5-2021 (Acknowledgement Certificate instead); e-invoicing by CAS users
  due 31 December 2026 (RR 11-2025 as extended by RR 26-2025), to be confirmed against BIR's text.
* **Hosting (C-04).** DICT's Cloud First Policy (2017, amended 2020) applies to GOCCs; the choice is MIAA's.
