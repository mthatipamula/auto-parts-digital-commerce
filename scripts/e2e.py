#!/usr/bin/env python3
"""End-to-end checks through the gateway, exactly as a client would call it. Reads demo passwords from .env."""

import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

# Local stack by default; E2E_BASE_URL=https://<host> tests a GCP deployment (one origin serves /api and the sign-in).
_base = os.environ.get("E2E_BASE_URL", "").rstrip("/")
GW = _base or "http://localhost:8080"
KC = (_base or "http://localhost:8180") + "/realms/auto-parts/protocol/openid-connect/token"
env = dict(l.split("=", 1) for l in Path(".env").read_text().splitlines() if "=" in l and not l.startswith("#"))
ok = True


def check(name, cond, detail=""):
    global ok
    ok &= bool(cond)
    print(f"{'PASS' if cond else 'FAIL'}  {name}{('  ' + str(detail)) if detail else ''}")


def token(user):
    body = urllib.parse.urlencode({"grant_type": "password", "client_id": "test-harness", "client_secret": env["TEST_CLIENT_SECRET"],
                                   "username": user, "password": env[f"DEMO_{user.upper()}_PASSWORD"], "scope": "openid"}).encode()
    with urllib.request.urlopen(urllib.request.Request(KC, data=body)) as r:
        return json.load(r)["access_token"]


def call(method, path, tok=None, body=None, headers=None):
    h = {"Content-Type": "application/json", **(headers or {})}
    if tok:
        h["Authorization"] = f"Bearer {tok}"
    req = urllib.request.Request(GW + path, method=method, headers=h, data=json.dumps(body).encode() if body is not None else None)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else None), dict(r.headers)
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw), dict(e.headers)
        except ValueError:
            return e.code, raw.decode()[:200], dict(e.headers)


def big_order(tok, over_cents):
    """A cart line the store can fill whose Pro net total exceeds over_cents, so the saga reaches the credit check."""
    _, all_parts, _ = call("GET", "/api/catalog/parts")
    skus = [p["sku"] for p in all_parts]
    _, avail, _ = call("GET", f"/api/inventory/availability?storeId={store}&" + "&".join(f"skus={x}" for x in skus))
    _, net, _ = call("POST", "/api/accounts/me/quote", tok, {"skus": skus})
    for line in sorted(net, key=lambda x: -x["netPriceCents"] * avail.get(x["sku"], 0)):
        qty = over_cents // line["netPriceCents"] + 1
        if qty <= avail.get(line["sku"], 0):
            return line["sku"], qty
    raise SystemExit(f"no part at {store} has enough stock to exceed ${over_cents/100:.2f}")


# ---------------------------------------------------------------- preconditions (a previous run may have left the
# payment circuit open and the rate-limit bucket empty)
_admin = token("admin")
call("PUT", "/api/payments/chaos", _admin, {"failureRate": 0, "latencyMs": 0})
for _ in range(30):
    st, deps, _ = call("GET", "/api/checkout/dependencies", _admin)
    if st == 200 and all(d["circuit"] == "CLOSED" for d in deps if d["dependency"] != "payment") and \
            next(d for d in deps if d["dependency"] == "payment")["circuit"] in ("CLOSED", "HALF_OPEN"):
        break
    time.sleep(1)
time.sleep(3)  # let the anonymous rate-limit bucket refill

# ---------------------------------------------------------------- browse by vehicle (public)
s, years, h = call("GET", "/api/catalog/vehicles/years")
check("vehicle years (public, cacheable)", s == 200 and 2024 in years, h.get("Cache-Control"))
s, makes, _ = call("GET", "/api/catalog/vehicles/makes?year=2021")
s, models, _ = call("GET", "/api/catalog/vehicles/models?year=2021&make=Toyota")
camry = next(m for m in models if m["model"] == "Camry")
t0 = time.perf_counter(); s, parts, _ = call("GET", f"/api/catalog/vehicles/{camry['id']}/parts"); cold = time.perf_counter() - t0
t0 = time.perf_counter(); call("GET", f"/api/catalog/vehicles/{camry['id']}/parts"); warm = time.perf_counter() - t0
check("parts that fit a 2021 Camry", s == 200 and len(parts) >= 8 and all("V8" not in p["description"] for p in parts),
      f"{len(parts)} parts; first call {cold*1000:.0f} ms, cached {warm*1000:.0f} ms")
check("product image served from GCS", parts[0]["imageUrl"] and urllib.request.urlopen(parts[0]["imageUrl"]).status == 200, parts[0]["imageUrl"])

# ---------------------------------------------------------------- happy path checkout
cust = token("customer")
admin = token("admin")
call("PUT", "/api/payments/chaos", admin, {"failureRate": 0, "latencyMs": 0})
store = "ST-0001"
# Pick the best-stocked parts, so repeated runs (which consume demo stock) keep working.
candidates = [p["sku"] for p in parts if p["priceCents"] < 30000]
_, stock, _ = call("GET", f"/api/inventory/availability?storeId={store}&" + "&".join(f"skus={c}" for c in candidates))
pads, oil = sorted(candidates, key=lambda c: -stock[c])[:2]
prices = {p["sku"]: p["priceCents"] for p in parts}
_, before, _ = call("GET", f"/api/inventory/availability?storeId={store}&skus={pads}&skus={oil}")
call("DELETE", "/api/cart", cust)
call("PUT", f"/api/cart/items/{pads}", cust, {"quantity": 1})
s, cart, _ = call("PUT", f"/api/cart/items/{oil}", cust, {"quantity": 2})
check("cart in Valkey", s == 200 and cart == {pads: 1, oil: 2}, cart)
key = str(uuid.uuid4())
s, order, _ = call("POST", "/api/orders", cust, {"storeId": store}, {"Idempotency-Key": key})
check("checkout saga confirms order", s == 200 and order["status"] == "CONFIRMED",
      f"{order.get('status')} total=${order.get('totalCents', 0)/100:.2f} steps={[x['step'] + ':' + x['outcome'] for x in order.get('steps', [])]}")
check("price came from catalog, not client", order["totalCents"] == prices[pads] + 2 * prices[oil])
s, again, _ = call("POST", "/api/orders", cust, {"storeId": store}, {"Idempotency-Key": key})
check("same Idempotency-Key returns the same order", again.get("orderId") == order["orderId"])
time.sleep(2)
sa, after, _ = call("GET", f"/api/inventory/availability?storeId={store}&skus={pads}&skus={oil}")
if sa != 200:
    print("availability call failed:", sa, after)
check("stock committed via OrderConfirmed event", after[pads] == before[pads] - 1 and after[oil] == before[oil] - 2, f"{before} -> {after}")
# The read model is eventually consistent (outbox -> Pub/Sub -> projection): poll briefly rather than assume a delay.
for _ in range(20):
    s, hist, _ = call("GET", "/api/orders", cust)
    if any(o["orderId"] == order["orderId"] and o["status"] == "CONFIRMED" for o in hist):
        break
    time.sleep(1)
check("CQRS order history (Valkey read model)", any(o["orderId"] == order["orderId"] and o["status"] == "CONFIRMED" for o in hist), f"{len(hist)} orders")

# ---------------------------------------------------------------- out of stock
alt = "ALT-8001"
call("DELETE", "/api/cart", cust); call("PUT", f"/api/cart/items/{alt}", cust, {"quantity": 1})
s, o, _ = call("POST", "/api/orders", cust, {"storeId": "ST-0003"}, {"Idempotency-Key": str(uuid.uuid4())})
check("out of stock at store -> REJECTED, nothing charged", o.get("status") == "REJECTED", o.get("failureReason"))

# ---------------------------------------------------------------- payment failure -> compensation
call("DELETE", "/api/cart", cust); call("PUT", f"/api/cart/items/{pads}", cust, {"quantity": 1})
_, b2, _ = call("GET", f"/api/inventory/availability?storeId={store}&skus={pads}")
call("PUT", "/api/payments/chaos", admin, {"failureRate": 1.0, "latencyMs": 0})
s, o, _ = call("POST", "/api/orders", cust, {"storeId": store}, {"Idempotency-Key": str(uuid.uuid4())})
steps = [x["step"] + ":" + x["outcome"] for x in o.get("steps", [])]
check("payment down -> compensated (stock released)", o.get("status") == "PAYMENT_FAILED" and "RELEASE_STOCK:OK" in steps, steps)
_, a2, _ = call("GET", f"/api/inventory/availability?storeId={store}&skus={pads}")
check("availability restored after compensation", a2[pads] == b2[pads], f"{b2} -> {a2}")

# ---------------------------------------------------------------- circuit breaker opens
results = []
for _ in range(8):
    _, o, _ = call("POST", "/api/orders", cust, {"storeId": store}, {"Idempotency-Key": str(uuid.uuid4())})
    results.append(next((x["detail"] for x in o.get("steps", []) if x["step"] == "AUTHORIZE_PAYMENT"), ""))
s, deps, _ = call("GET", "/api/checkout/dependencies", admin)
pay = next(d for d in deps if d["dependency"] == "payment")
check("payment circuit breaker OPEN, later calls fail fast", pay["circuit"] == "OPEN" and any("circuit breaker open" in r for r in results),
      f"state={pay['circuit']} failureRate={pay['failureRate']} notPermitted={pay['notPermittedCalls']}")
call("PUT", "/api/payments/chaos", admin, {"failureRate": 0, "latencyMs": 0})
call("DELETE", "/api/cart", cust)

# ---------------------------------------------------------------- Pro (B2B) accounts
rick, jordan, mike = token("rick"), token("jordan"), token("mike")
_, me_mike, _ = call("GET", "/api/accounts/me", mike)
s, me, _ = call("GET", "/api/accounts/me", rick)
check("Pro user resolves to their commercial account", s == 200 and me["account"]["name"] == "Rick's Automotive" and me["member"]["role"] == "OWNER",
      f"{me['account']['id']} {me['account']['tier']} {me['credit']['terms']} available=${me['credit']['availableCents']/100:.2f}")
s, quote, _ = call("POST", "/api/accounts/me/quote", rick, {"skus": ["BRK-PAD-1001", "FLT-OIL-3001"]})
q = {x["sku"]: x for x in quote}
check("contract price beats tier discount", q["BRK-PAD-1001"]["netPriceCents"] == 3499 and q["BRK-PAD-1001"]["rule"] == "Contract price")
check("tier discount on non-contract part", q["FLT-OIL-3001"]["netPriceCents"] < q["FLT-OIL-3001"]["listPriceCents"], q["FLT-OIL-3001"]["rule"])

# Order the contract part where it's best stocked, so repeated runs (which consume demo stock) keep working.
_, all_stores, _ = call("GET", "/api/inventory/stores")
pad_store = max((x["storeId"] for x in all_stores),
                key=lambda sid: call("GET", f"/api/inventory/availability?storeId={sid}&skus=BRK-PAD-1001")[1].get("BRK-PAD-1001", 0))
call("DELETE", "/api/cart", rick); call("PUT", "/api/cart/items/BRK-PAD-1001", rick, {"quantity": 2})
s, o, _ = call("POST", "/api/orders", rick, {"storeId": pad_store, "fulfillment": "DELIVERY", "paymentMethod": "ACCOUNT", "poNumber": "RA-4471"},
               {"Idempotency-Key": str(uuid.uuid4())})
check("charge to account with PO -> CONFIRMED at net price", s == 200 and o["status"] == "CONFIRMED" and o["totalCents"] == 2 * 3499
      and o["terms"]["poNumber"] == "RA-4471", [x["step"] + ":" + x["outcome"] for x in o.get("steps", [])])
time.sleep(2)
_, me2, _ = call("GET", "/api/accounts/me", rick)
check("credit hold captured into open balance", me2["credit"]["openBalanceCents"] == me["credit"]["openBalanceCents"] + o["totalCents"]
      and me2["credit"]["onHoldCents"] == 0, f"balance ${me['credit']['openBalanceCents']/100:.2f} -> ${me2['credit']['openBalanceCents']/100:.2f}")

sku, qty = big_order(jordan, 75000)
call("DELETE", "/api/cart", jordan); call("PUT", f"/api/cart/items/{sku}", jordan, {"quantity": qty})
s, o, _ = call("POST", "/api/orders", jordan, {"storeId": store, "paymentMethod": "ACCOUNT"}, {"Idempotency-Key": str(uuid.uuid4())})
check("buyer over their purchase limit -> declined", o.get("status") == "PAYMENT_FAILED" and "purchase limit" in (o.get("failureReason") or ""),
      o.get("failureReason"))
time.sleep(1)
s, team, _ = call("GET", "/api/orders?scope=account", rick)
check("owner sees orders placed by the whole account", any(x.get("placedBy") == "jordan" for x in team) and any(x.get("placedBy") == "rick" for x in team),
      f"{len(team)} orders")
s, _, _ = call("GET", "/api/orders?scope=account", jordan)
check("buyer cannot see account-wide history", s == 403, s)

sku, qty = big_order(mike, me_mike["credit"]["availableCents"])
_, mb, _ = call("GET", f"/api/inventory/availability?storeId={store}&skus={sku}")
call("DELETE", "/api/cart", mike); call("PUT", f"/api/cart/items/{sku}", mike, {"quantity": qty})
s, o, _ = call("POST", "/api/orders", mike, {"storeId": store, "paymentMethod": "ACCOUNT"}, {"Idempotency-Key": str(uuid.uuid4())})
steps = [x["step"] + ":" + x["outcome"] for x in o.get("steps", [])]
check("over available credit -> declined, stock released", o.get("status") == "PAYMENT_FAILED" and "Credit limit" in (o.get("failureReason") or "")
      and "RELEASE_STOCK:OK" in steps, o.get("failureReason"))
_, ma, _ = call("GET", f"/api/inventory/availability?storeId={store}&skus={sku}")
check("availability restored after credit decline", ma == mb, f"{mb} -> {ma}")
for u in (rick, jordan, mike):
    call("DELETE", "/api/cart", u)

call("PUT", f"/api/cart/items/{pads}", cust, {"quantity": 1})
s, _, _ = call("POST", "/api/orders", cust, {"storeId": store, "paymentMethod": "ACCOUNT"}, {"Idempotency-Key": str(uuid.uuid4())})
check("retail customer cannot charge to account", s == 403, s)
call("DELETE", "/api/cart", cust)
s, _, _ = call("GET", "/api/accounts/me", cust)
check("retail customer has no Pro account access", s == 403, s)
s, accts, _ = call("GET", "/api/accounts", admin)
check("staff see all Pro accounts with credit", s == 200 and len(accts) >= 2, [a["account"]["name"] if "account" in a else a.get("name") for a in accts] if s == 200 else s)
s, _, _ = call("POST", "/api/accounts/me/credit-holds", rick, {"orderId": "x", "amountCents": 1})
check("internal credit-hold API not routed by the gateway", s in (401, 403, 404), s)

# ---------------------------------------------------------------- security boundaries
s, _, _ = call("POST", "/api/inventory/reservations", cust, {"orderId": "x", "storeId": store, "items": [{"sku": pads, "quantity": 1}]})
check("internal reservation API not routed by the gateway", s in (401, 403, 404), s)
s, _, _ = call("PUT", "/api/payments/chaos", cust, {"failureRate": 1, "latencyMs": 0})
check("customer cannot inject faults (admin only)", s == 403, s)
s, _, _ = call("GET", "/api/cart")
check("cart requires sign-in", s == 401, s)

# ---------------------------------------------------------------- rate limiting
# Concurrent, so the burst exceeds the bucket (40 tokens, refill 20/s) even with internet latency per call.
with ThreadPoolExecutor(max_workers=35) as pool:
    codes = list(pool.map(lambda _: call("GET", "/api/catalog/categories")[0], range(80)))
check("gateway rate limit (token bucket in Valkey)", 429 in codes, f"{codes.count(200)} x 200, {codes.count(429)} x 429")

sys.exit(0 if ok else 1)
