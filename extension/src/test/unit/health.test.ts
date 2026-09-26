import { describe, expect, it, vi } from 'vitest';
import { FetchLike, healthUrl, isBackendUp } from '../../health';

const respond = (ok: boolean, body: unknown): FetchLike => async () => ({ ok, json: async () => body });

describe('healthUrl', () => {
  it('appends the API path and tolerates trailing slashes', () => {
    expect(healthUrl('http://localhost:8080')).toBe('http://localhost:8080/api/v1/health');
    expect(healthUrl('http://localhost:8080//')).toBe('http://localhost:8080/api/v1/health');
  });
});

describe('isBackendUp', () => {
  it('is true when the backend reports UP', async () => {
    expect(await isBackendUp('http://x', respond(true, { status: 'UP', db: 'UP' }))).toBe(true);
  });

  it('is false when the backend reports DOWN', async () => {
    expect(await isBackendUp('http://x', respond(true, { status: 'DOWN' }))).toBe(false);
  });

  it('is false on a non-2xx response', async () => {
    expect(await isBackendUp('http://x', respond(false, { status: 'UP' }))).toBe(false);
  });

  it('is false when the body is not JSON', async () => {
    const badJson: FetchLike = async () => ({ ok: true, json: async () => { throw new SyntaxError('bad'); } });
    expect(await isBackendUp('http://x', badJson)).toBe(false);
  });

  it('is false when the request fails', async () => {
    const refused: FetchLike = async () => { throw new TypeError('fetch failed'); };
    expect(await isBackendUp('http://x', refused)).toBe(false);
  });

  it('aborts and returns false when the backend is too slow', async () => {
    vi.useFakeTimers();
    const hang: FetchLike = (_url, init) =>
      new Promise((_resolve, reject) => init.signal.addEventListener('abort', () => reject(new Error('aborted'))));
    const result = isBackendUp('http://x', hang, 3000);
    await vi.advanceTimersByTimeAsync(3000);
    expect(await result).toBe(false);
    vi.useRealTimers();
  });
});
