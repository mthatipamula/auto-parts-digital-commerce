# auto-parts-digital-commerce

Auto parts commerce for both channels: **retail (B2C)** buy-online-pick-up-in-store, and **Pro (B2B)** commercial
accounts for repair shops and mechanics, with contract and tier pricing, charge-to-account on Net 30 terms, PO numbers and
delivery to the shop. Find parts by **year / make / model**, see stock at your store, and check out through a **saga** that
reserves stock, then authorizes the card or places a credit hold, and compensates on failure.
Java 21 / Spring Boot microservices for **GKE**, on **Spanner**, **Memorystore (Valkey)**, **Pub/Sub** and **GCS**,
fronted by **Apigee**, secured with **OAuth2/OIDC** and an **Istio** zero-trust mesh, provisioned with **Terraform**
and deployed with **Helm**.

> Independent portfolio project. Brands, SKUs and stores are fictional.

![Shop by vehicle](docs/screenshots/01-shop-by-vehicle.png)

## Architecture

```
Browser / mobile / partners
   │  Apigee on GCP (deploy/apigee) or Kong locally (deploy/kong):
   │  API key per app, spike arrest, per-app quota, CORS, internal paths blocked
   ▼
gateway  (Spring Cloud Gateway: JWT, per-user rate limit in Valkey, circuit breaker on catalog, no internal routes)
   │
   ├── catalog-service ── Spanner (vehicles ⟂ fitments interleaved, parts) · Valkey cache-aside · GCS images
   ├── inventory-service ─ Spanner (stores ⟂ stock interleaved, reservations) · consumes order events
   ├── checkout-service ── cart in Valkey · SAGA orchestrator · outbox → Pub/Sub · CQRS read model in Valkey
   │        │ Resilience4j: Retry(CircuitBreaker(Bulkhead(call))) per dependency, token relay
   │        ├──► catalog (prices)   ├──► inventory (reserve / release)   └──► payment-service (authorize)
   ├── payment-service ── mock processor, idempotent by order ID, admin fault injection
   └── account-service ── Pro accounts: Spanner (accounts ⟂ members, contract prices interleaved; credit holds)
            contract price > tier discount > list · credit holds in read-write transactions · owner/buyer roles

Pub/Sub topic order-events (ordering key = order ID, DLQ)
   ├── inventory.order-events   → commit stock on OrderConfirmed, release on OrderFailed   (choreography)
   ├── checkout.order-history   → order-history projection, per user and per Pro account  (CQRS)
   └── account.order-events     → capture credit hold on OrderConfirmed, release on OrderFailed
```

## How the job description maps to the code

| Requirement | Where |
|---|---|
| Microservices, EDA, **orchestration and choreography** | `CheckoutSaga` orchestrates reserve → pay → confirm; inventory reacts to events on its own (`InventoryEvents`) |
| **Saga** with compensation | Payment failure → `RELEASE_STOCK`; if that fails too, a `ReservationReleaseRequested` event; inventory also expires orphaned holds |
| **CQRS** | Writes in Spanner (`OrderStore`); order history read model in Valkey built from events (`Messaging.project`) |
| **Transactional outbox**, idempotency | Order change + event in one Spanner transaction; `Idempotency-Key` on checkout; reservations and payments idempotent by order ID |
| **Resilience4j**: circuit breaker, bulkhead, retry, rate limit | `Downstream` + `application.yml` (retry only transient failures, with jitter); gateway token-bucket rate limiter |
| **Spanner** | Interleaved tables, covering index (`STORING`), UUID keys (no hotspots), read-write transactions to prevent overselling, stale reads for availability |
| **Memorystore (Valkey)** | Cart, catalog cache-aside with TTL (degrades gracefully), CQRS read model, gateway rate limiting |
| **GCS** | Product images (`CatalogSeeder`), public bucket with versioning in Terraform |
| **Apigee** (and **Kong**) | `deploy/apigee`: VerifyAPIKey, SpikeArrest, Quota per app, CORS, internal paths blocked. The same policies on Kong in `deploy/kong/kong.yml`, running locally ([Kong at the edge](#kong-at-the-edge-local)) |
| **OAuth2/OIDC, zero trust** | Every service validates the JWT (`SecurityConfig`); roles customer / associate / admin; Istio STRICT mTLS + AuthorizationPolicies by service identity (`deploy/istio`) |
| **GKE, Helm, Terraform, CI/CD** | `deploy/helm` (one chart, values per service, Workload Identity, probes, HPA, PDB, zone spreading); `deploy/terraform`; `.github/workflows/ci.yml` |
| **Observability** | OpenTelemetry traces and metrics → Grafana (Tempo/Prometheus); Resilience4j state on `/actuator` and the Ops page; SLO thresholds in `loadtest/` |
| **JVM / Kubernetes troubleshooting** | Container JVM flags, always-on JFR, GC logs; a JVM lab (leak, CPU hot spot, lock contention) and runbook in [docs/jvm-diagnostics.md](docs/jvm-diagnostics.md) |

## Run it locally

Requires Docker (about 6 GB of RAM for the stack).

```bash
./scripts/init-env.sh                 # random demo passwords and Kong API keys into .env
docker compose up -d --build          # emulators, Valkey, Keycloak, Grafana, Kong, 6 services, storefront
python3 scripts/e2e.py                # 31 end-to-end checks (retail + Pro)
```

| URL | |
|---|---|
| http://localhost:5173 | Storefront. Retail: `customer`. Pro: `rick`, `jordan`, `mike`. Staff: `associate`, `admin` (passwords in `.env`) |
| http://localhost:5173/#/ops | (admin) live circuit breakers + payment fault injection |
| http://localhost:3000 | Grafana: traces across kong → gateway → checkout → inventory / payment |
| http://localhost:8002 | Kong Manager: routes, plugins and consumers (read-only in DB-less mode) |
| http://localhost:8000 | Kong proxy, for partner-app calls (`./scripts/kong-demo.sh`) |

## Demo script (5 minutes)

1. **Shop:** 2021 → Toyota → Camry. Only parts that fit, with stock at the Springfield store.
2. **Checkout:** order confirms; open it to see the saga steps; inventory drops after `OrderConfirmed`.
3. **Out of stock:** switch to the Dallas store and order the alternator → `REJECTED`, nothing charged.
4. **Failure + compensation:** Ops page → payment failure rate 1.0 → order → `PAYMENT_FAILED` with `RELEASE_STOCK: OK`.
5. **Circuit breaker:** a few more orders → payment circuit **OPEN**, calls fail fast; reset → half-open → closed.
6. **JVM:** `docs/jvm-diagnostics.md` lab: heap dump of a leak, flame graph of a hot loop, thread dump of contention.

## Kong at the edge (local)

Locally, Kong (OSS 3.9, DB-less) plays Apigee's role in front of Spring Cloud Gateway, with the same policies as one
declarative file, [`deploy/kong/kong.yml`](deploy/kong/kong.yml):

```
browser ──► nginx (storefront, adds the storefront's API key) ──► Kong :8000 ──► gateway :8080 ──► services
partner app (curl) ─────────────────────────────────────────────►┘
```

| Apigee policy | Kong |
|---|---|
| VerifyAPIKey (`x-api-key`) | `key-auth`; consumers `storefront` and `partner-fleet-app`, keys from `.env` |
| AM-StripApiKey | `key-auth` `hide_credentials`: the key never reaches the backend |
| SpikeArrest + Quota per app | `rate-limiting` per consumer (storefront 100/s and 10,000/h; partner 10/min), counters in Valkey |
| CORS | `cors` |
| RaiseFault on internal paths | Allow-list routes: reservations, payment authorizations and credit holds have no route → 404 |
| (Apigee analytics) | `opentelemetry`: each request starts a trace that continues through the gateway and services |

Kong identifies the calling **app**; the bearer token still identifies the **user**, and the gateway and every service
still validate it. Moving from Apigee to Kong only changes this top layer.

**Demo (about 3 minutes):**

1. Terminal 1, watch requests arrive (one line each: method, path, status, upstream time, request id):
   ```bash
   docker compose logs -f kong
   ```
2. Use the storefront (http://localhost:5173): every API call shows up in terminal 1. In the browser's DevTools →
   Network, responses carry `Via: kong`, `X-Kong-Upstream-Latency`, `X-Kong-Request-Id` and `RateLimit-Remaining`.
3. Terminal 2, call the API as a partner app:
   ```bash
   ./scripts/kong-demo.sh
   ```
   No key → 401, wrong key → 401, partner key → 200 with quota headers, internal path → 404, burst past the
   10/minute quota → 429.
4. Grafana → Explore → Tempo → Service name `kong`: one trace from Kong (cors → key-auth → rate-limiting) through the
   gateway into the services.
5. Kong Manager (http://localhost:8002): the service, routes, plugins and the two consumers.

After pulling this change, run `./scripts/init-env.sh` once (it adds the two Kong keys to an existing `.env`), then
`docker compose up -d --build web kong`. The e2e script and load test call the gateway directly, as before.

## Pro (B2B) accounts

Commercial customers are most of the business, so they get their own pricing, credit and ordering rules rather than a
coupon on the retail flow. Demo accounts (seeded by `commerce-common/.../seed/DemoAccounts.java`):

| User | Account | Role | What it shows |
|---|---|---|---|
| `rick` | Rick's Automotive (repair shop, GOLD, Net 30, $5,000 limit) | Owner | Contract prices, charge to account with PO, delivery to the shop, whole-account order history |
| `jordan` | Rick's Automotive | Buyer, $750 per-order limit | Orders over the limit are declined: "ask the account owner" |
| `mike` | Mike's Mobile Mechanic (independent, SILVER) | Owner | Only $150 of credit left: a larger order on account is declined and its stock released |

**How it works**

- **Pricing** (`POST /api/accounts/me/quote`): contract price for the account, else the tier discount for the part's
  category, else list. Checkout re-prices on the server, so the browser never sets a price.
- **Credit is a saga step.** For `paymentMethod: ACCOUNT`, `HOLD_CREDIT` replaces `AUTHORIZE_PAYMENT`. The hold is a Spanner
  read-write transaction that checks account status, the buyer's per-order limit and available credit
  (limit − open balance − holds), then reserves the amount. It is idempotent by order ID, so a retried call never holds twice.
- **Settlement by event.** `OrderConfirmed` turns the hold into open balance (the Net 30 receivable); `OrderFailed` releases it.
  A sweeper releases holds left by orders that never finished.
- **Who sees what.** Account owners see every order placed on the account (`GET /api/orders?scope=account`, a Valkey
  projection keyed by account); buyers see only their own. Retail customers cannot charge to account or request delivery (403).
  Store staff see all Pro accounts and their credit position on the Ops page.
- **Internal by default.** Credit-hold endpoints have no gateway route, and the Istio policy allows only checkout-service to call them.

**Pro demo:** sign in as `rick` → the shop shows net prices with list struck through → add brake pads → Cart: deliver to the
shop, charge to account, PO `RA-4471` → the order shows `HOLD_CREDIT: OK` → Account page: balance up, hold back to zero.
Then sign in as `jordan` and order more than $750 on account (declined), and as `mike` order more than $150 (declined, stock released).

## Deploy to GCP

The same stack, on managed GCP services, behind one public HTTPS URL. Verified end to end: `scripts/e2e.py` passes all
31 checks against the deployment.

```
Browser ── https://<lb-ip>.sslip.io ──► GKE Ingress (global HTTPS LB, Google-managed certificate, HTTP→HTTPS redirect)
                                          │
                                          ▼
                              storefront nginx ── /          → React app
                                               ├─ /api/      → gateway → services
                                               └─ /realms/   → Keycloak (sign-in; admin console not exposed)

GKE Autopilot (private nodes, Cloud NAT) ── namespace commerce: gateway, catalog, inventory, checkout, payment, account,
                                            Keycloak, storefront · namespace observability: Grafana LGTM
  each service ── own Kubernetes SA ⇄ own Google SA (Workload Identity, no key files)
     ├── Spanner (instance commerce; databases catalog, inventory, checkout, accounts)
     ├── Memorystore for Valkey (private PSC endpoint)
     ├── Pub/Sub topic order-events (+ DLQ) and one subscription per consumer
     └── Cloud Storage bucket <project>-product-images (public read)
Images: Cloud Build → Artifact Registry (<region>-docker.pkg.dev/<project>/commerce)
```

| What | Where |
|---|---|
| Infrastructure | `deploy/terraform` (GKE, Spanner, Valkey, Pub/Sub, GCS, Artifact Registry, NAT, IAM, GitHub OIDC) |
| Image build | `cloudbuild.yaml` (all 6 services + storefront, built on Cloud Build as linux/amd64) |
| Services | `deploy/helm/commerce-service` + `deploy/helm/values/<service>.yaml` + `gcp-demo.yaml` (demo sizing, public issuer) |
| Keycloak, Grafana, storefront, Ingress, certificate | `deploy/k8s/` |
| Build + deploy everything | `scripts/gcp-deploy.sh` (idempotent; safe to rerun) |
| Grafana tunnel | `scripts/gcp-connect.sh` |

### Prerequisites

- A Google account with a billing account (`gcloud billing accounts list` shows it with `OPEN True`).
- `gcloud`, `terraform` (≥ 1.9), `kubectl`, `helm`, and the GKE auth plugin:

```bash
brew install helm terraform kubectl
gcloud components install gke-gcloud-auth-plugin
```

- `.env` from `./scripts/init-env.sh`. The deploy copies these demo passwords into a Kubernetes Secret; they are never
  printed and never uploaded with the build source (`.gcloudignore`).

### 1. Create the project and link billing

Project IDs are unique across all of Google Cloud, permanently. If `gcloud projects create` says the ID is in use, or
later commands say you "do not have permission to access projects instance", pick another ID. The display name can stay
the same.

```bash
export PROJECT_ID=autoparts-digital-commerce REGION=us-central1
gcloud projects create $PROJECT_ID --name="auto-parts-digital-commerce"
gcloud billing projects link $PROJECT_ID --billing-account=XXXXXX-XXXXXX-XXXXXX
gcloud config set project $PROJECT_ID
```

### 2. Credentials for Terraform

```bash
gcloud auth application-default login
gcloud auth application-default set-quota-project $PROJECT_ID
```

### 3. Enable the APIs

Terraform enables these too; doing it first avoids ordering races on a brand-new project.

```bash
gcloud services enable serviceusage.googleapis.com cloudresourcemanager.googleapis.com iam.googleapis.com \
  compute.googleapis.com container.googleapis.com spanner.googleapis.com memorystore.googleapis.com \
  pubsub.googleapis.com storage.googleapis.com artifactregistry.googleapis.com cloudbuild.googleapis.com \
  iamcredentials.googleapis.com networkconnectivity.googleapis.com serviceconsumermanagement.googleapis.com
```

### 4. Create the infrastructure (about 20–25 minutes)

```bash
terraform -chdir=deploy/terraform init
terraform -chdir=deploy/terraform apply -var project_id=$PROJECT_ID -var region=$REGION \
  -var github_repository=OWNER/auto-parts-digital-commerce
```

State is kept locally in `deploy/terraform` (gitignored). For a team, add a GCS backend.

### 5. Let Cloud Build push images and write logs

Cloud Build runs as the Compute Engine default service account in new projects.

```bash
PROJECT_NUMBER=$(gcloud projects describe $PROJECT_ID --format='value(projectNumber)')
for r in roles/artifactregistry.writer roles/logging.logWriter roles/storage.objectViewer; do
  gcloud projects add-iam-policy-binding $PROJECT_ID --condition=None -q \
    --member="serviceAccount:${PROJECT_NUMBER}-compute@developer.gserviceaccount.com" --role=$r >/dev/null
done
```

### 6. Build and deploy (about 5 minutes to build, 10 to roll out)

```bash
PROJECT_ID=$PROJECT_ID REGION=$REGION ./scripts/gcp-deploy.sh
```

In order, the script:

1. builds the 7 images on Cloud Build, tagged with the git commit (`TAG=…` overrides it);
2. fetches cluster credentials and finds the Valkey endpoint from the Terraform outputs;
3. creates the `commerce` namespace, the `demo-secrets` Secret (from `.env`) and the Keycloak realm ConfigMap;
4. creates the Ingress and waits for its IP, then derives `PUBLIC_URL=https://<ip-with-dashes>.sslip.io`;
5. deploys Grafana and Keycloak (with `PUBLIC_URL` as its hostname), then the six services with Helm, one at a time,
   each waiting until ready;
6. deploys the storefront (nginx config and `config.js` pointing at `PUBLIC_URL`), then requests the managed
   certificate and attaches it to the Ingress.

It ends by printing the storefront URL.

### 7. Wait for the certificate (15–60 minutes, first time only)

HTTPS does not work until the certificate is `Active`. HTTP already redirects to HTTPS.

```bash
kubectl -n commerce get managedcertificate web-cert -o jsonpath='{.status.certificateStatus}{"\n"}'
```

HTTPS is required, not just nice to have: the PKCE sign-in uses the browser's Web Crypto API, which only exists on HTTPS
pages (and on `localhost`), and passwords and bearer tokens must not cross the internet unencrypted. A certificate needs a
hostname, which is why the URL uses [sslip.io](https://sslip.io) (wildcard DNS that resolves `1-2-3-4.sslip.io` to
`1.2.3.4`) rather than the bare IP. With your own domain, point an A record at the Ingress IP and use that name in
`deploy/k8s/certificate.yaml` and the `PUBLIC_URL` derivation in the script.

### 8. Verify

Open the printed URL on any device and sign in as `customer`, `rick`, `jordan`, `mike`, `associate` or `admin`
(passwords in `.env`). Then run the full suite against the cloud:

```bash
E2E_BASE_URL=https://<ip-with-dashes>.sslip.io python3 scripts/e2e.py      # 31 checks
```

Grafana (traces across gateway → checkout → inventory / payment / account) is not public:

```bash
./scripts/gcp-connect.sh      # then http://localhost:3000
```

Useful checks:

```bash
kubectl -n commerce get pods
kubectl -n commerce logs deploy/checkout-service --tail=100
gcloud spanner databases ddl describe catalog --instance=commerce
gcloud storage ls gs://$PROJECT_ID-product-images/categories/
```

### Redeploying

| Change | Command |
|---|---|
| Java or web code | `TAG=<new-tag> PROJECT_ID=$PROJECT_ID ./scripts/gcp-deploy.sh` (or commit first; the default tag is the commit hash) |
| Only Kubernetes / Helm / nginx config | `SKIP_BUILD=1 TAG=<current-tag> PROJECT_ID=$PROJECT_ID ./scripts/gcp-deploy.sh` |
| Terraform | `terraform -chdir=deploy/terraform apply …` with the same `-var`s as step 4 |

Always use a new tag after a code change. The pods reuse an image they already have for the same tag, so rebuilding under
the old tag leaves them running the old code. Don't edit `gcp-deploy.sh` while it is running: bash reads the file as it
goes.

### Cost

As sized, about **$11–14/day** (us-central1 list prices; check Billing → Reports for actuals):

| Item | ≈ per day |
|---|---|
| GKE Autopilot pods (9 pods, about 4.75 vCPU and 9.75 GiB requested) | $6.20 |
| GKE cluster fee (covered by the free-tier credit for one cluster per billing account) | $0 – 2.40 |
| Spanner, 100 processing units | $2.20 |
| Memorystore for Valkey, shared-core nano, primary + replica | $1.50 – 2.00 |
| HTTPS load balancer | $0.60 |
| Cloud NAT | $0.25 |
| Artifact Registry, Cloud Build (about $0.06 per build), Logging, Pub/Sub, GCS | < $0.10 |

Pause the pods between demos (saves about $6/day; Spanner, Valkey, the load balancer and NAT keep billing), then bring
them back with a `SKIP_BUILD=1` redeploy:

```bash
kubectl scale deployment --all --replicas=0 -n commerce && kubectl scale deployment --all --replicas=0 -n observability
```

Optional budget alert (alerts only; it does not stop spending):

```bash
gcloud billing budgets create --billing-account=XXXXXX-XXXXXX-XXXXXX --display-name="$PROJECT_ID" \
  --budget-amount=100USD --filter-projects=projects/$PROJECT_ID \
  --threshold-rule=percent=0.5 --threshold-rule=percent=0.9 --threshold-rule=percent=1.0
```

To stop all charges, tear it down (see [Teardown](#teardown) at the end).

### Troubleshooting (seen on a real first deploy)

| Symptom | Cause and fix |
|---|---|
| `does not have permission to access projects instance` on billing link | The project ID belongs to someone else, so your create failed. Use another ID. |
| Terraform: `Identity Pool does not exist (PROJECT.svc.id.goog)` | That pool appears only once the GKE cluster exists. The bindings now depend on the cluster; rerun `apply`. |
| Service in `CrashLoopBackOff` with `Table not found` | Terraform creates the databases empty, and the services apply their schema on first start. Fixed in `SpannerSetup`; redeploy with a new tag. |
| Images 404, catalog log says `storage.buckets.get` denied | The catalog SA needs bucket metadata read (granted in Terraform). `apply`, then `kubectl -n commerce rollout restart deployment/catalog-service`. |
| Helm `context deadline exceeded` | A pod didn't become ready within 10 minutes: `kubectl -n commerce logs deploy/<service> --previous`. |
| HTTPS doesn't load | The certificate is still `Provisioning` (step 7). |
| e2e: order history or rate limit fails | Over the internet, the read model trails by a few seconds, and sequential calls are too slow to drain the token bucket. The suite polls and sends a concurrent burst. |

### Not in the demo deploy

- The Istio policies (`deploy/istio`) need Cloud Service Mesh enabled on the cluster.
- Apigee (`deploy/apigee`) needs an Apigee organization.
- For production: a real domain, Cloud Armor on the load balancer, Keycloak (or another IdP) backed by a database,
  2+ replicas per service (the chart defaults), and a remote Terraform state backend.
- `.github/workflows/ci.yml` can push images keylessly once `GCP_WORKLOAD_IDENTITY_PROVIDER`, `GCP_CI_SERVICE_ACCOUNT`,
  `GCP_PROJECT_ID` and `GCP_REGION` are set as repository variables (values from `terraform output`).

### Teardown

Deleting the project stops all billing for it and removes everything: GKE, Spanner, Valkey, the load balancer and
certificate, NAT, Pub/Sub, the buckets, Artifact Registry images and service accounts. Deletion protection on GKE,
Spanner and Valkey only blocks `terraform destroy`; it does not block deleting the project.

```bash
gcloud projects delete $PROJECT_ID
```

Confirm it's gone (state `DELETE_REQUESTED`) and that billing is unlinked:

```bash
gcloud projects describe $PROJECT_ID --format="value(lifecycleState)"
gcloud billing projects describe $PROJECT_ID --format="value(billingEnabled)"
```

The project can be restored for 30 days (`gcloud projects undelete $PROJECT_ID`), but its resources are shut down
immediately, and its ID can never be reused, even after the 30 days.

Clean up the local references, so a later deploy starts fresh in a new project:

```bash
rm -f deploy/terraform/terraform.tfstate deploy/terraform/terraform.tfstate.backup
kubectl config delete-context gke_${PROJECT_ID}_${REGION}_commerce
gcloud config unset project
gcloud auth application-default revoke
```

To deploy again later, start from step 1 with a new project ID.
