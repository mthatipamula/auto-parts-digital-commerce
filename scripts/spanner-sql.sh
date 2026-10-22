#!/usr/bin/env bash
# Run SQL against the local Spanner emulator, using the gcloud CLI inside the Pub/Sub emulator container.
#   ./scripts/spanner-sql.sh catalog "SELECT * FROM fitments LIMIT 5"
#   ./scripts/spanner-sql.sh inventory "SELECT * FROM stock WHERE store_id = 'ST-0001'"
#   ./scripts/spanner-sql.sh checkout "SELECT order_id, status, total_cents FROM orders"
set -euo pipefail
cd "$(dirname "$0")/.."
db="${1:?database: catalog | inventory | checkout}"
sql="${2:?SQL statement}"
docker compose exec -T \
  -e CLOUDSDK_API_ENDPOINT_OVERRIDES_SPANNER=http://spanner:9020/ \
  -e CLOUDSDK_AUTH_DISABLE_CREDENTIALS=true \
  -e CLOUDSDK_CORE_PROJECT=auto-parts-local \
  pubsub gcloud spanner databases execute-sql "$db" --instance=commerce --sql="$sql"
