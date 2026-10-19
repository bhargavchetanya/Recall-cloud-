import type { Answer, ResolveRequest, SearchRequest } from './answer';
import type { CommandEvent } from './events';
import type { SendResult } from './queue';

export interface AuthResponse {
  token: string;
  expiresAt: string;
  teamId: string;
  inviteCode?: string;
}

/** A non-2xx answer; `detail` comes from the RFC 7807 body when there is one. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly detail: string,
  ) {
    super(`HTTP ${status}: ${detail}`);
  }
}

export type Fetch = typeof fetch;

export class RecallClient {
  constructor(
    private readonly serverUrl: () => string,
    private readonly token: () => Promise<string | undefined>,
    private readonly fetchImpl: Fetch = fetch,
    private readonly timeoutMs = 10_000,
  ) {}

  register(body: { teamName: string; email: string; password: string; displayName: string }): Promise<AuthResponse> {
    return this.postJson<AuthResponse>('/auth/register', body, false);
  }

  join(body: { inviteCode: string; email: string; password: string; displayName: string }): Promise<AuthResponse> {
    return this.postJson<AuthResponse>('/auth/join', body, false);
  }

  login(body: { email: string; password: string }): Promise<AuthResponse> {
    return this.postJson<AuthResponse>('/auth/login', body, false);
  }

  /** One command for a task in plain words, from the caller's team only. */
  search(body: SearchRequest): Promise<Answer> {
    return this.postJson<Answer>('/search', body, true);
  }

  /** The team's fix for an error that just happened, or NONE. */
  resolveError(body: ResolveRequest): Promise<Answer> {
    return this.postJson<Answer>('/errors/resolve', body, true);
  }

  /** Tells the server whether a fix worked. */
  async fixFeedback(fixId: string, worked: boolean): Promise<void> {
    await this.postJson<unknown>(`/fixes/${encodeURIComponent(fixId)}/feedback`, { worked }, true);
  }

  /** Maps the outcome onto what the offline queue should do next. Never throws. */
  async postEvents(events: CommandEvent[]): Promise<SendResult> {
    try {
      await this.postJson('/events', { events }, true);
      return 'ok';
    } catch (err) {
      if (err instanceof ApiError) {
        if (err.status === 401 || err.status === 403) {
          return 'unauthorized';
        }
        if (err.status === 400 || err.status === 413 || err.status === 422) {
          return 'drop';
        }
      }
      return 'retry';
    }
  }

  private async postJson<T>(path: string, body: unknown, authenticated: boolean): Promise<T> {
    const headers: Record<string, string> = { 'content-type': 'application/json' };
    if (authenticated) {
      const token = await this.token();
      if (!token) {
        throw new ApiError(401, 'Not signed in');
      }
      headers.authorization = `Bearer ${token}`;
    }
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);
    try {
      const res = await this.fetchImpl(`${this.serverUrl().replace(/\/+$/, '')}/api/v1${path}`, {
        method: 'POST',
        headers,
        body: JSON.stringify(body),
        signal: controller.signal,
      });
      const text = await res.text();
      const parsed = text ? safeJson(text) : undefined;
      if (!res.ok) {
        const detail = (parsed as { detail?: string } | undefined)?.detail ?? res.statusText;
        throw new ApiError(res.status, detail);
      }
      return parsed as T;
    } finally {
      clearTimeout(timer);
    }
  }
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}
