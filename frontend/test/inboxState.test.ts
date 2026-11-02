import { test, describe } from 'node:test';
import assert from 'node:assert';
import {
  applyFirstPage,
  applyIncoming,
  applyMarkedRead,
  compareSeqDesc,
  emptyInbox,
  highestSeq,
  isHigherSeq,
  mergePage,
} from '../src/pages/inboxState.ts';
import type { InboxMessage, InboxState } from '../src/pages/inboxState.ts';

/**
 * Unread accounting and list merging .
 *
 * The original `InboxPage` incremented the badge outside its deduplication branch, so every duplicate frame - which
 * the at-least-once drain produces on every reconnect - inflated the count. `duplicateDoesNotInflateTheBadge` fails
 * on that code and passes here.
 */

function message(seq: string, read = false): InboxMessage {
  return {
    seq,
    notificationId: `n-${seq}`,
    subject: `Subject ${seq}`,
    body: `Body ${seq}`,
    read,
    readAt: read ? '2026-10-15T00:00:00Z' : null,
    createdAt: '2026-10-15T00:00:00Z',
  };
}

function withMessages(rows: InboxMessage[], unread: number, nextCursor: string | null = null): InboxState {
  return applyFirstPage(rows, unread, nextCursor);
}

describe('inbox unread accounting', () => {
  test('a duplicate copy of an unread message does not inflate the badge', () => {
    let state = emptyInbox;
    state = applyIncoming(state, message('10'));
    assert.strictEqual(state.unread, 1);

    const afterDuplicate = applyIncoming(state, message('10'));

    assert.strictEqual(afterDuplicate.unread, 1);
    assert.strictEqual(afterDuplicate.messages.length, 1);
    assert.strictEqual(afterDuplicate, state, 'the same object, so React skips the render entirely');
  });

  test('an already-read arrival does not count', () => {
    const state = applyIncoming(emptyInbox, message('10', true));
    assert.strictEqual(state.unread, 0);
    assert.strictEqual(state.messages.length, 1);
  });

  test('mark-read decrements once and is idempotent', () => {
    let state = applyIncoming(emptyInbox, message('10'));
    state = applyIncoming(state, message('11'));
    assert.strictEqual(state.unread, 2);

    state = applyMarkedRead(state, '10');
    assert.strictEqual(state.unread, 1);
    assert.strictEqual(state.messages.find((m) => m.seq === '10')?.read, true);

    const again = applyMarkedRead(state, '10');
    assert.strictEqual(again.unread, 1);
    assert.strictEqual(again, state, 'no state change at all on a repeat');
  });

  test('mark-read of an unknown seq changes nothing and cannot go negative', () => {
    const state = applyMarkedRead(emptyInbox, '999');
    assert.strictEqual(state.unread, 0);
    assert.strictEqual(state, emptyInbox);
  });

  test('a stale duplicate cannot reverse read state', () => {
    let state = applyIncoming(emptyInbox, message('10'));
    state = applyMarkedRead(state, '10');
    assert.strictEqual(state.unread, 0);

    // The server's page still shows it unread because it was fetched before the POST committed.
    const merged = mergePage(state, [message('10', false)], 1, null);

    assert.strictEqual(merged.messages[0].read, true, 'read is monotone per message');
  });
});

describe('reconnect and resync overlap', () => {
  test('replaying rows the stream already delivered neither duplicates rows nor counts', () => {
    let state = applyIncoming(emptyInbox, message('10'));
    state = applyIncoming(state, message('11'));

    // A reconnect replays from the last row actually sent; the drain is at-least-once by design.
    state = applyIncoming(state, message('11'));
    state = applyIncoming(state, message('12'));

    assert.deepStrictEqual(state.messages.map((m) => m.seq), ['12', '11', '10']);
    assert.strictEqual(state.unread, 3);
  });

  test('a recovery page overlapping the live list keeps one row each and the server count', () => {
    const state = withMessages([message('30'), message('29')], 2, '29');

    const merged = mergePage(state, [message('30'), message('29'), message('28'), message('27')], 4, '27');

    assert.deepStrictEqual(merged.messages.map((m) => m.seq), ['30', '29', '28', '27']);
    assert.strictEqual(merged.unread, 4, 'the badge is the database count on a page load');
    assert.strictEqual(merged.nextCursor, '27');
  });

  test('the list stays newest-first across merges, including beyond 2^53', () => {
    const big = '9007199254740993';
    const bigger = '9007199254740994';
    let state = applyIncoming(emptyInbox, message(big));
    state = applyIncoming(state, message('7'));
    state = mergePage(state, [message(bigger)], 3, null);

    assert.deepStrictEqual(state.messages.map((m) => m.seq), [bigger, big, '7']);
  });
});

describe('cursor helpers', () => {
  test('comparison and advance use BigInt, so a 64-bit seq is exact', () => {
    assert.strictEqual(compareSeqDesc('9007199254740993', '9007199254740992'), -1);
    assert.strictEqual(compareSeqDesc('5', '5'), 0);
    assert.strictEqual(isHigherSeq('9007199254740993', '9007199254740992'), true);
    assert.strictEqual(isHigherSeq('4', '5'), false);
    assert.strictEqual(isHigherSeq('4', null), true);
  });

  test('highestSeq returns a value actually present, or the fallback', () => {
    assert.strictEqual(highestSeq([message('3'), message('17'), message('9')]), '17');
    assert.strictEqual(highestSeq([], '42'), '42');
    assert.strictEqual(highestSeq([]), null);
    assert.strictEqual(highestSeq([message('3')], '42'), '42', 'never below the fallback');
  });

  test('an unparseable cursor is ignored rather than trusted', () => {
    assert.strictEqual(isHigherSeq('abc', '5'), false);
    assert.strictEqual(compareSeqDesc('abc', '5'), 1, 'sorts last instead of throwing');
  });
});
