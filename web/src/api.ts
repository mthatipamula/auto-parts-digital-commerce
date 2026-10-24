import { users } from './auth';

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const user = await users.getUser();
  const headers = new Headers(init.headers);
  if (user && !user.expired) headers.set('Authorization', `Bearer ${user.access_token}`);
  if (init.body) headers.set('Content-Type', 'application/json');
  const res = await fetch(path, { ...init, headers });
  if (res.status === 401 && !path.startsWith('/api/catalog')) { await users.signinRedirect(); throw new Error('Signing you in…'); }
  if (!res.ok) {
    let msg = `${res.status} ${res.statusText}`;
    try { const p = await res.json(); msg = p.detail ?? p.title ?? msg; } catch { /* not json */ }
    if (res.status === 403) throw new Error("You don't have permission to do that (your role doesn't allow it).");
    throw new Error(res.status === 429 ? 'Too many requests, slow down a moment.' : msg);
  }
  return res.status === 204 ? (undefined as T) : res.json();
}

export const money = (cents: number) => `$${(cents / 100).toFixed(2)}`;

export interface Vehicle { id: string; year: number; make: string; model: string; engine: string }
export interface Part { sku: string; name: string; brand: string; category: string; priceCents: number; description: string; imageUrl: string | null }
export interface Store { storeId: string; name: string; city: string; state: string }
export interface Step { seq: number; step: string; outcome: string; detail: string | null; at: string }
export interface Order {
  orderId: string; status: string; storeId: string; totalCents: number; failureReason: string | null; paymentId: string | null;
  items: { sku: string; name: string; quantity: number; unitPriceCents: number; listPriceCents: number }[]; steps: Step[]; createdAt: string;
  terms: Terms;
}
export interface Terms { accountId: string | null; poNumber: string | null; placedBy: string | null; fulfillment: 'PICKUP' | 'DELIVERY'; paymentMethod: 'CARD' | 'ACCOUNT'; deliveryAddress: string | null }
export interface OrderView {
  orderId: string; status: string; storeId: string; totalCents: string; itemCount: string; reason: string; updatedAt: string;
  poNumber?: string; placedBy?: string; fulfillment?: string; paymentMethod?: string;
}

// Pro (commercial) accounts
export interface Account { id: string; name: string; type: string; tier: string; creditLimitCents: number; openBalanceCents: number; heldCents: number; terms: string; homeStoreId: string; deliveryAddress: string; status: string }
export interface Member { accountId: string; userId: string; username: string; role: 'OWNER' | 'BUYER'; orderLimitCents: number }
export interface Credit { limitCents: number; openBalanceCents: number; onHoldCents: number; availableCents: number; terms: string }
export interface ProMe { account: Account; member: Member; credit: Credit; members: Member[] }
export interface PriceLine { sku: string; name: string; category: string; listPriceCents: number; netPriceCents: number; rule: string }

/** Account-specific net prices for a set of SKUs (contract price, else tier discount, else list). */
export const quote = (skus: string[]) => skus.length
  ? api<PriceLine[]>('/api/accounts/me/quote', { method: 'POST', body: JSON.stringify({ skus }) }).then((l) => Object.fromEntries(l.map((x) => [x.sku, x])))
  : Promise.resolve({} as Record<string, PriceLine>);
export interface Dependency { dependency: string; circuit: string; failureRate: number; bufferedCalls: number; notPermittedCalls: number; bulkheadAvailable: number; retriedCalls: number }
