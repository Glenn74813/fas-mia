#!/usr/bin/env bash
# Shared settings for the DR exercise kit (development and test environments). The production design uses
# Patroni and pgBackRest (docs/07-infrastructure-dr.md); these scripts are the plain-PostgreSQL reference for the
# same steps: WAL archiving, base backup, point-in-time restore into an isolated cluster, verification.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
set -a; source .env; set +a
BIN=${PG_BIN:-/usr/lib/postgresql/18/bin}
DR=${DR_DIR:-$ROOT/.devdata/dr}
ARCHIVE=$DR/wal-archive
SRC_PORT=${DR_SOURCE_PORT:-55432}
SRC_DATA=${DR_SOURCE_DATA:-$ROOT/.devdata/pgdata}
RESTORE_PORT=${DR_RESTORE_PORT:-55433}
RESTORE_DATA=$DR/restore/pgdata
RESTORE_SOCK=$DR/restore/sock
API_PORT=${DR_API_PORT:-8081}
export PGPASSWORD="$CAS_DB_PASSWORD"
psql_src() { psql -h localhost -p "$SRC_PORT" -U "$CAS_DB_USER" -d cas -v ON_ERROR_STOP=1 -At "$@"; }
psql_restored() { psql -h localhost -p "$RESTORE_PORT" -U "$CAS_DB_USER" -d cas -v ON_ERROR_STOP=1 -At "$@"; }
now() { date -u +%Y-%m-%dT%H:%M:%SZ; }
epoch() { date +%s; }
log() { echo "[dr $(date +%H:%M:%S)] $*"; }
