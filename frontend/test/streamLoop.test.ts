import { test, describe, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert';
import { InAppStreamLoop, MAX_CONSECUTIVE_TOKEN_FAILURES } from '../src/api/streamLoop.ts';
// Type-only: node's type stripping leaves named imports in place, so a type imported as a value would fail to
// resolve at runtime.
import type {
  StreamLoopDeps,
  StreamMessage,
  StreamState,
  TokenGrant,
  TokenRefusal,
} from '../src/api/streamLoop.ts';

/**
 * Deterministic coverage for the reconnect loop and the resync handover .
 *
 * Every test here fails on the original implementation:
 *
 *  - F2: `connect()` awaited the token request outside any `try`, so a rejection produced an unhandled rejection and
 *    scheduled nothing. "zero retries scheduled" is asserted directly.
 *  - F3: the loop reconnected immediately after `resync` with the cursor it already had, so the server reproduced
 *    GAP_TOO_LARGE at once. "paused, and the next connection uses the recovered cursor" is asserted directly.
 *  - A2: the cursor was assigned from every frame, so a duplicate or out-of-order frame could rewind it.
 */

class FakeSource {
  closed = false;
  readonly url: string;
  private readonly listeners = new Map<string, ((event: { data?: string }) => void)[]>();

  constructor(url: string) {
    this.url = url;
  }

  addEventListener(type: string, listener: (event: { data?: string }) => void): void {
    const existing = this.listeners.get(type) ?? [];
    existing.push(listener);
    this.listeners.set(type, existing);
  }

  close(): void {
    this.closed = true;
  }

  fire(type: string, data?: unknown): void {
    const payload = data === undefined ? undefined : JSON.stringify(data);
    for (const listener of this.listeners.get(type) ?? []) {
      listener({ data: payload });
    }
  }
}

interface Harness {
  deps: StreamLoopDeps;
  sources: FakeSource[];
  timers: { id: number; delay: number; run: () => void }[];
  tokenUrls: string[];
  states: { state: StreamState; detail?: string }[];
  messages: StreamMessage[];
  resyncs: { reason: string; fromCursor: string }[];
  /** Fires the single pending timer. Asserts there is exactly one. */
  fireTimer(): void;
  loop: InAppStreamLoop;
}

function grant(token: string): TokenGrant {
  return {
    token,
    expiresInSeconds: 60,
    streamUrl: '/sse/inapp',
    admittedStreams: 1,
    capPerTenant: 100,
  };
}

function harness(
  respond: (attempt: number) => Promise<TokenGrant | TokenRefusal>,
  startCursor: string | null = null,
): Harness {
  const sources: FakeSource[] = [];
  const timers: { id: number; delay: number; run: () => void }[] = [];
  const tokenUrls: string[] = [];
  const states: { state: StreamState; detail?: string }[] = [];
  const messages: StreamMessage[] = [];
  const resyncs: { reason: string; fromCursor: string }[] = [];
  let attempt = 0;
  let nextTimerId = 1;

  const deps: StreamLoopDeps = {
    requestToken: () => respond(++attempt),
    openSource: (url) => {
      const source = new FakeSource(url);
      sources.push(source);
      tokenUrls.push(url);
      return source;
    },
    setTimer: (run, delay) => {
      const id = nextTimerId++;
      timers.push({ id, delay, run });
      return id;
    },
    clearTimer: (handle) => {
      const index = timers.findIndex((entry) => entry.id === handle);
      if (index >= 0) timers.splice(index, 1);
    },
    random: () => 0,
  };

  const loop = new InAppStreamLoop(
    'user-1',
    {
      onMessage: (message) => messages.push(message),
      onResync: (reason, fromCursor) => resyncs.push({ reason, fromCursor }),
      onState: (state, detail) => states.push({ state, detail }),
    },
    deps,
    startCursor,
  );

  const self: Harness = {
    deps,
    sources,
    timers,
    tokenUrls,
    states,
    messages,
    resyncs,
    loop,
    fireTimer: () => {
      assert.strictEqual(timers.length, 1, `expected exactly one pending timer, found ${timers.length}`);
      const entry = timers.shift()!;
      entry.run();
    },
  };
  return self;
}

/** Flushes the microtask queue so an awaited token reply is processed. */
async function settle(): Promise<void> {
  for (let i = 0; i < 5; i++) {
    await new Promise((resolve) => setImmediate(resolve));
  }
}

function lastState(h: Harness): { state: StreamState; detail?: string } {
  return h.states[h.states.length - 1];
}

describe('InAppStreamLoop', { concurrency: 1 }, () => {
  const unhandled: unknown[] = [];
  const onUnhandled = (reason: unknown) => unhandled.push(reason);

  beforeEach(() => {
    unhandled.length = 0;
    process.on('unhandledRejection', onUnhandled);
  });

  afterEach(() => {
    process.off('unhandledRejection', onUnhandled);
  });

  describe('F2 a rejected token request', () => {
    test('is reported, retried exactly once, and never becomes an unhandled rejection', async () => {
      const h = harness((attempt) =>
        attempt === 1
          ? Promise.reject(new Error('simulated offline'))
          : Promise.resolve(grant('tok-2')),
      );

      h.loop.start();
      await settle();

      assert.strictEqual(unhandled.length, 0, 'the rejection must be handled inside the loop');
      assert.strictEqual(lastState(h).state, 'waiting');
      assert.match(lastState(h).detail ?? '', /simulated offline/);
      assert.strictEqual(h.timers.length, 1, 'exactly one retry scheduled - the original scheduled none');
      assert.strictEqual(h.sources.length, 0, 'no stream was opened');

      h.fireTimer();
      await settle();

      assert.strictEqual(h.sources.length, 1, 'the retry obtained a fresh token and opened a stream');
      assert.match(h.tokenUrls[0], /token=tok-2/);
      h.sources[0].fire('open');
      assert.strictEqual(lastState(h).state, 'live');
    });

    test('retries with exponential backoff and stops after a bounded number of failures', async () => {
      const h = harness(() => Promise.reject(new Error('offline')));

      h.loop.start();
      await settle();

      const delays: number[] = [];
      for (let i = 1; i < MAX_CONSECUTIVE_TOKEN_FAILURES; i++) {
        assert.strictEqual(h.timers.length, 1, `one timer after failure ${i}`);
        delays.push(h.timers[0].delay);
        h.fireTimer();
        await settle();
      }

      assert.deepStrictEqual(delays.slice(0, 6), [1000, 2000, 4000, 8000, 16000, 30000]);
      assert.strictEqual(lastState(h).state, 'stopped');
      assert.match(lastState(h).detail ?? '', /could not be started after 10 attempts/);
      assert.strictEqual(h.timers.length, 0, 'nothing is still scheduled');
      assert.strictEqual(unhandled.length, 0);
    });

    test('a failure arriving after close changes nothing', async () => {
      let reject: (error: Error) => void = () => {};
      const h = harness(() => new Promise<TokenGrant>((_, rej) => (reject = rej)));

      h.loop.start();
      h.loop.close();
      reject(new Error('too late'));
      await settle();

      assert.strictEqual(lastState(h).state, 'stopped');
      assert.strictEqual(h.timers.length, 0);
      assert.strictEqual(unhandled.length, 0);
    });
  });

  describe('server-directed refusals', () => {
    test('a 429 waits exactly the interval the server gave, not an invented one', async () => {
      const refusal: TokenRefusal = {
        refused: true,
        status: 429,
        retryAfterSeconds: 95,
        detail: 'admission state unknown; quarantined',
      };
      const h = harness(() => Promise.resolve(refusal));

      h.loop.start();
      await settle();

      assert.strictEqual(lastState(h).state, 'waiting');
      assert.strictEqual(lastState(h).detail, 'admission state unknown; quarantined');
      assert.strictEqual(h.timers.length, 1);
      assert.strictEqual(h.timers[0].delay, 95_000);
    });

    test('a 401 is terminal: the loop stops instead of retrying for the life of the tab', async () => {
      const h = harness(() =>
        Promise.resolve<TokenRefusal>({
          refused: true,
          status: 401,
          retryAfterSeconds: 30,
          detail: 'session expired',
        }),
      );

      h.loop.start();
      await settle();

      assert.strictEqual(lastState(h).state, 'stopped');
      assert.match(lastState(h).detail ?? '', /not authorized to stream \(401\)/);
      assert.strictEqual(h.timers.length, 0);
      assert.ok(h.loop.isClosed());
    });
  });

  describe('F3 the resync handover', () => {
    async function openedLoop(startCursor: string | null = '0') {
      const h = harness((attempt) => Promise.resolve(grant(`tok-${attempt}`)), startCursor);
      h.loop.start();
      await settle();
      h.sources[0].fire('open');
      return h;
    }

    test('pauses the loop instead of reconnecting with the stale cursor', async () => {
      const h = await openedLoop('0');

      h.sources[0].fire('resync', { reason: 'GAP_TOO_LARGE', fromCursor: '0' });

      assert.deepStrictEqual(h.resyncs, [{ reason: 'GAP_TOO_LARGE', fromCursor: '0' }]);
      assert.ok(h.loop.isPaused(), 'the loop must be paused, not waiting to reconnect');
      assert.strictEqual(h.timers.length, 0, 'the original scheduled a reconnect here, which looped for ever');
      assert.ok(h.sources[0].closed);
      assert.strictEqual(lastState(h).state, 'recovering');
      assert.strictEqual(h.sources.length, 1, 'no second connection without an explicit resume');
    });

    test('resumes with the recovered cursor and a fresh token', async () => {
      const h = await openedLoop('0');
      h.sources[0].fire('resync', { reason: 'GAP_TOO_LARGE', fromCursor: '0' });

      h.loop.resumeFrom('142');
      await settle();

      assert.strictEqual(h.loop.isPaused(), false);
      assert.strictEqual(h.sources.length, 2);
      assert.match(h.tokenUrls[1], /lastEventId=142/);
      assert.match(h.tokenUrls[1], /token=tok-2/, 'a new single-use token, never the consumed one');
      assert.strictEqual(h.loop.currentCursor(), '142');
    });

    test('a resume that would rewind the cursor is ignored', async () => {
      const h = await openedLoop('500');
      h.sources[0].fire('resync', { reason: 'GAP_TOO_LARGE', fromCursor: '500' });

      h.loop.resumeFrom('12');
      await settle();

      assert.strictEqual(h.loop.currentCursor(), '500');
      assert.match(h.tokenUrls[1], /lastEventId=500/);
    });

    test('closing while paused never reconnects', async () => {
      const h = await openedLoop('0');
      h.sources[0].fire('resync', { reason: 'GAP_TOO_LARGE', fromCursor: '0' });

      h.loop.close();
      await settle();

      assert.strictEqual(h.sources.length, 1);
      assert.strictEqual(h.timers.length, 0);
      assert.strictEqual(lastState(h).state, 'stopped');
    });

    test('a frame from the superseded source after a resume cannot change the active session', async () => {
      const h = await openedLoop('0');
      h.sources[0].fire('resync', { reason: 'GAP_TOO_LARGE', fromCursor: '0' });
      h.loop.resumeFrom('142');
      await settle();

      h.sources[0].fire('message', { seq: '9', notificationId: 'n', subject: '', body: '', read: false, createdAt: '' });
      h.sources[0].fire('error');

      assert.strictEqual(h.messages.length, 0, 'the stale source delivered nothing');
      assert.strictEqual(h.loop.currentCursor(), '142');
      assert.strictEqual(h.timers.length, 0, 'and it scheduled no competing reconnect');
    });
  });

  describe('A2 the cursor only advances', () => {
    test('a duplicate or out-of-order frame is dropped and never rewinds the cursor', async () => {
      const h = harness(() => Promise.resolve(grant('tok-1')), null);
      h.loop.start();
      await settle();
      h.sources[0].fire('open');

      const frame = (seq: string) => ({
        seq,
        notificationId: `n-${seq}`,
        subject: 's',
        body: 'b',
        read: false,
        createdAt: '2026-10-15T00:00:00Z',
      });

      h.sources[0].fire('message', frame('5'));
      h.sources[0].fire('message', frame('5'));
      h.sources[0].fire('message', frame('3'));
      h.sources[0].fire('message', frame('9007199254740993'));   // beyond 2^53: must survive as a string

      assert.deepStrictEqual(h.messages.map((m) => m.seq), ['5', '9007199254740993']);
      assert.strictEqual(h.loop.currentCursor(), '9007199254740993');
    });

    test('an unparseable frame is dropped without advancing anything', async () => {
      const h = harness(() => Promise.resolve(grant('tok-1')), '7');
      h.loop.start();
      await settle();
      h.sources[0].fire('open');

      h.sources[0].fire('message', undefined);

      assert.strictEqual(h.messages.length, 0);
      assert.strictEqual(h.loop.currentCursor(), '7');
    });
  });

  describe('no duplicate timers or connections', () => {
    test('two errors in a row leave exactly one pending reconnect', async () => {
      const h = harness((attempt) => Promise.resolve(grant(`tok-${attempt}`)), '1');
      h.loop.start();
      await settle();
      h.sources[0].fire('open');

      h.sources[0].fire('error');
      h.sources[0].fire('error');

      assert.strictEqual(h.timers.length, 1);
    });

    test('start() twice opens one stream', async () => {
      const h = harness((attempt) => Promise.resolve(grant(`tok-${attempt}`)), '1');
      h.loop.start();
      h.loop.start();
      await settle();

      assert.strictEqual(h.sources.length, 1);
    });
  });
});
