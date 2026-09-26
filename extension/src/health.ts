export interface HealthResponse {
  ok: boolean;
  json(): Promise<unknown>;
}

export type FetchLike = (url: string, init: { signal: AbortSignal }) => Promise<HealthResponse>;

export function healthUrl(serverUrl: string): string {
  return `${serverUrl.replace(/\/+$/, '')}/api/v1/health`;
}

/** True only when the backend responds within the timeout and reports `status: "UP"`. */
export async function isBackendUp(serverUrl: string, fetchImpl: FetchLike, timeoutMs = 3000): Promise<boolean> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const res = await fetchImpl(healthUrl(serverUrl), { signal: controller.signal });
    if (!res.ok) {
      return false;
    }
    const body = (await res.json()) as { status?: unknown } | null;
    return body?.status === 'UP';
  } catch {
    return false;
  } finally {
    clearTimeout(timer);
  }
}
