import type { CommandEvent } from './events';

/** The slice of vscode.Memento the queue needs; globalState in production, a Map in tests. */
export interface QueueStorage {
  get<T>(key: string): T | undefined;
  update(key: string, value: unknown): Thenable<void>;
}

/**
 * ok: stored remotely. retry: try again later (offline, 5xx, 429). drop: the server will never accept this batch
 * (400), so keeping it would block everything behind it. unauthorized: pause until the user signs in again.
 */
export type SendResult = 'ok' | 'retry' | 'drop' | 'unauthorized';

export type SendBatch = (events: CommandEvent[]) => Promise<SendResult>;

export interface QueueOptions {
  cap: number;
  batchSize: number;
  minBackoffMs: number;
  maxBackoffMs: number;
  persistDelayMs: number;
}

export const DEFAULT_QUEUE_OPTIONS: QueueOptions = {
  cap: 5000,
  batchSize: 50,
  minBackoffMs: 1000,
  maxBackoffMs: 60_000,
  persistDelayMs: 500,
};

export const STORAGE_KEY = 'recall.pendingEvents';

/**
 * Buffers events in globalState and sends them in order, in batches, with exponential backoff. Enqueue never
 * blocks: sending and persisting happen on timers. When full, the oldest events are dropped first.
 */
export class EventQueue {
  private items: CommandEvent[];
  private flushing = false;
  private paused = false;
  private backoffMs: number;
  private retryTimer: ReturnType<typeof setTimeout> | undefined;
  private persistTimer: ReturnType<typeof setTimeout> | undefined;
  private disposed = false;
  private readonly options: QueueOptions;

  constructor(
    private readonly storage: QueueStorage,
    private readonly send: SendBatch,
    options: Partial<QueueOptions> = {},
    private readonly onChange: (size: number) => void = () => undefined,
  ) {
    this.options = { ...DEFAULT_QUEUE_OPTIONS, ...options };
    this.items = [...(storage.get<CommandEvent[]>(STORAGE_KEY) ?? [])].slice(-this.options.cap);
    this.backoffMs = this.options.minBackoffMs;
  }

  size(): number {
    return this.items.length;
  }

  isPaused(): boolean {
    return this.paused;
  }

  /** Current wait before the next retry; exposed for tests and the status tooltip. */
  currentBackoffMs(): number {
    return this.backoffMs;
  }

  enqueue(event: CommandEvent): void {
    this.items.push(event);
    if (this.items.length > this.options.cap) {
      this.items.splice(0, this.items.length - this.options.cap);
    }
    this.changed();
    void this.flush();
  }

  /** Called after sign-in or when the backend comes back: clears the pause and backoff, sends now. */
  resume(): Promise<void> {
    this.paused = false;
    this.backoffMs = this.options.minBackoffMs;
    this.clearRetry();
    return this.flush();
  }

  /** Sends batches until the queue is empty or a send does not succeed. Concurrent calls share one run. */
  async flush(): Promise<void> {
    if (this.flushing || this.paused || this.disposed || this.retryTimer) {
      return;
    }
    this.flushing = true;
    try {
      while (this.items.length > 0 && !this.disposed) {
        const batch = this.items.slice(0, this.options.batchSize);
        let result: SendResult;
        try {
          result = await this.send(batch);
        } catch {
          result = 'retry';
        }
        if (result === 'ok' || result === 'drop') {
          const sent = new Set(batch);
          this.items = this.items.filter((e) => !sent.has(e));
          this.backoffMs = this.options.minBackoffMs;
          this.changed();
          continue;
        }
        if (result === 'unauthorized') {
          this.paused = true;
        } else {
          this.scheduleRetry();
        }
        return;
      }
    } finally {
      this.flushing = false;
    }
  }

  /** Writes pending events to storage immediately (used on deactivate). */
  async persistNow(): Promise<void> {
    if (this.persistTimer) {
      clearTimeout(this.persistTimer);
      this.persistTimer = undefined;
    }
    await this.storage.update(STORAGE_KEY, this.items);
  }

  async dispose(): Promise<void> {
    this.disposed = true;
    this.clearRetry();
    await this.persistNow();
  }

  private scheduleRetry(): void {
    const wait = this.backoffMs;
    this.backoffMs = Math.min(this.backoffMs * 2, this.options.maxBackoffMs);
    this.retryTimer = setTimeout(() => {
      this.retryTimer = undefined;
      void this.flush();
    }, wait);
  }

  private clearRetry(): void {
    if (this.retryTimer) {
      clearTimeout(this.retryTimer);
      this.retryTimer = undefined;
    }
  }

  private changed(): void {
    this.onChange(this.items.length);
    if (!this.persistTimer && !this.disposed) {
      this.persistTimer = setTimeout(() => {
        this.persistTimer = undefined;
        void this.storage.update(STORAGE_KEY, this.items);
      }, this.options.persistDelayMs);
    }
  }
}
