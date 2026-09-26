import { describe, expect, it, vi } from 'vitest';
import { ApiError, Fetch, RecallClient } from '../../api';
import type { CommandEvent } from '../../events';

const event: CommandEvent = {
  sessionId: 's',
  seq: 1,
  commandText: 'ls',
  redacted: false,
  os: 'linux',
  exitCode: 0,
  startedAt: '2026-09-01T10:00:00.000Z',
  durationMs: 1,
};

function respond(status: number, body?: unknown): Fetch {
  return vi.fn(async () => new Response(body === undefined ? null : JSON.stringify(body), { status })) as Fetch;
}

describe('RecallClient', () => {
  it('posts events with the bearer token to /api/v1/events', async () => {
    const fetchImpl = respond(202, { accepted: 1 });
    const client = new RecallClient(() => 'http://server:8080/', async () => 'jwt', fetchImpl);

    expect(await client.postEvents([event])).toBe('ok');

    const [url, init] = vi.mocked(fetchImpl).mock.calls[0];
    expect(url).toBe('http://server:8080/api/v1/events');
    expect((init?.headers as Record<string, string>).authorization).toBe('Bearer jwt');
    expect(JSON.parse(init?.body as string)).toEqual({ events: [event] });
  });

  it.each([
    [401, 'unauthorized'],
    [403, 'unauthorized'],
    [400, 'drop'],
    [413, 'drop'],
    [429, 'retry'],
    [500, 'retry'],
    [503, 'retry'],
  ])('maps HTTP %i to %s', async (status, expected) => {
    const client = new RecallClient(() => 'http://s', async () => 'jwt', respond(status, { detail: 'x' }));
    expect(await client.postEvents([event])).toBe(expected);
  });

  it('maps a network failure to retry', async () => {
    const failing = vi.fn(async () => {
      throw new TypeError('fetch failed');
    }) as Fetch;
    expect(await new RecallClient(() => 'http://s', async () => 'jwt', failing).postEvents([event])).toBe('retry');
  });

  it('does not call the server without a token', async () => {
    const fetchImpl = respond(202, {});
    const client = new RecallClient(() => 'http://s', async () => undefined, fetchImpl);
    expect(await client.postEvents([event])).toBe('unauthorized');
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it('logs in without a token and returns the parsed body', async () => {
    const fetchImpl = respond(200, { token: 't', expiresAt: '2030-01-01T00:00:00Z', teamId: 'team' });
    const client = new RecallClient(() => 'http://s', async () => undefined, fetchImpl);

    await expect(client.login({ email: 'a@b.co', password: 'pw' })).resolves.toEqual({
      token: 't',
      expiresAt: '2030-01-01T00:00:00Z',
      teamId: 'team',
    });
    const [url, init] = vi.mocked(fetchImpl).mock.calls[0];
    expect(url).toBe('http://s/api/v1/auth/login');
    expect((init?.headers as Record<string, string>).authorization).toBeUndefined();
  });

  it('surfaces the problem detail on failure', async () => {
    const client = new RecallClient(() => 'http://s', async () => undefined,
      respond(404, { title: 'Unknown invite code', detail: 'No team has this invite code.' }));
    await expect(client.join({ inviteCode: 'X', email: 'a@b.co', password: 'pw', displayName: 'A' }))
      .rejects.toEqual(new ApiError(404, 'No team has this invite code.'));
  });

  it('falls back to the status text when the error body is not JSON', async () => {
    const fetchImpl = vi.fn(async () => new Response('<html>bad gateway</html>', { status: 502, statusText: 'Bad Gateway' })) as Fetch;
    const client = new RecallClient(() => 'http://s', async () => undefined, fetchImpl);
    await expect(client.register({ teamName: 't', email: 'a@b.co', password: 'pw', displayName: 'A' }))
      .rejects.toEqual(new ApiError(502, 'Bad Gateway'));
  });

  it('searches with the token and returns the answer', async () => {
    const answer = { command: 'docker ps -a', source: 'TEAM', confidence: 0.64, explanation: null, fixId: null,
      provenance: { solvedBy: 'Ada', lastUsedAt: '2026-09-20T10:00:00Z', timesUsed: 2 } };
    const fetchImpl = respond(200, answer);
    const client = new RecallClient(() => 'http://s', async () => 'jwt', fetchImpl);

    await expect(client.search({ query: 'all containers', repoName: 'shop' })).resolves.toEqual(answer);
    const [url, init] = vi.mocked(fetchImpl).mock.calls[0];
    expect(url).toBe('http://s/api/v1/search');
    expect(JSON.parse(init?.body as string)).toEqual({ query: 'all containers', repoName: 'shop' });
    expect((init?.headers as Record<string, string>).authorization).toBe('Bearer jwt');
  });

  it('search surfaces 401 as an ApiError', async () => {
    const client = new RecallClient(() => 'http://s', async () => 'old', respond(401, { detail: 'A valid bearer token is required.' }));
    await expect(client.search({ query: 'x' })).rejects.toEqual(new ApiError(401, 'A valid bearer token is required.'));
  });

  it('resolves an error with the token', async () => {
    const answer = { command: 'npx kill-port 3000', source: 'TEAM', confidence: 1, explanation: null, fixId: 'f1',
      provenance: { solvedBy: 'Ada', lastUsedAt: '2026-09-20T10:00:00Z', timesUsed: 3 } };
    const fetchImpl = respond(200, answer);
    const client = new RecallClient(() => 'http://s', async () => 'jwt', fetchImpl);
    const body = { commandText: 'npm run dev', errorText: 'EADDRINUSE', exitCode: 1, osUser: 'ada' };

    await expect(client.resolveError(body)).resolves.toEqual(answer);
    const [url, init] = vi.mocked(fetchImpl).mock.calls[0];
    expect(url).toBe('http://s/api/v1/errors/resolve');
    expect(JSON.parse(init?.body as string)).toEqual(body);
  });

  it('sends fix feedback and accepts 204 without a body', async () => {
    const fetchImpl = vi.fn(async () => new Response(null, { status: 204 })) as Fetch;
    const client = new RecallClient(() => 'http://s', async () => 'jwt', fetchImpl);

    await client.fixFeedback('f 1', false);
    const [url, init] = vi.mocked(fetchImpl).mock.calls[0];
    expect(url).toBe('http://s/api/v1/fixes/f%201/feedback');
    expect(JSON.parse(init?.body as string)).toEqual({ worked: false });
  });

  it('surfaces 403 on a fix of another team', async () => {
    const client = new RecallClient(() => 'http://s', async () => 'jwt', respond(403, { detail: 'This fix belongs to another team.' }));
    await expect(client.fixFeedback('x', true)).rejects.toEqual(new ApiError(403, 'This fix belongs to another team.'));
  });
});
