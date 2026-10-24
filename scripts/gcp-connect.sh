#!/usr/bin/env bash
# Opens the GKE deployment on the same localhost ports as the local stack (Ctrl-C to stop):
#   storefront http://localhost:5173 · gateway :8080 · Keycloak :8180 · Grafana :3000
# so the storefront, e2e.py and the PKCE sign-in work unchanged against the cloud deployment.
set -euo pipefail
pids=()
trap 'kill "${pids[@]}" 2>/dev/null' EXIT
kubectl -n commerce port-forward svc/web 5173:80 >/dev/null & pids+=($!)
kubectl -n commerce port-forward svc/gateway 8080:80 >/dev/null & pids+=($!)
kubectl -n commerce port-forward svc/keycloak 8180:8080 >/dev/null & pids+=($!)
kubectl -n observability port-forward svc/otel-collector 3000:3000 >/dev/null & pids+=($!)
echo "Storefront http://localhost:5173  ·  Grafana http://localhost:3000  ·  Ctrl-C to stop"
wait
