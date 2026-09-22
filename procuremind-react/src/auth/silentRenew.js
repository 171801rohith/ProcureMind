/*
  Silent-renew iframe callback.

  Kept to the bare minimum on purpose: this runs inside a hidden iframe, so it must not
  import the application, its providers or its styles. It only completes the prompt=none
  response and posts the result to the parent window.
*/
import { UserManager } from 'oidc-client-ts';
import { oidcConfig } from './authConfig';

new UserManager(oidcConfig)
  .signinSilentCallback()
  .catch((error) => {
    // The parent treats a missing result as a failed renewal and falls back to a full
    // redirect, so swallowing this would only hide the reason.
    console.error('[ProcureMind] silent renew callback failed', error);
  });
