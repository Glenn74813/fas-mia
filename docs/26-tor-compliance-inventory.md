# 26 — TOR compliance inventory

Date: 2026-11-07. Baseline: `main` at `21aa8a2`, plus one uncommitted fix to dropdowns inside dialogs. Source: `docs/TOR.docx`
(the revised BAC copy, `CAS Revised_Final for BAC 07.06.26.docx`, has the same requirements). This re-checks the
[TOR gap analysis](22-tor-gap-analysis.md) of 27 October 2026 against the system as it is now, and looks at every change
made since then for anything that weakens a TOR requirement.

**Left out at the user's request:** the demo accounts signing in without the authenticator code (`4ff3134`, `35fd7aa`).
It is a demonstration setting, off unless the demo start script or the dev profile turns it on.

Status values: **Met** (built, with a test or named artefact); **Met, demo deviation** (met as built; the demonstration
setup departs from it on purpose); **Partly met** (the missing part is named); **Blocked (C-xx)** (waits for an AVTECH
decision); **Contract** (a service or activity, not software).

## 1. Verdict

The CAS as it would be deployed is **still compliant with everything it met on 27 October**, and nothing has
regressed:

* Every test the gap analysis cites as evidence still exists, and the full backend suite passes (see §6).
* Changes since then add to compliance: BIR Form 2307, the QAP, 1604-E, SAWT and VAT lists; impairment and
  revaluation; GAM-style subsidiary ledger cards; letterhead PDF, Excel and CSV exports; a subsidiary-account page.
* **One change departs from the TOR in the demonstration only:** the demo super administrator may approve and post its
  own work (§3.1). It is off by default and cannot be switched on for a real account, but the setting itself is not
  tied to a demo database. §5 proposes closing that.
* **One small gap was found:** the TOR says Accountants "manage general and subsidiary ledger accounts"; in CAS they
  manage suppliers and customers (and so their subsidiary ledgers) but not GL accounts or employee and other
  subsidiary accounts, which need the Chief Accountant (§3.4).
* The items blocked on AVTECH decisions and the contract activities are unchanged (§4).

## 2. Requirements re-checked

| TOR | Requirement | Status | Evidence | Since 27 Oct |
|---|---|---|---|---|
| §3.5.1, B.1 | Journal entries, posting, GL/SL, real-time balances, trial balance | Met | `JournalWorkflowIT`, `PostingInvariantsIT` | JV Excel import and export, date range (`ad3ceb7`) |
| B.1 | Automatic and manual clearing; recurring entries | Met | `ClearingIT`, `DocumentsAndRecurringIT` | — |
| B.1, §2.2 (GAM) | Display GL/SL balances; subsidiary ledgers per GAM | Met (exact form C-07) | `OperationalReportsIT` | Subsidiary ledger now one card per control account and subsidiary, headed by account code and title (`7f5a5e2`) |
| B.1, B.8 | Day-, month- and year-end close | Met | `PeriodCloseIT`, `CloseCalendarIT` | — |
| §3.5.1, A | DVs with approval workflow, duplicate-payment prevention, aging of payables, supplier SL, CDJ | Met | `PayablesIT`, `OperationalReportsIT` | RFP and RIAR references (`b769f3c`); aging as PDF, Excel, CSV (`747ef48`) |
| A | Report of Checks Issued, ADA references; two-person check approval | Met | `PayablesIT` | Approve-check button hidden from the check's own preparer again (`b570618`) |
| A, B.10 | BIR Form 2307 | Met (registration C-06) | `Form2307IT` | Built 3 Nov (`c1dccf1`) |
| §3.5.1, A, B.5 | BIR withholding and VAT lists (QAP, 1604-E, SAWT, SLS, SLP) | Met (file layouts per RMC 7-2021; delimiter C-39) | `BirAlphalistIT`, `BirDatTest` | Built 4 Nov (`32123c3`) |
| §3.5.1, A, B.6 | Invoicing, collections, matching, SOA, aging of receivables, customer SL, memos | Met | `ReceivablesIT` | DCR Excel form, deposit receipt picker (`c1dccf1`) |
| A | Concessionaire portal: invoices and payment history | Met | `PortalSelfServiceIT` | — |
| B.6 | Credit limits | Met (policy C-27) | `ReceivablesIT` | — |
| §3.5.1, A, B.2 | Asset master, depreciation, disposal, AuC, transfers, impairment, revaluation | Met (policy values C-30) | `FixedAssetsIT`, `RemeasurementIT` | Impairment (PAS 36) and revaluation (PAS 16) (`11faf2e`); depreciation and measurement policy set per asset class on the Setup page |
| §3.5.1, A, B.3 | Bank statements, automated reconciliation, BRS, DCR, cash position | Met | `TreasuryIT` | — |
| B.3 | GR/IR; payment files | Met (bank layouts C-11) | `InventoryIT`, `PaymentFileIT` | — |
| B.4 | Weighted-average valuation; lower of cost and market | Met | `InventoryIT` | — |
| §3.5.1, A, B.7, B.8 | Budget control, variance analysis, utilisation; overhead allocation; forecasts | Met (keys C-10) | `BudgetControlIT`, `VarianceReportIT`, `AllocationIT`, `CashForecastIT` | Allocation rules and receivers can be removed (`21aa8a2`) |
| §3.5.1, A, B.5 | Trial balance, statements incl. cash flow, templates, dashboards; ad hoc reports | Met (formats C-07) | `FinancialReportingIT`, `MovementStatementsIT`, `AdHocReportIT` | Every export on the organisation's letterhead (`747ef48`) |
| §3.6, §4.3, Annex B | Immutable, tamper-protected audit trail of all actions; auditor read-only | Met | `AuditIntegrityIT`, `auditor-readonly.spec.ts` | Demo database rebuilt on 6 Nov (§3.3) |
| §4.2 | RBAC with the named roles | Met; partly for Accountants (§3.4) | `SodPolicyTest`, `UserAdminIT` | Users-by-role table (`0a1441c`) |
| §4.2 | Segregation of duties: no single user completes a financial transaction alone | Met, demo deviation (§3.1) | `JournalWorkflowIT`, `DemoSuperAdminOverrideIT`, database two-person constraints | Demo super administrator override (`e9c4524`) |
| §4.2, A.3 | MFA: authenticator apps, e-mail OTP, biometric (passkeys); SMS when a gateway exists | Met (SMS C-16); demo exemption left out | `AuthFlowIT`, `MfaRecoveryIT` | — (see the note at the top) |
| §4.2, A.3 | AES-256 at rest, TLS 1.3 | Met | `AesGcmEncryptorTest`, `TlsPolicyTest` | — |
| §2.2.3 | Fraud detection and alerts | Met | `AnomalyDetectionIT` | — |
| §4.5, A.9 | 10-year retention, archive export, authorised purge | Partly met: purge waits for C-05, C-13 | `RecordsRetentionIT` | — |
| §3.6 | Full export of the client's data | Met | `DataExportIT` | — |
| §4.6, A.4 | REST API, JWT, rate limits, OAuth 2.0 clients, webhooks; inbound interfaces with XML | Met | `ApiConventionsIT`, `ApiAccessIT`, `InboundInterfacesIT` | — |
| §3.7, F | Migration with test cycles; parallel run | Met (data C-12) | `MigrationIT`, `ParallelRunIT` | — |
| §8.1, §8.2.4 | 20 internal and 60 concessionaire users; response times | Met on a workstation; acceptance on the target host (C-02) | `perf/` | — |
| B.9 | Configurable approval routing; responsive UI | Met | `JournalWorkflowIT`, UI | Form alignment across all pages (`372bde5`); dropdowns inside dialogs (uncommitted) |
| E | Ticketing with SLAs, staging and rollback | Met | `TicketsIT` | — |
| §3.5.2, §5.1 | Change control | Met | `TicketsIT`, [19](19-deployment-and-rollback.md) | — |
| H | Privacy impact assessment | Drafted | [23](23-privacy-impact-assessment.md) | — |

## 3. Changes since 27 October that touch a TOR control

### 3.1 Demo super administrator override (§4.2, segregation of duties)

`e9c4524` lets the `DEMO_SUPERADMIN` role approve, post and decide its own work, take every step of an approval chain
and pass approval limits. The TOR (§4.2) says segregation of duties "must be enforced to prevent any single user from
completing an entire financial transaction without appropriate oversight and approval".

* **Real deployments keep the rule.** The override needs `cas.demo.superadmin-overrides-controls`, which is false
  by default and set only by the dev profile and `scripts/run-backend-demo.sh`. The `DEMO_SUPERADMIN` role is created
  only by the dev profile, and user and role administration refuse to create it, change its permissions or grant it.
  Every other account keeps every control, and `DemoSuperAdminOverrideIT` shows the rule applies to the role when the
  setting is off.
* **The database still enforces nine two-person rules for everyone**, the demo super administrator included: check
  approval, portal-link decision, role-grant decision, export decision, budget adjustment decision, period reopening,
  migration apply, parallel-run sign-off and access review.
* **Residual risk:** the setting is read in any profile, so a production server started with
  `CAS_DEMO_SUPERADMIN_OVERRIDES_CONTROLS=true` and a copied demo database would carry the override. See §5.

### 3.2 Demo accounts and passwords

On 6 November the demo database was rebuilt with `superadmin.demo` and one named user per role, all with the password
`password123`, set directly in the database. That is shorter than the 12-character minimum the system enforces when a
password is set, and the change is not in the audit trail. It concerns the demonstration data only; the password
policy itself is unchanged and still applies to every password set through the system.

### 3.3 Demo database rebuild and the audit trail (§3.6, §4.3)

The rebuild dropped the demo database's tables, its audit trail included, after a full backup
(`.devdata/backups/miacas_db-before-reset-2026-11-06.dump`). The TOR requires audit logs "protected from modification
or deletion". That protection holds for the deployment design in [19](19-deployment-and-rollback.md): migrations run as
`cas_owner` and the application as `cas_app`, which may not delete or alter tables. The demo runs as a single owner
account, which is why the rebuild was possible. The demonstration is not a production record, but it should not be
presented as showing the production protection.

### 3.4 Accountants and subsidiary ledger accounts (§4.2 RBAC)

The TOR gives Accountants the right "to input transactions, journal entries and manage general and subsidiary ledger
accounts". In CAS the Accountant creates journal vouchers and maintains suppliers and customers, whose subsidiary
ledgers come with them. GL accounts and employee and other subsidiary accounts need account administration, held by the
Chief Accountant. Either AVTECH confirms that split as its segregation of duties, or Accountants get the right to add
and maintain subsidiary accounts (not GL accounts).

### 3.5 Reverted: an account's subsidiary ledger setting

`803bd22` (choosing which subsidiary ledger an account keeps, used to give Petty Cash and two allowance accounts a
subsidiary ledger) was reverted by `d450ba5` because the TOR does not ask for it. Those three accounts are back to no
subsidiary ledger.

### 3.6 Naming

The system and its exports carry the name Avtech Information Technology Solutions, Inc. (`9b5c1d3`, `747ef48`); the TOR
is written for MIAA. This changes no requirement; it matters for which name appears on reports at acceptance.

## 4. Unchanged

* **Blocked on AVTECH decisions**, as listed in [22 §5](22-tor-gap-analysis.md) and sent in
  [25](25-miaa-decision-request.md): BIR registration and EIS (C-06); BIR returns and COA, DBM and GCG report formats
  (C-06, C-07); sub-accounts and account flags (C-22, C-07); lapsing schedule and revaluation (C-07, C-30); bank files
  (C-11); migration data (C-12); hosting with two Philippine regions (C-04); SMS gateway (C-16); anti-malware engine
  (C-25); overhead keys and budget definitions (C-10); whether concession receipts are billed in CAS (C-38: the TOR was
  written after the concession, so its billing scope stands); response time and laptop
  count (C-02, C-01); records purge (C-05, C-13).
* **Not applicable as written**, to be confirmed: production-cost WIP and company codes (C-35); machine learning for
  cash application (C-36).
* **Contract activities**, as in [22 §6](22-tor-gap-analysis.md): hosting and DR operation, UAT, parallel run,
  penetration test, training, helpdesk, reports, project documents, governance, laptops and the post-warranty
  agreement.

## 5. Recommended actions

1. **Tie the demo override to the demo database** (§3.1): refuse `cas.demo.superadmin-overrides-controls` at start-up
   unless the database is marked as a demonstration database, so a production server cannot carry it by mistake.
2. **Decide the Accountant's rights over subsidiary accounts** (§3.4) and record the decision in the clarification
   register.
3. **Before any demonstration to the client or COA**, say that the demo relaxes MFA and segregation of duties for its
   demo accounts, and that it runs on a single database account; or run it with those settings off.
4. **Keep the BAC and COA naming question** (§3.6) with the project manager.

## 6. Evidence run

Full backend suite and SpotBugs on 7 November 2026 at `21aa8a2`: **264 tests, 0 failures, 0 errors, 0 skipped;
SpotBugs 0 findings.** Frontend: 47 tests, type check and lint pass. Every test cited in §2 exists in the repository.
The browser end-to-end suite (`frontend/e2e/`) was not rerun for this inventory.
