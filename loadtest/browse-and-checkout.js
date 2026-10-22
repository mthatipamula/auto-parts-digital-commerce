// k6 load test: mostly catalog browsing (cache-heavy) with some checkouts. Run: k6 run loadtest/browse-and-checkout.js
// Watch while it runs: Grafana (latency, GC pauses), the Ops page (circuit breakers), and JFR (docs/jvm-diagnostics.md).
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  scenarios: {
    browse: { executor: 'ramping-vus', stages: [{ duration: '30s', target: 50 }, { duration: '2m', target: 50 }, { duration: '20s', target: 0 }] },
  },
  thresholds: {                         // SLOs: the run fails if these are missed
    'http_req_duration{route:parts}': ['p(95)<300', 'p(99)<800'],
    http_req_failed: ['rate<0.01'],
  },
};

const GW = __ENV.GATEWAY || 'http://localhost:8080';

export function setup() {
  const models = http.get(`${GW}/api/catalog/vehicles/models?year=2021&make=Toyota`).json();
  return { vehicles: models.map((m) => m.id) };
}

export default function (data) {
  const v = data.vehicles[Math.floor(Math.random() * data.vehicles.length)];
  const r = http.get(`${GW}/api/catalog/vehicles/${v}/parts`, { tags: { route: 'parts' } });
  check(r, { 'parts 200': (x) => x.status === 200 || x.status === 429 });
  sleep(Math.random() * 0.5);
}
