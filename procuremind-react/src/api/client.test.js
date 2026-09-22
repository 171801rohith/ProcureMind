import { describe, it, expect, beforeEach, vi } from 'vitest';
import { ApiClient, setAccessToken, setOnAuthExpired } from './client';

function jsonResponse(status, body) {
  return { ok: status >= 200 && status < 300, status, json: async () => body };
}

const mockFetch = vi.fn();

beforeEach(() => {
  mockFetch.mockReset();
  global.fetch = mockFetch;
  setAccessToken(null);
  setOnAuthExpired(null);
});

describe('ApiClient auth wiring (Phase 5)', () => {
  it('attaches Authorization: Bearer <token> when a token is set', async () => {
    setAccessToken('tok-1');
    mockFetch.mockResolvedValueOnce(jsonResponse(200, { totalAnalyzed: 3 }));

    const res = await ApiClient.getDashboardMetrics();

    expect(res).toEqual({ totalAnalyzed: 3 });
    expect(mockFetch.mock.calls[0][1].headers.Authorization).toBe('Bearer tok-1');
  });

  it('sends no Authorization header when no token is set', async () => {
    mockFetch.mockResolvedValueOnce(jsonResponse(200, {}));

    await ApiClient.getDashboardMetrics();

    expect(mockFetch.mock.calls[0][1].headers.Authorization).toBeUndefined();
  });

  it('on 401 refreshes once and retries with the fresh token', async () => {
    setAccessToken('stale');
    let refreshCalls = 0;
    setOnAuthExpired(async () => {
      refreshCalls += 1;
      setAccessToken('fresh');
      return true;
    });
    mockFetch
      .mockResolvedValueOnce(jsonResponse(401, {}))
      .mockResolvedValueOnce(jsonResponse(200, { totalAnalyzed: 7 }));

    const res = await ApiClient.getDashboardMetrics();

    expect(refreshCalls).toBe(1);
    expect(mockFetch).toHaveBeenCalledTimes(2);
    expect(mockFetch.mock.calls[1][1].headers.Authorization).toBe('Bearer fresh');
    expect(res).toEqual({ totalAnalyzed: 7 });
  });

  it('does not retry more than once — returns null when re-auth fails', async () => {
    setAccessToken('x');
    setOnAuthExpired(async () => false);
    mockFetch.mockResolvedValueOnce(jsonResponse(401, {}));

    const res = await ApiClient.getDashboardMetrics();

    expect(res).toBeNull();
    expect(mockFetch).toHaveBeenCalledTimes(1);
  });

  it('uploadContract attaches the bearer and retries once on 401', async () => {
    setAccessToken('u1');
    setOnAuthExpired(async () => {
      setAccessToken('u2');
      return true;
    });
    mockFetch
      .mockResolvedValueOnce(jsonResponse(401, {}))
      .mockResolvedValueOnce(jsonResponse(202, { id: 'c1' }));

    const res = await ApiClient.uploadContract(new Blob(['pdf']), 'Acme');

    expect(res).toEqual({ id: 'c1' });
    expect(mockFetch).toHaveBeenCalledTimes(2);
    expect(mockFetch.mock.calls[1][1].headers.Authorization).toBe('Bearer u2');
  });

  it('sendChatMessage keeps Content-Type and attaches the bearer', async () => {
    setAccessToken('c-tok');
    mockFetch.mockResolvedValueOnce(jsonResponse(200, { answer: 'hi' }));

    await ApiClient.sendChatMessage('hello', 'conv-1');

    const headers = mockFetch.mock.calls[0][1].headers;
    expect(headers.Authorization).toBe('Bearer c-tok');
    expect(headers['Content-Type']).toBe('application/json');
  });
});
