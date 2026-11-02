import { test, describe, beforeEach } from 'node:test';
import assert from 'node:assert';
import { InAppStreamLoop } from '../src/api/streamLoop.ts';
import type { StreamLoopDeps, StreamLoopHandlers, TokenGrant } from '../src/api/streamLoop.ts';

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
}

describe('SSE route-unmount stream cleanup seam (finding 5)', () => {
  let sources: FakeSource[];
  let timers: { id: number; delay: number; run: () => void; cancelled: boolean }[];
  let nextTimerId = 1;
  let states: string[];

  const testDeps: StreamLoopDeps = {
    requestToken: async () => ({
      token: 'grant-token',
      expiresInSeconds: 60,
      streamUrl: '/sse/inapp',
    } satisfies TokenGrant),
    openSource: (url) => {
      const s = new FakeSource(url);
      sources.push(s);
      return s;
    },
    setTimer: (run, delay) => {
      const entry = { id: nextTimerId++, delay, run, cancelled: false };
      timers.push(entry);
      return entry.id;
    },
    clearTimer: (handle) => {
      const entry = timers.find((t) => t.id === handle);
      if (entry) entry.cancelled = true;
    },
    random: () => 0,
  };

  const handlers: StreamLoopHandlers = {
    onMessage: () => {},
    onResync: () => {},
    onState: (state) => {
      states.push(state);
    },
  };

  beforeEach(() => {
    sources = [];
    timers = [];
    nextTimerId = 1;
    states = [];
  });

  test('route unmount invokes stream close, terminates active EventSource, and cancels pending timers', async () => {
    // 1. Mount phase (as in InboxPage): stream instance created and started
    const stream = new InAppStreamLoop('recipient-1', handlers, testDeps, '100');
    let streamRef: InAppStreamLoop | null = stream;
    let generation = 1;

    void stream.start();
    await new Promise((r) => setTimeout(r, 10));

    assert.strictEqual(sources.length, 1, 'Stream opened an EventSource connection');
    const activeSource = sources[0];
    assert.strictEqual(activeSource.closed, false, 'EventSource is initially open');
    assert.strictEqual(stream.isClosed(), false, 'Stream is active');

    // 2. Unmount cleanup seam (exact pattern in InboxPage useEffect return):
    // return () => { generationRef.current++; streamRef.current?.close(); streamRef.current = null; };
    const unmountCleanup = () => {
      generation++;
      streamRef?.close();
      streamRef = null;
    };

    unmountCleanup();

    // 3. Verify complete cleanup
    assert.strictEqual(activeSource.closed, true, 'Active EventSource closed immediately on unmount');
    assert.strictEqual(stream.isClosed(), true, 'Stream loop marked closed');
    assert.strictEqual(streamRef, null, 'Component reference cleared');
    assert.ok(states.includes('stopped'), 'Stream transitioned to stopped state');

    // 4. Any subsequent attempt to resume or reconnect must be rejected
    stream.resumeFrom('200');
    assert.strictEqual(sources.length, 1, 'No new EventSource opened after unmount');
  });

  test('stream closed while waiting for retry timer cancels timer and never reconnects', async () => {
    const failingDeps: StreamLoopDeps = {
      ...testDeps,
      requestToken: async () => ({
        refused: true,
        retryAfterSeconds: 5,
        detail: 'Rate limited',
        status: 429,
      }),
    };

    const stream = new InAppStreamLoop('recipient-1', handlers, failingDeps, null);
    void stream.start();
    await new Promise((r) => setTimeout(r, 10));

    assert.strictEqual(timers.length, 1, 'One retry timer was scheduled');
    assert.strictEqual(timers[0].cancelled, false, 'Timer is pending');

    // Route unmount occurs while timer is waiting
    stream.close();

    assert.strictEqual(timers[0].cancelled, true, 'Pending retry timer was cancelled on close');
    assert.strictEqual(stream.isClosed(), true);

    // If timer callback were somehow executed after close, it must no-op
    timers[0].run();
    assert.strictEqual(sources.length, 0, 'No EventSource opened after closed timer invocation');
  });
});
