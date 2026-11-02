import { test, describe } from 'node:test';
import assert from 'node:assert';
import {
  computeLifecycleStages,
  computeCompactSegments,
  formatRelativeTime,
  isTerminalStatus,
} from '../src/components/lifecycleRailLogic.ts';
import type { NotificationHistoryEntry, NotificationStatus } from '../src/api/notifications.ts';

describe('LifecycleRail pure logic & stage computation', () => {
  test('SCHEDULED notification creates SCHEDULED stage as current', () => {
    const data = computeLifecycleStages({
      status: 'SCHEDULED',
      scheduledAt: '2026-10-27T12:00:00Z',
      createdAt: '2026-10-27T10:00:00Z',
    });

    assert.strictEqual(data.stages.length, 4);
    assert.strictEqual(data.stages[0].name, 'SCHEDULED');
    assert.strictEqual(data.stages[0].state, 'current');
    assert.strictEqual(data.stages[1].name, 'QUEUED');
    assert.strictEqual(data.stages[1].state, 'upcoming');
    assert.strictEqual(data.isTerminal, false);
    assert.strictEqual(data.terminalOutcome, null);
  });

  test('QUEUED notification without prior scheduling begins at QUEUED stage', () => {
    const data = computeLifecycleStages({
      status: 'QUEUED',
      queuedAt: '2026-10-27T10:00:00Z',
      createdAt: '2026-10-27T10:00:00Z',
    });

    assert.strictEqual(data.stages.length, 3);
    assert.strictEqual(data.stages[0].name, 'QUEUED');
    assert.strictEqual(data.stages[0].state, 'current');
    assert.strictEqual(data.stages[1].name, 'PROCESSING');
    assert.strictEqual(data.stages[1].state, 'upcoming');
    assert.strictEqual(data.stages[2].name, 'DELIVERED');
    assert.strictEqual(data.stages[2].state, 'upcoming');
    assert.strictEqual(data.isTerminal, false);
  });

  test('PROCESSING notification marks QUEUED as done and PROCESSING as current', () => {
    const history: NotificationHistoryEntry[] = [
      { id: 1, fromStatus: null, toStatus: 'QUEUED', actor: 'SYSTEM', reason: null, occurredAt: '2026-10-27T10:00:00Z' },
      { id: 2, fromStatus: 'QUEUED', toStatus: 'PROCESSING', actor: 'SYSTEM', reason: 'Worker Claim', occurredAt: '2026-10-27T10:00:02Z' },
    ];

    const data = computeLifecycleStages({
      status: 'PROCESSING',
      queuedAt: '2026-10-27T10:00:00Z',
      processingStartedAt: '2026-10-27T10:00:02Z',
      history,
    });

    assert.strictEqual(data.stages[0].name, 'QUEUED');
    assert.strictEqual(data.stages[0].state, 'done');
    assert.strictEqual(data.stages[1].name, 'PROCESSING');
    assert.strictEqual(data.stages[1].state, 'current');
    assert.strictEqual(data.stages[2].name, 'DELIVERED');
    assert.strictEqual(data.stages[2].state, 'upcoming');
  });

  test('RETRYING notification marks processing attempt count and current state', () => {
    const data = computeLifecycleStages({
      status: 'RETRYING',
      attemptCount: 2,
    });

    assert.strictEqual(data.stages[1].name, 'RETRYING');
    assert.strictEqual(data.stages[1].state, 'current');
    assert.strictEqual(data.stages[1].attemptNumber, 2);
    assert.strictEqual(data.retryCount, 2);
    assert.strictEqual(data.isTerminal, false);
  });

  test('DELIVERED terminal state marks all preceding stages as done and DELIVERED as current', () => {
    const history: NotificationHistoryEntry[] = [
      { id: 1, fromStatus: null, toStatus: 'QUEUED', actor: 'SYSTEM', reason: null, occurredAt: '2026-10-27T10:00:00Z' },
      { id: 2, fromStatus: 'QUEUED', toStatus: 'PROCESSING', actor: 'SYSTEM', reason: null, occurredAt: '2026-10-27T10:00:01Z' },
      { id: 3, fromStatus: 'PROCESSING', toStatus: 'DELIVERED', actor: 'SYSTEM', reason: '200 OK', occurredAt: '2026-10-27T10:00:02Z' },
    ];

    const data = computeLifecycleStages({
      status: 'DELIVERED',
      completedAt: '2026-10-27T10:00:02Z',
      history,
    });

    assert.strictEqual(data.isTerminal, true);
    assert.strictEqual(data.terminalOutcome, 'DELIVERED');
    assert.strictEqual(data.stages[0].state, 'done');
    assert.strictEqual(data.stages[1].state, 'done');
    assert.strictEqual(data.stages[2].name, 'DELIVERED');
    assert.strictEqual(data.stages[2].state, 'current');
  });

  test('DLQ terminal state marks DLQ outcome with terminal caps', () => {
    const data = computeLifecycleStages({
      status: 'DLQ',
      attemptCount: 3,
      completedAt: '2026-10-27T10:05:00Z',
    });

    assert.strictEqual(data.isTerminal, true);
    assert.strictEqual(data.terminalOutcome, 'DLQ');
    assert.strictEqual(data.stages[data.stages.length - 1].name, 'DLQ');
    assert.strictEqual(data.stages[data.stages.length - 1].state, 'current');
  });

  test('CANCELLED notification from QUEUED status identifies CANCELLED terminal state', () => {
    const data = computeLifecycleStages({
      status: 'CANCELLED',
      queuedAt: '2026-10-27T10:00:00Z',
      completedAt: '2026-10-27T10:01:00Z',
    });

    assert.strictEqual(data.isTerminal, true);
    assert.strictEqual(data.terminalOutcome, 'CANCELLED');
    assert.strictEqual(data.stages[0].name, 'QUEUED');
    assert.strictEqual(data.stages[0].state, 'done');
    assert.strictEqual(data.stages[1].name, 'PROCESSING');
    assert.strictEqual(data.stages[1].state, 'skipped');
    assert.strictEqual(data.stages[2].name, 'CANCELLED');
    assert.strictEqual(data.stages[2].state, 'current');
  });
});

describe('computeCompactSegments 96x6 bar generation', () => {
  test('returns 4 segments for QUEUED status', () => {
    const segments = computeCompactSegments('QUEUED');
    assert.strictEqual(segments.length, 4);
    assert.strictEqual(segments[0], 'var(--color-st-queued)');
    assert.strictEqual(segments[1], 'var(--color-rail)');
    assert.strictEqual(segments[2], 'var(--color-rail)');
    assert.strictEqual(segments[3], 'var(--color-rail)');
  });

  test('returns 4 segments for DELIVERED status without retry', () => {
    const segments = computeCompactSegments('DELIVERED', 1);
    assert.strictEqual(segments.length, 4);
    assert.strictEqual(segments[0], 'var(--color-st-queued)');
    assert.strictEqual(segments[1], 'var(--color-st-processing)');
    assert.strictEqual(segments[2], 'var(--color-st-processing)');
    assert.strictEqual(segments[3], 'var(--color-st-delivered)');
  });

  test('returns 4 segments with retry highlight for DELIVERED status with retry', () => {
    const segments = computeCompactSegments('DELIVERED', 2);
    assert.strictEqual(segments.length, 4);
    assert.strictEqual(segments[2], 'var(--color-st-retrying)');
    assert.strictEqual(segments[3], 'var(--color-st-delivered)');
  });

  test('returns DLQ color on segment 4 for DLQ status', () => {
    const segments = computeCompactSegments('DLQ', 3);
    assert.strictEqual(segments[3], 'var(--color-st-dlq)');
  });
});

describe('formatRelativeTime helper', () => {
  test('handles null and undefined gracefully', () => {
    assert.strictEqual(formatRelativeTime(null), null);
    assert.strictEqual(formatRelativeTime(undefined), null);
    assert.strictEqual(formatRelativeTime('invalid-date'), null);
  });

  test('formats recent times in seconds, minutes, hours', () => {
    const now = Date.now();
    assert.strictEqual(formatRelativeTime(new Date(now - 2000).toISOString()), 'just now');
    assert.strictEqual(formatRelativeTime(new Date(now - 30000).toISOString()), '30s ago');
    assert.strictEqual(formatRelativeTime(new Date(now - 5 * 60 * 1000).toISOString()), '5m ago');
    assert.strictEqual(formatRelativeTime(new Date(now - 2 * 3600 * 1000).toISOString()), '2h ago');
  });

  test('isTerminalStatus correctly checks terminal vs in-flight statuses', () => {
    assert.strictEqual(isTerminalStatus('DELIVERED'), true);
    assert.strictEqual(isTerminalStatus('FAILED'), true);
    assert.strictEqual(isTerminalStatus('DLQ'), true);
    assert.strictEqual(isTerminalStatus('CANCELLED'), true);
    assert.strictEqual(isTerminalStatus('QUEUED'), false);
    assert.strictEqual(isTerminalStatus('PROCESSING'), false);
    assert.strictEqual(isTerminalStatus('RETRYING'), false);
    assert.strictEqual(isTerminalStatus('SCHEDULED'), false);
  });
});
