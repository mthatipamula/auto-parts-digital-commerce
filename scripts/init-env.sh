#!/usr/bin/env bash
# Creates .env with random local passwords (only if missing). Values are never printed.
set -euo pipefail
cd "$(dirname "$0")/.."
gen() { openssl rand -base64 24 | tr -dc 'A-Za-z0-9' | head -c 24; }
if [[ -f .env ]]; then
  # Add any keys introduced since .env was created (e.g. the Pro demo users, Kong API keys), without touching existing ones.
  added=0
  for key in DEMO_RICK_PASSWORD DEMO_JORDAN_PASSWORD DEMO_MIKE_PASSWORD KONG_STOREFRONT_KEY KONG_PARTNER_KEY; do
    grep -q "^$key=" .env || { echo "$key=$(gen)" >> .env; added=$((added + 1)); }
  done
  echo ".env already exists; added $added new key(s)."
  exit 0
fi
umask 077
cat > .env <<ENV
KEYCLOAK_ADMIN_PASSWORD=$(gen)
TEST_CLIENT_SECRET=$(gen)
DEMO_CUSTOMER_PASSWORD=$(gen)
DEMO_ASSOCIATE_PASSWORD=$(gen)
DEMO_ADMIN_PASSWORD=$(gen)
DEMO_RICK_PASSWORD=$(gen)
DEMO_JORDAN_PASSWORD=$(gen)
DEMO_MIKE_PASSWORD=$(gen)
KONG_STOREFRONT_KEY=$(gen)
KONG_PARTNER_KEY=$(gen)
ENV
echo "Wrote .env (demo users: customer / associate / admin; Pro: rick / jordan / mike)."
