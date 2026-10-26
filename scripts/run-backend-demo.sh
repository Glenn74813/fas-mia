#!/usr/bin/env bash
# Runs the packaged backend in PRODUCTION mode for a public demo behind the Cloudflare Worker (docs/21):
# MFA for everyone (no dev bypass), secure cookies, API docs off, /api only through the Worker (shared secret),
# and listening on 127.0.0.1 only, so the tunnel is the one way in. Reads .env like run-backend-dev.sh.
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; source .env; set +a
: "${CAS_PROXY_SHARED_SECRET:?Set CAS_PROXY_SHARED_SECRET in .env (same value as the Worker secret PROXY_SHARED_SECRET)}"
: "${CAS_FRONTEND_BASE_URL:?Set CAS_FRONTEND_BASE_URL in .env, e.g. https://fas-mia.pg1992-gp.workers.dev}"
if [[ ${#CAS_PROXY_SHARED_SECRET} -lt 32 ]]; then
  echo "CAS_PROXY_SHARED_SECRET must be at least 32 characters (openssl rand -hex 32)." >&2; exit 1
fi
export CAS_JWT_PRIVATE_KEY_PATH="$PWD/.secrets/jwt_private.pem" CAS_JWT_PUBLIC_KEY_PATH="$PWD/.secrets/jwt_public.pem"
export SPRING_PROFILES_ACTIVE=prod CAS_OPENAPI_ENABLED=false
export SERVER_ADDRESS=127.0.0.1 MANAGEMENT_SERVER_ADDRESS=127.0.0.1
exec java -jar backend/target/cas-backend-0.1.0-SNAPSHOT.jar
