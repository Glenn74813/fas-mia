#!/usr/bin/env bash
# Generates DEVELOPMENT-ONLY secrets: an RS256 JWT key pair in .secrets/ and a .env file.
# Production secrets must come from the approved secrets manager / KMS, never from this script.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .secrets && chmod 700 .secrets
if [[ ! -f .secrets/jwt_private.pem ]]; then
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out .secrets/jwt_private.pem
  openssl pkey -in .secrets/jwt_private.pem -pubout -out .secrets/jwt_public.pem
  chmod 600 .secrets/jwt_private.pem
fi
if [[ ! -f .env ]]; then
  cp .env.example .env
  sed -i.bak "s|^CAS_DB_PASSWORD=.*|CAS_DB_PASSWORD=$(openssl rand -hex 16)|" .env
  sed -i.bak "s|^CAS_DATA_ENCRYPTION_KEY=.*|CAS_DATA_ENCRYPTION_KEY=$(openssl rand -base64 32)|" .env
  rm -f .env.bak
  chmod 600 .env
fi
echo "Development secrets ready (.env, .secrets/). Set CAS_BOOTSTRAP_ADMIN_* in .env for the first admin."
