# Software bill of materials and third-party licences

Generated 2026-10-26 from the working tree (no repository or release tag yet). Format: CycloneDX 1.6 JSON.

| File | Scope | Components | Tool |
|---|---|---|---|
| `sbom-backend.json` | Backend runtime (compile + runtime scopes) | 137 | cyclonedx-maven-plugin 2.9.3 |
| `sbom-backend-incl-test.json` | Backend including test scope | 177 | cyclonedx-maven-plugin 2.9.3 |
| `sbom-frontend.json` | Frontend production dependencies (shipped in the bundle) | 37 | @cyclonedx/cyclonedx-npm 6.0.1 (npm 11.19.1) |
| `sbom-frontend-incl-dev.json` | Frontend including build and test tooling | 264 | @cyclonedx/cyclonedx-npm 6.0.1 |

Regenerate (network access to Maven Central and npm; nothing in `pom.xml` or `package.json` changes):

```bash
cd backend && mvn -q org.cyclonedx:cyclonedx-maven-plugin:2.9.3:makeBom -DoutputFormat=json -DoutputName=sbom-backend -DincludeTestScope=false
```

```bash
cd backend && mvn -q org.cyclonedx:cyclonedx-maven-plugin:2.9.3:makeBom -DoutputFormat=json -DoutputName=sbom-backend-incl-test -DincludeTestScope=true
```

```bash
cd frontend && npx --yes @cyclonedx/cyclonedx-npm@6.0.1 --omit dev --output-format JSON --output-file ../docs/sbom/sbom-frontend.json
```

```bash
cd frontend && npx --yes @cyclonedx/cyclonedx-npm@6.0.1 --output-format JSON --output-file ../docs/sbom/sbom-frontend-incl-dev.json
```

The backend files land in `backend/target/` and are copied here. The container images add their base-image
packages (Temurin JRE, NGINX, Alpine), which these files do not list; the CI image scan (Trivy) covers those.

## Licence summary

Licences as declared in the SBOMs. Where a component offers a choice, all options are listed.

**Backend runtime (137)**

| Licence | Components |
|---|---|
| Apache-2.0 | 111 |
| BSD-3-Clause | 9 |
| MIT | 3 |
| EPL-2.0, alone or with a choice of LGPL-2.1, GPL-2.0-with-classpath-exception or BSD-3-Clause | 7 |
| LGPL-2.1-only | 1 |
| LGPL-2.1 / MPL-2.0 | 1 |
| BSD-2-Clause, CC0-1.0, Public Domain, Bouncy Castle Licence | 5 |

**Frontend production (37):** MIT 30, ISC 7.

**Frontend build and test tooling (264):** MIT 208, Apache-2.0 20, ISC 18, BSD-2-Clause 8, BSD-3-Clause 3, MIT-0 2,
MPL-2.0 2, BlueOak-1.0.0 1, CC0-1.0 1, CC-BY-4.0 1. None of these is shipped to users.

**Test scope adds** JUnit 5 (EPL-2.0), JUnit 4 (EPL-1.0, via Testcontainers) and JNA (LGPL-2.1-or-later / Apache-2.0).

## Components needing licence review

Weak-copyleft components that ship in the backend. They are used unmodified as separate JARs, which these
licences generally permit in proprietary or government software. MIAA's legal reviewer should confirm.

| Component | Version | Licence | Use |
|---|---|---|---|
| ch.qos.logback:logback-classic, logback-core | 1.5.34 | EPL-2.0 / LGPL-2.1-only | Logging (Spring Boot default) |
| org.hibernate.orm:hibernate-core | 6.6.53.Final | LGPL-2.1-only | JPA for identity and masters (ADR-006) |
| com.github.librepdf:openpdf | 3.0.5 | LGPL-2.1 / MPL-2.0 | PDF report export |
| org.aspectj:aspectjweaver | 1.9.25.1 | EPL-2.0 | Spring AOP |
| jakarta.annotation-api 2.1.1, jakarta.transaction-api 2.0.1, jakarta.persistence-api 3.1.0, org.eclipse.angus:jakarta.mail 2.0.5 | | EPL-2.0 with GPL-2.0-with-classpath-exception or BSD-3-Clause alternatives | Jakarta EE APIs, SMTP |

No component declares a strong-copyleft licence (GPL without the classpath exception, AGPL) or has no licence.
The frontend production bundle is MIT/ISC only. `lightningcss` (MPL-2.0) is a build-time CSS tool.

## Proposal

Regenerate these files in CI for every release and attach them to the release, alongside the Trivy image
reports. The commands above can run as a step in the `images` job. Not yet added to `ci.yml`.
