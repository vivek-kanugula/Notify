/**
 * The in-app stream reconnect loop and the resync handover, as a dependency-injected state machine
 * In-App SSE stream connection loop and reconnect resilience.
 *
 * This module deliberately imports nothing. Everything it touches - the token request, the `EventSource`, the timer
 * and the jitter source - arrives through {@link StreamLoopDeps}, so the whole loop is exercised by deterministic
 * tests with no browser, no network and no real clock. `sseClient.ts` is the thin wiring that supplies the real ones.
 *
 * Key connection loop invariants:
 *
 *  1. **A rejected token request used to escape the loop.** `await requestToken(...)` sat outside any `try`, so a
 *     network failure produced an unhandled rejection and scheduled nothing: the stream never came back without a
 *     page reload. Acquisition failures are now caught, reported as a readable state, and retried with bounded
 *     exponential backoff and jitter.
 *  2. **A resync used to reconnect with the stale cursor.** The server emits `resync` *before* sending the rows and
 *     *before* advancing its cursor, so reconnecting with the same `lastEventId` reproduced the same condition
 *     immediately - an endless resync loop. The loop now *pauses* on resync and hands over: it reconnects only when
 *     the owner calls {@link InAppStreamLoop.resumeFrom} with the cursor its REST recovery actually reached.
 *  3. **The cursor used to be assigned, not advanced.** A duplicate or out-of-order frame could move it backwards,
 *     which would replay already-seen rows. It now only ever increases, compared as a `BigInt` because a 64-bit
 *     `seq` does not survive `Number`.
 *
 * Terminal authorization failures are handled on purpose rather than by waiting: a `401` or `403` from the token API
 * is not something a backoff can fix, so the loop stops and says so instead of retrying every thirty seconds for the
 * life of the tab.
 */

export type StreamState = 'idle' | 'connecting' | 'live' | 'waiting' | 'recovering' | 'stopped';

/** A granted single-use stream token. */
export interface TokenGrant {
  token: string;
  expiresInSeconds: number;
  streamUrl: string;
  admittedStreams: number;
  capPerTenant: number;
}

/** A readable refusal: the cap, or the provenance barrier. Unlike `EventSource.onerror`, this carries a reason. */
export interface TokenRefusal {
  refused: true;
  status: number;
  retryAfterSeconds: number;
  detail: string;
}

export interface StreamMessage {
  seq: string;
  notificationId: string;
  subject: string;
  body: string;
  read: boolean;
  createdAt: string;
}

/** The slice of `EventSource` this loop uses. */
export interface StreamSource {
  addEventListener(type: string, listener: (event: { data?: string }) => void): void;
  close(): void;
}

export interface StreamLoopDeps {
  requestToken(recipientRef: string): Promise<TokenGrant | TokenRefusal>;
  openSource(url: string): StreamSource;
  setTimer(run: () => void, delayMillis: number): unknown;
  clearTimer(handle: unknown): void;
  /** [0, 1). Injected so jitter is reproducible in tests. */
  random(): number;
}

export interface StreamLoopHandlers {
  onMessage(message: StreamMessage): void;
  /**
   * The server declined to truncate a long backlog. The loop is now PAUSED: it will not reconnect until
   * {@link InAppStreamLoop.resumeFrom} or {@link InAppStreamLoop.close} is called.
   */
  onResync(reason: string, fromCursor: string): void;
  onState(state: StreamState, detail?: string): void;
}

/** Bounds, so no failure mode can retry for ever or schedule an unbounded number of timers. */
export const MAX_BACKOFF_STEP = 6;
export const MAX_BACKOFF_MILLIS = 30_000;
export const MAX_CONSECUTIVE_TOKEN_FAILURES = 10;

export class InAppStreamLoop {
  private readonly recipientRef: string;
  private readonly handlers: StreamLoopHandlers;
  private readonly deps: StreamLoopDeps;

  private cursor: string | null;
  private source: StreamSource | null = null;
  private timer: unknown = null;
  private closed = true;
  private connecting = false;
  private paused = false;
  private attempt = 0;
  private tokenFailures = 0;
  /** Incremented by every close, resume and connect, so a late callback can tell it is stale. */
  private generation = 0;

  constructor(
    recipientRef: string,
    handlers: StreamLoopHandlers,
    deps: StreamLoopDeps,
    startCursor: string | null = null,
  ) {
    this.recipientRef = recipientRef;
    this.handlers = handlers;
    this.deps = deps;
    this.cursor = startCursor;
  }

  start(): void {
    if (!this.closed) return;                      // never two loops from one instance
    this.closed = false;
    this.paused = false;
    void this.connect();
  }

  /** Terminal for this instance. Idempotent, and nothing scheduled earlier can revive it. */
  close(): void {
    if (this.closed) {
      this.cancelTimer();
      return;
    }
    this.closed = true;
    this.paused = false;
    this.generation++;
    this.cancelTimer();
    this.closeSource();
    this.handlers.onState('stopped');
  }

  /** The cursor actually reached: the highest `seq` emitted to {@link StreamLoopHandlers.onMessage}. */
  currentCursor(): string | null {
    return this.cursor;
  }

  isPaused(): boolean {
    return this.paused;
  }

  isClosed(): boolean {
    return this.closed;
  }

  /**
   * Ends the paused state after a REST recovery and reconnects with a fresh token.
   *
   * <p>The cursor only moves forward: a recovery that read less than the stream already had cannot rewind it, which
   * is what stops the handover from replaying rows the user has seen.
   */
  resumeFrom(cursor: string | null): void {
    if (this.closed) return;
    this.advanceCursor(cursor);
    this.paused = false;
    this.attempt = 0;
    this.generation++;
    this.cancelTimer();
    void this.connect();
  }

  private advanceCursor(candidate: string | null | undefined): void {
    if (candidate === null || candidate === undefined || candidate === '') return;
    if (this.cursor === null) {
      this.cursor = candidate;
      return;
    }
    try {
      if (BigInt(candidate) > BigInt(this.cursor)) {
        this.cursor = candidate;
      }
    } catch {
      // A cursor that is not a decimal integer is ignored rather than trusted.
    }
  }

  private cancelTimer(): void {
    if (this.timer !== null) {
      this.deps.clearTimer(this.timer);
      this.timer = null;
    }
  }

  private closeSource(): void {
    if (this.source) {
      this.source.close();
      this.source = null;
    }
  }

  private async connect(): Promise<void> {
    if (this.closed || this.paused || this.connecting) return;
    this.connecting = true;
    const generation = this.generation;
    this.handlers.onState('connecting');

    let granted: TokenGrant | TokenRefusal;
    try {
      granted = await this.deps.requestToken(this.recipientRef);
    } catch (error) {
      this.connecting = false;
      if (this.closed || generation !== this.generation) return;    // closed or replaced while awaiting
      this.onTokenRequestFailed(error);
      return;
    }
    this.connecting = false;
    if (this.closed || generation !== this.generation) return;

    if ('refused' in granted) {
      this.onTokenRefused(granted);
      return;
    }

    this.tokenFailures = 0;
    this.openStream(granted, generation);
  }

  /** A rejected token request: a transient failure the loop must survive. This is finding F2. */
  private onTokenRequestFailed(error: unknown): void {
    this.tokenFailures++;
    const message = error instanceof Error ? error.message : String(error);
    if (this.tokenFailures >= MAX_CONSECUTIVE_TOKEN_FAILURES) {
      this.handlers.onState(
        'stopped',
        `the stream could not be started after ${this.tokenFailures} attempts (${message})`,
      );
      this.closed = true;
      this.generation++;
      this.cancelTimer();
      return;
    }
    this.handlers.onState('waiting', `connection unavailable: ${message}`);
    this.scheduleRetry(this.backoffMillis());
  }

  private onTokenRefused(refusal: TokenRefusal): void {
    if (refusal.status === 401 || refusal.status === 403) {
      // Not a backoff case. Waiting cannot mint authority the session does not have; the auth layer owns recovery.
      this.handlers.onState('stopped', `not authorized to stream (${refusal.status}): ${refusal.detail}`);
      this.closed = true;
      this.generation++;
      this.cancelTimer();
      return;
    }
    this.tokenFailures = 0;                         // the server answered; this is server-directed backoff
    this.handlers.onState('waiting', refusal.detail);
    this.scheduleRetry(Math.max(1000, refusal.retryAfterSeconds * 1000));
  }

  private openStream(grant: TokenGrant, generation: number): void {
    const params = new URLSearchParams({ token: grant.token });
    if (this.cursor) params.set('lastEventId', this.cursor);
    const source = this.deps.openSource(`${grant.streamUrl}?${params.toString()}`);
    this.source = source;

    const stale = () => this.closed || generation !== this.generation || this.source !== source;

    source.addEventListener('open', () => {
      if (stale()) return;
      this.attempt = 0;
      this.handlers.onState('live');
    });

    source.addEventListener('message', (event) => {
      if (stale()) return;
      let payload: StreamMessage;
      try {
        payload = JSON.parse(event.data ?? '') as StreamMessage;
      } catch {
        return;                                     // an unparseable frame never moves the cursor
      }
      const before = this.cursor;
      this.advanceCursor(payload.seq);
      if (before !== null && this.cursor === before) {
        return;                                     // a duplicate or out-of-order frame: already accounted for
      }
      this.handlers.onMessage(payload);
    });

    source.addEventListener('resync', (event) => {
      if (stale()) return;
      let reason = 'UNKNOWN';
      let fromCursor = this.cursor ?? '0';
      try {
        const payload = JSON.parse(event.data ?? '') as { reason?: string; fromCursor?: string };
        reason = payload.reason ?? reason;
        fromCursor = payload.fromCursor ?? fromCursor;
      } catch {
        // Fall back to what the client already knows.
      }
      // PAUSE. The server closes after a resync, and reconnecting with this cursor would reproduce it at once.
      this.paused = true;
      this.generation++;
      this.cancelTimer();
      this.closeSource();
      this.handlers.onState('recovering', `resync: ${reason}`);
      this.handlers.onResync(reason, fromCursor);
    });

    source.addEventListener('error', () => {
      if (stale()) return;
      // No status, no Retry-After and no body are available here, which is why no backoff is derived from it. Close
      // at once so the browser cannot auto-reconnect with the already-consumed token.
      this.closeSource();
      if (this.closed || this.paused) return;
      this.handlers.onState('waiting', 'stream interrupted');
      this.scheduleRetry(this.backoffMillis());
    });
  }

  private backoffMillis(): number {
    this.attempt = Math.min(this.attempt + 1, MAX_BACKOFF_STEP);
    const base = Math.min(1000 * 2 ** (this.attempt - 1), MAX_BACKOFF_MILLIS);
    return base + Math.floor(this.deps.random() * 250);
  }

  private scheduleRetry(delayMillis: number): void {
    if (this.closed || this.paused) return;
    this.cancelTimer();                             // exactly one pending timer, always
    const generation = this.generation;
    this.timer = this.deps.setTimer(() => {
      this.timer = null;
      if (this.closed || this.paused || generation !== this.generation) return;
      void this.connect();
    }, delayMillis);
  }
}
