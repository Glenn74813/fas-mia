#!/usr/bin/env bash
# Takes a compressed base backup of the source cluster into .devdata/dr/base/<time>/ and prints its directory.
# WAL is not copied into the backup (-X none): restores replay it from the WAL archive, as the DR design intends.
source "$(dirname "$0")/common.sh"
[[ "$(psql_src -c "SHOW archive_mode")" == "on" ]] || { echo "Enable WAL archiving first (scripts/dr/enable-archiving.sh)." >&2; exit 1; }
dir=$DR/base/$(date -u +%Y%m%dT%H%M%SZ)
mkdir -p "$dir"
pg_basebackup -h localhost -p "$SRC_PORT" -U "$CAS_DB_USER" -D "$dir" -Ft -z -X none -c fast -l "cas-dr-$(basename "$dir")" >&2
echo "$dir"
