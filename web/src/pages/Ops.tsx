import { useEffect, useState } from 'react';
import { api, money, type Account, type Credit, type Dependency } from '../api';

/** Live resilience view: circuit breakers and bulkheads in the checkout service, and the payment fault switch. */
export default function Ops() {
  const [deps, setDeps] = useState<Dependency[]>([]);
  const [chaos, setChaos] = useState({ failureRate: 0, latencyMs: 0 });
  const [active, setActive] = useState<{ failureRate: number; latencyMs: number } | null>(null);
  const [saved, setSaved] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [accounts, setAccounts] = useState<{ account: Account; credit: Credit; members: number }[]>([]);
  useEffect(() => { api<typeof accounts>('/api/accounts').then(setAccounts).catch(() => {}); }, []);
  const load = () => api<Dependency[]>('/api/checkout/dependencies').then(setDeps).catch((e) => setError(e.message));
  useEffect(() => { load(); const t = setInterval(load, 2000); return () => clearInterval(t); }, []);
  useEffect(() => {  // what the payment service is actually using right now
    api<{ failureRate: number; latencyMs: number }>('/api/payments/chaos').then((c) => { setActive(c); setChaos(c); }).catch(() => {});
  }, []);
  const apply = (c: typeof chaos) => {
    setError(null); setSaved(null);
    api<{ failureRate: number; latencyMs: number }>('/api/payments/chaos', { method: 'PUT', body: JSON.stringify(c) })
      .then((now) => {
        setChaos(now); setActive(now);
        setSaved(now.failureRate === 0 && now.latencyMs === 0
          ? '✓ Saved: fault injection is off. Payments behave normally.'
          : `✓ Saved: ${Math.round(now.failureRate * 100)}% of payment calls will fail${now.latencyMs ? `, with ${now.latencyMs} ms added latency` : ''}.`);
      })
      .catch((e) => setError(e.message));
  };

  return (
    <div className="page narrow">
      <h1>Ops</h1>
      {error && <div className="notice error">{error}</div>}
      <div className="card"><table>
        <thead><tr><th>Dependency</th><th>Circuit</th><th className="num">Failure rate</th><th className="num">Calls in window</th><th className="num">Rejected fast</th><th className="num">Bulkhead free</th><th className="num">Retried</th></tr></thead>
        <tbody>{deps.map((d) => (
          <tr key={d.dependency}><td>{d.dependency}</td><td><span className={`tag ${d.circuit === 'CLOSED' ? 'confirmed' : d.circuit === 'OPEN' ? 'payment_failed' : 'pending'}`}>{d.circuit === 'CLOSED' ? '● ' : d.circuit === 'OPEN' ? '■ ' : '◐ '}{d.circuit}</span></td>
            <td className="num">{d.failureRate < 0 ? '—' : `${d.failureRate.toFixed(0)}%`}</td><td className="num">{d.bufferedCalls}</td><td className="num">{d.notPermittedCalls}</td>
            <td className="num">{d.bulkheadAvailable}</td><td className="num">{d.retriedCalls}</td></tr>))}</tbody>
      </table></div>
      <div className="card">
        <h2>Pro accounts</h2>
        <table>
          <thead><tr><th>Account</th><th>Tier</th><th>Terms</th><th className="num">Users</th><th className="num">Credit limit</th><th className="num">Open balance</th><th className="num">On hold</th><th className="num">Available</th><th>Status</th></tr></thead>
          <tbody>{accounts.map(({ account: a, credit: c, members }) => (
            <tr key={a.id}><td><strong>{a.name}</strong><div className="small muted">{a.id} · {a.type.toLowerCase()} · home {a.homeStoreId}</div></td>
              <td>{a.tier}</td><td>{c.terms.replace('NET', 'Net ')}</td><td className="num">{members}</td><td className="num">{money(c.limitCents)}</td>
              <td className="num">{money(c.openBalanceCents)}</td><td className="num">{money(c.onHoldCents)}</td>
              <td className="num"><span className={c.availableCents < c.limitCents * 0.2 ? 'low' : ''}>{money(c.availableCents)}</span></td>
              <td><span className={`tag ${a.status === 'ACTIVE' ? 'confirmed' : 'payment_failed'}`}>{a.status === 'ACTIVE' ? '● Active' : `■ ${a.status}`}</span></td></tr>))}
            {accounts.length === 0 && <tr><td colSpan={9} className="muted">No Pro accounts.</td></tr>}</tbody>
        </table>
      </div>
      <div className="card">
        <h2>Payment fault injection (admin only)</h2>
        {active && <p className="small">Currently active: <strong>{Math.round(active.failureRate * 100)}% failures</strong>, <strong>{active.latencyMs} ms</strong> added latency</p>}
        <div className="row">
          <label>Failure rate <input type="number" min={0} max={1} step={0.1} value={chaos.failureRate} onChange={(e) => setChaos({ ...chaos, failureRate: Number(e.target.value) })} /></label>
          <label>Added latency (ms) <input type="number" min={0} max={30000} step={500} value={chaos.latencyMs} onChange={(e) => setChaos({ ...chaos, latencyMs: Number(e.target.value) })} /></label>
          <button className="primary" onClick={() => apply(chaos)}>Apply</button>
          <button className="secondary" onClick={() => apply({ failureRate: 0, latencyMs: 0 })}>Reset</button>
        </div>
        {saved && <div className="notice ok" role="status">{saved}</div>}
        <p className="small muted">Set failure rate 1.0, place a few orders: they compensate (stock released), then the payment circuit opens and checkouts fail fast. Reset, wait 15 s: half-open, then closed.</p>
      </div>
    </div>
  );
}
