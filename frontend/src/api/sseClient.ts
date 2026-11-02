import { requestStreamToken } from './inapp';
import { InAppStreamLoop, StreamLoopDeps, StreamLoopHandlers } from './streamLoop';

/**
 * The browser wiring for the in-app stream.
 *
 * The loop itself - reconnect, backoff, the resync handover, the monotonic cursor - lives in `streamLoop.ts`, which
 * imports nothing and is therefore tested deterministically. This file supplies the three real dependencies and
 * nothing else:
 *
 *  - `requestStreamToken`, the readable readiness and backoff channel. `EventSource.onerror`
 *    carries no status, no `Retry-After` and no body, so the loop never infers anything from it;
 *  - a real `EventSource`, whose native auto-reconnect is suppressed by closing the source on the first error -
 *    it would otherwise retry with the single-use token it has already consumed;
 *  - the real timer and `Math.random`.
 */
export const browserStreamDeps: StreamLoopDeps = {
  requestToken: requestStreamToken,
  openSource: (url) => {
    const source = new EventSource(url);
    return {
      addEventListener: (type, listener) =>
        source.addEventListener(type, (event) => listener({ data: (event as MessageEvent).data })),
      close: () => source.close(),
    };
  },
  setTimer: (run, delayMillis) => setTimeout(run, delayMillis),
  clearTimer: (handle) => clearTimeout(handle as ReturnType<typeof setTimeout>),
  random: () => Math.random(),
};

export class InAppStream extends InAppStreamLoop {
  constructor(
    recipientRef: string,
    handlers: StreamLoopHandlers,
    startCursor: string | null = null,
    deps: StreamLoopDeps = browserStreamDeps,
  ) {
    super(recipientRef, handlers, deps, startCursor);
  }
}
