#!/usr/bin/env bash
# Grafana (traces, metrics, logs) isn't exposed publicly; this tunnels it to http://localhost:3000 (Ctrl-C to stop).
# The storefront and API are public: see the URL printed by scripts/gcp-deploy.sh.
set -euo pipefail
echo "Grafana http://localhost:3000  ·  Ctrl-C to stop"
exec kubectl -n observability port-forward svc/otel-collector 3000:3000
