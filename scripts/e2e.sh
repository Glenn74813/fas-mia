#!/usr/bin/env bash
# DEVELOPMENT ONLY: a separate backend (port 8092) and frontend (port 5175) for the Playwright end-to-end tests, on
# their own database (cas_e2e in the scripts/dev-db.sh cluster), so the test users and documents every run creates
# never reach the database the demo and day-to-day development use.
#   scripts/e2e.sh start            start the test database, backend and frontend (onboards the test admin once)
#   scripts/e2e.sh test [args...]   run Playwright against them, e.g. scripts/e2e.sh test e2e/budget.spec.ts
#   scripts/e2e.sh stop             stop the test backend and frontend
#   scripts/e2e.sh reset            stop and drop the test database (the next start begins empty)
# The cluster's login comes from CAS_E2E_DB_USER / CAS_E2E_DB_PASSWORD in .env.
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; source .env; set +a
: "${CAS_E2E_DB_USER:?Set CAS_E2E_DB_USER and CAS_E2E_DB_PASSWORD in .env (the scripts/dev-db.sh cluster login)}"
API_PORT=8092 WEB_PORT=5175 DB=cas_e2e PGPORT=55432
ADMIN_ENV=.devdata/e2e-admin.env
pid_on() { ss -ltnpH "sport = :$1" | grep -o 'pid=[0-9]*' | head -1 | cut -d= -f2; }
stop() {
  for port in $API_PORT $WEB_PORT; do
    p=$(pid_on $port || true)
    if [[ -n $p ]]; then kill "$p"; while ss -ltnH "sport = :$port" | grep -q .; do sleep 1; done; fi
  done
}
case "${1:-}" in
  start)
    scripts/dev-db.sh start >/dev/null
    export PGPASSWORD=$CAS_E2E_DB_PASSWORD
    if ! psql -h localhost -p $PGPORT -U "$CAS_E2E_DB_USER" -d postgres -Atc "SELECT 1 FROM pg_database WHERE datname = '$DB'" | grep -q 1; then
      createdb -h localhost -p $PGPORT -U "$CAS_E2E_DB_USER" $DB
      rm -f $ADMIN_ENV
    fi
    if [[ -z $(pid_on $API_PORT || true) ]]; then
      (export CAS_DB_URL="jdbc:postgresql://localhost:$PGPORT/$DB" CAS_DB_USER="$CAS_E2E_DB_USER" CAS_DB_PASSWORD="$CAS_E2E_DB_PASSWORD" \
         CAS_PROXY_SHARED_SECRET= SPRING_PROFILES_ACTIVE=dev CAS_COOKIE_SECURE=false SERVER_PORT=$API_PORT \
         CAS_EXPORTS_POLL_INTERVAL=PT3S CAS_WEBHOOKS_ALLOW_LOCAL_TARGETS=true CAS_WEBAUTHN_ORIGINS=http://localhost:$WEB_PORT \
         CAS_JWT_PRIVATE_KEY_PATH="$PWD/.secrets/jwt_private.pem" CAS_JWT_PUBLIC_KEY_PATH="$PWD/.secrets/jwt_public.pem" \
         CAS_MFA_DEVBYPASSUSERS=
       # No MFA bypass here: the test administrator signs in with an authenticator code, as in production.
       setsid -f java -jar backend/target/cas-backend-0.1.0-SNAPSHOT.jar > .devdata/backend-e2e-tests.log 2>&1 < /dev/null)
      for _ in $(seq 1 120); do
        grep -aq "Started CasApplication" .devdata/backend-e2e-tests.log && break
        grep -aq "Application run failed" .devdata/backend-e2e-tests.log && { echo "Backend failed; see .devdata/backend-e2e-tests.log" >&2; exit 1; }
        sleep 2
      done
      until curl -sf -o /dev/null http://127.0.0.1:$API_PORT/actuator/health; do sleep 2; done
    fi
    if [[ -z $(pid_on $WEB_PORT || true) ]]; then
      (cd frontend && CAS_API_TARGET=http://127.0.0.1:$API_PORT setsid -f npx vite --port $WEB_PORT --strictPort > ../.devdata/vite-e2e.log 2>&1 < /dev/null)
      until curl -sf -o /dev/null http://localhost:$WEB_PORT/; do sleep 1; done
    fi
    [[ -f $ADMIN_ENV ]] || python3 scripts/e2e-admin.py http://127.0.0.1:$API_PORT $ADMIN_ENV
    echo "End-to-end environment: backend http://127.0.0.1:$API_PORT, frontend http://localhost:$WEB_PORT, database $DB."
    ;;
  test)
    shift
    set -a; source $ADMIN_ENV; set +a
    cd frontend && E2E_API_URL=http://127.0.0.1:$API_PORT E2E_BASE_URL=http://localhost:$WEB_PORT npx playwright test --workers=1 "$@"
    ;;
  stop) stop ;;
  reset)
    stop
    PGPASSWORD=$CAS_E2E_DB_PASSWORD dropdb -h localhost -p $PGPORT -U "$CAS_E2E_DB_USER" --if-exists $DB
    rm -f $ADMIN_ENV
    ;;
  *) echo "usage: $0 start|test [playwright args]|stop|reset" >&2; exit 2 ;;
esac
