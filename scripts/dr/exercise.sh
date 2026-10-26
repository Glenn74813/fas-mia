#!/usr/bin/env bash
# DR exercise (MS §17: RPO ≤ 1 h, RTO ≤ 4 h; §18: successful backup restoration and DR tests). Against the running
# development stack it:
#   1. ensures WAL archiving and takes a base backup;
#   2. writes a "before" marker through the API, fingerprints the books, sets a named restore point and measures
#      how long the archive takes to hold it;
#   3. writes an "after" marker that the restore must NOT contain;
#   4. declares the disaster: restores to the restore point in an isolated cluster and starts a second backend on it
#      (outbound e-mail and integrations off, so nothing is sent twice);
#   5. checks the fingerprint, both markers and both audit hash chains, and times the recovery.
# The report goes to .devdata/dr/results/<time>/report.md. Use --keep to leave the restored stack running.
source "$(dirname "$0")/common.sh"
keep=${1:-}
stamp=$(date -u +%Y%m%dT%H%M%SZ)
run=$DR/results/$stamp
mkdir -p "$run"
api_src=${DR_SOURCE_API:-http://127.0.0.1:8080}
api_dr=http://127.0.0.1:$API_PORT
before=dr.before.$(date +%s)
after=dr.after.$(date +%s)
fingerprint() { psql -h localhost -p "$1" -U "$CAS_DB_USER" -d cas -v ON_ERROR_STOP=1 -At -F '=' -f "$ROOT/scripts/dr/fingerprint.sql"; }

curl -sf "$api_src/actuator/health" >/dev/null || { echo "The source backend is not running on $api_src." >&2; exit 1; }
"$ROOT/scripts/dr/enable-archiving.sh"
for _ in $(seq 1 60); do curl -sf "$api_src/actuator/health" >/dev/null && break; sleep 1; done

log "1. base backup"
t0=$(epoch)
base=$("$ROOT/scripts/dr/base-backup.sh")
backup_seconds=$(( $(epoch) - t0 ))
backup_size=$(du -sh "$base" | cut -f1)

log "2. marker before the restore point, fingerprint, restore point"
node "$ROOT/scripts/dr/api.mjs" "$api_src" create-user "$before" >/dev/null
fingerprint "$SRC_PORT" > "$run/fingerprint-source.txt"
rp=dr-$stamp
read -r rp_lsn rp_wal < <(psql_src -F ' ' -c "SELECT p, pg_walfile_name(p) FROM (SELECT pg_create_restore_point('$rp') AS p) x")
rp_time=$(epoch)
psql_src -c "SELECT pg_switch_wal()" >/dev/null
archived=""
for _ in $(seq 1 600); do
  last=$(psql_src -c "SELECT coalesce(last_archived_wal, '') FROM pg_stat_archiver")
  if [[ -n "$last" && ! "$last" < "$rp_wal" ]]; then archived=$(epoch); break; fi
  sleep 1
done
[[ -n "$archived" ]] || { echo "The restore point's WAL segment was not archived within 10 minutes." >&2; exit 1; }
archive_lag=$(( archived - rp_time ))
archive_timeout=$(psql_src -c "SHOW archive_timeout")

log "3. marker after the restore point (must not survive the restore)"
node "$ROOT/scripts/dr/api.mjs" "$api_src" create-user "$after" >/dev/null

log "4. disaster declared: point-in-time restore to $rp"
t_disaster=$(epoch)
"$ROOT/scripts/dr/restore.sh" "$base" name "$rp"
t_db=$(epoch)
fingerprint "$RESTORE_PORT" > "$run/fingerprint-restored.txt"
log "   starting a backend on the restored database (port $API_PORT, outbound messages off)"
(
  export CAS_DB_URL="jdbc:postgresql://localhost:$RESTORE_PORT/cas"
  export CAS_JWT_PRIVATE_KEY_PATH="$ROOT/.secrets/jwt_private.pem" CAS_JWT_PUBLIC_KEY_PATH="$ROOT/.secrets/jwt_public.pem"
  export SPRING_PROFILES_ACTIVE=dev CAS_COOKIE_SECURE=false
  exec java -jar "$ROOT/backend/target/cas-backend-0.1.0-SNAPSHOT.jar" --server.port="$API_PORT" \
    --cas.outbox.dispatch-enabled=false --cas.integrations.bir-eis.mode=DISABLED > "$run/restored-backend.log" 2>&1
) &
backend_pid=$!
cleanup() {
  [[ "$keep" == "--keep" ]] && return
  kill "$backend_pid" 2>/dev/null || true
  wait "$backend_pid" 2>/dev/null || true
  "$BIN/pg_ctl" -D "$RESTORE_DATA" -w stop -m fast >/dev/null 2>&1 || true
}
trap cleanup EXIT
for _ in $(seq 1 600); do curl -sf "$api_dr/actuator/health" >/dev/null && break; sleep 1; done
curl -sf "$api_dr/actuator/health" >/dev/null || { echo "The restored backend did not start; see $run/restored-backend.log" >&2; exit 1; }
t_app=$(epoch)

log "5. verification"
has_before=$(node "$ROOT/scripts/dr/api.mjs" "$api_dr" user-exists "$before")
has_after=$(node "$ROOT/scripts/dr/api.mjs" "$api_dr" user-exists "$after")
chains=$(node "$ROOT/scripts/dr/api.mjs" "$api_dr" verify-chains)
t_verified=$(epoch)
fp_diff=$(diff "$run/fingerprint-source.txt" "$run/fingerprint-restored.txt" || true)
rto=$(( t_verified - t_disaster ))
intact=$(node -e "const c=JSON.parse(process.argv[1]); console.log(Object.values(c).every(v=>v.intact))" "$chains")

ok() { [[ "$1" == "$2" ]] && echo PASS || echo FAIL; }
fp_ok=$([[ -z "$fp_diff" ]] && echo PASS || echo FAIL)
rto_ok=$([[ $rto -le 14400 ]] && echo PASS || echo FAIL)
timeout_s=${archive_timeout%s}; [[ "$archive_timeout" == *min ]] && timeout_s=$(( ${archive_timeout%min} * 60 ))
rpo_ok=$([[ $timeout_s -le 3600 && $archive_lag -le 3600 ]] && echo PASS || echo FAIL)
overall=PASS
for r in "$fp_ok" "$(ok "$has_before" true)" "$(ok "$has_after" false)" "$(ok "$intact" true)" "$rto_ok" "$rpo_ok"; do
  [[ "$r" == PASS ]] || overall=FAIL
done

cat > "$run/report.md" <<EOF
# CAS DR exercise $stamp: $overall

Development workstation (indicative only): source cluster port $SRC_PORT, restore into an isolated cluster on port
$RESTORE_PORT and a second backend on port $API_PORT. The production exercise follows the same steps with Patroni and
pgBackRest on MIAA's infrastructure (docs/07-infrastructure-dr.md).

| Check | Result | Detail |
|---|---|---|
| Books and audit trail identical at the restore point | $fp_ok | $(wc -l < "$run/fingerprint-source.txt") fingerprint lines compared (row counts, ledger totals, balance digest, last journal number, audit chain heads, document digest) |
| Write before the restore point present | $(ok "$has_before" true) | user \`$before\` |
| Write after the restore point absent | $(ok "$has_after" false) | user \`$after\` |
| Audit and security hash chains verify on the restored system | $(ok "$intact" true) | \`$chains\` |
| RTO ≤ 4 h (disaster declared to verified service) | $rto_ok | $rto s: database restored in $(( t_db - t_disaster )) s, backend up after $(( t_app - t_disaster )) s, verified after $rto s |
| RPO ≤ 1 h | $rpo_ok | Restore point archived $archive_lag s after it was set (the exercise forces a WAL switch). Without one, archive_timeout = $archive_timeout bounds the committed work outside the archive on a total site failure |

Base backup: $(basename "$base"), $backup_size, taken in $backup_seconds s. Restore point \`$rp\` at LSN $rp_lsn (WAL $rp_wal).
$( [[ -n "$fp_diff" ]] && printf '\nFingerprint differences (source < > restored):\n\n```\n%s\n```\n' "$fp_diff" )

Notes:
* The RTO here excludes what a real failover adds: detection, the decision to fail over, DNS or gateway changes.
  The runbook's 4-hour budget must cover those too.
* The restored backend ran with outbound e-mail and integrations switched off so that nothing was sent twice;
  a real failover turns them back on once the primary is confirmed out of service.
EOF
cp "$run/report.md" "$DR/results/latest-report.md"
log "$overall: report in $run/report.md"
[[ "$overall" == PASS ]]
