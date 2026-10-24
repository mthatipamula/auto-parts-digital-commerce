import { useEffect, useState } from 'react';
import { api, money, quote, type Part, type PriceLine, type Vehicle } from '../api';
import { users } from '../auth';
import type { Ctx } from '../App';

/** Year -> make -> model, then the parts that fit, with availability at the selected store. */
export default function Shop({ ctx }: { ctx: Ctx }) {
  const [years, setYears] = useState<number[]>([]);
  const [year, setYear] = useState<number | ''>('');
  const [makes, setMakes] = useState<string[]>([]);
  const [make, setMake] = useState('');
  const [models, setModels] = useState<Vehicle[]>([]);
  const [vehicle, setVehicle] = useState<Vehicle | null>(null);
  const [category, setCategory] = useState('');
  const [parts, setParts] = useState<Part[]>([]);
  const [stock, setStock] = useState<Record<string, number>>({});
  const [net, setNet] = useState<Record<string, PriceLine>>({});
  const [msg, setMsg] = useState<string | null>(null);

  useEffect(() => { api<number[]>('/api/catalog/vehicles/years').then(setYears).catch((e) => setMsg(e.message)); }, []);
  useEffect(() => { setMake(''); setModels([]); setVehicle(null); if (year) api<string[]>(`/api/catalog/vehicles/makes?year=${year}`).then(setMakes); }, [year]);
  useEffect(() => { setVehicle(null); if (year && make) api<Vehicle[]>(`/api/catalog/vehicles/models?year=${year}&make=${encodeURIComponent(make)}`).then(setModels); }, [year, make]);
  useEffect(() => {
    if (!vehicle) { setParts([]); return; }
    api<Part[]>(`/api/catalog/vehicles/${vehicle.id}/parts${category ? `?category=${encodeURIComponent(category)}` : ''}`).then(setParts).catch((e) => setMsg(e.message));
  }, [vehicle, category]);
  useEffect(() => {
    if (!parts.length) return;
    const q = parts.map((p) => `skus=${encodeURIComponent(p.sku)}`).join('&');
    api<Record<string, number>>(`/api/inventory/availability?storeId=${ctx.store}&${q}`).then(setStock).catch(() => setStock({}));
  }, [parts, ctx.store]);
  useEffect(() => { if (ctx.pro) quote(parts.map((p) => p.sku)).then(setNet).catch(() => setNet({})); else setNet({}); }, [parts, ctx.pro]);

  const add = async (sku: string) => {
    if (!ctx.user) { await users.signinRedirect(); return; }
    try {
      const cart = await api<Record<string, number>>('/api/cart');
      await api(`/api/cart/items/${sku}`, { method: 'PUT', body: JSON.stringify({ quantity: (cart[sku] ?? 0) + 1 }) });
      ctx.refreshCart(); setMsg('Added to cart.');
    } catch (e) { setMsg((e as Error).message); }
  };
  const categories = [...new Set(parts.map((p) => p.category))];
  const storeName = ctx.stores.find((s) => s.storeId === ctx.store)?.name ?? ctx.store;

  return (
    <div className="page">
      <section className="ymm card">
        <h1>{ctx.pro ? 'Find parts for the job' : 'Find parts for your vehicle'}</h1>
        {ctx.pro && <p className="small muted">Showing {ctx.pro.account.name} pricing ({ctx.pro.account.tier} tier and contract prices).</p>}
        <div className="row">
          <select value={year} onChange={(e) => setYear(e.target.value ? Number(e.target.value) : '')} aria-label="Year">
            <option value="">Year</option>{years.map((y) => <option key={y}>{y}</option>)}</select>
          <select value={make} onChange={(e) => setMake(e.target.value)} disabled={!makes.length} aria-label="Make">
            <option value="">Make</option>{makes.map((m) => <option key={m}>{m}</option>)}</select>
          <select value={vehicle?.id ?? ''} onChange={(e) => setVehicle(models.find((m) => m.id === e.target.value) ?? null)} disabled={!models.length} aria-label="Model">
            <option value="">Model</option>{models.map((m) => <option key={m.id} value={m.id}>{m.model} ({m.engine})</option>)}</select>
        </div>
      </section>
      {msg && <div className="notice" onClick={() => setMsg(null)}>{msg}</div>}
      {vehicle && (<>
        <div className="row between">
          <h2>{vehicle.year} {vehicle.make} {vehicle.model} <span className="muted">· {vehicle.engine}</span></h2>
          <div className="chips">
            <button className={!category ? 'chip on' : 'chip'} onClick={() => setCategory('')}>All</button>
            {categories.map((c) => <button key={c} className={category === c ? 'chip on' : 'chip'} onClick={() => setCategory(c)}>{c}</button>)}
          </div>
        </div>
        <div className="grid">
          {parts.map((p) => {
            const n = stock[p.sku];
            return (
              <article key={p.sku} className="card part">
                {p.imageUrl && <img src={p.imageUrl} alt="" loading="lazy" />}
                <div className="small muted">{p.brand} · {p.sku}</div>
                <h3>{p.name}</h3>
                <p className="small">{p.description}</p>
                <div className="row between">
                  {net[p.sku] && net[p.sku].netPriceCents < p.priceCents
                    ? <span><strong className="price">{money(net[p.sku].netPriceCents)}</strong> <s className="small muted">{money(p.priceCents)}</s>
                      <div className="small pro-rule">{net[p.sku].rule}</div></span>
                    : <strong className="price">{money(p.priceCents)}</strong>}
                  <span className={`stock ${n ? 'in' : 'out'}`}>{n == null ? '…' : n ? `✓ ${n} at ${storeName}` : '✕ Not at this store'}</span>
                </div>
                <button className="primary" disabled={!n} onClick={() => add(p.sku)}>Add to cart</button>
              </article>);
          })}
        </div>
      </>)}
    </div>
  );
}
