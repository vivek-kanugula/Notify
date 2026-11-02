/**
 * The inbox list and unread accounting, as pure functions .
 *
 * This module imports nothing and touches no React API, so every rule below is tested directly and the component's
 * state updaters stay pure - no nested `setState`, no side effect inside an updater.
 *
 * The defect this replaces: `InboxPage` deduplicated the message list but incremented the unread badge
 * *unconditionally*, outside the dedupe branch. The stream drain is at-least-once by design (a reconnect replays
 * from the last row actually sent, and a hint can arrive twice), so every duplicate inflated the badge. Counting is
 * now a consequence of insertion: {@link applyIncoming} returns the same state object when nothing was inserted, so
 * the count cannot move without a row moving.
 *
 * Two further rules the tests pin down:
 *
 *  - **read is monotone per message.** A merge from the paginated read never turns a row the user has already marked
 *    read back to unread, because a page fetched before the POST committed is older information than the click.
 *  - **the badge is a server count on a load.** `unread` over the whole inbox is a database count, not something the
 *    loaded page can derive; it is taken from the response on a load and adjusted locally only by an insertion or a
 *    mark-read.
 */

export interface InboxMessage {
  /** The cursor. A decimal string, never a number: a 64-bit `seq` does not survive `Number`. */
  seq: string;
  notificationId: string;
  subject: string | null;
  body: string;
  read: boolean;
  readAt: string | null;
  createdAt: string;
}

export interface InboxState {
  /** Newest first, by `seq`. */
  messages: InboxMessage[];
  unread: number;
  nextCursor: string | null;
}

export const emptyInbox: InboxState = { messages: [], unread: 0, nextCursor: null };

/** `seq` ordering. Decimal strings compared as `BigInt`; an unparseable value sorts last rather than throwing. */
export function compareSeqDesc(left: string, right: string): number {
  const a = toBig(left);
  const b = toBig(right);
  if (a === null && b === null) return 0;
  if (a === null) return 1;
  if (b === null) return -1;
  return a === b ? 0 : a > b ? -1 : 1;
}

export function isHigherSeq(candidate: string, current: string | null): boolean {
  if (current === null) return true;
  const a = toBig(candidate);
  const b = toBig(current);
  return a !== null && b !== null && a > b;
}

/** The highest `seq` in a batch, or the fallback when the batch is empty or unusable. */
export function highestSeq(rows: readonly InboxMessage[], fallback: string | null = null): string | null {
  let best = fallback;
  for (const row of rows) {
    if (isHigherSeq(row.seq, best)) best = row.seq;
  }
  return best;
}

/**
 * A message from the live stream.
 *
 * Returns the SAME state object when the message is already present, which is what makes the badge correct: the
 * component's updater returns `current` unchanged and React skips the render.
 */
export function applyIncoming(state: InboxState, message: InboxMessage): InboxState {
  if (state.messages.some((existing) => existing.seq === message.seq)) {
    return state;
  }
  const messages = [message, ...state.messages];
  messages.sort((left, right) => compareSeqDesc(left.seq, right.seq));
  return {
    messages,
    unread: message.read ? state.unread : state.unread + 1,
    nextCursor: state.nextCursor,
  };
}

/** A local mark-read. Idempotent: a second call on the same `seq` changes nothing. */
export function applyMarkedRead(state: InboxState, seq: string): InboxState {
  const index = state.messages.findIndex((message) => message.seq === seq);
  if (index < 0 || state.messages[index].read) {
    return state;
  }
  const messages = state.messages.slice();
  messages[index] = { ...messages[index], read: true };
  return { messages, unread: Math.max(0, state.unread - 1), nextCursor: state.nextCursor };
}

/** A fresh first page: the list is replaced and the badge takes the server's count. */
export function applyFirstPage(rows: readonly InboxMessage[], unread: number, nextCursor: string | null): InboxState {
  const messages = rows.slice();
  messages.sort((left, right) => compareSeqDesc(left.seq, right.seq));
  return { messages, unread, nextCursor };
}

/**
 * Merges a page from the paginated read into the current list - the resync recovery and "load older" path.
 *
 * Rows already present are kept, not replaced, except that server fields other than `read` are refreshed; `read` is
 * monotone, so a page read before a mark-read committed cannot un-read the row.
 */
export function mergePage(
  state: InboxState,
  rows: readonly InboxMessage[],
  unread: number,
  nextCursor: string | null,
): InboxState {
  const bySeq = new Map<string, InboxMessage>();
  for (const message of state.messages) {
    bySeq.set(message.seq, message);
  }
  for (const row of rows) {
    const existing = bySeq.get(row.seq);
    bySeq.set(row.seq, existing ? { ...row, read: existing.read || row.read } : row);
  }
  const messages = Array.from(bySeq.values());
  messages.sort((left, right) => compareSeqDesc(left.seq, right.seq));
  return { messages, unread, nextCursor };
}

function toBig(value: string): bigint | null {
  try {
    return BigInt(value);
  } catch {
    return null;
  }
}
