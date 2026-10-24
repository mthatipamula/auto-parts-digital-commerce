import { useEffect, useState } from 'react';
import { api, money, type Order, type OrderView } from '../api';
import type { Ctx } from '../App';

const LABEL: Record<string, string> = { CONFIRMED: '✓ Confirmed', REJECTED: '✕ Out of stock', PAYMENT_FAILED: '✕ Payment failed', FAILED: '! Failed', PENDING: '◷ Processing', STOCK_RESERVED: '◷ Processing' };

export default function Orders({ ctx }: { ctx: Ctx }) {
  const [list, setList] = useState<OrderView[] | null>(null);
  const [open, setOpen] = useState<Order | null>(null);
  const owner = ctx.pro?.member.role === 'OWNER';
  const [scope, setScope] = useState<'mine' | 'account'>('mine');
  useEffect(() => { if (ctx.user) api<OrderView[]>(`/api/orders?scope=${scope}`).then(setList).catch(() => setList([])); }, [ctx.user, scope]);
  if (!ctx.user) return <div className="page"><p>Sign in to see your orders.</p></div>;
  const b2b = !!ctx.pro;
  return (
    <div className="page narrow">
      <div className="row between">
        <h1>{scope === 'account' ? `${ctx.pro?.account.name} orders` : 'My orders'}</h1>
        {owner && <div className="chips">
          <button className={scope === 'mine' ? 'chip on' : 'chip'} onClick={() => setScope('mine')}>Placed by me</button>
          <button className={scope === 'account' ? 'chip on' : 'chip'} onClick={() => setScope('account')}>Whole account</button>
        </div>}
      </div>
      <p className="small muted">This list is the read model (CQRS): built from order events in Valkey, so it can trail the order by a moment.</p>
      {open && <OrderDetail order={open} />}
      <div className="card"><table>
        <thead><tr><th>Order</th>{b2b && <><th>PO</th><th>Placed by</th><th>Terms</th></>}<th>Status</th><th className="num">Items</th><th className="num">Total</th><th>Updated</th></tr></thead>
        <tbody>{(list ?? []).map((o) => (
          <tr key={o.orderId} className="click" onClick={() => api<Order>(`/api/orders/${o.orderId}`).then(setOpen)}>
            <td className="mono small">{o.orderId.slice(0, 8)}</td>
            {b2b && <><td className="small">{o.poNumber || <span className="muted">—</span>}</td><td className="small">{o.placedBy}</td>
              <td className="small">{o.paymentMethod === 'ACCOUNT' ? 'On account' : 'Card'} · {o.fulfillment === 'DELIVERY' ? 'Delivery' : 'Pickup'}</td></>}
            <td><span className={`tag ${o.status.toLowerCase()}`}>{LABEL[o.status] ?? o.status}</span></td>
            <td className="num">{o.itemCount}</td><td className="num">{money(Number(o.totalCents))}</td><td className="small muted">{new Date(o.updatedAt).toLocaleString()}</td></tr>))}
          {list?.length === 0 && <tr><td colSpan={b2b ? 8 : 5} className="muted">No orders yet.</td></tr>}</tbody>
      </table></div>
    </div>
  );
}

export function OrderDetail({ order }: { order: Order }) {
  return (
    <div className={`card order ${order.status.toLowerCase()}`}>
      <div className="row between"><h2>Order {order.orderId.slice(0, 8)}</h2><span className={`tag ${order.status.toLowerCase()}`}>{LABEL[order.status] ?? order.status}</span></div>
      {order.failureReason && <p className="notice error">{order.failureReason}</p>}
      <p>{order.items.map((i) => `${i.quantity} × ${i.name}`).join(', ')} · <strong>{money(order.totalCents)}</strong></p>
      {order.terms?.accountId && (
        <p className="small">Account {order.terms.accountId}{order.terms.poNumber && <> · PO <strong>{order.terms.poNumber}</strong></>} · placed by {order.terms.placedBy}
          {' · '}{order.terms.paymentMethod === 'ACCOUNT' ? 'charged to account' : 'paid by card'}
          {' · '}{order.terms.fulfillment === 'DELIVERY' ? `delivery to ${order.terms.deliveryAddress}` : 'store pickup'}</p>)}
      <h3>Checkout saga</h3>
      <ol className="steps">{order.steps.map((s) => (
        <li key={s.seq} className={s.outcome === 'OK' ? 'ok' : s.outcome === 'DEFERRED' ? 'warn' : s.outcome === 'FAILED' || s.outcome === 'ERROR' || s.outcome === 'DECLINED' || s.outcome === 'REJECTED' ? 'bad' : ''}>
          <strong>{s.step.replace(/_/g, ' ').toLowerCase()}</strong> · {s.outcome}{s.detail && <span className="small muted"> · {s.detail.slice(0, 120)}</span>}
        </li>))}</ol>
    </div>
  );
}
