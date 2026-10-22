#!/usr/bin/env bash
# Restores the original demo data (catalog, stock, no orders, empty carts) without rebuilding anything.
# The Spanner emulator keeps data in memory, so restarting it wipes it; the services re-create and re-seed on start.
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose restart spanner
docker compose exec -T valkey valkey-cli FLUSHALL >/dev/null
docker compose restart catalog-service inventory-service checkout-service payment-service gateway
echo "Waiting for services..."
for i in $(seq 1 60); do
  n=$(docker compose ps --format '{{.Status}}' | grep -c "(healthy)" || true)
  [[ "$n" -ge 9 ]] && break
  sleep 3
done
sleep 5
curl -sf http://localhost:5173/api/inventory/stores >/dev/null && echo "Demo data reset. Open http://localhost:5173"
