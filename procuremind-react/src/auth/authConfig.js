/*
  OIDC configuration for the ProcureMind React SPA (Phase 5).

  - Authorization Code + PKCE against the existing auth-service (Spring Authorization
    Server) issuer. No new identity provider.
  - Public client `procuremind-react` (no secret; client_authentication_methods = none).
  - The OIDC session (User object incl. access + refresh token) is kept in sessionStorage
    only — never localStorage.
  - No router: the redirect URI is the app root and `onSigninCallback` strips the
    `?code=&state=` query string so the existing tab-based shell is untouched.

  See docs/AUTH_IMPLEMENTATION_PLAN.md sections 5, 13, 14.
*/
import { WebStorageStateStore } from 'oidc-client-ts';

export { AUTH_REQUIRED } from './authFlags';

const env = import.meta.env;

export const AUTH_AUTHORITY = env.VITE_OIDC_AUTHORITY || 'http://localhost:8083';
export const AUTH_CLIENT_ID = env.VITE_OIDC_CLIENT_ID || 'procuremind-react';
export const AUTH_SCOPE = env.VITE_OIDC_SCOPE || 'openid profile roles';


export const oidcConfig = {
  authority: AUTH_AUTHORITY,
  client_id: AUTH_CLIENT_ID,
  redirect_uri: window.location.origin + '/',
  post_logout_redirect_uri: window.location.origin + '/',
  response_type: 'code',
  scope: AUTH_SCOPE,
  // Spring Authorization Server does not issue refresh tokens to public clients (see
  // OAuth2RefreshTokenGenerator: it returns null for the authorization_code grant when the
  // client authenticates with `none`). Renewal therefore goes through a prompt=none
  // Authorization Code + PKCE request in a hidden iframe rather than a refresh token.
  //
  // silent_redirect_uri defaults to redirect_uri, which would boot the entire SPA inside
  // that iframe and never post the result back, so renewal must land on its own page.
  automaticSilentRenew: true,
  silent_redirect_uri: window.location.origin + '/silent-renew.html',
  // Keep everything (tokens + transient PKCE state) in sessionStorage, not localStorage.
  userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  stateStore: new WebStorageStateStore({ store: window.sessionStorage }),
  onSigninCallback: () => {
    window.history.replaceState({}, document.title, window.location.pathname);
  },
};
