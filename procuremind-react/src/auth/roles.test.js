import { describe, it, expect } from 'vitest';
import { claimedRoles, hasWriteAccess, isAdminRole } from './roles';

describe('role gating (Phase 8)', () => {
  it('reads the roles claim the gateway also enforces', () => {
    expect(claimedRoles({ profile: { roles: ['VIEWER', 'ANALYST'] } })).toEqual(['VIEWER', 'ANALYST']);
  });

  it('treats an absent or malformed roles claim as no roles', () => {
    expect(claimedRoles(undefined)).toEqual([]);
    expect(claimedRoles({ profile: {} })).toEqual([]);
    expect(claimedRoles({ profile: { roles: 'ADMIN' } })).toEqual([]);
  });

  it('allows upload and chat for ANALYST and ADMIN only', () => {
    expect(hasWriteAccess(['ANALYST'], true)).toBe(true);
    expect(hasWriteAccess(['ADMIN'], true)).toBe(true);
    expect(hasWriteAccess(['VIEWER'], true)).toBe(false);
    expect(hasWriteAccess([], true)).toBe(false);
  });

  it('hides nothing when the login gate is disabled', () => {
    expect(hasWriteAccess([], false)).toBe(true);
  });

  it('restricts user management to ADMIN', () => {
    expect(isAdminRole(['ADMIN'])).toBe(true);
    expect(isAdminRole(['ANALYST', 'VIEWER'])).toBe(false);
    expect(isAdminRole([])).toBe(false);
  });

  it('does not grant admin just because the login gate is disabled', () => {
    // Unlike write access, admin has no anonymous fallback: with no token there is nothing
    // to authenticate the /api/users calls the screen would immediately make.
    expect(hasWriteAccess([], false)).toBe(true);
    expect(isAdminRole([])).toBe(false);
  });
});
