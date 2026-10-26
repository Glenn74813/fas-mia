# 07 — Infrastructure, Data Residency, Availability, Backup and DR

> No cloud provider, region or price is asserted here. Every provider capability below is a
> **requirement to verify** with vendor documentation and contract terms (C-04).

## 1. Target topology

| Tier | Production (PH region, ≥ 2 availability zones) | DR (geographically separate PH site) |
|---|---|---|
| Edge | WAF + L7 load balancer, TLS 1.3 (TLS 1.2 fallback only for listed legacy integrations) | Warm standby LB, DNS failover (low TTL) |
| Web | NGINX containers serving the SPA (≥ 2) | Same images, scaled to 1 |
| App | `cas-backend` containers (≥ 2, autoscale on CPU/latency) in private subnets | Scaled to 1, started on failover |
| DB | PostgreSQL primary + synchronous standby in second AZ; WAL archived to object storage every ≤ 5 min (`archive_timeout=300`) | Asynchronous streaming replica + WAL archive copy |
| Storage | Private object storage, versioning + object lock (WORM) for audit exports and backups, SSE with customer-managed keys | Cross-site replication |
| Secrets | Managed KMS / secrets manager; DB credentials rotated | Replicated |

Network segmentation: public subnet (LB only) → web subnet → app subnet → data subnet; DB
reachable only from app subnet; administration via bastion/VPN with MFA; no public DB endpoint.

## 2. Meeting the targets

| Target | Mechanism | Verification |
|---|---|---|
| 99.9 % monthly | Multi-AZ app/DB, rolling deployments, readiness probes | Synthetic probe per C-03 |
| RPO ≤ 1 h | Synchronous standby (RPO≈0 in-region); WAL archive ≤ 5 min lag to DR | Measured WAL lag alerts; DR test restores to a timestamp |
| RTO ≤ 4 h | Runbook: promote DR replica, switch DNS, start app tier | Twice-yearly DR exercise with timed report |
| Daily backups | Nightly `pg_basebackup`/provider snapshot + continuous WAL, 35-day PITR window; monthly full retained per retention policy | Weekly automated restore test into an isolated environment |

Daily backups alone do not achieve RPO ≤ 1 h; the WAL archive does.

## 3. Backup protection

Separate backup account/credentials, object lock (compliance mode) for the retention
window, encryption with keys the production app role cannot use to delete, alerts on any
backup failure or deletion attempt; restores require a ticketed approval and generate an
audit record.

## 4. Environments

`dev` (developer workstation, Docker Compose), `test` (CI ephemeral), `staging`
(production-like, masked data), `prod`, `dr`. Each has separate secrets, keys and databases;
production data is never copied to lower environments unmasked.

## 5. On-premises / hybrid option

The same container images run on MIAA-hosted Kubernetes or VMs with Docker; PostgreSQL via
Patroni (3 nodes) + pgBackRest to on-prem object storage; NGINX as the gateway.

## 6. Monitoring

Actuator health (liveness/readiness), Micrometer → Prometheus, OpenTelemetry traces via
OTLP collector, structured ECS JSON logs to a central PH-resident log store, alerts for:
error rate, p95 latency, DB connections, replication lag, WAL archive failures, backup
failures, audit-chain verification failure, refresh-token reuse events, lockout spikes.
