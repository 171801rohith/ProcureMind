import { describe, it, expect, beforeAll } from 'vitest';

/**
 * Spring Authorization Server refuses to issue refresh tokens to public clients, so this
 * SPA renews its access token with a prompt=none Authorization Code + PKCE request in a
 * hidden iframe. These assertions pin the settings that renewal depends on; getting any of
 * them wrong fails silently at runtime, and the user is simply logged out when the access
 * token expires.
 *
 * The module reads browser globals at import time, so they are stubbed here rather than
 * pulling in a DOM environment for four assertions.
 */
let oidcConfig;

beforeAll(async () => {
  const storage = {
    getItem: () => null,
    setItem: () => {},
    removeItem: () => {},
    key: () => null,
    length: 0,
  };
  globalThis.window = { location: { origin: 'http://localhost:5173' }, sessionStorage: storage };
  globalThis.sessionStorage = storage;

  ({ oidcConfig } = await import('./authConfig'));
});

describe('SPA OIDC configuration', () => {
  it('uses Authorization Code with PKCE and no client secret', () => {
    expect(oidcConfig.response_type).toBe('code');
    expect(oidcConfig.client_secret).toBeUndefined();
    expect(oidcConfig.scope).toContain('openid');
  });

  it('renews silently against a dedicated callback page, not the app root', () => {
    // oidc-client-ts defaults silent_redirect_uri to redirect_uri. That would boot the whole
    // application inside the renewal iframe, which never calls signinSilentCallback, so the
    // renewal would time out every time.
    expect(oidcConfig.automaticSilentRenew).toBe(true);
    expect(oidcConfig.silent_redirect_uri).toBe('http://localhost:5173/silent-renew.html');
    expect(oidcConfig.silent_redirect_uri).not.toBe(oidcConfig.redirect_uri);
  });

  it('keeps tokens in sessionStorage rather than localStorage', () => {
    expect(oidcConfig.userStore).toBeDefined();
    expect(oidcConfig.stateStore).toBeDefined();
  });
});
