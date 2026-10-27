# 18 — Compliance Matrix: Security, Audit, Privacy and Quality

Date: 2026-10-26 (schema V41). This matrix maps the master specification's security, audit, privacy, quality and
delivery requirements (MS §) to how CAS meets them and to the evidence. It replaces the six-row skeleton that was
in the traceability matrix ([02](02-requirements-traceability.md)). Functional requirements are traced in 02, and
the section verdicts are in [16](16-specification-compliance.md).

**Status values**
* **Met**: implemented and shown by a passing automated test, or by the named artefact.
* **Partly met**: the missing part is named.
* **Blocked (C-xx)**: waiting for the named clarification.
* **Not started**.
* **MIAA activity**: not a software deliverable.

Formal compliance is decided by the competent authorities and MIAA's officers (the Data Protection Officer,
COA auditors, BIR). This matrix records the technical controls and the evidence the system can produce, not
certification or legal advice.

## 1. Identity and access (MS §9.1–9.3)

| Requirement | How it is met | Evidence | Status | Notes |
|---|---|---|---|---|
| Unique named accounts; username or e-mail sign-in | `users` with unique username and case-insensitive e-mail | `AuthFlowIT` | Met | |
| Strong password policy and hashing | `PasswordPolicy` (12–128, classes, blocked list, history); Argon2id | `PasswordPolicyTest`, `PasswordHashingTest` | Met | Argon2 cost to be tuned on production hardware |
| MFA for all interactive users | TOTP, mandatory, replay-safe; single-use recovery codes for a lost authenticator, which force a new enrolment | `TotpTest`, `AuthFlowIT`, `MfaRecoveryIT` | Met | E-mail/SMS OTP blocked (C-16); WebAuthn not built |
| Lockout and throttling | 5 failures → 15 min lock (database); per-address auth limit; cluster-wide API limit | `AuthFlowIT`, `AuthRateLimitFilterTest`, `ApiRateLimitIT` | Met | |
| Session security and revocation | Short RS256 access token; rotating refresh token with reuse detection; admin revocation | `AuthFlowIT`, `UserAdminIT` | Met | |
| Role-based access, deny by default | Permissions per role; `@PreAuthorize` everywhere; `denyAll()` fallback | `SecurityBaselineIT`, `RoleAdminIT` | Met | Role matrix is a proposal (C-08) |
| Segregation of duties | 136 role-level rules (`SodPolicy`); transaction-level maker-checker in every workflow | `SodPolicyTest`, `UserAdminIT`, `ApprovalInboxIT` | Met | |
| Maker-checker on financial access grants | `RoleGrantService` (V37) | `AccessGovernanceIT` | Met | Proposed in C-19 |
| Approval limits per role | `ApprovalLimitService` | `AccessGovernanceIT` | Partly met | Values pending (C-24) |
| Periodic access reviews | `AccessReviewService`, Access reviews page | `AccessGovernanceIT` | Met | Frequency to be set by MIAA |
| Time-bound external-auditor access | `access_expires_at` | `UserAdminIT` | Met | |
| Auditor read-only | Auditor roles without write permissions; SoD auditor-independence rules | Playwright `auditor-readonly.spec.ts` | Met | |
| Portal tenant isolation | Portal queries scoped to the linked customer; linking by a second person | `PortalIT`, `PortalSelfServiceIT` | Met | Identity evidence C-18 |

## 2. Data protection (MS §9.4–9.5)

| Requirement | How it is met | Evidence | Status | Notes |
|---|---|---|---|---|
| Encryption in transit | TLS at the gateway (`deploy/nginx/gateway.conf`); HSTS | `SecurityBaselineIT` (HSTS header) | Partly met | Certificates and TLS 1.3 enforcement are environment work (C-04) |
| Encryption at rest, application level | AES-256-GCM for MFA secrets, supplier bank accounts, stored files (V35) | `AesGcmEncryptorTest`, `DocumentsAndRecurringIT`, `PaymentFileIT` | Met | Key rotation not built |
| Encryption at rest, database and backups | Infrastructure | — | Blocked (C-04) | |
| No secrets in source control | `.env.example` only; gitleaks in CI | CI workflow | Partly met | CI has not run (no repository) |
| Log masking | `LogMasker` and converters for text and JSON logs | `LogMaskerTest`, `SensitiveLogIT` | Met | |
| Secure headers, CORS, size limits | CSP, HSTS, nosniff, frame denial, referrer policy; same-origin; 10 MB limits | `SecurityBaselineIT`, `XssIT` | Met | |
| Injection and XSS protection | Bound parameters; FindSecBugs; React text rendering; upload type and signature checks | `SqlInjectionIT`, `XssIT`, `security/xss.test.tsx`, `mvn verify` | Met | |
| Malware scanning of uploads | `MalwareScanner` interface; files marked NOT_SCANNED until an engine is chosen | `DocumentsAndRecurringIT` | Blocked (C-25) | |
| Rate limiting and abuse protection | Auth and API limits | `ApiRateLimitIT` | Met | Network DDoS protection C-04 |
| Static security analysis | SpotBugs + FindSecBugs in `mvn verify`, reviewed suppressions | `backend/spotbugs-exclude.xml` | Met | |
| Penetration test | Threat model in [05](05-security-design.md) §6 as input | — | MIAA activity | Needs a staging environment |

## 3. Audit trail (MS §10)

| Requirement | How it is met | Evidence | Status | Notes |
|---|---|---|---|---|
| Every business action and security event recorded | `audit_events`, `security_events` with actor, roles, address, correlation id | Module ITs, `AuthFlowIT` | Met | |
| Tamper evidence | Append-only triggers, INSERT/SELECT-only runtime grants, SHA-256 hash chain, verification endpoint | `AuditIntegrityIT`, `SecurityAlertsIT` | Met | |
| Tamper proofing and retention | Sealed WORM exports, 10-year retention, archival | — | Blocked (C-05, C-13) | |
| Search and export of audit records | Audit trail page; CSV/XLSX/PDF export job | `ManagementReportsIT` (audit export) | Met | |
| Immutable posted records | Triggers on ledger, decisions, movements, events | `PostingInvariantsIT` | Met | |
| Alerts on high-severity events and dead integrations | `AlertService`, throttled | `SecurityAlertsIT`, `IntegrationsIT` | Met | Central monitoring C-04 |

## 4. Data Privacy Act of 2012 (RA 10173)

General obligations only. Their legal interpretation for MIAA, including the privacy impact assessment,
registration and notices, is the responsibility of MIAA's Data Protection Officer.

| Obligation (general) | What CAS provides | Evidence | Status | Notes |
|---|---|---|---|---|
| Lawful, proportionate processing | Collects only operational data (staff accounts; customer, supplier and payee details needed for billing and payment); no personal data in tokens | Data dictionary [04](04-data-model.md) | Partly met | Privacy impact assessment not started (DPO) |
| Security measures (organisational, physical, technical) | Technical: §1–§3 above | This matrix | Partly met | Organisational and physical measures are MIAA's |
| Access limited to need-to-know | Roles, SoD, portal scoping, access reviews | §1 | Met | |
| Accountability and logging of access to personal data | Audit and security trails; downloads of documents are audited | `DocumentsAndRecurringIT` | Met | |
| Breach detection and notification readiness | Security events, alerts, audit chain verification, correlation ids | `SecurityAlertsIT` | Partly met | Breach procedure and deadlines are MIAA's (DPO); monitoring C-04 |
| Data-subject requests (access, correction) | Records can be viewed, exported and corrected by authorised staff; portal users see their own data | Portal, report exports | Partly met | No dedicated request workflow; erasure conflicts with record retention (C-13) |
| Retention and disposal | Retention design pending | — | Blocked (C-05, C-13) | |
| Data residency | Hosting in the Philippines per C-04 | — | Blocked (C-04) | |

## 5. Government accounting and tax (MS §5, §20)

Only as already described in this repository; official requirements await MIAA, COA and BIR guidance.

| Requirement | How it is met | Evidence | Status | Notes |
|---|---|---|---|---|
| Complete audit trail of every posted amount (COA expectation) | Voucher workflow with decisions, immutable entries linked to source documents and supporting files | `JournalWorkflowIT`, `PostingInvariantsIT` | Met | |
| Approved chart of accounts | Placeholder chart (PLC- codes) | `PlaceholderGuardIT` | Blocked (C-22) | |
| Official statement formats and reports | Configurable statement engine, including cash flow and changes-in-equity statements (gap 14) | `FinancialReportingIT`, `MovementStatementsIT` | Blocked (C-07) | Layouts are placeholders until the framework is approved |
| Official voucher and receipt numbering and forms | Gap-free interim series | `JournalWorkflowIT` | Blocked (C-23, C-29) | |
| BIR registration of the system, EIS transmission | Sandbox adapter and transmission log | `IntegrationsIT` | Blocked (C-06) | |
| Withholding and VAT | Tax codes (placeholders), withholding schedule | `PayablesIT` | Partly met | Rates and forms to be confirmed (C-06) |
| Budget control | Allotments, reservations, BLOCK enforcement | `BudgetControlIT`, Playwright `budget.spec.ts` | Met | Budget definitions C-10 |
| Period close controls | Checklists with justified waivers; closed periods refuse posting; reopen by a second person; close calendar | `PeriodCloseIT`, `CloseCalendarIT`, Playwright `period-close.spec.ts` | Met | Close calendar C-33 |

## 6. Availability, backup and recovery (MS §15–17)

| Requirement | How it is met | Evidence | Status | Notes |
|---|---|---|---|---|
| RPO ≤ 1 h, RTO ≤ 4 h, PITR | Design in [07](07-infrastructure-dr.md); DR kit `scripts/dr/` | Workstation exercise PASS (Phase 5d) | Partly met | Production exercise C-04 |
| Daily backups, restore tests | pgBackRest design; restore and verification scripts | `scripts/dr/exercise.sh` | Partly met | Automated weekly test needs infrastructure |
| Performance targets | Performance kit `perf/` | Workstation run (Phase 5c) | Partly met | Acceptance run needs the target environment, volumes (C-12) and thresholds (C-02) |
| Horizontal scaling | Stateless API; database-backed limits and locks | `SecurityBaselineIT.noSessionCookie`, `ApiRateLimitIT` | Met | |

## 7. Quality, testing and delivery (MS §18–19, §27)

| Requirement | How it is met | Evidence | Status | Notes |
|---|---|---|---|---|
| Automated unit and integration tests | 172 backend tests on PostgreSQL; 32 frontend unit tests | `mvn verify`, Vitest | Met | |
| End-to-end tests of critical flows | 16 Playwright spec files (20 tests) | `frontend/e2e` | Met | 20/20 in a single full run on 2026-10-26 (docs/20 §3) |
| Security tests | Injection, XSS, sensitive logs, auth abuse cases | `SqlInjectionIT`, `XssIT`, `SensitiveLogIT`, `AuthFlowIT` | Met | |
| CI pipeline with scans | Build, test, lint, SpotBugs, e2e, npm audit, Trivy, gitleaks | `.github/workflows/ci.yml` | Partly met | Never executed (no repository) |
| Release and rollback procedure | [19-deployment-and-rollback.md](19-deployment-and-rollback.md); change control in tickets | `TicketsIT` | Partly met | Not exercised in a production-like environment |
| UAT with ≥ 95 % pass and no critical defects | 58 scripts, coverage and pass-rate checker | `docs/uat/`, `scripts/uat/coverage.mjs` | MIAA activity | Tooling ready |
| Parallel run with no unexplained differences | Comparison and sign-off pages | `ParallelRunIT`, Playwright `parallel-run.spec.ts` | MIAA activity | Needs legacy data (C-12) |
| Documentation deliverables | Data dictionary, threat model, manuals, deployment guide, SBOM, ADRs | `docs/` | Met | Training material is a delivery activity |
| Third-party licence inventory | CycloneDX SBOMs and licence review | [sbom/README.md](sbom/README.md) | Met | Weak-copyleft components listed for legal review |

## 8. Records and delivery obligations carried from the earlier skeleton

| Ref | Description | Status | Notes |
|---|---|---|---|
| CMP-01 RA 10173 | Minimise personal data; access logging | Partly met | See §4; privacy impact assessment not started |
| CMP-02 COA | Audit trail of every posted amount | Met | See §3 and §5 |
| CMP-03 BIR | System registration, permit to use, EIS transmission | Blocked (C-06) | |
| CMP-04 Data residency | Philippine residency of data and backups | Blocked (C-04) | |
| CMP-05 Security standards | Application security controls against ISO/IEC 27001 and OWASP ASVS L2 (as named in the earlier skeleton) | Partly met | Controls in §1–§3 and [05](05-security-design.md) §6; external penetration test pending |
| CMP-06 Delivery | Procurement records and IP turnover | Not started | Contract matter: source escrow, licence register (the SBOM is an input) |

## 9. Summary

| Status | Rows |
|---|---|
| Met | 35 |
| Partly met | 15 |
| Blocked | 11 |
| MIAA activity | 3 |
| Not started | 1 |
| **Total** | **65** |
