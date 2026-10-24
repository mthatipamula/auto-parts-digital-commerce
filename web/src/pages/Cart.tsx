import { useEffect, useState } from 'react';
import { api, money, quote, type Order, type Part, type PriceLine } from '../api';
import { users } from '../auth';
import type { Ctx } from '../App';
import { OrderDetail } from './Orders';

export default function CartPage({ ctx }: { ctx: Ctx }) {
  const [cart, setCart] = useState<Record<string, number>>({});
  const [parts, setParts] = useState<Record<string, Part>>({});
  const [net, setNet] = useState<Record<string, PriceLine>>({});
  const [order, setOrder] = useState<Order | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [idemKey, setIdemKey] = useState(() => crypto.randomUUID());   // one key per checkout attempt
  // Pro checkout terms
  const [fulfillment, setFulfillment] = useState<'PICKUP' | 'DELIVERY'>('PICKUP');
  const [paymentMethod, setPaymentMethod] = useState<'CARD' | 'ACCOUNT'>('ACCOUNT');
  const [poNumber, setPoNumber] = useState('');
  const pro = ctx.pro;

  const load = async () => {
    const c = await api<Record<string, number>>('/api/cart');
    setCart(c);
    const all = await Promise.all(Object.keys(c).map((s) => api<Part>(`/api/catalog/parts/${s}`)));
    setParts(Object.fromEntries(all.map((p) => [p.sku, p])));
  };
  useEffect(() => { if (ctx.user) load().catch((e) => setError(e.message)); }, [ctx.user]); // eslint-disable-line react-hooks/exhaustive-deps
  const skus = Object.keys(cart).sort().join(',');
  useEffect(() => { if (pro) quote(skus ? skus.split(',') : []).then(setNet).catch(() => setNet({})); }, [pro, skus]);

  if (!ctx.user) return <div className="page"><div className="card"><p>Sign in to see your cart.</p><button className="primary" onClick={() => users.signinRedirect()}>Sign in</button></div></div>;

  const setQty = async (sku: string, quantity: number) => {
    setCart(await api(`/api/cart/items/${sku}`, { method: 'PUT', body: JSON.stringify({ quantity }) }));
    ctx.refreshCart();
  };
  const checkout = async () => {
    setBusy(true); setError(null);
    const body = pro ? { storeId: ctx.store, fulfillment, paymentMethod, poNumber: poNumber.trim() || null } : { storeId: ctx.store };
    try {
      const o = await api<Order>('/api/orders', { method: 'POST', body: JSON.stringify(body), headers: { 'Idempotency-Key': idemKey } });
      setOrder(o); setIdemKey(crypto.randomUUID()); setPoNumber(''); ctx.refreshCart(); ctx.refreshPro(); await load();
    } catch (e) { setError((e as Error).message); }   // same key on retry: no duplicate order
    setBusy(false);
  };
  const unit = (sku: string) => net[sku]?.netPriceCents ?? parts[sku]?.priceCents ?? 0;
  const total = Object.entries(cart).reduce((a, [s, q]) => a + unit(s) * q, 0);
  const listTotal = Object.entries(cart).reduce((a, [s, q]) => a + (parts[s]?.priceCents ?? 0) * q, 0);
  const storeName = ctx.stores.find((s) => s.storeId === ctx.store)?.name;
  const limit = pro?.member.role === 'BUYER' ? pro.member.orderLimitCents : 0;
  const overCredit = !!pro && paymentMethod === 'ACCOUNT' && total > pro.credit.availableCents;
  const overLimit = !!pro && paymentMethod === 'ACCOUNT' && limit > 0 && total > limit;

  return (
    <div className="page narrow">
      <h1>Cart</h1>
      {order && <OrderDetail order={order} />}
      {Object.keys(cart).length === 0 ? <p className="muted">Your cart is empty.</p> : (
        <div className="card">
          <table><tbody>
            {Object.entries(cart).map(([sku, q]) => (
              <tr key={sku}><td><strong>{parts[sku]?.name ?? sku}</strong><div className="small muted">{sku}{net[sku] && net[sku].netPriceCents < net[sku].listPriceCents && ` · ${net[sku].rule}`}</div></td>
                <td><input type="number" min={0} max={20} value={q} onChange={(e) => setQty(sku, Number(e.target.value))} aria-label={`Quantity of ${sku}`} /></td>
                <td className="num">{money(unit(sku) * q)}</td></tr>))}
          </tbody><tfoot>
            {pro && listTotal > total && <tr className="savings"><td>Account savings vs. list</td><td /><td className="num">−{money(listTotal - total)}</td></tr>}
            <tr><td>Estimated total</td><td /><td className="num">{money(total)}</td></tr>
          </tfoot></table>

          {pro ? (
            <div className="terms">
              <fieldset><legend>Fulfillment</legend>
                <label><input type="radio" checked={fulfillment === 'PICKUP'} onChange={() => setFulfillment('PICKUP')} /> Pick up at {storeName}</label>
                <label><input type="radio" checked={fulfillment === 'DELIVERY'} onChange={() => setFulfillment('DELIVERY')} /> Deliver to the shop <span className="small muted">({pro.account.deliveryAddress})</span></label>
              </fieldset>
              <fieldset><legend>Payment</legend>
                <label><input type="radio" checked={paymentMethod === 'ACCOUNT'} onChange={() => setPaymentMethod('ACCOUNT')} /> Charge to account ({pro.credit.terms.replace('NET', 'Net ')}) <span className="small muted">· {money(pro.credit.availableCents)} available</span></label>
                <label><input type="radio" checked={paymentMethod === 'CARD'} onChange={() => setPaymentMethod('CARD')} /> Card</label>
              </fieldset>
              <label className="po">PO number <span className="small muted">(optional, printed on the invoice)</span>
                <input value={poNumber} maxLength={40} onChange={(e) => setPoNumber(e.target.value)} placeholder="e.g. RA-4471" /></label>
              {overLimit && <div className="notice warn">This order is over your purchase limit of {money(limit)}. Ask the account owner to place it.</div>}
              {overCredit && <div className="notice warn">This order is more than the account's available credit ({money(pro.credit.availableCents)}).</div>}
            </div>
          ) : <p className="small muted">Pick up at <strong>{storeName}</strong>. Final prices are confirmed at checkout.</p>}
          {error && <div className="notice error">{error}</div>}
          <button className="primary" disabled={busy} onClick={checkout}>
            {busy ? 'Placing order…' : pro ? `Place order${paymentMethod === 'ACCOUNT' ? ' on account' : ''}` : 'Place order for pickup'}</button>
        </div>)}
    </div>
  );
}
