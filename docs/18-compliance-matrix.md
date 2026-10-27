# 18 — Compliance Matrix: Security, Audit, Privacy and Quality

Date: 2026-10-27 (schema V45). This matrix maps the master specification's security, audit, privacy, quality and
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
| MFA for all interactive users | TOTP, mandatory, replay-safe; single-use recovery codes; e-mail one-time codes as an alternative when enabled (TOR §4.2); passkeys with user verification (biometric or device PIN) | `TotpTest`, `AuthFlowIT`, `MfaRecoveryIT`, `MfaEmailCodeIT`, `PasskeyIT`, Playwright `passkeys.spec.ts` | Met | SMS OTP needs a gateway (C-16); e-mail codes need MIAA's SMTP relay |
| Lockout and throttling | 5 failures → 15 min lock (database); per-address auth limit; cluster-wide API limit | `AuthFlowIT`, `AuthRateLimitFilterTest`, `ApiRateLimitIT` | Met | |
| Session security and revocation | Short RS256 access token; rotating refresh token with reuse detection; admin revocation | `AuthFlowIT`, `UserAdminIT` | Met | |
| Role-based access, deny by default | Permissions per role; `@PreAuthorize` everywhere; `denyAll()` fallback | `SecurityBaselineIT`, `RoleAdminIT` | Met | Role matrix is a proposal (C-08) |
| Segregation of duties | 139 role-level rules (`SodPolicy`); transaction-level maker-checker in every workflow | `SodPolicyTest`, `UserAdminIT`, `ApprovalInboxIT` | Met | |
| Maker-checker on financial access grants | `RoleGrantService` (V37) | `AccessGovernanceIT` | Met | Proposed in C-19 |
| Approval limits per role | `ApprovalLimitService` | `AccessGovernanceIT` | Partly met | Values pending (C-24) |
| Periodic access reviews | `AccessReviewService`, Access reviews page | `AccessGovernanceIT` | Met | Frequency to be set by MIAA |
| Time-bound external-auditor access | `access_expires_at`, required for external auditors and set on the user screens | `UserAdminIT`, Playwright `admin-onboarding.spec.ts` | Met | |
| Auditor read-only | Auditor roles without write permissions; SoD auditor-independence rules | Playwright `auditor-readonly.spec.ts` | Met | |
| Portal tenant isolation | Portal queries scoped to the linked customer; linking by a second person | `PortalIT`, `PortalSelfServiceIT` | Met | Identity evidence C-18 |

## 2. Data protection (MS §9.4–9.5)

| Requirement | How it is met | Evidence | Status | Notes |
|---|---|---|---|---|
| Encryption in transit | TLS 1.3 minimum (DICT DC No. 007, s. 2020) on every path CAS controls: gateway `ssl_protocols TLSv1.3`; the Cloudflare Worker refuses older connections (426); outbound SMTP and BIR EIS clients and the JVM (database driver included) limited to TLS 1.3; production refuses to start otherwise, or with an unverified database link (`TlsPolicy`); HSTS | `TlsPolicyTest`, `worker/index.test.ts`, `SecurityBaselineIT` (HSTS); production start refused without the setting (verified 2026-10-27) | Partly met | Certificates, the PostgreSQL server's `ssl_min_protocol_version = TLSv1.3` and a Cloudflare zone minimum for a custom domain are environment work (C-04). TLS 1.2 only for a listed legacy integration, with a recorded reason |
| Encryption at rest, application level | AES-256-GCM for MFA secrets, supplier bank accounts, stored files (V35) | `AesGcmEncryptorTest`, `DocumentsAndRecurringIT`, `PaymentFileIT` | Met | Key rotation not built |
| Encryption at rest, database and backups | Infrastructure | — | Blocked (C-04) | |
| No secrets in source control | `.env.example` only; gitleaks in CI | CI workflow | Partly met | CI has not run (no repository) |
| Log masking | `LogMasker` and converters for text and JSON logs | `LogMaskerTest`, `SensitiveLogIT` | Met | |
| Secure headers, CORS, size limits | CSP, HSTS, nosniff, frame denial, referrer policy; same-origin; 10 MB limits | `SecurityBaselineIT`, `XssIT` | Met | |
| Injection and XSS protection | Bound parameters; FindSecBugs; React text rendering; upload type and signature checks | `SqlInjectionIT`, `XssIT`, `security/xss.test.tsx`, `mvn verify` | Met | |
| Malware scanning of uploads | `MalwareScanner` interface; files marked NOT_SCANNED until an engine is chosen | `DocumentsAndRecurringIT` | Blocked (C-25) | |
| Rate limiting and abuse protection | Auth and API limits | `ApiRateLimitIT` | Met | Network DDoS protection C-04 |
| Third-party access (TOR §4.6) | OAuth 2.0 client credentials with read scopes, Argon2id secrets, rotation and revocation; signed webhooks with retries and SSRF protection | `ApiAccessIT`, `InboundInterfacesIT` | Met | Clients may also send interface files for review (`INTERFACE_SUBMIT`, T9) |
| Static security analysis | SpotBugs + FindSecBugs in `mvn verify`, reviewed suppressions | `backend/spotbugs-exclude.xml` | Met | |
| Penetration test | Threat model in [05](05-security-design.md) §6 as input | — | MIAA activity | Needs a staging environment |

## 3. Audit trail (MS §10)

| Requirement | How it is met | Evidence | Status | Notes |
|---|---|---|---|---|
| Every business action and security event recorded | `audit_events`, `security_events` with actor, roles, address, correlation id | Module ITs, `AuthFlowIT` | Met | |
| Tamper evidence | Append-only triggers, INSERT/SELECT-only runtime grants, SHA-256 hash chain, verification endpoint | `AuditIntegrityIT`, `SecurityAlertsIT` | Met | |
| Tamper proofing and retention | 10-year retention schedule per fiscal year; archive of a closed year with checksums, its SHA-256 sealed in the audit chain; legal holds | `RecordsRetentionIT` | Partly met | Write-once (WORM) storage of archives is MIAA's infrastructure; lawful purge awaits C-05, C-13 |
| Search and export of audit records | Audit trail page; CSV/XLSX/PDF export job | `ManagementReportsIT` (audit export) | Met | |
| Immutable posted records | Triggers on ledger, decisions, movements, events | `PostingInvariantsIT` | Met | |
| Alerts on high-severity events and dead integrations | `AlertService`, throttled | `SecurityAlertsIT`, `IntegrationsIT` | Met | Central monitoring C-04 |
| Fraud detection: anomaly detection and alerts (TOR §2.2.3) | Hourly rules for duplicate invoices, payments after a bank-detail change, amounts just below thresholds, backdated, off-hours and round-sum entries, and Benford deviation; alerts to reviewers; independent review with a note | `AnomalyDetectionIT`, Playwright `anomalies.spec.ts` | Met | Thresholds (45 days, 5 %, business hours, round-sum floor) are proposals for MIAA's internal audit to confirm |

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
| MIAA's ownership of and access to its data (TOR §3.6) | Two-person full export: every table as CSV with a data dictionary, documents, checksums and audit-chain proof | `DataExportIT`, Playwright `data-export.spec.ts` | Met | Destination and custody of exported files are MIAA's |
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
| CI pipeline with scans | Build, test, lint, SpotBugs, architecture rules, e2e, npm audit, Trivy, gitleaks; versioned, signed releases with SBOMs (`release.yml`); every action pinned to a commit | `.github/workflows/ci.yml`, `.github/workflows/release.yml` | Partly met | Never executed: GitHub refuses Actions jobs while the account is locked over billing |
| Release and rollback procedure | [19-deployment-and-rollback.md](19-deployment-and-rollback.md); change control in tickets | `TicketsIT` | Partly met | Not exercised in a production-like environment |
| UAT with ≥ 95 % pass and no critical defects | 58 scripts, coverage and pass-rate checker | `docs/uat/`, `scripts/uat/coverage.mjs` | MIAA activity | Tooling ready |
| Parallel run with no unexplained differences | Comparison and sign-off pages | `ParallelRunIT`, Playwright `parallel-run.spec.ts` | MIAA activity | Needs legacy data (C-12) |
| Documentation deliverables | Data dictionary, threat model, manuals, deployment guide, SBOM, ADRs | `docs/` | Met | Training material is a delivery activity |
| Third-party licence inventory | CycloneDX SBOMs and licence review | [sbom/README.md](sbom/README.md) | Met | Weak-copyleft components listed for legal review |

## 8. Records and delivery obligations carried from the earlier skeleton

| Ref | Description | Status | Notes |
|---|---|---|---|
| CMP-01 RA 10173 | Minimise personal data; access logging | Partly met | See §4; privacy impact assessment drafted ([23](23-privacy-impact-assessment.md)) for the DPO to complete |
| CMP-02 COA | Audit trail of every posted amount | Met | See §3 and §5 |
| CMP-03 BIR | System registration, permit to use, EIS transmission | Blocked (C-06) | |
| CMP-04 Data residency | Philippine residency of data and backups | Blocked (C-04) | |
| CMP-05 Security standards | Application security controls against ISO/IEC 27001 and OWASP ASVS L2 (as named in the earlier skeleton) | Partly met | Controls in §1–§3 and [05](05-security-design.md) §6; external penetration test pending |
| CMP-06 Delivery | Procurement records and IP turnover | Not started | Contract matter: source escrow, licence register (the SBOM is an input) |

## 9. Standards and frameworks named by MIAA

The best-practice list supplied by MIAA (27 October 2026). The management-system standards (ISO 9001, ISO/IEC 27001,
ISO 22301, BS 10012) are certified for an **organisation**, not for a piece of software. For those, CAS supplies the
controls and the evidence the organisation's management system needs; establishing, operating and certifying the
system is MIAA's. Statuses use the values above.

| Standard / framework | Requirement | What CAS provides | Evidence | Status | Still needed |
|---|---|---|---|---|---|
| ISO 9001 | Quality management system best practices | Documented requirements and traceability ([02](02-requirements-traceability.md)); clarification register for every open decision ([03](03-clarification-register.md)); automated tests at every level (§7); change requests with release notes, staging validation and rollback plan, and incident tickets with SLAs (gap 11); controlled deployment and rollback ([19](19-deployment-and-rollback.md)); UAT scripts with a pass-rate gate | §7 above, `TicketsIT`, `docs/uat/` | Partly met | MIAA's QMS: quality policy and objectives, internal audits, management review, corrective-action records. CI must run (GitHub billing lock) |
| ISO/IEC 27001 | Information security management system best practices | The technical Annex A controls in §1–§3: access control and SoD, MFA, cryptography (Argon2id, AES-256-GCM, RS256, TLS 1.3), logging and tamper-evident audit, secure development (threat model, SpotBugs/FindSecBugs, injection and XSS tests), supplier inventory (SBOM), vulnerability scans in CI | §1–§3, [05](05-security-design.md) §6 | Partly met | MIAA's ISMS: scope, risk assessment and treatment plan, Statement of Applicability, policies, internal audit; penetration test; key rotation (A02) |
| ISO 22301 | Business continuity management best practices | Recovery design with RPO ≤ 1 h and RTO ≤ 4 h ([07](07-infrastructure-dr.md)); backup, point-in-time restore and verification scripts; a DR exercise kit with a passed workstation exercise; stateless application tier for fail-over | §6 above, `scripts/dr/` | Partly met | Business impact analysis and continuity plan (MIAA); production DR site and a real exercise (C-04) |
| BS 10012 | Personal information management best practices | The Data Privacy Act controls in §4: minimal personal data, need-to-know access, access and download logging, masked logs, encrypted bank details; portal users see only their own records | §4 above | Partly met | MIAA's PIMS: completing the drafted privacy impact assessment ([23](23-privacy-impact-assessment.md), CMP-01), records of processing, retention and disposal (C-05, C-13), a documented data-subject request procedure |
| ISO/IEC 27018 | Cloud privacy (PII in public clouds) best practices | No personal data in tokens, URLs or logs; personal data encrypted where sensitive; processing location chosen by MIAA. In the Cloudflare demo, Cloudflare carries traffic only: the database and files stay on the CAS host | §2, §4, [21](21-cloudflare-deployment.md) | Partly met | Applies once a cloud host is chosen (C-04): the provider's 27018 attestation, a processing agreement, Philippine residency (CMP-04) |
| ISO/IEC 27017 | Cloud service information security best practices | Cloud-ready design: stateless containers, secrets from the environment, encrypted application data, infrastructure guidance with shared-responsibility split ([07](07-infrastructure-dr.md)); the Cloudflare deployment's edge hardening (TLS 1.3, headers, shared-secret gate) | [07](07-infrastructure-dr.md), [21](21-cloudflare-deployment.md), `ProxyGatewayIT` | Partly met | The chosen provider's 27017 attestation and a responsibility matrix in the hosting contract (C-04) |
| DICT MC No. 5 (2017) | PNS ISO/IEC 27002 code of practice for GOCCs | The 27002 technological controls are those of the ISO/IEC 27001 row: access rights, authentication, cryptography, logging and monitoring, secure coding and testing, protection against malware (interface pending C-25), backup, capacity (performance kit) | §1–§3, §6 | Partly met | The organisational, people and physical controls are MIAA's; malware engine (C-25) |
| DICT DC No. 007 (2020) | TLS 1.3 minimum for all government systems | Enforced on every connection CAS controls (row "Encryption in transit" in §2): gateway, Cloudflare Worker, outbound clients, JVM and database driver; production start refused if not | `TlsPolicyTest`, `worker/index.test.ts` | Partly met | Environment settings outside the code: the PostgreSQL server's minimum, certificates, and the edge minimum on MIAA's own domain (C-04). Older browsers without TLS 1.3 are refused by design |

## 10. Summary

| Status | Rows |
|---|---|
| Met | 35 |
| Partly met | 15 |
| Blocked | 11 |
| MIAA activity | 3 |
| Not started | 1 |
| **Total** | **65** |
