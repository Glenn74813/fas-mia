#!/usr/bin/env bash
# Point-in-time restore of a base backup into an isolated cluster on port 55433 (never the source's port), replaying
# the WAL archive up to a named restore point or a timestamp, then promoting it.
#   scripts/dr/restore.sh <base-backup-dir> name <restore-point-name>
#   scripts/dr/restore.sh <base-backup-dir> time '<YYYY-MM-DD HH:MM:SS+00>'
source "$(dirname "$0")/common.sh"
base=$1 kind=$2 target=$3
[[ -f "$base/base.tar.gz" ]] || { echo "No base.tar.gz in $base" >&2; exit 1; }
if [[ -f "$RESTORE_DATA/PG_VERSION" ]]; then
  "$BIN/pg_ctl" -D "$RESTORE_DATA" -w stop -m fast >/dev/null 2>&1 || true
  rm -rf "$RESTORE_DATA"
fi
mkdir -p "$RESTORE_DATA" "$RESTORE_SOCK" && chmod 700 "$RESTORE_DATA"
tar -xzf "$base/base.tar.gz" -C "$RESTORE_DATA"
case "$kind" in
  name) target_line="recovery_target_name = '$target'" ;;
  time) target_line="recovery_target_time = '$target'" ;;
  *) echo "kind must be name or time" >&2; exit 2 ;;
esac
# The restored cluster must never archive into the source's archive, so archiving is switched off here.
cat >> "$RESTORE_DATA/postgresql.auto.conf" <<EOF
# --- DR restore settings ($(now)) ---
port = $RESTORE_PORT
unix_socket_directories = '$RESTORE_SOCK'
listen_addresses = 'localhost'
archive_mode = 'off'
restore_command = 'cp $ARCHIVE/%f %p'
$target_line
recovery_target_action = 'promote'
EOF
touch "$RESTORE_DATA/recovery.signal"
"$BIN/pg_ctl" -D "$RESTORE_DATA" -l "$DR/restore/postgres.log" -w -t 3600 start >/dev/null
for _ in $(seq 1 3600); do
  [[ "$(psql_restored -c "SELECT pg_is_in_recovery()" 2>/dev/null)" == "f" ]] && break
  sleep 1
done
[[ "$(psql_restored -c "SELECT pg_is_in_recovery()")" == "f" ]] || { echo "Recovery did not finish; see $DR/restore/postgres.log" >&2; exit 1; }
log "restored and promoted on port $RESTORE_PORT ($kind $target)"
