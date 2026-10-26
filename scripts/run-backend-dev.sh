#!/usr/bin/env bash
# Runs the packaged backend locally with the dev profile, reading .env and .secrets/ (dev only).
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; source .env; set +a
export CAS_JWT_PRIVATE_KEY_PATH="$PWD/.secrets/jwt_private.pem" CAS_JWT_PUBLIC_KEY_PATH="$PWD/.secrets/jwt_public.pem"
export SPRING_PROFILES_ACTIVE=dev CAS_COOKIE_SECURE=false
exec java -jar backend/target/cas-backend-0.1.0-SNAPSHOT.jar
