#!/usr/bin/env bash
# Turns on continuous WAL archiving for the source cluster (archive_timeout=300 as in the DR design, so at most
# five minutes of committed work is outside the archive) and restarts it. Idempotent.
source "$(dirname "$0")/common.sh"
mkdir -p "$ARCHIVE"
if [[ "$(psql_src -c "SHOW archive_mode")" == "on" ]]; then
  log "WAL archiving already on (archive in $(psql_src -c "SHOW archive_command"))"
  exit 0
fi
psql_src -c "ALTER SYSTEM SET archive_mode = 'on'" \
  -c "ALTER SYSTEM SET archive_command = 'test ! -f $ARCHIVE/%f && cp %p $ARCHIVE/%f'" \
  -c "ALTER SYSTEM SET archive_timeout = '300s'" >/dev/null
log "restarting the source cluster to enable archiving (the backend reconnects by itself)"
"$BIN/pg_ctl" -D "$SRC_DATA" -w restart -m fast >/dev/null
psql_src -c "SELECT 'archive_mode=' || current_setting('archive_mode') || ', archive_timeout=' || current_setting('archive_timeout')"
