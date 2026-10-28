#!/usr/bin/env bash
# Runs the packaged backend locally with the dev profile, reading .env and .secrets/ (dev only).
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; source .env; set +a
export CAS_JWT_PRIVATE_KEY_PATH="$PWD/.secrets/jwt_private.pem" CAS_JWT_PUBLIC_KEY_PATH="$PWD/.secrets/jwt_public.pem"
export SPRING_PROFILES_ACTIVE=dev CAS_COOKIE_SECURE=false
# Run from a copy, so rebuilding backend/target while the backend is up cannot pull the jar from under it.
mkdir -p .devdata
cp backend/target/cas-backend-0.1.0-SNAPSHOT.jar .devdata/cas-backend-dev.jar.new
mv -f .devdata/cas-backend-dev.jar.new .devdata/cas-backend-dev.jar
exec java -jar .devdata/cas-backend-dev.jar
