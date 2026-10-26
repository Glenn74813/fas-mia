# 08 — Phased Implementation Roadmap and Acceptance Gates

| Phase | Scope | Depends on | Exit gate (all required) |
|---|---|---|---|
| 0 Discovery | TOR clause mapping (C-00), CoA & templates, role matrix, volumes | — | Signed business blueprint; clarification items for Phase 1–2 decided |
| **1 Foundation** | Backend & SPA skeleton, Flyway, JWT + refresh rotation, TOTP MFA, users/roles/permissions, SoD rules, audit store, OpenAPI, Docker, CI | — | Backend + frontend build; all automated tests green; security baseline review by MISD; dev environment reproducible from README |
| **2 Accounting engine** (delivered, see 11-phase-2-report) | CoA, fiscal years/periods, numbering, documents, approval workflow, JEV workflow, posting engine, reversals, recurring and closing vouchers, GL/SL inquiry, trial balance, idempotency | Phase 1; C-07, C-08 | Posting invariants proven by tests (balanced, immutable, no duplicate, period lock, SoD); Chief Accountant walkthrough |
| **3 Financial modules** (delivered except org API, open-item clearing, WebAuthn, FX; see 12-phase-3-report) | AP/DV, AR/billing, treasury, bank recon, fixed assets, budgeting, inventory accounting; org API, open-item clearing, WebAuthn, FX (C-21) | Phase 2; C-09, C-10, C-11 | Each sub-ledger reconciles to GL in tests; concurrency tests for budget reservation |
| 4 Reports & integrations | Statutory/management reports, report jobs, exports, concessionaire portal, BIR EIS sandbox adapter, bank files, ERP APIs, ticketing | Phase 3; C-06, C-15, C-18 | Reports match MIAA reference results; cross-tenant tests pass; sandbox transmission evidence |
| 5 Migration & acceptance | Migration tooling & two test migrations, load tests, pen-test, DR exercise, UAT, 1-month parallel run, cutover | Phase 4; C-12 | MS §18 acceptance conditions; MIAA sign-off |

The 13-week migration workstream (Annex F) runs in parallel from late Phase 2.
