# MIAA Computerized Accounting System (CAS)

Independently deployable **Spring Boot 3.5 / Java 17** API and **React 19 + TypeScript** SPA backed by
**PostgreSQL**, built in phases against the CAS development specification.

**Current state: Phase 5 (migration and acceptance support) in progress — see
[docs/15-phase-5-plan.md](docs/15-phase-5-plan.md).** Earlier phases: [1](docs/10-phase-1-report.md),
[2](docs/11-phase-2-report.md), [3](docs/12-phase-3-report.md), [4](docs/14-phase-4-report.md). What the
specification asks for against what is built: [docs/16-specification-compliance.md](docs/16-specification-compliance.md).
The chart of accounts loaded in
development is a clearly labelled **placeholder** until MIAA supplies the approved chart.

| Path | Contents |
|---|---|
| `backend/` | Spring Boot modular monolith (`com.miaa.cas.*`), Flyway migrations, tests |
| `frontend/` | Vite React SPA, Vitest unit tests, Playwright e2e |
| `docs/` | Architecture, traceability matrix, clarification register, data model and data dictionary, security design and threat model, posting design, infrastructure/DR, compliance matrix, deployment and rollback guide, user and administrator manuals, SBOM, phase reports, ADRs |
| `deploy/` | Reference gateway NGINX config, database role/grant scripts |
| `scripts/` | Development helpers (secrets, dev database, backend run), the DR exercise kit (`dr/`), the UAT coverage checker (`uat/`), the data-dictionary generator (`docs/`) and the CI e2e bootstrap |
| `.github/workflows/ci.yml` | Build, test, lint, secret/dependency/image scanning |

Start here: [docs/09-dev-setup.md](docs/09-dev-setup.md).

Compliance note: the software provides technical controls and evidence. BIR permits, COA/DBM acceptance,
cloud residency attestations and certifications must be obtained through the competent authorities.
