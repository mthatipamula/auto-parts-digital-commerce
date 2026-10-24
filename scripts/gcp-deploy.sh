#!/usr/bin/env bash
# Builds images on Cloud Build and deploys the whole stack to the GKE cluster created by deploy/terraform.
# Run after `terraform apply`. Usage: PROJECT_ID=my-project ./scripts/gcp-deploy.sh
set -euo pipefail
cd "$(dirname "$0")/.."
: "${PROJECT_ID:?set PROJECT_ID}"
REGION="${REGION:-us-central1}"
TAG="${TAG:-$(git rev-parse --short HEAD)}"
REGISTRY="$REGION-docker.pkg.dev/$PROJECT_ID/commerce"
SERVICES=(catalog-service inventory-service payment-service account-service checkout-service gateway)
[[ -f .env ]] || { echo "No .env: run ./scripts/init-env.sh first"; exit 1; }

if [[ "${SKIP_BUILD:-}" == "1" ]]; then
  echo "==> Using existing images $REGISTRY/*:$TAG (SKIP_BUILD=1)"
else
  echo "==> Building images $REGISTRY/*:$TAG on Cloud Build (about 5 minutes)"
  gcloud builds submit --project "$PROJECT_ID" --region "$REGION" --config cloudbuild.yaml \
    --substitutions "_REGISTRY=$REGISTRY,_TAG=$TAG" .
fi

echo "==> Cluster credentials"
gcloud container clusters get-credentials commerce --region "$REGION" --project "$PROJECT_ID"

VALKEY_IP=$(terraform -chdir=deploy/terraform output -json valkey_endpoints | python3 -c '
import json, sys
def ips(x):
    if isinstance(x, dict):
        for k, v in x.items():
            if k in ("ip_address", "ipAddress") and v: yield v
            else: yield from ips(v)
    elif isinstance(x, list):
        for v in x: yield from ips(v)
print(next(ips(json.load(sys.stdin))))')
echo "    Valkey at $VALKEY_IP"

echo "==> Namespace, demo secrets, observability, Keycloak"
kubectl create namespace commerce --dry-run=client -o yaml | kubectl apply -f -
# Secrets go straight from .env into the cluster; they are never printed.
kubectl -n commerce create secret generic demo-secrets --from-env-file=.env --dry-run=client -o yaml | kubectl apply -f -

echo "==> Public entry point (load balancer IP)"
kubectl apply -f deploy/k8s/ingress.yaml
for i in $(seq 1 90); do
  IP=$(kubectl -n commerce get ingress web -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || true)
  [[ -n "$IP" ]] && break
  sleep 5
done
[[ -n "${IP:-}" ]] || { echo "The Ingress got no IP after 7 minutes: kubectl -n commerce describe ingress web"; exit 1; }
PUBLIC_HOST="${IP//./-}.sslip.io"
PUBLIC_URL="https://$PUBLIC_HOST"
echo "    $PUBLIC_URL"

kubectl -n commerce create configmap keycloak-realm --from-file=realm.json=infra/keycloak/realm.json --dry-run=client -o yaml | kubectl apply -f -
kubectl apply -f deploy/k8s/observability.yaml
sed "s#PUBLIC_URL#$PUBLIC_URL#g" deploy/k8s/keycloak.yaml | kubectl apply -f -
kubectl -n commerce rollout restart deployment/keycloak >/dev/null   # pick up realm/secret changes on redeploy
kubectl -n commerce rollout status deployment/keycloak --timeout=10m

echo "==> Services (Helm)"
rendered=$(mktemp -d)
sed "s#PUBLIC_URL#$PUBLIC_URL#g" deploy/helm/values/gcp-demo.yaml > "$rendered/gcp-demo.yaml"
for svc in "${SERVICES[@]}"; do
  sed -e "s/PROJECT_ID/$PROJECT_ID/g" -e "s/VALKEY_PRIVATE_IP/$VALKEY_IP/g" -e "s/us-central1-docker.pkg.dev/$REGION-docker.pkg.dev/" \
    "deploy/helm/values/$svc.yaml" > "$rendered/$svc.yaml"
  helm upgrade --install "$svc" deploy/helm/commerce-service -n commerce \
    -f "$rendered/$svc.yaml" -f "$rendered/gcp-demo.yaml" --set image.tag="$TAG" --wait --timeout 10m
done
rm -rf "$rendered"

echo "==> Storefront"
sed -e "s#image: IMAGE#image: $REGISTRY/web:$TAG#" -e "s#PUBLIC_URL#$PUBLIC_URL#g" deploy/k8s/web.yaml | kubectl apply -f -
kubectl -n commerce rollout restart deployment/web >/dev/null   # pick up nginx/config.js changes
kubectl -n commerce rollout status deployment/web --timeout=5m
sed "s#PUBLIC_HOST#$PUBLIC_HOST#g" deploy/k8s/certificate.yaml | kubectl apply -f -
kubectl -n commerce annotate ingress web networking.gke.io/managed-certificates=web-cert --overwrite >/dev/null

kubectl -n commerce get pods
cert=$(kubectl -n commerce get managedcertificate web-cert -o jsonpath='{.status.certificateStatus}' 2>/dev/null || true)
echo
echo "Deployed $TAG."
echo "  Storefront: $PUBLIC_URL   (certificate: ${cert:-Provisioning}; first time takes 15-60 min)"
echo "  Tests:      E2E_BASE_URL=$PUBLIC_URL python3 scripts/e2e.py"
echo "  Grafana:    ./scripts/gcp-connect.sh, then http://localhost:3000 (not exposed publicly)"
