import { UserManager, WebStorageStateStore, type User } from 'oidc-client-ts';

declare global { interface Window { __CONFIG__: { oidcAuthority: string; oidcClientId: string } } }

export const users = new UserManager({
  authority: window.__CONFIG__.oidcAuthority,
  client_id: window.__CONFIG__.oidcClientId,
  redirect_uri: `${window.location.origin}/`,
  post_logout_redirect_uri: `${window.location.origin}/`,
  response_type: 'code',                 // authorization code + PKCE
  scope: 'openid profile',
  automaticSilentRenew: true,
  userStore: new WebStorageStateStore({ store: window.sessionStorage }),
});

export function roles(user: User | null): string[] {
  if (!user) return [];
  try {
    const p = JSON.parse(atob(user.access_token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
    return p.realm_access?.roles ?? [];
  } catch { return []; }
}

export async function initialUser(): Promise<User | null> {
  const q = new URLSearchParams(window.location.search);
  if (q.has('code') && q.has('state')) {
    const u = await users.signinRedirectCallback();
    window.history.replaceState({}, '', window.location.pathname + window.location.hash);
    return u;
  }
  const u = await users.getUser();
  return u && !u.expired ? u : null;
}
