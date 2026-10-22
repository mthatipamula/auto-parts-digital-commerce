#!/usr/bin/env bash
# Creates .env with random local passwords (only if missing). Values are never printed.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -f .env ]]; then echo ".env already exists; leaving it alone."; exit 0; fi
gen() { openssl rand -base64 24 | tr -dc 'A-Za-z0-9' | head -c 24; }
umask 077
cat > .env <<ENV
KEYCLOAK_ADMIN_PASSWORD=$(gen)
TEST_CLIENT_SECRET=$(gen)
DEMO_CUSTOMER_PASSWORD=$(gen)
DEMO_ASSOCIATE_PASSWORD=$(gen)
DEMO_ADMIN_PASSWORD=$(gen)
ENV
echo "Wrote .env (demo users: customer / associate / admin)."
