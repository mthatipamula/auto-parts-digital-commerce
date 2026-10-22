import { useEffect, useState } from 'react';
import { api, money, type Order, type Part } from '../api';
import { users } from '../auth';
import type { Ctx } from '../App';
import { OrderDetail } from './Orders';

export default function CartPage({ ctx }: { ctx: Ctx }) {
  const [cart, setCart] = useState<Record<string, number>>({});
  const [parts, setParts] = useState<Record<string, Part>>({});
  const [order, setOrder] = useState<Order | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [idemKey, setIdemKey] = useState(() => crypto.randomUUID());   // one key per checkout attempt

  const load = async () => {
    const c = await api<Record<string, number>>('/api/cart');
    setCart(c);
    const all = await Promise.all(Object.keys(c).map((s) => api<Part>(`/api/catalog/parts/${s}`)));
    setParts(Object.fromEntries(all.map((p) => [p.sku, p])));
  };
  useEffect(() => { if (ctx.user) load().catch((e) => setError(e.message)); }, [ctx.user]); // eslint-disable-line react-hooks/exhaustive-deps

  if (!ctx.user) return <div className="page"><div className="card"><p>Sign in to see your cart.</p><button className="primary" onClick={() => users.signinRedirect()}>Sign in</button></div></div>;

  const setQty = async (sku: string, quantity: number) => {
    setCart(await api(`/api/cart/items/${sku}`, { method: 'PUT', body: JSON.stringify({ quantity }) }));
    ctx.refreshCart();
  };
  const checkout = async () => {
    setBusy(true); setError(null);
    try {
      const o = await api<Order>('/api/orders', { method: 'POST', body: JSON.stringify({ storeId: ctx.store }), headers: { 'Idempotency-Key': idemKey } });
      setOrder(o); setIdemKey(crypto.randomUUID()); ctx.refreshCart(); await load();
    } catch (e) { setError((e as Error).message); }   // same key on retry: no duplicate order
    setBusy(false);
  };
  const total = Object.entries(cart).reduce((a, [s, q]) => a + (parts[s]?.priceCents ?? 0) * q, 0);
  const storeName = ctx.stores.find((s) => s.storeId === ctx.store)?.name;

  return (
    <div className="page narrow">
      <h1>Cart</h1>
      {order && <OrderDetail order={order} />}
      {Object.keys(cart).length === 0 ? <p className="muted">Your cart is empty.</p> : (
        <div className="card">
          <table><tbody>
            {Object.entries(cart).map(([sku, q]) => (
              <tr key={sku}><td><strong>{parts[sku]?.name ?? sku}</strong><div className="small muted">{sku}</div></td>
                <td><input type="number" min={0} max={20} value={q} onChange={(e) => setQty(sku, Number(e.target.value))} aria-label={`Quantity of ${sku}`} /></td>
                <td className="num">{money((parts[sku]?.priceCents ?? 0) * q)}</td></tr>))}
          </tbody><tfoot><tr><td>Estimated total</td><td /><td className="num">{money(total)}</td></tr></tfoot></table>
          <p className="small muted">Pick up at <strong>{storeName}</strong>. Final prices are confirmed at checkout.</p>
          {error && <div className="notice error">{error}</div>}
          <button className="primary" disabled={busy} onClick={checkout}>{busy ? 'Placing order…' : 'Place order for pickup'}</button>
        </div>)}
    </div>
  );
}
