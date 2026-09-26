import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { CommandEvent } from '../../events';
import { EventQueue, QueueStorage, STORAGE_KEY, SendResult } from '../../queue';

class MemoryStorage implements QueueStorage {
  readonly data = new Map<string, unknown>();
  writes = 0;
  get<T>(key: string): T | undefined {
    return this.data.get(key) as T | undefined;
  }
  update(key: string, value: unknown): Thenable<void> {
    this.writes++;
    this.data.set(key, JSON.parse(JSON.stringify(value)));
    return Promise.resolve();
  }
}

function ev(seq: number): CommandEvent {
  return {
    sessionId: 's',
    seq,
    commandText: `cmd ${seq}`,
    redacted: false,
    os: 'linux',
    exitCode: 0,
    startedAt: '2026-09-01T10:00:00.000Z',
    durationMs: 1,
  };
}

/** A fake server: answers with the scripted results in order, then 'ok'; records every batch. */
function server(...results: SendResult[]) {
  const batches: number[][] = [];
  const send = vi.fn(async (events: CommandEvent[]) => {
    batches.push(events.map((e) => e.seq));
    return results.shift() ?? 'ok';
  });
  return { send, batches };
}

describe('EventQueue', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('sends queued events in order, in batches of 50', async () => {
    const storage = new MemoryStorage();
    storage.data.set(STORAGE_KEY, Array.from({ length: 120 }, (_, i) => ev(i)));
    const { send, batches } = server();
    const queue = new EventQueue(storage, send);

    await queue.flush();

    expect(batches.map((b) => b.length)).toEqual([50, 50, 20]);
    expect(batches.flat()).toEqual(Array.from({ length: 120 }, (_, i) => i));
    expect(queue.size()).toBe(0);
  });

  it('caps at 5000 and drops the oldest', () => {
    const { send } = server('retry');
    const queue = new EventQueue(new MemoryStorage(), send, { cap: 5 });
    for (let i = 0; i < 8; i++) {
      queue.enqueue(ev(i));
    }
    expect(queue.size()).toBe(5);
  });

  it('uses the 5000 cap by default and trims an oversized stored queue on load', () => {
    const storage = new MemoryStorage();
    storage.data.set(STORAGE_KEY, Array.from({ length: 5003 }, (_, i) => ev(i)));
    const queue = new EventQueue(storage, server().send);
    expect(queue.size()).toBe(5000);
  });

  it('keeps events and backs off exponentially from 1 s to 60 s while the server is down', async () => {
    const spy = vi.spyOn(globalThis, 'setTimeout');
    const { send } = server(...Array<SendResult>(9).fill('retry'));
    const queue = new EventQueue(new MemoryStorage(), send);
    queue.enqueue(ev(1));

    await vi.advanceTimersByTimeAsync(100_000);
    expect(queue.size()).toBe(1);
    await vi.advanceTimersByTimeAsync(200_000);

    const waits = spy.mock.calls.map((c) => c[1]).filter((ms): ms is number => typeof ms === 'number' && ms >= 1000);
    expect(waits).toEqual([1000, 2000, 4000, 8000, 16000, 32000, 60000, 60000, 60000]);
    expect(send).toHaveBeenCalledTimes(10);
    expect(queue.size()).toBe(0);
  });

  it('retries after the backoff and then delivers', async () => {
    const { send, batches } = server('retry');
    const queue = new EventQueue(new MemoryStorage(), send);
    queue.enqueue(ev(1));
    await vi.advanceTimersByTimeAsync(0);
    expect(queue.size()).toBe(1);

    await vi.advanceTimersByTimeAsync(1000);

    expect(queue.size()).toBe(0);
    expect(batches).toEqual([[1], [1]]);
    expect(queue.currentBackoffMs()).toBe(1000);
  });

  it('treats a throwing sender as retry', async () => {
    const send = vi.fn().mockRejectedValueOnce(new Error('network')).mockResolvedValue('ok');
    const queue = new EventQueue(new MemoryStorage(), send);
    queue.enqueue(ev(1));
    await vi.advanceTimersByTimeAsync(0);
    expect(queue.size()).toBe(1);
    await vi.advanceTimersByTimeAsync(1000);
    expect(queue.size()).toBe(0);
  });

  it('does not send while a retry is pending, even when new events arrive', async () => {
    const { send } = server('retry');
    const queue = new EventQueue(new MemoryStorage(), send);
    queue.enqueue(ev(1));
    await vi.advanceTimersByTimeAsync(0);
    queue.enqueue(ev(2));
    await vi.advanceTimersByTimeAsync(0);
    expect(send).toHaveBeenCalledTimes(1);
  });

  it('drops a batch the server rejects as invalid and carries on', async () => {
    const storage = new MemoryStorage();
    storage.data.set(STORAGE_KEY, Array.from({ length: 60 }, (_, i) => ev(i)));
    const { send, batches } = server('drop');
    const queue = new EventQueue(storage, send);

    await queue.flush();

    expect(batches.map((b) => b.length)).toEqual([50, 10]);
    expect(queue.size()).toBe(0);
  });

  it('pauses on unauthorized and resumes after sign-in', async () => {
    const { send } = server('unauthorized');
    const queue = new EventQueue(new MemoryStorage(), send);
    queue.enqueue(ev(1));
    await vi.advanceTimersByTimeAsync(0);
    expect(queue.isPaused()).toBe(true);

    queue.enqueue(ev(2));
    await vi.advanceTimersByTimeAsync(120_000);
    expect(send).toHaveBeenCalledTimes(1);

    await queue.resume();
    expect(queue.isPaused()).toBe(false);
    expect(queue.size()).toBe(0);
  });

  it('resume cancels a pending backoff and sends immediately', async () => {
    const { send } = server('retry', 'retry');
    const queue = new EventQueue(new MemoryStorage(), send);
    queue.enqueue(ev(1));
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(1000);
    expect(queue.currentBackoffMs()).toBe(4000);

    await queue.resume();

    expect(queue.size()).toBe(0);
    expect(queue.currentBackoffMs()).toBe(1000);
  });

  it('keeps events that arrive while a batch is in flight', async () => {
    let release!: (r: SendResult) => void;
    const send = vi.fn(
      (events: CommandEvent[]) =>
        new Promise<SendResult>((resolve) => {
          if (events[0].seq === 1) {
            release = resolve;
          } else {
            resolve('ok');
          }
        }),
    );
    const queue = new EventQueue(new MemoryStorage(), send);
    queue.enqueue(ev(1));
    queue.enqueue(ev(2));
    expect(queue.size()).toBe(2);
    release('ok');
    await vi.advanceTimersByTimeAsync(0);
    expect(queue.size()).toBe(0);
    expect(send.mock.calls.map(([events]) => events.map((e) => e.seq))).toEqual([[1], [2]]);
  });

  it('persists to storage (debounced) and restores after a restart', async () => {
    const storage = new MemoryStorage();
    const first = new EventQueue(storage, server(...Array<SendResult>(5).fill('retry')).send);
    first.enqueue(ev(1));
    first.enqueue(ev(2));
    first.enqueue(ev(3));
    await vi.advanceTimersByTimeAsync(500);
    expect(storage.writes).toBe(1);
    await first.dispose();

    const { send, batches } = server();
    const second = new EventQueue(storage, send);
    expect(second.size()).toBe(3);
    await second.flush();
    expect(batches).toEqual([[1, 2, 3]]);
  });

  it('reports size changes', async () => {
    const sizes: number[] = [];
    const queue = new EventQueue(new MemoryStorage(), server().send, {}, (n) => sizes.push(n));
    queue.enqueue(ev(1));
    await vi.advanceTimersByTimeAsync(0);
    expect(sizes).toEqual([1, 0]);
  });

  it('enqueue returns without waiting for the network', () => {
    const send = vi.fn(() => new Promise<SendResult>(() => undefined));
    const queue = new EventQueue(new MemoryStorage(), send);
    const started = Date.now();
    queue.enqueue(ev(1));
    expect(Date.now() - started).toBeLessThan(5);
    expect(queue.size()).toBe(1);
  });
});
