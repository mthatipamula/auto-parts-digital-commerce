import { useEffect, useState } from 'react';
import type { User } from 'oidc-client-ts';
import { api, type ProMe, type Store } from './api';
import { initialUser, roles, users } from './auth';
import Shop from './pages/Shop';
import CartPage from './pages/Cart';
import Orders from './pages/Orders';
import Ops from './pages/Ops';
import AccountPage from './pages/Account';

export interface Ctx { user: User | null; roles: string[]; store: string; stores: Store[]; cartCount: number; refreshCart: () => void; pro: ProMe | null; refreshPro: () => void }

export default function App() {
  const [user, setUser] = useState<User | null>(null);
  const [page, setPage] = useState(() => location.hash.replace('#/', '') || 'shop');
  const [stores, setStores] = useState<Store[]>([]);
  const [store, setStore] = useState(() => { try { return localStorage.getItem('store') ?? 'ST-0001'; } catch { return 'ST-0001'; } });
  const [cartCount, setCartCount] = useState(0);
  const [pro, setPro] = useState<ProMe | null>(null);

  useEffect(() => { initialUser().then(setUser).catch(() => setUser(null)); }, []);
  useEffect(() => { api<Store[]>('/api/inventory/stores').then(setStores).catch(() => {}); }, []);
  useEffect(() => { const on = () => setPage(location.hash.replace('#/', '') || 'shop'); addEventListener('hashchange', on); return () => removeEventListener('hashchange', on); }, []);
  useEffect(() => { try { localStorage.setItem('store', store); } catch { /* unavailable */ } }, [store]);
  const refreshCart = () => { if (user) api<Record<string, number>>('/api/cart').then((c) => setCartCount(Object.values(c).reduce((a, b) => a + b, 0))).catch(() => {}); };
  useEffect(refreshCart, [user]); // eslint-disable-line react-hooks/exhaustive-deps
  const refreshPro = () => { if (roles(user).includes('pro')) api<ProMe>('/api/accounts/me').then(setPro).catch(() => setPro(null)); else setPro(null); };
  useEffect(refreshPro, [user]); // eslint-disable-line react-hooks/exhaustive-deps

  const ctx: Ctx = { user, roles: roles(user), store, stores, cartCount, refreshCart, pro, refreshPro };
  const tabs: [string, string][] = [['shop', 'Shop parts'], ['cart', `Cart${cartCount ? ` (${cartCount})` : ''}`], ['orders', pro ? 'Orders' : 'My orders']];
  if (pro) tabs.push(['account', 'Account']);
  if (ctx.roles.includes('admin') || ctx.roles.includes('associate')) tabs.push(['ops', 'Ops']);

  return (
    <div>
      <header className="top">
        <div className="brand">⚙ Parts Counter{pro && <span className="pro-badge" title={`${pro.account.tier} tier · ${pro.credit.terms}`}>PRO · {pro.account.name}</span>}</div>
        <nav>{tabs.map(([id, label]) => <a key={id} href={`#/${id}`} className={page === id ? 'active' : ''}>{label}</a>)}</nav>
        <label className="store">My store
          <select value={store} onChange={(e) => setStore(e.target.value)}>
            {stores.map((s) => <option key={s.storeId} value={s.storeId}>{s.name}, {s.state}</option>)}
          </select>
        </label>
        {user
          ? <span className="who">{user.profile.preferred_username} <button className="link" onClick={() => users.signoutRedirect()}>Sign out</button></span>
          : <button className="primary" onClick={() => users.signinRedirect()}>Sign in</button>}
      </header>
      <main>
        {page === 'cart' ? <CartPage ctx={ctx} /> : page === 'orders' ? <Orders ctx={ctx} /> : page === 'ops' ? <Ops /> : page === 'account' ? <AccountPage ctx={ctx} /> : <Shop ctx={ctx} />}
      </main>
    </div>
  );
}
