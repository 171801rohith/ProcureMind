/*
  Role helpers for the React SPA (Phase 8).

  The `roles` claim is minted by auth-service into both the access and the ID token, so the
  role the UI reads is the same one the gateway and each service enforce. This is presentation
  only: hiding a control is a convenience, never a security boundary. Every protected call is
  still authorized server-side, so a VIEWER who forces an upload request gets a 403.

  See docs/AUTH_IMPLEMENTATION_PLAN.md section 12.
*/
import { useAuth } from 'react-oidc-context';
import { AUTH_REQUIRED } from './authFlags';

export const ROLE_ADMIN = 'ADMIN';
export const ROLE_ANALYST = 'ANALYST';
export const ROLE_VIEWER = 'VIEWER';

/** Roles allowed to ingest contracts and use the LLM assistant. */
const WRITE_ROLES = [ROLE_ANALYST, ROLE_ADMIN];

/** The `roles` claim, or an empty list when it is absent or not an array. */
export function claimedRoles(user) {
  const roles = user?.profile?.roles;
  return Array.isArray(roles) ? roles : [];
}

/**
 * Pure form of the write rule, so it can be exercised without a React tree.
 * `authRequired` false means the login gate is off and nothing is hidden.
 */
export function hasWriteAccess(roles, authRequired = AUTH_REQUIRED) {
  if (!authRequired) return true;
  return roles.some((role) => WRITE_ROLES.includes(role));
}

/**
 * Pure form of the admin rule, gating the user-management screen.
 *
 * Note the asymmetry with `hasWriteAccess`: disabling the login gate does NOT grant admin.
 * With no token there is nothing to send to `/api/users`, so pretending otherwise would
 * only render a screen whose every request fails with a 401.
 */
export function isAdminRole(roles) {
  return roles.includes(ROLE_ADMIN);
}

/**
 * `canWrite` gates upload and chat, `isAdmin` gates user management, and `roles` is
 * exposed for labelling.
 */
export function useRoles() {
  const auth = useAuth();
  const roles = claimedRoles(auth?.user);

  return { roles, canWrite: hasWriteAccess(roles), isAdmin: isAdminRole(roles) };
}
