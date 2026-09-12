/*
  Auth feature flags, kept separate from `authConfig.js` because that module touches
  `window.location` at import time and so cannot be loaded outside a browser environment.
*/

/** When `false`, the login gate is skipped and the app behaves as it did pre-auth. */
export const AUTH_REQUIRED = (import.meta.env.VITE_AUTH_REQUIRED ?? 'true') !== 'false';
