# auto-parts-digital-commerce

Buy-online, pick-up-in-store commerce for auto parts: find parts by **year / make / model**, see stock at your store,
and check out through a **saga** that reserves stock, authorizes payment and compensates on failure.
Java 21 / Spring Boot microservices for **GKE**, on **Spanner**, **Memorystore (Valkey)**, **Pub/Sub** and **GCS**,
fronted by **Apigee**, secured with **OAuth2/OIDC** and an **Istio** zero-trust mesh, provisioned with **Terraform**
and deployed with **Helm**.

> Independent portfolio project. Brands, SKUs and stores are fictional.

![Shop by vehicle](docs/screenshots/01-shop-by-vehicle.png)

## Architecture

```
Browser / mobile / partners
   │  Apigee (API key, spike arrest, per-app quota, CORS)            deploy/apigee
   ▼
gateway  (Spring Cloud Gateway: JWT, per-user rate limit in Valkey, circuit breaker on catalog, no internal routes)
   │
   ├── catalog-service ── Spanner (vehicles ⟂ fitments interleaved, parts) · Valkey cache-aside · GCS images
   ├── inventory-service ─ Spanner (stores ⟂ stock interleaved, reservations) · consumes order events
   ├── checkout-service ── cart in Valkey · SAGA orchestrator · outbox → Pub/Sub · CQRS read model in Valkey
   │        │ Resilience4j: Retry(CircuitBreaker(Bulkhead(call))) per dependency, token relay
   │        ├──► catalog (prices)   ├──► inventory (reserve / release)   └──► payment-service (authorize)
   └── payment-service ── mock processor, idempotent by order ID, admin fault injection

Pub/Sub topic order-events (ordering key = order ID, DLQ)
   ├── inventory.order-events   → commit stock on OrderConfirmed, release on OrderFailed   (choreography)
   └── checkout.order-history   → order-history projection                                 (CQRS)
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
| **Apigee** | `deploy/apigee`: VerifyAPIKey, SpikeArrest, Quota per app, CORS, internal paths blocked |
| **OAuth2/OIDC, zero trust** | Every service validates the JWT (`SecurityConfig`); roles customer / associate / admin; Istio STRICT mTLS + AuthorizationPolicies by service identity (`deploy/istio`) |
| **GKE, Helm, Terraform, CI/CD** | `deploy/helm` (one chart, values per service, Workload Identity, probes, HPA, PDB, zone spreading); `deploy/terraform`; `.github/workflows/ci.yml` |
| **Observability** | OpenTelemetry traces and metrics → Grafana (Tempo/Prometheus); Resilience4j state on `/actuator` and the Ops page; SLO thresholds in `loadtest/` |
| **JVM / Kubernetes troubleshooting** | Container JVM flags, always-on JFR, GC logs; a JVM lab (leak, CPU hot spot, lock contention) and runbook in [docs/jvm-diagnostics.md](docs/jvm-diagnostics.md) |

## Run it locally

Requires Docker (about 6 GB of RAM for the stack).

```bash
./scripts/init-env.sh                 # random demo passwords into .env
docker compose up -d --build          # emulators, Valkey, Keycloak, Grafana, 5 services, storefront
python3 scripts/e2e.py                # 17 end-to-end checks
```

| URL | |
|---|---|
| http://localhost:5173 | Storefront. Sign in as `customer`, `associate` or `admin` (passwords in `.env`) |
| http://localhost:5173/#/ops | (admin) live circuit breakers + payment fault injection |
| http://localhost:3000 | Grafana: traces across gateway → checkout → inventory / payment |

## Demo script (5 minutes)

1. **Shop:** 2021 → Toyota → Camry. Only parts that fit, with stock at the Springfield store.
2. **Checkout:** order confirms; open it to see the saga steps; inventory drops after `OrderConfirmed`.
3. **Out of stock:** switch to the Dallas store and order the alternator → `REJECTED`, nothing charged.
4. **Failure + compensation:** Ops page → payment failure rate 1.0 → order → `PAYMENT_FAILED` with `RELEASE_STOCK: OK`.
5. **Circuit breaker:** a few more orders → payment circuit **OPEN**, calls fail fast; reset → half-open → closed.
6. **JVM:** `docs/jvm-diagnostics.md` lab: heap dump of a leak, flame graph of a hot loop, thread dump of contention.

## Deploy to GCP

`deploy/terraform` (GKE Autopilot, Spanner, Memorystore for Valkey via PSC, GCS, Pub/Sub with DLQ, Artifact Registry,
per-service Google service accounts with Workload Identity, keyless GitHub OIDC), then `helm upgrade --install` each
service with `deploy/helm/values/<service>.yaml`, apply `deploy/istio`, and import `deploy/apigee` into an Apigee X
org. These create billable resources; size and delete accordingly.
