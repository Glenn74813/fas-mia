#!/usr/bin/env bash
# DEVELOPMENT ONLY: a local PostgreSQL 18 cluster on port 55432 kept in .devdata/ (gitignored), with the
# databases cas (dev server) and cas_test (integration tests). Usage: scripts/dev-db.sh start|stop|status
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; source .env; set +a
BIN=${PG_BIN:-/usr/lib/postgresql/18/bin}
DATA=.devdata/pgdata
SOCK=$PWD/.devdata/sock
PORT=55432
case "${1:-start}" in
  start)
    mkdir -p .devdata "$SOCK" && chmod 700 .devdata
    if [[ ! -f $DATA/PG_VERSION ]]; then
      pw=$(mktemp) && printf '%s\n' "$CAS_DB_PASSWORD" > "$pw"
      "$BIN/initdb" -D "$DATA" -U "$CAS_DB_USER" --pwfile="$pw" -A scram-sha-256 >/dev/null
      rm -f "$pw"
      fresh=1
    fi
    if [[ -z ${fresh:-} ]] && "$BIN/pg_ctl" -D "$DATA" status >/dev/null 2>&1; then
      echo "Dev database already running on localhost:$PORT."
      exit 0
    fi
    "$BIN/pg_ctl" -D "$DATA" -o "-p $PORT -k $SOCK -c listen_addresses=localhost" -l .devdata/postgres.log -w start
    if [[ -n ${fresh:-} ]]; then
      for db in cas cas_test; do
        PGPASSWORD=$CAS_DB_PASSWORD "$BIN/createdb" -h localhost -p $PORT -U "$CAS_DB_USER" "$db"
      done
      echo "Created databases cas and cas_test."
    fi
    ;;
  stop) "$BIN/pg_ctl" -D "$DATA" -w stop ;;
  status) "$BIN/pg_ctl" -D "$DATA" status ;;
  *) echo "usage: $0 start|stop|status" >&2; exit 2 ;;
esac
