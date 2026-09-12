import { describe, it, expect, beforeEach, vi } from 'vitest';
import { ApiClient, setAccessToken, setOnAuthExpired } from './client';

function jsonResponse(status, body) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  };
}

const mockFetch = vi.fn();

beforeEach(() => {
  mockFetch.mockReset();
  global.fetch = mockFetch;
  setAccessToken(null);
  setOnAuthExpired(null);
});

describe('user administration API (Phase 8 admin dashboard)', () => {
  it('lists users through the gateway with the bearer token attached', async () => {
    setAccessToken('admin-token');
    mockFetch.mockResolvedValueOnce(
      jsonResponse(200, [{ id: 'u1', username: 'admin', email: null, enabled: true, roles: ['ADMIN'] }])
    );

    const users = await ApiClient.listUsers();

    const [url, options] = mockFetch.mock.calls[0];
    expect(url).toContain('/api/users');
    expect(options.headers.Authorization).toBe('Bearer admin-token');
    expect(users).toHaveLength(1);
    expect(users[0].username).toBe('admin');
  });

  it('never receives or forwards a password hash', async () => {
    setAccessToken('admin-token');
    mockFetch.mockResolvedValueOnce(
      jsonResponse(200, [{ id: 'u1', username: 'admin', email: null, enabled: true, roles: ['ADMIN'] }])
    );

    const [user] = await ApiClient.listUsers();

    expect(Object.keys(user)).toEqual(['id', 'username', 'email', 'enabled', 'roles']);
    expect(user).not.toHaveProperty('passwordHash');
    expect(user).not.toHaveProperty('password');
  });

  it('posts the new user and the selected roles to the backend', async () => {
    setAccessToken('admin-token');
    mockFetch.mockResolvedValueOnce(
      jsonResponse(201, { id: 'u2', username: 'analyst', email: null, enabled: true, roles: ['ANALYST'] })
    );

    const created = await ApiClient.createUser({
      username: 'analyst',
      password: 'analyst-dev-pw',
      email: '',
      roles: ['ANALYST'],
    });

    const [url, options] = mockFetch.mock.calls[0];
    expect(url).toContain('/api/users');
    expect(options.method).toBe('POST');
    expect(JSON.parse(options.body)).toEqual({
      username: 'analyst',
      password: 'analyst-dev-pw',
      email: null,
      roles: ['ANALYST'],
    });
    expect(created.roles).toEqual(['ANALYST']);
  });

  it('surfaces a duplicate username as a 409 the form can explain', async () => {
    setAccessToken('admin-token');
    mockFetch.mockResolvedValueOnce(
      jsonResponse(409, { status: 409, message: 'Username already exists: admin' })
    );

    await expect(
      ApiClient.createUser({ username: 'admin', password: 'password1', roles: ['VIEWER'] })
    ).rejects.toMatchObject({ status: 409 });
  });

  it('surfaces per-field validation errors from the backend', async () => {
    setAccessToken('admin-token');
    mockFetch.mockResolvedValueOnce(
      jsonResponse(400, {
        status: 400,
        message: 'Validation failed',
        fieldErrors: { password: 'size must be between 8 and 100' },
      })
    );

    await expect(
      ApiClient.createUser({ username: 'x', password: 'short', roles: ['VIEWER'] })
    ).rejects.toMatchObject({
      status: 400,
      fieldErrors: { password: 'size must be between 8 and 100' },
    });
  });

  it('surfaces a 403 so a non-admin sees a denial rather than an empty list', async () => {
    setAccessToken('viewer-token');
    mockFetch.mockResolvedValueOnce(jsonResponse(403, null));

    await expect(ApiClient.listUsers()).rejects.toMatchObject({ status: 403 });
  });

  it('does not mask the status when the error body is not JSON', async () => {
    setAccessToken('admin-token');
    mockFetch.mockResolvedValueOnce({
      ok: false,
      status: 500,
      json: async () => {
        throw new Error('not json');
      },
    });

    await expect(ApiClient.listUsers()).rejects.toMatchObject({ status: 500 });
  });
});
