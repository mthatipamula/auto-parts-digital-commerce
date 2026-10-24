import { money } from '../api';
import type { Ctx } from '../App';

/** The Pro account: credit position, terms, delivery address, and who can buy on it. */
export default function AccountPage({ ctx }: { ctx: Ctx }) {
  const pro = ctx.pro;
  if (!pro) return <div className="page"><p className="muted">This page is for Pro (commercial) accounts.</p></div>;
  const { account: a, credit: c, member: me } = pro;
  const pctOf = (n: number) => `${Math.min(100, (n / c.limitCents) * 100)}%`;
  return (
    <div className="page narrow">
      <h1>{a.name}</h1>
      <p className="small muted">{a.id} · {a.type === 'SHOP' ? 'Repair shop' : 'Independent mechanic'} · {a.tier} tier · home store {ctx.stores.find((s) => s.storeId === a.homeStoreId)?.name ?? a.homeStoreId} · you are {me.role === 'OWNER' ? 'the account owner' : 'a buyer'}</p>
      <div className="stats">
        <div className="stat"><div className="small muted">Available credit</div><div className="v">{money(c.availableCents)}</div></div>
        <div className="stat"><div className="small muted">Open balance</div><div className="v">{money(c.openBalanceCents)}</div></div>
        <div className="stat"><div className="small muted">On hold (in-flight orders)</div><div className="v">{money(c.onHoldCents)}</div></div>
        <div className="stat"><div className="small muted">Credit limit · terms</div><div className="v">{money(c.limitCents)}</div><div className="small muted">{c.terms.replace('NET', 'Net ')}, invoiced monthly</div></div>
      </div>
      <div className="card">
        <h2>Credit used</h2>
        <div className="meter" role="img" aria-label={`${money(c.openBalanceCents)} balance and ${money(c.onHoldCents)} on hold of ${money(c.limitCents)}`}>
          <span className="bal" style={{ width: pctOf(c.openBalanceCents) }} /><span className="hold" style={{ width: pctOf(c.onHoldCents) }} />
        </div>
        <p className="small muted">■ Open balance · <span style={{ color: 'var(--warn)' }}>■</span> On hold. A hold is placed when an order is placed on account and becomes balance when the order is confirmed; failed orders release it.</p>
        <p className="small">Deliveries go to <strong>{a.deliveryAddress}</strong>.</p>
      </div>
      <div className="card">
        <h2>People on this account</h2>
        <table>
          <thead><tr><th>User</th><th>Role</th><th className="num">Per-order limit</th></tr></thead>
          <tbody>{pro.members.map((m) => (
            <tr key={m.userId}><td>{m.username}{m.userId === me.userId && <span className="small muted"> (you)</span>}</td><td>{m.role === 'OWNER' ? 'Owner' : 'Buyer'}</td>
              <td className="num">{m.orderLimitCents > 0 ? money(m.orderLimitCents) : <span className="muted">No limit</span>}</td></tr>))}</tbody>
        </table>
        {me.role === 'OWNER' && <p className="small muted">Owners see every order on the account under Orders → Whole account.</p>}
      </div>
    </div>
  );
}
