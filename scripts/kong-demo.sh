#!/usr/bin/env bash
# Kong edge demo: calls the API as a partner app straight through Kong (localhost:8000). Read-only (GET requests).
# Watch the requests arrive in another terminal: docker compose logs -f kong
set -euo pipefail
cd "$(dirname "$0")/.."
KONG=${KONG_URL:-http://localhost:8000}
PARTNER_KEY=$(grep '^KONG_PARTNER_KEY=' .env | cut -d= -f2-)   # never printed
[[ -n "$PARTNER_KEY" ]] || { echo "KONG_PARTNER_KEY missing: run ./scripts/init-env.sh"; exit 1; }
URL="$KONG/api/catalog/vehicles/years"

step() { printf '\n\033[1m%s\033[0m\n' "$1"; }
status() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

step "1. No API key -> Kong rejects it (the gateway is never called)"
echo "   HTTP $(status "$URL")"

step "2. Wrong API key -> rejected"
echo "   HTTP $(status -H 'x-api-key: not-a-real-key' "$URL")"

step "3. Partner app's key -> allowed; Kong adds latency, quota and request-id headers"
curl -s -D - -o /dev/null -H "x-api-key: $PARTNER_KEY" "$URL" \
  | grep -iE '^HTTP|^x-kong-(upstream-latency|proxy-latency|request-id)|^x-ratelimit-(limit|remaining)-minute|^via' | sed 's/^/   /'

step "4. Internal endpoint (stock reservations) -> no route in Kong, 404 even with a valid key"
echo "   HTTP $(status -H "x-api-key: $PARTNER_KEY" "$KONG/api/inventory/reservations")"

step "5. Partner quota is 10 calls/minute -> burst of 12, the rest get 429"
printf '   '
for _ in $(seq 1 12); do printf '%s ' "$(status -H "x-api-key: $PARTNER_KEY" "$URL")"; done
echo

step "See it: Kong Manager http://localhost:8002 (routes, plugins, consumers); Grafana -> Explore -> Tempo -> service kong"
